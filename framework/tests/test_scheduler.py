from datetime import datetime

import pytest

from doppel import DoppelRuntime, RuntimeConfig
from doppel.scheduler import ScheduleRule, Scheduler


def ms(value):
    return int(datetime.fromisoformat(value).timestamp() * 1000)


def test_rules_keep_anchor_and_explicit_timezone():
    rule = ScheduleRule(kind="interval", every_ms=60000, anchor_ms=1000)
    assert rule.next_after(120999) == 121000
    assert rule.next_after(121000) == 181000
    rule = ScheduleRule(kind="cron", expression="30 9 * * *", timezone="Asia/Shanghai")
    assert rule.next_after(ms("2026-09-08T00:00:00+00:00")) == ms("2026-09-08T01:30:00+00:00")


def test_cron_skips_gap_and_uses_only_first_fold():
    spring = ScheduleRule(kind="cron", expression="30 2 * * *", timezone="America/New_York")
    assert spring.next_after(ms("2026-03-08T00:00:00-05:00")) == ms("2026-03-09T02:30:00-04:00")
    fall = ScheduleRule(kind="cron", expression="30 1 * * *", timezone="America/New_York")
    first = ms("2026-11-01T01:30:00-04:00")
    assert fall.next_after(first - 1) == first
    assert fall.next_after(first) == ms("2026-11-02T01:30:00-05:00")


def test_cron_dom_dow_or_and_invalid_input():
    rule = ScheduleRule(kind="cron", expression="0 9 15 * 1", timezone="UTC")
    assert rule.next_after(ms("2026-09-13T12:00:00+00:00")) == ms("2026-09-14T09:00:00+00:00")
    for fields in [{"kind": "cron", "expression": "61 * * * *"},
                   {"kind": "cron", "expression": "* * * * *", "timezone": "Bogus/Zone"},
                   {"kind": "interval", "every_ms": 500},
                   {"kind": "once", "at_ms": True},
                   {"kind": "cron", "expression": "0 0 30 2 *"}]:
        with pytest.raises(ValueError):
            ScheduleRule(**fields).next_after(ms("2026-01-01T00:00:00+00:00"))


@pytest.fixture
def lab(tmp_path, monkeypatch):
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False))
    device = runtime.register_device("alice", "fixture", "Test device")
    clock = [ms("2026-09-08T00:00:00+00:00")]
    monkeypatch.setattr("doppel.runtime.time.time",lambda:clock[0]/1000)
    scheduler = Scheduler(runtime, clock=lambda: clock[0], readiness=lambda owner, device: None)
    yield runtime, device, clock, scheduler
    runtime.store.db.close()


def create(lab, **changes):
    _, device, clock, scheduler = lab
    body = dict(device_id=device.id, goal="Inspect a synthetic fixture", mode="ask",
                rule={"kind": "once", "at_ms": clock[0] + 1000, "timezone": "UTC"})
    body.update(changes)
    return scheduler.create("alice", body)


def test_once_dispatches_real_runtime_run_and_keeps_trace(lab):
    runtime, device, clock, scheduler = lab
    job = create(lab)
    clock[0] += 1000
    scheduler.tick()
    stored = scheduler.get("alice", job["id"])
    assert not stored["enabled"]
    assert stored["history"][-1]["status"] == "queued"
    run_id = stored["history"][-1]["run_id"]
    run = runtime.get_run("alice", run_id)
    assert run.goal == job["goal"]
    assert run.conversation_enabled is False
    assert run.source == "schedule"
    assert runtime.conversation("alice", run_id) == []
    assert runtime.get_run("alice", run_id).conversation_enabled is False
    assert runtime.conversation("alice", run_id) == []
    scheduler.tick()
    assert len(runtime.runs("alice")) == 1
    runtime.cancel_run("alice", run_id)
    scheduler.tick()
    assert scheduler.get("alice", job["id"])["history"][-1]["status"] == "cancelled"


def test_paused_task_keeps_scheduled_work_queued_past_grace_period(lab):
    runtime, device, clock, scheduler = lab
    existing = runtime.create_run("alice", device.id, "Existing user task", "ask")
    runtime.pause_run("alice", existing.id)
    job = create(lab)
    clock[0] += 1000
    scheduler.tick()
    assert scheduler.get("alice", job["id"])["history"][-1]["status"] == "queued"
    clock[0] += 300001
    scheduler.tick()
    assert scheduler.get("alice", job["id"])["history"][-1]["status"] == "queued"
    assert runtime.get_run("alice", existing.id).status == "paused"
    assert len(runtime.runs("alice")) == 2


def test_disabled_deleted_and_foreign_jobs_cannot_dispatch(lab):
    _, _, clock, scheduler = lab
    job = create(lab)
    with pytest.raises(Exception):
        scheduler.get("bob", job["id"])
    scheduler.update("alice", job["id"], {"enabled": False})
    clock[0] += 1000
    scheduler.tick()
    assert scheduler.get("alice", job["id"])["history"] == []
    scheduler.delete("alice", job["id"])
    assert scheduler.list("alice") == []


def test_restart_skips_old_occurrences_without_burst(lab):
    runtime, _, clock, scheduler = lab
    job = create(lab, rule={"kind": "interval", "every_ms": 60000, "anchor_ms": clock[0] + 1000})
    clock[0] += 24 * 3600 * 1000
    restored = Scheduler(runtime, clock=lambda: clock[0], readiness=lambda *_: None)
    restored.tick()
    state = restored.get("alice", job["id"])
    assert state["next_due_ms"] > clock[0]
    assert len(state["history"]) == 1 and state["history"][-1]["status"] == "missed"
    assert runtime.runs("alice") == []


def test_uncertain_claim_after_restart_is_not_replayed(lab):
    runtime, _, clock, scheduler = lab
    job = create(lab)
    with runtime.store.transaction() as db:
        import json
        job["history"].append({"scheduled_at_ms": job["next_due_ms"], "at_ms": clock[0], "status": "dispatching", "run_id": None})
        db.execute("UPDATE schedules SET payload=? WHERE id=?", (json.dumps(job), job["id"]))
    clock[0] += 1000
    restored = Scheduler(runtime, clock=lambda: clock[0], readiness=lambda *_: None)
    restored.tick()
    state = restored.get("alice", job["id"])
    assert state["history"][-1]["status"] == "uncertain"
    assert not state["enabled"]
    assert runtime.runs("alice") == []


def test_creation_failure_never_retries_occurrence(lab):
    runtime, _, clock, scheduler = lab
    job = create(lab)
    clock[0] += 1000
    def fail(*_,**kwargs):
        raise RuntimeError("secret upstream detail")
    runtime.create_run = fail
    scheduler.tick()
    scheduler.tick()
    stored = scheduler.get("alice", job["id"])
    assert len(stored["history"]) == 1
    assert stored["history"][-1]["status"] == "uncertain"
    assert "secret" not in str(stored)


def test_standalone_router_auth_crud_and_lifecycle(tmp_path):
    import time
    from fastapi.testclient import TestClient
    from doppel.cli import create_app
    app = create_app(tmp_path, auto_start=False)
    token = (tmp_path / "developer-token.txt").read_text().strip()
    with TestClient(app) as client:
        assert client.get("/v1/schedules").status_code == 401
        client.headers["Authorization"] = "Bearer " + token
        device = client.post("/v1/devices", json={"installation_id": "scheduled", "name": "Fixture"}).json()
        body = {"device_id": device["id"], "goal": "Inspect fixture", "rule": {"kind": "once", "at_ms": int(time.time() * 1000) + 3600000}}
        response = client.post("/v1/schedules", json=body)
        assert response.status_code == 201, response.text
        job = response.json()
        assert client.patch("/v1/schedules/" + job["id"], json={"enabled": False}).json()["enabled"] is False
        assert len(client.get("/v1/schedules").json()["items"]) == 1
        assert client.post("/v1/schedules", json={**body, "payment_consent_id": "bad"}).status_code == 422
        assert client.post("/v1/schedules", json={**body, "mode": []}).status_code == 422
        assert client.delete("/v1/schedules/" + job["id"]).json()["deleted"]
        scheduler = app.state.scheduler
        assert scheduler._loop_task is not None and not scheduler._loop_task.done()
    assert scheduler._loop_task is None


def test_write_failure_after_creation_reconciles_committed_submission(lab):
    runtime, _, clock, scheduler = lab
    job = create(lab, rule={"kind": "interval", "every_ms": 60000, "anchor_ms": clock[0] + 1000})
    save = scheduler._save
    calls = [0]
    def broken_save(owner, value):
        calls[0] += 1
        if calls[0] > 1:
            raise OSError("storage unavailable")
        return save(owner, value)
    scheduler._save = broken_save
    clock[0] += 1000
    with pytest.raises(OSError):
        scheduler.tick()
    assert len(runtime.runs("alice")) == 1
    runtime.cancel_run("alice", runtime.runs("alice")[0].id)
    scheduler._save = save
    clock[0] += 60000
    scheduler.tick()
    assert len(runtime.runs("alice")) == 1
    restored=scheduler.get("alice",job["id"])
    assert restored["enabled"]
    assert restored["history"][-1]["run_id"] == runtime.runs("alice")[0].id
    scheduler.tick()
    assert len(runtime.runs("alice")) == 2
    assert scheduler.get("alice",job["id"])["history"][-2]["status"] == "cancelled"

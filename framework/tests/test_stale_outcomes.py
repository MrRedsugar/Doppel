import json

from doppel.models import CommandResult
from doppel.tool_outcomes import record_tool_outcome
from test_completion import call, observe, setup


def stale(runtime, run, screen="s1", target="icon"):
    record_tool_outcome(runtime, run.id, "act", {
        "action": "tap", "screen_id": screen, "target": target,
    }, True, "stale")


def test_stale_action_loop_survives_changing_screen_ids_and_inspection(tmp_path):
    runtime, device, run, client = setup(tmp_path)
    observe(runtime, device, run, client, "initial")
    for index, inspection in enumerate(("observe", "describe_screen", "list_apps")):
        stale(runtime, run, f"screen-{index}")
        if index < 2:
            record_tool_outcome(runtime, run.id, inspection, {}, False, "ok")
    assert runtime.get_run("alice", run.id).status == "failed"
    outcomes = [event.data for event in runtime.events("alice", run.id) if event.kind == "tool_outcome"]
    assert [item["stale_failures"] for item in outcomes] == [0, 1, 1, 2, 2, 3]
    assert not any("icon" in json.dumps(item) for item in outcomes)
    assert client.post(f"/v1/internal/runs/{run.id}/chat/completions", json={"messages": []}).status_code == 200
    assert not any(event.kind == "model_start" for event in runtime.events("alice", run.id))


def test_internal_stale_results_activate_guard_and_never_issue_evidence(tmp_path):
    runtime, device, run, client = setup(tmp_path)
    observe(runtime, device, run, client, "initial")

    async def perform(run_id, kind, **fields):
        return CommandResult(command_id="fixture", run_id=run_id, status="stale", message="Changed")

    runtime.perform = perform
    for index in range(3):
        response = call(client, run, "act", action="tap", target="icon", screen_id=f"screen-{index}")
        assert response.status_code == 200
        assert response.json()["status"] == "stale"
        assert response.json()["error"] == "stale_target"
        assert "evidence_id" not in response.json()
    assert runtime.get_run("alice", run.id).status == "failed"


def test_changed_target_or_successful_mutation_resets_stale_streak(tmp_path):
    runtime, device, run, client = setup(tmp_path)
    for target in ("icon", "icon", "other", "icon", "icon"):
        stale(runtime, run, target=target)
    assert runtime.get_run("alice", run.id).status == "running"
    record_tool_outcome(runtime, run.id, "act", {"action": "back"}, False, "ok")
    stale(runtime, run)
    stale(runtime, run)
    assert runtime.get_run("alice", run.id).status == "running"


def test_user_input_resets_stale_streak(tmp_path):
    runtime, device, run, client = setup(tmp_path)
    stale(runtime, run)
    stale(runtime, run)
    with runtime.store.transaction() as db:
        runtime.store.event(db, run.id, "user_answer", "Continue", {})
    stale(runtime, run)
    stale(runtime, run)
    assert runtime.get_run("alice", run.id).status == "running"


def test_passive_wait_does_not_reset_stale_streak(tmp_path):
    runtime, device, run, client = setup(tmp_path)
    for index in range(3):
        stale(runtime, run, f"s{index}")
        if index < 2:
            record_tool_outcome(runtime, run.id, "act", {"action": "wait"}, False, "ok")
    assert runtime.get_run("alice", run.id).status == "failed"


def test_failed_visual_inspection_and_optional_null_do_not_reset_stale_streak(tmp_path):
    runtime, device, run, client = setup(tmp_path)
    for index in range(3):
        record_tool_outcome(runtime, run.id, "act", {
            "action": "tap", "screen_id": f"s{index}", "target": "icon",
            **({"text": None} if index == 1 else {}),
        }, True, "stale")
        if index < 2:
            record_tool_outcome(runtime, run.id, "describe_screen", {"question": "Find icon"}, True, "stale")
    assert runtime.get_run("alice", run.id).status == "failed"


def test_foreground_package_change_resets_stale_streak(tmp_path):
    runtime, device, run, client = setup(tmp_path)
    observe(runtime, device, run, client, "first")
    stale(runtime, run)
    stale(runtime, run)
    snapshot = runtime.observation(run.id)
    snapshot.package_name = "another.fixture"
    with runtime.store.transaction() as db:
        db.execute("UPDATE runs SET observation=? WHERE id=?", (snapshot.model_dump_json(), run.id))
    stale(runtime, run)
    stale(runtime, run)
    assert runtime.get_run("alice", run.id).status == "running"


def test_observed_package_round_trip_also_resets_stale_streak(tmp_path):
    runtime, device, run, client = setup(tmp_path)
    observe(runtime, device, run, client, "first")
    stale(runtime, run)
    stale(runtime, run)
    snapshot = runtime.observation(run.id)
    original = snapshot.package_name
    for package in ("another.fixture", original):
        snapshot.package_name = package
        with runtime.store.transaction() as db:
            db.execute("UPDATE runs SET observation=? WHERE id=?", (snapshot.model_dump_json(), run.id))
        record_tool_outcome(runtime, run.id, "observe", {}, False, "ok")
    stale(runtime, run)
    stale(runtime, run)
    assert runtime.get_run("alice", run.id).status == "running"

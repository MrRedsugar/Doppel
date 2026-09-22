import asyncio
import hashlib
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from doppel import DoppelRuntime, RuntimeConfig
from doppel.errors import Conflict
from doppel.model_proxy import call_model
from doppel import submission

@pytest.fixture
def queue(tmp_path):
    runtime=DoppelRuntime(RuntimeConfig(data_dir=tmp_path,auto_start=False))
    device=runtime.register_device("alice","phone","Phone")
    yield runtime,device.id
    runtime.store.db.close()

def create(queue,goal="Task",**kw):
    runtime,device=queue
    return runtime.create_run("alice",device,goal,"full",**kw)

def test_mixed_fifo_and_queue_cancellation_preserves_head_command(queue):
    r,d=queue
    a=create(queue,"A"); command=r.queue_command(a.id,"observe")
    b=create(queue,"B",source="schedule",conversation_enabled=False)
    c=create(queue,"C",source="trigger",conversation_enabled=False)
    assert [x.id for x in r.queue_snapshot("alice",d)] == [a.id,b.id,c.id]
    assert [x.queue_position for x in r.queue_snapshot("alice",d)] == [0,1,2]
    r.cancel_run("alice",b.id,expected_status="queued")
    assert r.next_command("alice",d).id == command.id
    assert r.get_run("alice",c.id).queue_position == 1
    with pytest.raises(Conflict):r.start_queued("alice",c.id)
    r.pause_run("alice",a.id)
    with pytest.raises(Conflict):r.start_queued("alice",c.id)
    with pytest.raises(Conflict):r.pause_run("alice",c.id)
    r.cancel_run("alice",a.id)
    assert r.get_run("alice",c.id).status == "queued"
    assert r.start_queued("alice",c.id).status == "running"
    with pytest.raises(Conflict):r.cancel_run("alice",c.id,expected_status="queued")
    assert r.get_run("alice",c.id).status == "running"

@pytest.mark.parametrize("outcome",["completed","failed","cancelled"])
def test_terminal_does_not_automatically_start_next_before_device_cleanup(queue,outcome):
    r,d=queue
    a=create(queue,"A");b=create(queue,"B")
    r.set_status(a.id,outcome)
    assert r.get_run("alice",b.id).status == "queued"
    assert r.next_command("alice",d) is None
    assert r.start_queued("alice",b.id).status == "running"
    assert r.start_queued("alice",b.id).status == "running"

@pytest.mark.parametrize("state",["paused","awaiting_input","awaiting_approval"])
def test_interrupted_head_never_allows_overtaking(queue,state):
    r,d=queue
    a=create(queue,"A");b=create(queue,"B")
    r.set_status(a.id,state)
    with pytest.raises(Conflict):r.start_queued("alice",b.id)
    with pytest.raises(Conflict):r.queue_command(b.id,"observe")
    with pytest.raises(Conflict):r.resume_run("alice",b.id)

@pytest.mark.asyncio
async def test_queued_cannot_call_model_or_start_planner(queue):
    r,d=queue
    a=create(queue,"auto",source="trigger",conversation_enabled=False)
    assert a.status == "queued" and not r.start_run(a.id)
    with pytest.raises(HTTPException) as error:
        await call_model(r,a.id,{"messages":[]})
    assert error.value.status_code == 409
    assert not r.tasks and not r.active_model_calls

def test_concurrent_creation_is_fifo_and_request_keys_are_atomic(queue):
    r,d=queue
    with ThreadPoolExecutor(max_workers=8) as pool:
        runs=list(pool.map(lambda n:create(queue,str(n)),range(20)))
    assert len([x for x in runs if x.status == "running"]) == 1
    snapshot=r.queue_snapshot("alice",d)
    assert [x.queue_sequence for x in snapshot] == sorted(x.queue_sequence for x in runs)
    with ThreadPoolExecutor(max_workers=8) as pool:
        dup=list(pool.map(lambda _:create(queue,"same",request_id="trigger:one"),range(8)))
    assert len(set(x.id for x in dup)) == 1 and len(r.queue_snapshot("alice",d)) == 21
    with pytest.raises(Conflict):create(queue,"other",request_id="trigger:one")

def test_same_conversation_can_link_queued_predecessors(queue):
    r,d=queue
    a=create(queue,"A");b=create(queue,"B",parent_run_id=a.id);c=create(queue,"C",parent_run_id=b.id)
    assert c.conversation_id == a.conversation_id
    assert [x["id"] for x in r.conversation("alice",c.id)] == [a.id,b.id,c.id]

def test_database_commit_failure_rolls_back_submission(queue,monkeypatch):
    r,d=queue
    a=create(queue,"A");command=r.queue_command(a.id,"observe")
    transaction=r.store.transaction
    @contextmanager
    def failed():
        with transaction() as db:
            yield db
            raise OSError("disk full")
    monkeypatch.setattr(r.store,"transaction",failed)
    with pytest.raises(OSError):create(queue,"B",request_id="retry-one")
    assert [x.id for x in r.queue_snapshot("alice",d)] == [a.id]
    monkeypatch.setattr(r.store,"transaction",transaction)
    assert r.next_command("alice",d).id == command.id
    b=create(queue,"B",request_id="retry-one")
    assert b.status == "queued"

def test_capacity_preserves_all_unfinished_tasks(queue):
    r,d=queue
    tasks=[create(queue,str(n)) for n in range(50)]
    with pytest.raises(Conflict):create(queue,"overflow")
    assert len(r.queue_snapshot("alice",d)) == 50
    r.cancel_run("alice",tasks[-1].id)
    assert create(queue,"replacement").status == "queued"
    assert r.queue_snapshot("alice",d)[0].id == tasks[0].id

@pytest.mark.asyncio
async def test_restart_preserves_pending_order_and_reissues_worker_token(queue):
    r,d=queue
    a=create(queue,"A");b=create(queue,"B",request_id="persisted")
    restarted=DoppelRuntime(r.config)
    try:
        assert [(x.id,x.status) for x in restarted.queue_snapshot("alice",d)] == [(a.id,"paused"),(b.id,"queued")]
        assert restarted.create_run("alice",d,"B","full",request_id="persisted").id == b.id
        with pytest.raises(Conflict):restarted.start_queued("alice",b.id)
        restarted.cancel_run("alice",a.id);restarted.start_queued("alice",b.id)
        done=asyncio.Event()
        class Driver:
            async def run(self,runtime,run_id):
                token=runtime.run_tokens[run_id]
                assert runtime.authorize_run_token(run_id,token) == "alice"
                runtime.set_status(run_id,"completed")
                done.set()
        restarted.driver=Driver()
        assert restarted.start_run(b.id) and not restarted.start_run(b.id)
        await asyncio.wait_for(done.wait(),2)
    finally:await restarted.close()

def test_http_queue_contract_and_cancel_precondition(tmp_path):
    from doppel.cli import create_app
    app=create_app(tmp_path,auto_start=False)
    with TestClient(app) as client:
        client.headers["Authorization"]="Bearer "+(tmp_path/"developer-token.txt").read_text().strip()
        d=client.post("/v1/devices",json={"installation_id":"queue","name":"Phone"}).json()["id"]
        body={"device_id":d,"goal":"A","mode":"full"}
        a=client.post("/v1/runs",json=body).json()
        b=client.post("/v1/runs",json={**body,"goal":"B","request_id":"http-one"}).json()
        assert b["status"] == "queued"
        assert [x["id"] for x in client.get(f"/v1/devices/{d}/queue").json()["items"]] == [a["id"],b["id"]]
        assert client.post(f"/v1/runs/{b['id']}/start").status_code == 409
        assert client.post(f"/v1/runs/{a['id']}/cancel",json={"expected_status":"queued"}).status_code == 409
        client.post(f"/v1/runs/{a['id']}/cancel")
        assert client.post(f"/v1/runs/{b['id']}/start").json()["status"] == "running"
        assert client.post(f"/v1/runs/{b['id']}/start").json()["status"] == "running"


def test_deleted_task_history_never_replays_durable_submission(queue):
    r,d=queue
    original=create(queue,"once",request_id="once")
    r.cancel_run("alice",original.id)
    with r.store.transaction() as db:
        db.execute("DELETE FROM events WHERE run_id=?",(original.id,))
        db.execute("DELETE FROM runs WHERE id=?",(original.id,))
    restarted=DoppelRuntime(r.config)
    try:
        duplicate=restarted.create_run("alice",d,"once","full",request_id="once")
        assert duplicate.id == original.id and duplicate.status == "cancelled"
        assert restarted.queue_snapshot("alice",d) == []
        with pytest.raises(Conflict):restarted.create_run("alice",d,"different","full",request_id="once")
    finally:restarted.store.db.close()


def test_submission_retry_still_returns_child_after_parent_history_is_deleted(queue):
    r,d=queue
    parent=create(queue,"parent")
    child=create(queue,"child",parent_run_id=parent.id,request_id="child-once")
    r.cancel_run("alice",parent.id)
    with r.store.transaction() as db:
        db.execute("DELETE FROM events WHERE run_id=?",(parent.id,))
        db.execute("DELETE FROM runs WHERE id=?",(parent.id,))
    duplicate=create(queue,"child",parent_run_id=parent.id,request_id="child-once")
    assert duplicate.id == child.id and duplicate.conversation_id == parent.conversation_id
    assert [run.id for run in r.queue_snapshot("alice",d)] == [child.id]


def test_deferred_manual_task_waits_for_head_start(queue):
    r,d=queue
    task=create(queue,defer_start=True)
    assert task.status == "queued" and r.next_command("alice",d) is None
    assert r.start_queued("alice",task.id).status == "running"


@pytest.mark.parametrize("state",["paused","awaiting_input","awaiting_approval"])
def test_interrupted_head_cancel_checks_observed_state_without_affecting_resumed_work(queue,state):
    r,d=queue
    a=create(queue,"A");b=create(queue,"B")
    r.set_status(a.id,state)
    r.set_status(a.id,"running")
    command=r.queue_command(a.id,"observe")
    with pytest.raises(Conflict):r.cancel_run("alice",a.id,expected_status=state)
    with pytest.raises(ValueError):r.cancel_run("alice",a.id,expected_status="invalid")
    assert r.get_run("alice",a.id).status == "running"
    assert r.next_command("alice",d).id == command.id
    r.set_status(a.id,state)
    assert r.cancel_run("alice",a.id,expected_status=state).status == "cancelled"
    assert r.start_queued("alice",b.id).status == "running"


def test_timed_submission_boundaries_and_legacy_compatibility():
    at=1000000
    key=submission.create(at,"one")
    assert submission.validate(key,at+submission.RETENTION_MS-1) == at
    with pytest.raises(ValueError):submission.validate(key,at+submission.RETENTION_MS)
    with pytest.raises(ValueError):submission.validate(submission.create(at+300001,"future"),at)
    for invalid in ("q2:bad:key","q2:01:key","q2:100:","q2:99999999999999999:key"):
        with pytest.raises(ValueError):submission.validate(invalid,at)
    assert submission.validate("schedule:legacy:1",at) is None


def test_expired_retry_receipts_never_expire_accepted_queue_or_revive_after_clock_rollback(queue,monkeypatch):
    r,d=queue
    clock=[1000000]
    monkeypatch.setattr("doppel.runtime.time.time",lambda:clock[0]/1000)
    old_key=submission.create(clock[0],"old")
    old=create(queue,"old",request_id=old_key,defer_start=True)
    clock[0]+=submission.RETENTION_MS
    new=create(queue,"new",request_id=submission.create(clock[0],"new"))
    assert r.store.one("SELECT COUNT(*) FROM run_submissions")[0] == 1
    assert [(x.id,x.status) for x in r.queue_snapshot("alice",d)] == [(old.id,"queued"),(new.id,"queued")]
    with pytest.raises(ValueError):create(queue,"old",request_id=old_key,defer_start=True)
    clock[0]=1000000
    restarted=DoppelRuntime(r.config)
    try:
        with pytest.raises(ValueError):restarted.create_run("alice",d,"old","full",request_id=old_key,defer_start=True)
        assert restarted.start_queued("alice",old.id).status == "running"
    finally:restarted.store.db.close()


@pytest.mark.parametrize("legacy",[True,False])
def test_retry_capacity_recovers_after_retention_window_and_legacy_does_not_consume_new_quota(queue,monkeypatch,legacy):
    r,d=queue
    clock=[1000000]
    monkeypatch.setattr("doppel.runtime.time.time",lambda:clock[0]/1000)
    key="legacy" if legacy else submission.create(clock[0],"old")
    old=create(queue,"old",request_id=key)
    r.cancel_run("alice",old.id)
    row=r.store.one("SELECT * FROM run_submissions")
    with r.store.transaction() as db:
        db.executemany("INSERT INTO run_submissions VALUES(?,?,?,?,?,?,?)",
            [(row["owner"],row["device_id"],f"seed-{i}",row["run_id"],row["fingerprint"],row["summary"],row["issued_at_ms"]) for i in range(2047)])
    if not legacy:
        with pytest.raises(Conflict):create(queue,request_id=submission.create(clock[0],"full"))
    clock[0]+=submission.RETENTION_MS
    create(queue,request_id=submission.create(clock[0],"next-window"))
    assert r.store.one("SELECT COUNT(*) FROM run_submissions")[0] == (2049 if legacy else 1)
    if legacy:assert create(queue,"old",request_id=key).id == old.id


def test_failed_expiry_cleanup_rolls_back_receipts_and_monotonic_floor(queue,monkeypatch):
    r,d=queue
    clock=[1000000]
    monkeypatch.setattr("doppel.runtime.time.time",lambda:clock[0]/1000)
    key=submission.create(clock[0],"old")
    old=create(queue,"old",request_id=key)
    clock[0]+=submission.RETENTION_MS
    transaction=r.store.transaction
    @contextmanager
    def failed():
        with transaction() as db:
            yield db
            raise OSError("disk full")
    monkeypatch.setattr(r.store,"transaction",failed)
    with pytest.raises(OSError):create(queue,request_id=submission.create(clock[0],"new"))
    monkeypatch.setattr(r.store,"transaction",transaction)
    assert r.store.one("SELECT COUNT(*) FROM run_submissions")[0] == 1
    assert r.store.one("SELECT COUNT(*) FROM submission_retention")[0] == 0
    clock[0]=1000000
    assert create(queue,"old",request_id=key).id == old.id

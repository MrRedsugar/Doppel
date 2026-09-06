import asyncio
from contextlib import contextmanager
from pathlib import Path

import pytest

from doppel import DoppelRuntime, RuntimeConfig
from doppel.errors import Conflict, NotFound
from doppel.models import CommandResult, Node, Observation


@pytest.fixture
def runtime(tmp_path: Path):
    return DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False))


def make_run(runtime, owner="alice", mode="full"):
    device = runtime.register_device(owner, "phone-a", "Test phone")
    run = runtime.create_run(owner, device.id, "Inspect the test app", mode)
    return device, run


def test_foreign_account_cannot_use_device_or_read_run(runtime):
    device, run = make_run(runtime)
    with pytest.raises(NotFound):
        runtime.create_run("bob", device.id, "Read messages", "full")
    with pytest.raises(NotFound):
        runtime.get_run("bob", run.id)


def test_one_device_cannot_execute_two_active_runs(runtime):
    device, run = make_run(runtime)
    with pytest.raises(Conflict):
        runtime.create_run("alice", device.id, "Another task", "full")
    runtime.cancel_run("alice", run.id)
    assert runtime.create_run("alice", device.id, "Next task", "full").id != run.id


def test_command_redelivery_keeps_id_and_duplicate_result_is_idempotent(runtime):
    device, run = make_run(runtime)
    command = runtime.queue_command(run.id, "observe")
    first = runtime.next_command("alice", device.id)
    second = runtime.next_command("alice", device.id)
    assert first.id == second.id == command.id
    result = CommandResult(command_id=command.id, run_id=run.id, status="ok", data={"value": 1})
    runtime.submit_result("alice", device.id, result)
    runtime.submit_result("alice", device.id, result)
    assert runtime.next_command("alice", device.id) is None
    completed = [e for e in runtime.events("alice", run.id) if e.kind == "command_result"]
    assert len(completed) == 1
    with pytest.raises(Conflict):
        runtime.submit_result("alice", device.id, result.model_copy(update={"data": {"value": 2}}))


def test_cancel_prevents_unexecuted_command_and_persists_across_restart(runtime):
    device, run = make_run(runtime)
    command = runtime.queue_command(run.id, "observe")
    runtime.cancel_run("alice", run.id)
    restarted = DoppelRuntime(runtime.config)
    assert restarted.get_run("alice", run.id).status == "cancelled"
    assert restarted.next_command("alice", device.id) is None
    assert restarted.command_result(command.id).status == "cancelled"


def test_paused_run_has_no_dispatch_until_resumed(runtime):
    device, run = make_run(runtime)
    command = runtime.queue_command(run.id, "observe")
    runtime.pause_run("alice", run.id)
    assert runtime.next_command("alice", device.id) is None
    runtime.resume_run("alice", run.id)
    assert runtime.next_command("alice", device.id).id == command.id


def test_wrong_command_run_binding_is_rejected(runtime):
    device, run = make_run(runtime)
    command = runtime.queue_command(run.id, "observe")
    with pytest.raises(Conflict):
        runtime.submit_result("alice", device.id, CommandResult(command_id=command.id, run_id="different", status="ok"))


def test_observation_is_stored_only_for_matching_result(runtime):
    device, run = make_run(runtime)
    command = runtime.queue_command(run.id, "observe")
    observation = Observation(screen_id="screen-a", package_name="test.app", width=100, height=200, nodes=[])
    runtime.submit_result("alice", device.id, CommandResult(command_id=command.id, run_id=run.id, status="ok", observation=observation))
    assert runtime.observation(run.id).screen_id == "screen-a"


@pytest.mark.parametrize("state", ["running", "paused", "awaiting_approval", "awaiting_input"])
async def test_restart_terminates_orphan_runs_without_dispatch(runtime, state):
    device, run = make_run(runtime, mode="ask" if state == "awaiting_approval" else "full")
    command = runtime.queue_command(run.id, "launch", package_name="test.app")
    if state in {"paused", "awaiting_input"}:
        runtime.set_status(run.id, state, pending_request={"id": "input", "kind": "input"})
    await runtime.close()
    restarted = DoppelRuntime(runtime.config)
    try:
        recovered = restarted.get_run("alice", run.id)
        assert recovered.status == "failed"
        assert recovered.pending_request is None
        assert "restart" in recovered.message.lower()
        result = restarted.command_result(command.id)
        assert result.status == "error" and result.data["uncertain"] is True
        assert restarted.next_command("alice", device.id) is None
        assert restarted.create_run("alice", device.id, "Next task", "full").id != run.id
    finally:
        await restarted.close()


@pytest.mark.parametrize("state", ["completed", "failed", "cancelled"])
async def test_restart_preserves_terminal_runs(runtime, state):
    _, run = make_run(runtime)
    original = runtime.set_status(run.id, state, "original reason")
    await runtime.close()
    restarted = DoppelRuntime(runtime.config)
    try:
        assert restarted.get_run("alice", run.id) == original
    finally:
        await restarted.close()


async def test_restart_releases_device_with_no_outstanding_command(runtime):
    device, run = make_run(runtime)
    await runtime.close()
    restarted = DoppelRuntime(runtime.config)
    try:
        assert restarted.get_run("alice", run.id).status == "failed"
        assert restarted.create_run("alice", device.id, "Next task", "full").id != run.id
    finally:
        await restarted.close()


@pytest.mark.parametrize("pending_kind", ["approval", "input"])
def test_pause_preserves_request_created_before_write_transaction(runtime, monkeypatch, pending_kind):
    device, run = make_run(runtime, mode="ask")
    original_transaction = runtime.store.transaction
    pending = []
    inject = True

    @contextmanager
    def interleaved_transaction():
        nonlocal inject
        if inject:
            inject = False
            if pending_kind == "approval":
                command = runtime.queue_command(run.id, "launch", package_name="test.app")
                pending.append(command.id)
            else:
                pending.append("manual-input")
                runtime.set_status(run.id, "awaiting_input", pending_request={"id": pending[0], "kind": "input"})
        with original_transaction() as db:
            yield db

    monkeypatch.setattr(runtime.store, "transaction", interleaved_transaction)
    paused = runtime.pause_run("alice", run.id)
    assert paused.status == "paused"
    assert paused.pending_request is not None
    assert paused.pending_request["id"] == pending[0]
    resumed = runtime.resume_run("alice", run.id)
    assert resumed.status == ("awaiting_approval" if pending_kind == "approval" else "awaiting_input")
    runtime.answer("alice", run.id, pending[0], approve=True if pending_kind == "approval" else None, text="done" if pending_kind == "input" else None)
    assert runtime.get_run("alice", run.id).pending_request is None
    if pending_kind == "approval":
        assert runtime.next_command("alice", device.id).id == pending[0]


def test_concurrent_resume_does_not_resurrect_answered_approval(runtime, monkeypatch):
    device, run = make_run(runtime, mode="ask")
    command = runtime.queue_command(run.id, "launch", package_name="test.app")
    runtime.pause_run("alice", run.id)
    original_transaction = runtime.store.transaction
    inject = True

    @contextmanager
    def interleaved_transaction():
        nonlocal inject
        if inject:
            inject = False
            runtime.resume_run("alice", run.id)
            runtime.answer("alice", run.id, command.id, approve=True)
        with original_transaction() as db:
            yield db

    monkeypatch.setattr(runtime.store, "transaction", interleaved_transaction)
    with pytest.raises(Conflict, match="not paused"):
        runtime.resume_run("alice", run.id)
    assert runtime.get_run("alice", run.id).pending_request is None
    assert runtime.next_command("alice", device.id).id == command.id


@pytest.mark.parametrize("state,expected", [("failed", "error"), ("completed", "error"), ("cancelled", "cancelled")])
async def test_terminal_transition_resolves_perform_and_persists_result(runtime, monkeypatch, state, expected):
    device, run = make_run(runtime)
    queued = asyncio.Event()
    original_queue = runtime.queue_command
    commands = []

    def notified_queue(*args, **kwargs):
        command = original_queue(*args, **kwargs)
        commands.append(command)
        queued.set()
        return command

    monkeypatch.setattr(runtime, "queue_command", notified_queue)
    waiter = asyncio.create_task(runtime.perform(run.id, "observe"))
    await queued.wait()
    runtime.set_status(run.id, state, "stop")
    result = await asyncio.wait_for(waiter, 0.5)
    assert result.status == expected and result.data["uncertain"] is True
    assert runtime.next_command("alice", device.id) is None
    assert runtime.command_result(commands[0].id) == result
    await runtime.close()
    restarted = DoppelRuntime(runtime.config)
    try:
        assert restarted.command_result(commands[0].id) == result
    finally:
        await restarted.close()


async def test_perform_timeout_records_durable_uncertain_result(runtime):
    device, run = make_run(runtime)
    runtime.config.command_timeout = 0
    result = await runtime.perform(run.id, "observe")
    assert result.status == "error" and result.data["uncertain"] is True
    assert runtime.command_result(result.command_id) == result
    assert runtime.get_run("alice", run.id).status == "failed"
    assert runtime.next_command("alice", device.id) is None


async def test_perform_pause_does_not_consume_active_timeout(runtime, monkeypatch):
    device, run = make_run(runtime)
    runtime.config.command_timeout = 1
    clock = [0.0]
    polls = [0]

    async def controlled_sleep(_):
        polls[0] += 1
        if polls[0] == 1:
            runtime.pause_run("alice", run.id)
            clock[0] += 100
        elif polls[0] == 2:
            runtime.resume_run("alice", run.id)
            clock[0] += 100
        elif polls[0] == 3:
            command = runtime.next_command("alice", device.id)
            runtime.submit_result("alice", device.id, CommandResult(command_id=command.id, run_id=run.id, status="ok"))
        else:
            pytest.fail("Command did not finish after result submission")

    monkeypatch.setattr("doppel.runtime.time.monotonic", lambda: clock[0])
    monkeypatch.setattr("doppel.runtime.asyncio.sleep", controlled_sleep)
    result = await runtime.perform(run.id, "observe")
    assert result.status == "ok"
    assert runtime.get_run("alice", run.id).status == "running"


def test_late_result_cannot_revive_failed_command_or_bypass_owner_checks(runtime):
    device, run = make_run(runtime)
    command = runtime.queue_command(run.id, "observe")
    runtime.set_status(run.id, "failed", "unknown device outcome")
    late = CommandResult(command_id=command.id, run_id=run.id, status="ok", observation=Observation(screen_id="late", package_name="test.app", width=100, height=200))
    with pytest.raises(NotFound):
        runtime.submit_result("bob", device.id, late)
    with pytest.raises(Conflict):
        runtime.submit_result("alice", device.id, late)
    assert runtime.get_run("alice", run.id).status == "failed"
    assert runtime.observation(run.id) is None
    assert runtime.command_result(command.id).status == "error"


@pytest.mark.parametrize("mode", ["ask", "assist", "full"])
def test_payment_cannot_be_dispatched_even_after_answer(runtime, mode):
    device, run = make_run(runtime, mode=mode)
    observed = runtime.queue_command(run.id, "observe")
    observation = Observation(screen_id="pay", package_name="test.app", width=100, height=200, nodes=[Node(id="pay", text="Pay $28.00", bounds=[0, 0, 100, 100], clickable=True)])
    runtime.submit_result("alice", device.id, CommandResult(command_id=observed.id, run_id=run.id, status="ok", observation=observation))
    command = runtime.queue_command(run.id, "tap", screen_id="pay", target="pay")
    assert runtime.get_run("alice", run.id).status == "awaiting_input"
    with pytest.raises(ValueError):
        runtime.answer("alice", run.id, command.id, approve=True)
    runtime.answer("alice", run.id, command.id, text="Handled manually")
    assert runtime.command_result(command.id).status == "blocked"
    assert runtime.next_command("alice", device.id) is None

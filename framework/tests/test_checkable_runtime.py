import asyncio

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from doppel import DoppelRuntime, RuntimeConfig, create_router, mcp_server
from doppel.errors import Conflict, ScopeDenied
from doppel.models import CommandResult, Node, Observation


@pytest.fixture
def runtime(tmp_path):
    instance = DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False, command_timeout=0))
    yield instance
    instance.store.db.close()


def observed_toggle(runtime, checked, *, mode="full", checkable=True, allowed_packages=None, label="Notifications"):
    device = runtime.register_device("alice", "checkable-phone", "Test phone")
    run = runtime.create_run("alice", device.id, "Enable notifications", mode, allowed_packages)
    observed = runtime.queue_command(run.id, "observe")
    screen = Observation(screen_id="toggle-screen", package_name="test.app", width=100, height=200,
                         nodes=[Node(id="toggle", text=label, bounds=[0, 0, 80, 50], clickable=True,
                                     checkable=checkable, checked=checked)])
    runtime.submit_result("alice", device.id, CommandResult(command_id=observed.id, run_id=run.id, status="ok", observation=screen))
    return device, run, screen


@pytest.mark.parametrize("checked", [False, True, None])
def test_known_checkable_tap_requires_explicit_desired_state_before_queueing(runtime, checked):
    device, run, _ = observed_toggle(runtime, checked)
    with pytest.raises(ValueError, match="desired_checked"):
        runtime.queue_command(run.id, "tap", target="toggle", screen_id="toggle-screen")
    assert runtime.next_command("alice", device.id) is None
    assert runtime.get_run("alice", run.id).status == "running"
    assert len([event for event in runtime.events("alice", run.id) if event.kind == "command"]) == 1


@pytest.mark.parametrize("checked", [False, True])
@pytest.mark.parametrize("mode", ["ask", "assist", "full"])
def test_cached_checked_state_requires_device_verification_before_noop_evidence(runtime, checked, mode):
    device, run, screen = observed_toggle(runtime, checked, mode=mode)
    command = runtime.queue_command(run.id, "tap", target="toggle", screen_id=screen.screen_id, desired_checked=checked)
    assert runtime.command_result(command.id) is None
    if mode == "ask":
        assert runtime.get_run("alice", run.id).status == "awaiting_approval"
        runtime.answer("alice", run.id, command.id, approve=True)
    assert runtime.next_command("alice", device.id).id == command.id
    fresh = screen.model_copy(update={"screen_id": "verified-on-device"})
    result = CommandResult(command_id=command.id, run_id=run.id, status="ok", observation=fresh,
                           data={"no_op": True, "checked": checked, "desired_checked": checked})
    runtime.submit_result("alice", device.id, result)
    assert runtime.command_result(command.id).observation == fresh
    assert runtime.next_command("alice", device.id) is None
    outcomes = [event for event in runtime.events("alice", run.id) if event.kind == "command_result" and event.data["command_id"] == command.id]
    assert len(outcomes) == 1 and outcomes[0].data["no_op"] is True


@pytest.mark.parametrize("checked,desired", [(False, True), (True, False), (None, True)])
def test_required_checkable_transition_reaches_device_with_explicit_state(runtime, checked, desired):
    device, run, screen = observed_toggle(runtime, checked)
    command = runtime.queue_command(run.id, "tap", target="toggle", screen_id=screen.screen_id, desired_checked=desired)
    dispatched = runtime.next_command("alice", device.id)
    assert dispatched.id == command.id and dispatched.desired_checked is desired
    assert runtime.command_result(command.id) is None


@pytest.mark.parametrize("checkable", [False, None])
def test_legacy_and_noncheckable_taps_keep_existing_dispatch_contract(runtime, checkable):
    device, run, screen = observed_toggle(runtime, None, checkable=checkable)
    command = runtime.queue_command(run.id, "tap", target="toggle", screen_id=screen.screen_id)
    assert runtime.next_command("alice", device.id).id == command.id


def test_desired_state_noop_cannot_bypass_stale_screen(runtime):
    device, run, _ = observed_toggle(runtime, True)
    with pytest.raises(Conflict, match="Screen changed"):
        runtime.queue_command(run.id, "tap", target="toggle", screen_id="previous-screen", desired_checked=True)
    assert runtime.next_command("alice", device.id) is None


def test_desired_state_noop_cannot_bypass_application_scope(runtime):
    device, run, screen = observed_toggle(runtime, True, allowed_packages=["other.app"])
    with pytest.raises(ScopeDenied):
        runtime.queue_command(run.id, "tap", target="toggle", screen_id=screen.screen_id, desired_checked=True)
    assert runtime.next_command("alice", device.id) is None


def test_desired_state_noop_does_not_override_payment_manual_boundary(runtime):
    device, run, screen = observed_toggle(runtime, True, label="Pay $28.00")
    command = runtime.queue_command(run.id, "pay", target="toggle", screen_id=screen.screen_id, desired_checked=True)
    assert runtime.command_result(command.id).status == "blocked"
    assert runtime.next_command("alice", device.id) is None
    assert runtime.get_run("alice", run.id).status == "paused"
    assert runtime.get_run("alice", run.id).pending_request["reason"] == "payment"


def test_noop_tool_result_includes_fresh_device_state_and_host_evidence(runtime, monkeypatch):
    device, run, screen = observed_toggle(runtime, True)
    runtime.config.command_timeout = 2
    original_perform = runtime.perform

    async def with_device_result(run_id, kind, **fields):
        task = asyncio.create_task(original_perform(run_id, kind, **fields))
        async with asyncio.timeout(2):
            while (command := runtime.next_command("alice", device.id)) is None:
                await asyncio.sleep(0.01)
        fresh = screen.model_copy(update={"screen_id": "verified-on-device"})
        runtime.submit_result("alice", device.id, CommandResult(command_id=command.id, run_id=run_id,
                              status="ok", observation=fresh, data={"no_op": True, "checked": True, "desired_checked": True}))
        return await task

    monkeypatch.setattr(runtime, "perform", with_device_result)
    app = FastAPI()
    app.include_router(create_router(runtime, lambda: "alice"))
    with TestClient(app) as client:
        response = client.post(f"/v1/internal/runs/{run.id}/tool",
                               headers={"Authorization": "Bearer " + runtime.run_tokens[run.id]},
                               json={"name": "act", "arguments": {"action": "tap", "target": "toggle",
                                     "screen_id": screen.screen_id, "desired_checked": True}})
    assert response.status_code == 200
    answer = response.json()
    assert answer["status"] == "ok" and answer["no_op"] is True
    assert answer["checked"] is True and answer["desired_checked"] is True
    assert "checked=true" in answer["screen"] and answer["evidence_id"]
    assert runtime.next_command("alice", device.id) is None
    evidence = [event.data for event in runtime.events("alice", run.id) if event.kind == "evidence"]
    assert any(item["id"] == answer["evidence_id"] and item["screen_id"] == "verified-on-device" for item in evidence)


async def test_mcp_act_catalog_advertises_desired_state_and_preserves_boolean(monkeypatch):
    catalog = await mcp_server.server.list_tools()
    act = next(tool for tool in catalog if tool.name == "act")
    assert "desired_checked" in act.input_schema["properties"]
    seen = []

    async def capture(name, arguments):
        seen.append((name, arguments))
        return {"status": "ok"}

    monkeypatch.setattr(mcp_server, "invoke", capture)
    result = await mcp_server.act("tap", target="toggle", screen_id="screen", desired_checked=False)
    assert result == {"status": "ok"}
    assert seen[0][0] == "act" and seen[0][1]["desired_checked"] is False

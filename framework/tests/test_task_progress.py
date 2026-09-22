import copy
import json

import httpx
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import ValidationError

from doppel import DoppelRuntime, RuntimeConfig, create_router, mcp_server
from doppel.errors import Conflict
from doppel.model_proxy import call_model
from doppel.models import CommandResult, Observation, Run, TaskStateUpdate


PLAN = {"plan": ["打开设置", "找到设备信息", "确认手机型号"], "completed": 0, "total_known": True}
RESET = {"plan": [], "completed": 0, "total_known": False}


@pytest.fixture
def setup(tmp_path):
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False))
    device = runtime.register_device("alice", "progress-phone", "Fixture")
    run = runtime.create_run("alice", device.id, "查看手机型号", "full", conversation_enabled=False, source="schedule")
    run = runtime.start_queued("alice",run.id)
    app = FastAPI()
    app.include_router(create_router(runtime, lambda: "alice"))
    client = TestClient(app)
    client.headers["Authorization"] = "Bearer " + runtime.run_tokens[run.id]
    calls = []

    async def perform(run_id, kind, **fields):
        calls.append((kind, fields))
        # Use the production Command model and queue: progress must never reach the device schema.
        command = runtime.queue_command(run_id, kind, **fields)
        runtime.next_command("alice", device.id)
        screen = Observation(screen_id=f"frame-{len(calls)}", package_name="fixture", width=100, height=200)
        result = CommandResult(command_id=command.id, run_id=run_id, status="ok", observation=screen)
        runtime.submit_result("alice", device.id, result)
        return result

    runtime.perform = perform
    yield runtime, device, run, client, calls
    client.close()
    runtime.store.db.close()


def call(client, run, name, **arguments):
    return client.post(f"/v1/internal/runs/{run.id}/tool", json={"name": name, "arguments": arguments})


def test_first_observation_and_actions_publish_revisable_progress_without_extra_calls(setup):
    runtime, _, run, client, calls = setup
    assert runtime.get_run("alice", run.id).task_state == {}
    response = call(client, run, "observe", task_state={"phase": "准备查看手机", "progress": PLAN})
    assert response.status_code == 200 and response.json()["evidence_id"]
    assert calls == [("observe", {})]
    assert client.get(f"/v1/runs/{run.id}").json()["task_state"]["progress"] == PLAN
    assert client.get("/v1/runs").json()["items"][0]["task_state"]["progress"] == PLAN

    revised = {"plan": ["找到系统设置", "核对设备型号"], "completed": 1, "total_known": False}
    response = call(client, run, "act", action="home", task_state={"phase": "查找设置", "progress": revised})
    assert response.status_code == 200
    assert calls == [("observe", {}), ("home", {})]
    saved = runtime.get_run("alice", run.id)
    assert saved.task_state == {"phase": "查找设置", "progress": revised}
    assert saved.status == "running" and saved.conversation_messages == []
    assert not any(event.kind == "model_start" for event in runtime.events("alice", run.id))


def test_null_and_omitted_progress_preserve_but_explicit_empty_plan_resets(setup):
    runtime, _, run, client, _ = setup
    runtime.update_task_state(run.id, {"phase": "查看手机", "progress": PLAN})
    runtime.update_task_state(run.id, {"progress": None})
    assert runtime.get_run("alice", run.id).task_state["progress"] == PLAN
    assert call(client, run, "observe", task_state=None).status_code == 200
    runtime.update_task_state(run.id, {"phase": ""})
    assert runtime.get_run("alice", run.id).task_state == {"phase": "", "progress": PLAN}
    runtime.update_task_state(run.id, {"progress": RESET})
    assert runtime.get_run("alice", run.id).task_state["progress"] == RESET


@pytest.mark.parametrize("state", [
    {"phase": 1}, {"phase": "x" * 121}, {"action": "tap"},
    {"progress": {**PLAN, "points": [[1, 2]]}},
    {"progress": {**PLAN, "completed": True}}, {"progress": {**PLAN, "completed": "1"}},
    {"progress": {**PLAN, "completed": 1.0}}, {"progress": {**PLAN, "completed": -1}},
    {"progress": {**PLAN, "completed": 4}}, {"progress": {**PLAN, "total_known": 1}},
    {"progress": {**PLAN, "plan": ["x"] * 6}}, {"progress": {**PLAN, "plan": ["x" * 61]}},
    {"progress": {**PLAN, "plan": [" "]}}, {"progress": {**PLAN, "plan": [17]}},
    {"progress": {**RESET, "total_known": True}},
])
def test_invalid_metadata_rejects_before_device_dispatch_and_never_replaces_state(setup, state):
    runtime, _, run, client, calls = setup
    runtime.update_task_state(run.id, {"progress": PLAN})
    assert call(client, run, "act", action="home", task_state=state).status_code == 422
    assert runtime.get_run("alice", run.id).task_state == {"progress": PLAN}
    assert calls == []


def test_progress_cannot_finish_task_or_replace_current_evidence(setup):
    runtime, _, run, client, _ = setup
    runtime.update_task_state(run.id, {"progress": PLAN})
    completed = {**PLAN, "completed": 3}
    response = call(client, run, "finish_task", outcome="completed", summary="已完成", task_state={"progress": completed})
    assert response.status_code == 422
    assert runtime.get_run("alice", run.id).task_state["progress"] == PLAN
    evidence = call(client, run, "observe").json()["evidence_id"]
    assert call(client, run, "finish_task", outcome="completed", summary="已看到手机型号", evidence_ids=[evidence], task_state={"progress": None}).status_code == 200
    saved = runtime.get_run("alice", run.id)
    assert saved.status == "completed"
    assert saved.task_state == {"phase": "completed", "progress": completed}


@pytest.mark.parametrize("outcome", ["failed", "cancelled", "paused"])
def test_incomplete_outcomes_preserve_progress_and_reject_late_updates(setup, outcome):
    runtime, _, run, client, _ = setup
    progress = {**PLAN, "completed": 1}
    runtime.update_task_state(run.id, {"progress": progress})
    if outcome == "failed":
        assert call(client, run, "finish_task", outcome=outcome, summary="无法确认设备信息").status_code == 200
    elif outcome == "cancelled":
        runtime.cancel_run("alice", run.id)
    else:
        runtime.pause_run("alice", run.id)
    assert runtime.get_run("alice", run.id).task_state["progress"] == progress
    with pytest.raises(Conflict):
        runtime.update_task_state(run.id, {"progress": {**PLAN, "completed": 3}})


def test_progress_survives_reload_and_legacy_runs_default_to_unknown(setup):
    runtime, _, run, _, _ = setup
    runtime.update_task_state(run.id, {"progress": {**PLAN, "total_known": False}})
    raw = runtime._row(run.id)["payload"]
    assert Run.model_validate_json(raw).task_state["progress"]["total_known"] is False
    old = run.model_dump()
    old.pop("task_state")
    assert Run.model_validate(old).task_state == {}
    with pytest.raises(ValidationError):
        TaskStateUpdate.model_validate({"remaining_steps": ["点击我的"]})


async def test_existing_mcp_tools_piggyback_metadata_without_adding_a_tool_call(monkeypatch):
    catalog = {tool.name: tool for tool in await mcp_server.server.list_tools()}
    for name in ("observe", "act", "finish_task"):
        assert "task_state" in catalog[name].input_schema["properties"]
        assert "task_state" not in catalog[name].input_schema.get("required", [])
    calls = []

    async def invoke(name, arguments):
        calls.append((name, arguments))
        return {"status": "ok"}

    monkeypatch.setattr(mcp_server, "invoke", invoke)
    state = TaskStateUpdate(progress=PLAN)
    await mcp_server.observe(state)
    await mcp_server.act("home", task_state=state)
    await mcp_server.finish_task("failed", "测试结束", task_state=state)
    assert [name for name, _ in calls] == ["observe", "act", "finish_task"]
    assert all(arguments["task_state"] == {"progress": PLAN} for _, arguments in calls)


async def test_later_model_calls_restore_latest_progress_without_extra_requests_or_grounder_context(setup, tmp_path):
    runtime, _, run, _, _ = setup
    key = tmp_path / "key.txt"
    key.write_text("sk-fixture-progress-key")
    runtime.config.api_key_file = key
    payloads = []

    def upstream(request):
        payloads.append(json.loads(request.content))
        return httpx.Response(200, json={"choices": [], "usage": {"prompt_tokens": 10, "completion_tokens": 1}})

    runtime.upstream_transport = httpx.MockTransport(upstream)
    body = {"messages": [{"role": "system", "content": "Fixture planner"}, {"role": "user", "content": run.goal}]}
    original = copy.deepcopy(body)
    await call_model(runtime, run.id, body)
    assert payloads[0]["messages"] == body["messages"]

    states = [
        {"phase": "查看设置", "progress": PLAN},
        {"phase": "已找到设备信息", "progress": {"plan": ["核对设备型号"], "completed": 0, "total_known": True}},
        {"phase": "重新判断", "progress": RESET},
    ]
    for index, state in enumerate(states, start=1):
        runtime.update_task_state(run.id, state)
        # Each fresh caller has only its goal, simulating a later/restored Harness context.
        assert (await call_model(runtime, run.id, body)).status_code == 200
        messages = payloads[index]["messages"]
        assert messages[0] == body["messages"][0] and messages[2:] == body["messages"][1:]
        assert json.loads(messages[1]["content"].split("：", 1)[1]) == state
    assert (await call_model(runtime, run.id, body, auxiliary=True)).status_code == 200
    assert payloads[-1]["messages"] == body["messages"]
    assert body == original
    assert len(payloads) == 5
    assert sum(event.kind == "model_start" for event in runtime.events("alice", run.id)) == 5

import asyncio

from fastapi import FastAPI
import httpx
import pytest

from doppel import DoppelRuntime, RuntimeConfig, create_router
from doppel.errors import Conflict
from doppel.models import Command, CommandResult, Node, Observation
from doppel.policy import ActionPolicy


def screen(text="Continue", screen_id="fresh", interactive=True):
    return Observation(screen_id=screen_id, package_name="fixture.app", width=100, height=200,
                       nodes=[Node(id="target", text=text, clickable=interactive, editable=interactive, bounds=[0, 0, 100, 100])])


@pytest.mark.parametrize("text", ["安全验证", "请拖动滑块完成拼图", "请完成下方人机验证", "Verify you are human", "I'm not a robot", "Drag the slider to complete the puzzle"])
def test_strong_verification_challenges_require_takeover(text):
    assert ActionPolicy().requires_verification(screen(text, interactive=False)) is True


@pytest.mark.parametrize("text", ["验证码", "获取短信验证码", "输入验证码登录", "账号安全设置", "Security settings", "Verification code", "Captcha support documentation", "查看安全验证设置", "验证成功"])
def test_login_codes_and_security_settings_are_not_challenges(text):
    assert ActionPolicy().requires_verification(screen(text)) is False


@pytest.mark.parametrize("text", ["人机验证", "安全验证", "Security verification", "CAPTCHA"])
def test_challenge_menu_entries_do_not_block_navigation(text):
    assert ActionPolicy().requires_verification(screen(text)) is False


@pytest.mark.parametrize("text", ["请完成安全验证", "请进行安全验证", "请验证您是真人", "请验证你是真人", "请拖动滑块完成拼图", "I'm not a robot"])
def test_explicit_verification_instruction_can_be_interactive(text):
    assert ActionPolicy().requires_verification(screen(text)) is True


@pytest.mark.asyncio
@pytest.mark.parametrize("reason", ["verification", "login"])
async def test_takeover_preserves_run_and_requires_resume_then_fresh_observation(tmp_path, reason):
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False))
    device = runtime.register_device("alice", "fixture", "Fixture")
    run = runtime.create_run("alice", device.id, "Continue the fixture", "full")
    observed = runtime.queue_command(run.id, "observe")
    runtime.submit_result("alice", device.id, CommandResult(command_id=observed.id, run_id=run.id, status="ok", observation=screen()))
    task = asyncio.create_task(runtime.perform(run.id, "tap", target="target", screen_id="fresh"))
    try:
        async with asyncio.timeout(2):
            while (command := runtime.next_command("alice", device.id)) is None:
                await asyncio.sleep(0.01)
        result = CommandResult(command_id=command.id, run_id=run.id, status="blocked", data={"human_takeover": reason}, observation=screen())
        runtime.submit_result("alice", device.id, result)
        runtime.submit_result("alice", device.id, result)
        paused = runtime.get_run("alice", run.id)
        assert paused.status == "paused" and paused.pending_request["reason"] == reason
        assert paused.pending_request["manual_only"] is True
        await asyncio.sleep(0.15)
        assert not task.done() and runtime.next_command("alice", device.id) is None
        with pytest.raises(Conflict):
            runtime.answer("alice", run.id, paused.pending_request["id"], text="continue")
        runtime.resume_run("alice", run.id)
        assert runtime.observation(run.id) is None
        with pytest.raises(Conflict):
            runtime.queue_command(run.id, "launch", package_name="fixture.app")
        async with asyncio.timeout(2):
            while (fresh := runtime.next_command("alice", device.id)) is None:
                await asyncio.sleep(0.01)
        assert fresh.kind == "observe"
        runtime.submit_result("alice", device.id, CommandResult(command_id=fresh.id, run_id=run.id, status="ok", observation=screen(screen_id="after-human")))
        answer = await asyncio.wait_for(task, 2)
        assert answer.status == "blocked" and answer.data["human_takeover"] == reason
        assert answer.observation.screen_id == "after-human"
        assert runtime.get_run("alice", run.id).requires_fresh_observation is False
        assert runtime.get_run("alice", run.id).status == "running"
        assert runtime.next_command("alice", device.id) is None
    finally:
        runtime.cancel_run("alice", run.id)
        await asyncio.gather(task, return_exceptions=True)
        await runtime.close()


@pytest.mark.asyncio
async def test_challenge_remaining_after_resume_waits_again_without_replaying_action(tmp_path):
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False))
    device = runtime.register_device("alice", "fixture", "Fixture")
    run = runtime.create_run("alice", device.id, "Continue the fixture", "full")
    task = asyncio.create_task(runtime.perform(run.id, "observe"))
    try:
        for number in range(2):
            async with asyncio.timeout(2):
                while (command := runtime.next_command("alice", device.id)) is None:
                    await asyncio.sleep(0.01)
            assert command.kind == "observe"
            runtime.submit_result("alice", device.id, CommandResult(command_id=command.id, run_id=run.id, status="ok", observation=screen("请完成安全验证", screen_id=str(number), interactive=False)))
            await asyncio.sleep(0.15)
            assert runtime.get_run("alice", run.id).status == "paused"
            assert not task.done() and runtime.next_command("alice", device.id) is None
            if number == 0:
                runtime.resume_run("alice", run.id)
        runtime.cancel_run("alice", run.id)
        result = await asyncio.wait_for(task, 2)
        assert result.status == "cancelled"
        commands = runtime.store.all("SELECT payload FROM commands WHERE run_id=?", (run.id,))
        assert len(commands) == 2
    finally:
        runtime.cancel_run("alice", run.id)
        await asyncio.gather(task, return_exceptions=True)
        await runtime.close()


@pytest.mark.asyncio
async def test_failed_resume_observation_returns_to_human_pause_without_retry(tmp_path):
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False))
    device = runtime.register_device("alice", "fixture", "Fixture")
    run = runtime.create_run("alice", device.id, "Continue the fixture", "full")
    task = asyncio.create_task(runtime.perform(run.id, "observe"))
    try:
        async with asyncio.timeout(2):
            while (command := runtime.next_command("alice", device.id)) is None:
                await asyncio.sleep(0.01)
        runtime.submit_result("alice", device.id, CommandResult(command_id=command.id, run_id=run.id, status="ok", observation=screen("安全验证", interactive=False)))
        await asyncio.sleep(0.15)
        runtime.resume_run("alice", run.id)
        async with asyncio.timeout(2):
            while (fresh := runtime.next_command("alice", device.id)) is None:
                await asyncio.sleep(0.01)
        runtime.submit_result("alice", device.id, CommandResult(command_id=fresh.id, run_id=run.id, status="error", message="Fixture unavailable"))
        await asyncio.sleep(0.15)
        assert runtime.get_run("alice", run.id).status == "paused"
        assert not task.done() and runtime.next_command("alice", device.id) is None
        runtime.cancel_run("alice", run.id)
        assert (await asyncio.wait_for(task, 2)).status == "cancelled"
    finally:
        runtime.cancel_run("alice", run.id)
        await asyncio.gather(task, return_exceptions=True)
        await runtime.close()


def test_host_observation_detects_challenge_before_any_mutation(tmp_path):
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False))
    device = runtime.register_device("alice", "fixture", "Fixture")
    run = runtime.create_run("alice", device.id, "Continue the fixture", "full")
    command = runtime.queue_command(run.id, "observe")
    runtime.submit_result("alice", device.id, CommandResult(command_id=command.id, run_id=run.id, status="ok", observation=screen("安全验证", interactive=False)))
    assert runtime.get_run("alice", run.id).status == "paused"
    with pytest.raises(Conflict):
        runtime.queue_command(run.id, "tap", target="target", screen_id="fresh")


@pytest.mark.parametrize("kind", ["long_press", "login_phone", "login_code"])
def test_native_commands_preserve_target_and_private_input_checks_without_payment_word_inference(kind):
    fields = {"package_name": "fixture.app"} if kind.startswith("login_") else {}
    command = Command(id="command", run_id="run", kind=kind, target="target", screen_id="fresh", **fields)
    observation = screen()
    observation.nodes[0].long_clickable = True
    assert ActionPolicy().evaluate("full", command, observation).decision == "allow"
    assert ActionPolicy().evaluate("ask", command, observation).decision == "approve"
    assert ActionPolicy().evaluate("full", command.model_copy(update={"screen_id": "old"}), observation).decision == "deny"
    observation.nodes[0].text = "立即支付"
    assert ActionPolicy().evaluate("full", command, observation).decision == "allow"
    observation.nodes[0].text = "Continue"
    observation.nodes[0].password = True
    assert ActionPolicy().evaluate("full", command, observation).decision == "manual"


@pytest.mark.parametrize("kind", ["login_phone", "login_code"])
def test_login_command_never_accepts_sensitive_text_or_missing_package(kind):
    with pytest.raises(ValueError):
        Command(id="c", run_id="r", kind=kind, target="target", screen_id="fresh", package_name="fixture.app", text="synthetic-input")
    with pytest.raises(ValueError):
        Command(id="c", run_id="r", kind=kind, target="target", screen_id="fresh")


@pytest.mark.asyncio
@pytest.mark.parametrize("kind", ["login_phone", "login_code"])
async def test_login_tool_derives_package_and_returns_no_sensitive_result_fields(tmp_path, kind):
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False))
    device = runtime.register_device("alice", "fixture", "Fixture")
    run = runtime.create_run("alice", device.id, "Continue the fixture", "full", ["fixture.app"])
    initial = runtime.queue_command(run.id, "observe")
    runtime.submit_result("alice", device.id, CommandResult(command_id=initial.id, run_id=run.id, status="ok", observation=screen()))
    app = FastAPI()
    app.include_router(create_router(runtime, lambda: "alice"))
    task = None
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://fixture", headers={"Authorization": "Bearer " + runtime.run_tokens[run.id]}) as client:
        try:
            url = f"/v1/internal/runs/{run.id}/tool"
            for extra in ({"text": "synthetic-value"}, {"package_name": "foreign.app"}):
                response = await client.post(url, json={"name": kind, "arguments": {"target": "target", "screen_id": "fresh", **extra}})
                assert response.status_code == 422
            task = asyncio.create_task(client.post(url, json={"name": kind, "arguments": {"target": "target", "screen_id": "fresh"}}))
            async with asyncio.timeout(2):
                while (command := runtime.next_command("alice", device.id)) is None:
                    await asyncio.sleep(0.01)
            assert command.kind == kind and command.package_name == "fixture.app"
            assert command.text is None
            runtime.submit_result("alice", device.id, CommandResult(command_id=command.id, run_id=run.id, status="ok", message="device-synthetic-detail", data={"private_fixture": "must-not-be-returned"}))
            response = await asyncio.wait_for(task, 2)
            assert response.status_code == 200 and response.json()["status"] == "ok"
            assert "device-synthetic-detail" not in response.text
            assert "must-not-be-returned" not in response.text
        finally:
            runtime.cancel_run("alice", run.id)
            if task:
                await asyncio.gather(task, return_exceptions=True)
            await runtime.close()


@pytest.mark.asyncio
async def test_verification_stops_provider_calls_until_resume_and_new_screen(tmp_path):
    from test_model_proxy import BillingRecorder
    key = tmp_path / "key.txt"
    key.write_text("sk-" + "fixture-not-a-real-provider-key", encoding="ascii")
    billing = BillingRecorder()
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path / "runtime", api_key_file=key, auto_start=False), billing)
    device = runtime.register_device("alice", "fixture", "Fixture")
    run = runtime.create_run("alice", device.id, "Continue the fixture", "full")
    observed = runtime.queue_command(run.id, "observe")
    runtime.submit_result("alice", device.id, CommandResult(command_id=observed.id, run_id=run.id, status="ok", observation=screen("安全验证", interactive=False)))
    calls = []
    def upstream(request):
        calls.append(request)
        return httpx.Response(200, json={"choices": [], "usage": {"prompt_tokens": 12, "completion_tokens": 3}})
    runtime.upstream_transport = httpx.MockTransport(upstream)
    app = FastAPI()
    app.include_router(create_router(runtime, lambda: "alice"))
    task = None
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://fixture", headers={"Authorization": "Bearer " + runtime.run_tokens[run.id]}) as client:
        try:
            task = asyncio.create_task(client.post(f"/v1/internal/runs/{run.id}/chat/completions", json={"messages": []}))
            await asyncio.sleep(0.25)
            assert not task.done() and calls == [] and billing.reservations == []
            runtime.resume_run("alice", run.id)
            await asyncio.sleep(0.25)
            assert not task.done() and calls == [] and billing.reservations == []
            fresh = runtime.queue_command(run.id, "observe")
            runtime.submit_result("alice", device.id, CommandResult(command_id=fresh.id, run_id=run.id, status="ok", observation=screen(screen_id="resolved")))
            response = await asyncio.wait_for(task, 2)
            assert response.status_code == 200
            assert len(calls) == len(billing.reservations) == 1
        finally:
            runtime.cancel_run("alice", run.id)
            if task:
                await asyncio.gather(task, return_exceptions=True)
            await runtime.close()


@pytest.mark.asyncio
async def test_mcp_schema_exposes_long_press_and_value_free_login_tools():
    from doppel.mcp_server import server
    tools = {tool.name: tool for tool in await server.list_tools()}
    assert "long_press" in tools["act"].input_schema["properties"]["action"]["enum"]
    for name in ("login_phone", "login_code"):
        assert set(tools[name].input_schema["properties"]) == {"target", "screen_id"}
        assert set(tools[name].input_schema["required"]) == {"target", "screen_id"}


@pytest.mark.asyncio
async def test_fresh_observation_gate_rejects_malformed_tool_name_with_422(tmp_path):
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False))
    device = runtime.register_device("alice", "fixture", "Fixture")
    run = runtime.create_run("alice", device.id, "Continue the fixture", "full")
    command = runtime.queue_command(run.id, "observe")
    runtime.submit_result("alice", device.id, CommandResult(command_id=command.id, run_id=run.id, status="ok", observation=screen("安全验证", interactive=False)))
    runtime.resume_run("alice", run.id)
    app = FastAPI()
    app.include_router(create_router(runtime, lambda: "alice"))
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app, raise_app_exceptions=False), base_url="http://fixture", headers={"Authorization": "Bearer " + runtime.run_tokens[run.id]}) as client:
        try:
            response = await client.post(f"/v1/internal/runs/{run.id}/tool", json={"name": [], "arguments": {}})
            assert response.status_code == 422
            assert runtime.get_run("alice", run.id).requires_fresh_observation is True
            assert runtime.next_command("alice", device.id) is None
        finally:
            runtime.cancel_run("alice", run.id)
            await runtime.close()

import asyncio
from contextlib import asynccontextmanager

import httpx
import pytest
from fastapi import FastAPI

from doppel import DoppelRuntime, RuntimeConfig, create_router
from doppel.errors import PermissionDenied
from doppel.models import CommandResult, Node, Observation
from test_model_proxy import BillingRecorder


def screen(screen_id="foreign", package="system.dialog"):
    return Observation(screen_id=screen_id, package_name=package, width=100, height=200,
                       nodes=[Node(id="confirm", text="Continue", clickable=True, editable=True,
                                   bounds=[0, 0, 100, 100])])


@asynccontextmanager
async def scope_fixture(tmp_path):
    key = tmp_path / "key.txt"
    key.write_text("sk-fixture-not-a-real-provider-key")
    billing = BillingRecorder()
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path / "data", api_key_file=key, auto_start=False), billing)
    device = runtime.register_device("alice", "fixture", "Fixture")
    run = runtime.create_run("alice", device.id, "Continue in the selected application", "full", ["allowed.app"])
    observed = runtime.queue_command(run.id, "observe")
    runtime.submit_result("alice", device.id, CommandResult(command_id=observed.id, run_id=run.id, status="ok", observation=screen()))
    app = FastAPI()
    app.include_router(create_router(runtime, lambda: "alice"))
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://fixture",
                                headers={"Authorization": "Bearer " + runtime.run_tokens[run.id]}) as client:
        try:
            yield runtime, device, run, client, billing
        finally:
            runtime.cancel_run("alice", run.id)
            await runtime.close()


def act(client, run, action="tap"):
    return client.post(f"/v1/internal/runs/{run.id}/tool", json={"name": "act", "arguments": {
        "action": action, "screen_id": "foreign", "target": "confirm",
        **({"text": "Example"} if action == "type" else {}),
    }})


async def wait_for_takeover(runtime, run, request):
    async with asyncio.timeout(2):
        while runtime.get_run("alice", run.id).status == "running" and not request.done():
            await asyncio.sleep(0.01)
    current = runtime.get_run("alice", run.id)
    assert current.status == "awaiting_input"
    assert current.pending_request["kind"] == "input"
    assert current.pending_request["manual_only"] is True
    assert not request.done()
    return current.pending_request


async def next_command(runtime, device):
    async with asyncio.timeout(2):
        while (command := runtime.next_command("alice", device.id)) is None:
            await asyncio.sleep(0.01)
    return command


@pytest.mark.asyncio
@pytest.mark.parametrize("action", ["tap", "type", "scroll", "back"])
async def test_foreign_screen_mutation_waits_for_manual_input_and_can_cancel(tmp_path, action):
    async with scope_fixture(tmp_path) as (runtime, device, run, client, billing):
        request = asyncio.create_task(act(client, run, action))
        try:
            await wait_for_takeover(runtime, run, request)
            assert runtime.get_run("alice", run.id).allowed_packages == ["allowed.app"]
            assert runtime.next_command("alice", device.id) is None
            assert len(runtime.store.all("SELECT id FROM commands WHERE run_id=?", (run.id,))) == 1
            runtime.cancel_run("alice", run.id)
            response = await asyncio.wait_for(request, 2)
            assert response.status_code == 200
            assert response.json()["status"] == "cancelled"
            assert runtime.get_run("alice", run.id).pending_request is None
        finally:
            request.cancel()
            await asyncio.gather(request, return_exceptions=True)


@pytest.mark.asyncio
async def test_scope_takeover_blocks_another_provider_request_and_reservation(tmp_path):
    async with scope_fixture(tmp_path) as (runtime, device, run, client, billing):
        provider_calls = []

        def upstream(request):
            provider_calls.append(request)
            return httpx.Response(200, json={"choices": [], "usage": {"prompt_tokens": 12, "completion_tokens": 3}})

        runtime.upstream_transport = httpx.MockTransport(upstream)
        url = f"/v1/internal/runs/{run.id}/chat/completions"
        assert (await client.post(url, json={"messages": []})).status_code == 200
        request = asyncio.create_task(act(client, run))
        model_request = None
        try:
            await wait_for_takeover(runtime, run, request)
            model_request = asyncio.create_task(client.post(url, json={"messages": []}))
            await asyncio.sleep(0.25)
            assert not model_request.done()
            assert len(provider_calls) == len(billing.reservations) == 1
            assert len([event for event in runtime.events("alice", run.id) if event.kind == "model_start"]) == 1
            runtime.cancel_run("alice", run.id)
            assert (await asyncio.wait_for(request, 2)).json()["status"] == "cancelled"
            result = await asyncio.wait_for(model_request, 2)
            assert result.json()["usage"]["total_tokens"] == 0
            assert len(provider_calls) == len(billing.reservations) == 1
        finally:
            pending = [task for task in (request, model_request) if task is not None]
            for task in pending:
                task.cancel()
            await asyncio.gather(*pending, return_exceptions=True)


@pytest.mark.asyncio
async def test_scope_answer_observes_fresh_screen_without_replaying_denied_action(tmp_path):
    async with scope_fixture(tmp_path) as (runtime, device, run, client, billing):
        request = asyncio.create_task(act(client, run))
        try:
            pending = await wait_for_takeover(runtime, run, request)
            with pytest.raises(ValueError):
                runtime.answer("alice", run.id, pending["id"], approve=True)
            runtime.answer("alice", run.id, pending["id"], text="Handled on the phone")
            assert runtime.observation(run.id) is None
            command = await next_command(runtime, device)
            assert command.kind == "observe"
            assert not request.done()
            runtime.submit_result("alice", device.id, CommandResult(command_id=command.id, run_id=run.id,
                                  status="ok", observation=screen("fresh", "allowed.app")))
            response = await asyncio.wait_for(request, 2)
            result = response.json()
            assert response.status_code == 200
            assert result["status"] == "blocked"
            assert result["error"] == "application_scope"
            assert result["answer"] == "Handled on the phone"
            assert "screen fresh" in result["screen"] and "allowed.app" in result["screen"]
            assert "evidence_id" not in result
            assert runtime.get_run("alice", run.id).status == "running"
            assert runtime.get_run("alice", run.id).allowed_packages == ["allowed.app"]
            assert runtime.next_command("alice", device.id) is None
            assert [event.message for event in runtime.events("alice", run.id) if event.kind == "command"] == ["observe", "observe"]
            permitted = runtime.queue_command(run.id, "tap", screen_id="fresh", target="confirm")
            assert runtime.next_command("alice", device.id).id == permitted.id
        finally:
            request.cancel()
            await asyncio.gather(request, return_exceptions=True)


@pytest.mark.asyncio
async def test_direct_scope_errors_and_foreign_screen_observe_and_allowed_launch_stay_available(tmp_path):
    async with scope_fixture(tmp_path) as (runtime, device, run, client, billing):
        with pytest.raises(PermissionDenied) as error:
            runtime.queue_command(run.id, "tap", screen_id="foreign", target="confirm")
        assert error.value.status_code == 403
        assert runtime.get_run("alice", run.id).status == "running"
        for kind, fields in (("observe", {}), ("launch", {"package_name": "allowed.app"})):
            command = runtime.queue_command(run.id, kind, **fields)
            assert runtime.next_command("alice", device.id).id == command.id
            runtime.submit_result("alice", device.id, CommandResult(command_id=command.id, run_id=run.id, status="ok", observation=screen()))
        with pytest.raises(PermissionDenied):
            runtime.queue_command(run.id, "launch", package_name="system.dialog")
        assert runtime.get_run("alice", run.id).allowed_packages == ["allowed.app"]

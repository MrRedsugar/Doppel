import asyncio
from contextlib import asynccontextmanager
import socket

from fastapi import FastAPI
import httpx
from mcp.server import MCPServer
import pytest
import uvicorn

from doppel import DoppelRuntime, RuntimeConfig, create_router
from doppel.extension_runtime import get_extension_manager
from doppel.extensions import MCPConnector


@asynccontextmanager
async def extension_fixture(tmp_path):
    provider = MCPServer("Approval fixture")
    calls = []

    @provider.tool()
    def write(value: int) -> dict:
        calls.append(value)
        return {"saved": value}

    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    server = uvicorn.Server(uvicorn.Config(provider.streamable_http_app(), log_level="error", timeout_graceful_shutdown=1))
    serving = asyncio.create_task(server.serve(sockets=[sock]))
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False))
    device = runtime.register_device("alice", "fixture", "Fixture")
    run = runtime.create_run("alice", device.id, "Write the fixture", "assist")
    manager = get_extension_manager(runtime)
    configuration = {"name": "fixture", "url": f"http://127.0.0.1:{sock.getsockname()[1]}/mcp", "allowed_tools": ["write"], "read_only_tools": []}
    manager.create_config("alice", configuration)
    app = FastAPI()
    app.include_router(create_router(runtime, lambda: "alice"))
    try:
        async with asyncio.timeout(10):
            while not server.started:
                await asyncio.sleep(0.01)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://fixture", headers={"Authorization": "Bearer " + runtime.run_tokens[run.id]}) as client:
            yield runtime, run, manager, configuration, client, calls
    finally:
        server.should_exit = True
        await asyncio.wait_for(serving, 5)
        sock.close()
        await runtime.close()


async def wait_for_approval(runtime, run):
    async with asyncio.timeout(8):
        while True:
            current = runtime.get_run("alice", run.id)
            if current.status == "awaiting_approval":
                return current.pending_request
            await asyncio.sleep(0.01)


def call_extension(client, run):
    return client.post(f"/v1/internal/runs/{run.id}/tool", json={
        "name": "call_extension", "arguments": {"name": "mcp.fixture.write", "arguments": {"value": 7}},
    })


@pytest.mark.asyncio
async def test_extension_http_approval_requires_exact_revision(tmp_path):
    async with extension_fixture(tmp_path) as (runtime, run, manager, configuration, client, calls):
        request = asyncio.create_task(call_extension(client, run))
        pending = await wait_for_approval(runtime, run)
        assert calls == []
        revised = await client.put("/v1/extensions/fixture", json=configuration)
        assert revised.status_code == 200
        answer = await client.post(f"/v1/runs/{run.id}/answer", json={"request_id": pending["id"], "approve": True})
        try:
            assert answer.status_code == 409
            assert calls == []
            assert runtime.get_run("alice", run.id).pending_request["id"] == pending["id"]
        finally:
            runtime.cancel_run("alice", run.id)
            await asyncio.wait_for(request, 5)


@pytest.mark.asyncio
async def test_new_tool_cannot_replace_pending_extension_approval(tmp_path):
    async with extension_fixture(tmp_path) as (runtime, run, manager, configuration, client, calls):
        request = asyncio.create_task(call_extension(client, run))
        pending = await wait_for_approval(runtime, run)
        try:
            response = await client.post(f"/v1/internal/runs/{run.id}/tool", json={"name": "read_memory", "arguments": {}})
            assert response.status_code == 409
            assert runtime.get_run("alice", run.id).pending_request["id"] == pending["id"]
            assert calls == []
        finally:
            runtime.cancel_run("alice", run.id)
            await asyncio.wait_for(request, 5)


@pytest.mark.asyncio
@pytest.mark.parametrize("transition", ["pause", "cancel"])
async def test_extension_rechecks_run_state_after_remote_discovery(tmp_path, monkeypatch, transition):
    ready, release = asyncio.Event(), asyncio.Event()
    discoveries = 0

    class HeldDiscovery(MCPConnector):
        async def list_tools(self):
            nonlocal discoveries
            tools = await super().list_tools()
            discoveries += 1
            if discoveries == 2:
                ready.set()
                await release.wait()
            return tools

    monkeypatch.setattr("doppel.extension_runtime.MCPConnector", HeldDiscovery)
    async with extension_fixture(tmp_path) as (runtime, run, manager, configuration, client, calls):
        request = asyncio.create_task(call_extension(client, run))
        pending = await wait_for_approval(runtime, run)
        answer = await client.post(f"/v1/runs/{run.id}/answer", json={"request_id": pending["id"], "approve": True})
        assert answer.status_code == 200
        await asyncio.wait_for(ready.wait(), 5)
        assert (await client.post(f"/v1/runs/{run.id}/{transition}")).status_code == 200
        release.set()
        try:
            if transition == "pause":
                await asyncio.sleep(0.35)
                assert calls == []
                assert not request.done()
                assert (await client.post(f"/v1/runs/{run.id}/resume")).status_code == 200
                response = await asyncio.wait_for(request, 5)
                assert response.status_code == 200
                assert calls == [7]
                assert "evidence_id" in response.json()
            else:
                await asyncio.wait_for(request, 5)
                assert calls == []
                assert runtime.get_run("alice", run.id).status == "cancelled"
        finally:
            if not request.done():
                runtime.cancel_run("alice", run.id)
                await asyncio.wait_for(request, 5)

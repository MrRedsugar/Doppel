from fastapi import FastAPI, Header, HTTPException
from fastapi.testclient import TestClient
import asyncio
import socket

import httpx
import pytest
import uvicorn
from mcp.server import MCPServer

from doppel import DoppelRuntime, RuntimeConfig
from doppel.extension_api import create_extension_router
from doppel.extension_runtime import get_extension_manager


def test_configuration_authentication_owner_scope_and_updates(tmp_path):
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False))

    def owner(authorization: str | None = Header(default=None)):
        if authorization not in {'Bearer alice', 'Bearer bob'}:
            raise HTTPException(401, 'Authenticate first')
        return authorization.split()[1]

    app = FastAPI()
    app.include_router(create_extension_router(runtime, owner), prefix='/v1')
    with TestClient(app) as client:
        assert client.get('/v1/extensions').status_code == 401
        alice = {'Authorization': 'Bearer alice'}
        bob = {'Authorization': 'Bearer bob'}
        payload = {'name': 'sample', 'url': 'http://127.0.0.1:9876/mcp', 'allowed_tools': [], 'read_only_tools': []}
        assert client.post('/v1/extensions', json=payload).status_code == 401
        assert client.post('/v1/extensions', json=payload, headers=alice).status_code == 201
        assert client.post('/v1/extensions', json=payload, headers=alice).status_code == 409
        assert client.get('/v1/extensions', headers=bob).json() == {'items': []}
        assert client.get('/v1/extensions/sample/tools', headers=bob).status_code == 404
        assert client.delete('/v1/extensions/sample', headers=bob).status_code == 404
        assert client.post('/v1/extensions', json={**payload, 'command': 'cmd.exe'}, headers=alice).status_code == 422
        payload.update(allowed_tools=['read'], read_only_tools=['read'])
        assert client.put('/v1/extensions/sample', json=payload, headers=alice).status_code == 200
        assert client.get('/v1/extensions', headers=alice).json()['items'][0]['allowed_tools'] == ['read']
        assert client.delete('/v1/extensions/sample', headers=alice).status_code == 200
        assert client.get('/v1/extensions', headers=alice).json() == {'items': []}


def test_multiple_services_keep_independent_grants_and_reject_stale_edit(tmp_path):
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False))
    app = FastAPI()
    app.include_router(create_extension_router(runtime, lambda: 'alice'))
    with TestClient(app) as client:
        first = {'name': 'notes', 'url': 'http://127.0.0.1:9876/mcp', 'allowed_tools': ['read'], 'read_only_tools': ['read']}
        created = client.post('/extensions', json=first).json()
        assert client.post('/extensions', json={**first, 'name': 'calendar', 'allowed_tools': [], 'read_only_tools': []}).status_code == 201
        updated = client.put('/extensions/notes', json={**first, 'expected_revision': created['revision']})
        assert updated.status_code == 200, updated.text
        stale = client.put('/extensions/notes', json={**first, 'expected_revision': created['revision']})
        assert stale.status_code == 409
        moved = client.put('/extensions/notes', json={**first, 'url': 'http://127.0.0.1:9999/mcp'})
        assert moved.status_code == 422
        clean = {**first, 'url': 'http://127.0.0.1:9999/mcp', 'allowed_tools': [], 'read_only_tools': []}
        assert client.put('/extensions/notes', json=clean).status_code == 200
        assert client.delete('/extensions/notes').status_code == 200
        remaining = client.get('/extensions').json()['items']
        assert len(remaining) == 1 and remaining[0]['name'] == 'calendar' and remaining[0]['allowed_tools'] == []


@pytest.mark.asyncio
async def test_discovery_and_execution_with_real_http_provider(tmp_path):
    provider = MCPServer('Integration fixture')
    calls = []

    @provider.tool()
    def add(left: int, right: int) -> int:
        calls.append((left, right))
        return left + right

    sock = socket.socket()
    sock.bind(('127.0.0.1', 0))
    port = sock.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(provider.streamable_http_app(), log_level='error', timeout_graceful_shutdown=1))
    task = asyncio.create_task(server.serve(sockets=[sock]))
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False))
    app = FastAPI()
    app.include_router(create_extension_router(runtime, lambda: 'alice'))
    try:
        async with asyncio.timeout(10):
            while not server.started:
                await asyncio.sleep(0.01)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test') as client:
            config = {'name': 'real', 'url': f'http://127.0.0.1:{port}/mcp'}
            assert (await client.post('/extensions', json=config)).status_code == 201
            descriptors = (await client.get('/extensions/real/tools')).json()['items']
            assert descriptors[0]['name'] == 'mcp.real.add'
            assert descriptors[0]['allowed'] is False
            config.update(allowed_tools=['add'], read_only_tools=['add'])
            assert (await client.put('/extensions/real', json=config)).status_code == 200
            manager = get_extension_manager(runtime)
            result = await manager.call_tool('alice', 'mcp.real.add', {'left': 6, 'right': 7}, 'assist')
            assert result['structuredContent']['result'] == 13
            with pytest.raises(ValueError):
                await manager.call_tool('alice', 'mcp.real.add', {'left': 'bad', 'right': 7}, 'full')
            assert calls == [(6, 7)]
    finally:
        server.should_exit = True
        await asyncio.wait_for(task, 5)
        sock.close()

import asyncio
import sys
import importlib.util
import socket
from pathlib import Path

import pytest
import uvicorn

from doppel.extensions import MCPConnector, MCPServerConfig


@pytest.mark.asyncio
async def test_real_stdio_initialize_list_call_and_cancel():
    fixture = Path(__file__).parents[1] / 'examples' / 'mcp_server.py'
    config = MCPServerConfig(name='sample', transport='stdio', command=sys.executable, args=(str(fixture),), timeout_seconds=10)
    async with MCPConnector(config) as connector:
        tools = await connector.list_tools()
        assert 'mcp.sample.add' in [tool['name'] for tool in tools]
        assert all(tool['permission'] == 'external' for tool in tools)
        result = await connector.call_tool('mcp.sample.add', {'left': 3, 'right': 4})
        assert result['isError'] is False
        assert result['structuredContent']['result'] == 7
        with pytest.raises(ValueError):
            await connector.call_tool('mcp.other.add', {})
        pending = asyncio.create_task(connector.call_tool('mcp.sample.wait', {'seconds': 10}))
        await asyncio.sleep(0.05)
        pending.cancel()
        with pytest.raises(asyncio.CancelledError):
            await pending
        assert (await connector.call_tool('mcp.sample.add', {'left': 1, 'right': 2}))['structuredContent']['result'] == 3
        with pytest.raises(TimeoutError):
            await connector.call_tool('mcp.sample.wait', {'seconds': 20})


def test_configuration_is_explicit_and_bounded():
    with pytest.raises(ValueError):
        MCPServerConfig(name='bad.name', transport='stdio', command='python')
    with pytest.raises(ValueError):
        MCPServerConfig(name='remote', transport='streamable_http', url='file:///secret')
    with pytest.raises(ValueError):
        MCPServerConfig(name='missing', transport='stdio')


@pytest.mark.asyncio
async def test_real_streamable_http_initialize_and_call():
    fixture = Path(__file__).parents[1] / 'examples' / 'mcp_server.py'
    spec = importlib.util.spec_from_file_location('fixture_mcp_server', fixture)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    sock = socket.socket()
    sock.bind(('127.0.0.1', 0))
    port = sock.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(module.server.streamable_http_app(), log_level='error', timeout_graceful_shutdown=1))
    server_task = asyncio.create_task(server.serve(sockets=[sock]))
    try:
        async with asyncio.timeout(10):
            while not server.started:
                await asyncio.sleep(0.01)
        async with MCPConnector(MCPServerConfig(name='http', transport='streamable_http', url=f'http://127.0.0.1:{port}/mcp')) as connector:
            assert len(await connector.list_tools()) == 2
            result = await connector.call_tool('mcp.http.add', {'left': 12, 'right': 7})
            assert result['structuredContent']['result'] == 19
    finally:
        server.should_exit = True
        await asyncio.wait_for(server_task, 5)
        sock.close()
        assert server_task.done()

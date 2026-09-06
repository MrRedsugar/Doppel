from pathlib import Path
import sys

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
import pytest

from doppel import mcp_server
from doppel.models import CommandResult, Observation
from test_completion import call, setup
from test_harness_mcp import bridge_environment, gateway_fixture


APPS = [
    {"label": "Mail", "package_name": "com.example.mail"},
    {"label": "City Maps", "package_name": "com.example.maps"},
    {"label": "Stra\u00dfe Guide", "package_name": "org.example.guide"},
    {"label": "\u5730\u56fe", "package_name": "net.example.navigation"},
]


@pytest.mark.parametrize("arguments,expected", [
    ({}, [0, 1, 2, 3]),
    ({"query": ""}, [0, 1, 2, 3]),
    ({"query": "   "}, [0, 1, 2, 3]),
    ({"query": " maps "}, [1]),
    ({"query": "COM.EXAMPLE.MAIL"}, [0]),
    ({"query": "STRASSE"}, [2]),
    ({"query": "\u5730\u56fe"}, [3]),
    ({"query": "com.example"}, [0, 1]),
    ({"query": "not installed"}, []),
])
def test_app_discovery_filters_labels_and_packages_without_changing_screen_or_catalog(tmp_path, arguments, expected):
    runtime, device, run, client = setup(tmp_path)
    observations = []

    async def perform(run_id, kind):
        observations.append(kind)
        command = runtime.queue_command(run_id, kind)
        result = CommandResult(command_id=command.id, run_id=run_id, status="ok", data={"apps": APPS},
                               observation=Observation(screen_id="current", package_name="fixture", width=100, height=200))
        runtime.submit_result("alice", device.id, result)
        return result

    runtime.perform = perform
    response = call(client, run, "list_apps", **arguments)
    assert response.status_code == 200
    result = response.json()
    assert result["apps"] == [APPS[index] for index in expected]
    assert result["status"] == "ok"
    assert "screen current" in result["screen"]
    assert result["evidence_id"]
    assert observations == ["observe"]
    command = runtime.store.one("SELECT id FROM commands WHERE run_id=?", (run.id,))
    assert runtime.command_result(command["id"]).data["apps"] == APPS


@pytest.mark.parametrize("query", [None, 12, True, [], {}])
def test_invalid_app_query_is_rejected_before_device_observation(tmp_path, query):
    runtime, device, run, client = setup(tmp_path)

    async def unexpected_observation(*args, **kwargs):
        pytest.fail("Invalid query must be rejected before device work")

    runtime.perform = unexpected_observation
    assert call(client, run, "list_apps", query=query).status_code == 422
    assert runtime.next_command("alice", device.id) is None


@pytest.mark.asyncio
async def test_app_query_is_optional_typed_and_forwarded_over_stdio(tmp_path):
    with gateway_fixture() as (gateway, state):
        params = StdioServerParameters(command=sys.executable, args=[str(Path(mcp_server.__file__).resolve())],
                                       env=bridge_environment(gateway), cwd=str(tmp_path))
        async with stdio_client(params) as (read, write):
            async with ClientSession(read, write, read_timeout_seconds=10) as session:
                await session.initialize()
                tool = next(tool for tool in (await session.list_tools()).tools if tool.name == "list_apps")
                assert tool.input_schema["properties"]["query"]["type"] == "string"
                assert tool.input_schema["properties"]["query"]["default"] == ""
                assert "query" not in tool.input_schema.get("required", [])
                assert (await session.call_tool("list_apps", {"query": []})).is_error
                assert state["tool_requests"] == []
                assert not (await session.call_tool("list_apps", {})).is_error
                assert not (await session.call_tool("list_apps", {"query": "\u5730\u56fe"})).is_error
    assert [request["body"] for request in state["tool_requests"]] == [
        {"name": "list_apps", "arguments": {"query": ""}},
        {"name": "list_apps", "arguments": {"query": "\u5730\u56fe"}},
    ]

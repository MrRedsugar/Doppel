from pathlib import Path
import sys

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
import pytest

from doppel import mcp_server
from test_harness_mcp import bridge_environment, gateway_fixture


@pytest.mark.asyncio
async def test_group_operation_schema_rejects_ambiguous_arguments_before_gateway(tmp_path):
    with gateway_fixture() as (gateway, state):
        params = StdioServerParameters(command=sys.executable, args=[str(Path(mcp_server.__file__).resolve())], env=bridge_environment(gateway), cwd=str(tmp_path))
        async with stdio_client(params) as (read, write):
            async with ClientSession(read, write, read_timeout_seconds=10) as session:
                await session.initialize()
                catalog = await session.list_tools()
                tool = next(tool for tool in catalog.tools if tool.name == "transform_document")
                schema = str(tool.input_schema)
                assert "group_by" in schema and "sheet" in schema and "array" in schema
                base = {"source": "source.xlsx", "output": "output.xlsx", "operations": [{"op": "group_sum", "group_by": "Region", "sum_columns": ["Amount"], "output_sheet": "Summary"}]}
                rejected = await session.call_tool("transform_document", base)
                assert rejected.is_error
                assert state["tool_requests"] == []
                base["operations"][0].update(sheet="Orders", group_by=["Region"])
                accepted = await session.call_tool("transform_document", base)
                assert not accepted.is_error
    assert state["tool_requests"][0]["body"]["arguments"] == base


@pytest.mark.asyncio
async def test_filter_null_value_is_preserved_over_stdio(tmp_path):
    from test_harness_mcp import call_stdio_tool
    arguments = {"source": "source.xlsx", "output": "output.xlsx", "operations": [{"op": "filter", "sheet": "Orders", "column": "Region", "operator": "eq", "value": None}]}
    with gateway_fixture() as (gateway, state):
        result = await call_stdio_tool(bridge_environment(gateway), tmp_path, "transform_document", arguments)
    assert not result.is_error
    assert state["tool_requests"][0]["body"]["arguments"] == arguments

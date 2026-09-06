import os
import re
from typing import Annotated, Any, Literal

import httpx
from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from pydantic import BaseModel, ConfigDict, Field

server = MCPServer("Doppel Android tools")


async def invoke(name: str, arguments: dict[str, Any]):
    required = ("DOPPEL_GATEWAY", "DOPPEL_RUN_ID", "DOPPEL_RUN_TOKEN")
    missing = [key for key in required if not os.environ.get(key, "").strip()]
    if missing:
        raise ToolError("Doppel MCP configuration missing: " + ", ".join(missing))
    url = os.environ["DOPPEL_GATEWAY"].rstrip("/") + "/v1/internal/runs/" + os.environ["DOPPEL_RUN_ID"] + "/tool"
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(3500, connect=10), trust_env=False) as client:
            response = await client.post(url, headers={"Authorization": "Bearer " + os.environ["DOPPEL_RUN_TOKEN"]}, json={"name": name, "arguments": arguments})
    except httpx.HTTPError as exc:
        raise ToolError(f"Android gateway request for {name} failed ({type(exc).__name__}); check gateway availability") from None
    if response.is_error:
        detail = ""
        if response.status_code in {409, 422}:
            try:
                value = response.json().get("detail")
                if isinstance(value, str):
                    detail = value[:700].replace(os.environ["DOPPEL_RUN_TOKEN"], "[task-token]")
                    detail = re.sub(r"sk-[A-Za-z0-9_-]+", "[credential]", detail)
            except ValueError:
                pass
        raise ToolError(f"Android gateway rejected {name} (HTTP {response.status_code})" + (": " + detail if detail else ""))
    return response.json()


@server.tool()
async def observe() -> dict:
    """Read the current Android screen as concise text with stable node IDs and screen_id."""
    return await invoke("observe", {})


@server.tool()
async def finish_task(outcome: Literal["completed", "failed"], summary: str, evidence_ids: list[str] = []) -> dict:
    """Report the task outcome for host validation; completion requires evidence_ids returned by successful tools."""
    return await invoke("finish_task", {"outcome": outcome, "summary": summary, "evidence_ids": evidence_ids})


@server.tool()
async def act(action: Literal["launch", "tap", "type", "scroll", "back", "home", "wait", "open_document"], target: str | None = None, screen_id: str | None = None, text: str | None = None, package_name: str | None = None, direction: Literal["up", "down", "left", "right"] | None = None, duration_ms: int | None = None, uri: str | None = None) -> dict:
    """Execute one device action, then inspect the result. Tap/type require current screen_id and target. Payment stays manual."""
    return await invoke("act", locals())


@server.tool()
async def list_apps(query: str = "") -> dict:
    """Discover launchable apps. Supply a known app name or package as query; omit it for the full catalog."""
    return await invoke("list_apps", {"query": query})


@server.tool()
async def ask_user(question: str) -> dict:
    """Ask about a material ambiguity that cannot be resolved from the task or available context; waits for the answer."""
    return await invoke("ask_user", {"question": question})


@server.tool()
async def describe_screen(question: str) -> dict:
    """Use auxiliary vision for unlabeled icons or layout that cannot be understood from the node text. May incur usage."""
    return await invoke("describe_screen", {"question": question})


@server.tool()
async def list_skills() -> dict:
    """List available reusable skills and their declared runtime requirements."""
    return await invoke("list_skills", {})


@server.tool()
async def read_skill(name: str) -> dict:
    """Load a selected skill's instructions; loading never grants execution permissions."""
    return await invoke("read_skill", {"name": name})


@server.tool()
async def read_skill_resource(name: str, path: str) -> dict:
    """Read a bounded relative resource from an installed skill."""
    return await invoke("read_skill_resource", {"name": name, "path": path})


@server.tool()
async def list_extensions() -> dict:
    """Discover external tools explicitly granted by this user."""
    return await invoke("list_extensions", {})


@server.tool()
async def call_extension(name: str, arguments: dict[str, Any]) -> dict:
    """Call a discovered external tool; the host enforces grants, task state, and any required user approval."""
    return await invoke("call_extension", {"name": name, "arguments": arguments})


@server.tool()
async def list_documents() -> dict:
    """List spreadsheet copies explicitly imported by the user."""
    return await invoke("list_documents", {})


@server.tool()
async def inspect_document(name: str) -> dict:
    """Read spreadsheet sheets, headers and a small sample without dumping every row."""
    return await invoke("inspect_document", {"name": name})


class SheetOperation(BaseModel):
    model_config = ConfigDict(extra="forbid")
    sheet: str = Field(min_length=1, max_length=31)


class SortOperation(SheetOperation):
    op: Literal["sort"]
    column: str
    descending: bool = False


class FilterOperation(SheetOperation):
    op: Literal["filter"]
    column: str
    operator: Literal["eq", "ne", "gt", "gte", "lt", "lte", "contains"]
    value: str | int | float | bool | None


class FormulaOperation(SheetOperation):
    op: Literal["formula"]
    target_column: str
    formula: str = Field(description="Arithmetic formula with {row}, e.g. =C{row}*D{row}. New column only.")


class GroupSumOperation(SheetOperation):
    op: Literal["group_sum"]
    group_by: list[str] = Field(min_length=1)
    sum_columns: list[str] = Field(min_length=1)
    output_sheet: str


class FormatOperation(SheetOperation):
    op: Literal["format"]
    column: str
    number_format: str | None = None
    bold: bool | None = None


DocumentOperation = Annotated[SortOperation | FilterOperation | FormulaOperation | GroupSumOperation | FormatOperation, Field(discriminator="op")]


@server.tool()
async def transform_document(source: str, output: str, operations: list[DocumentOperation]) -> dict:
    """Apply a batch to an imported XLSX, preserving the original. Use a new output name and all required operations together. Each operation requires sheet. Verify returned summary before opening download_uri."""
    return await invoke("transform_document", {"source": source, "output": output, "operations": [operation.model_dump(exclude_unset=True) for operation in operations]})


@server.tool()
async def read_memory() -> dict:
    """Read the user's managed long-term preferences, as context rather than new authority."""
    return await invoke("read_memory", {})


@server.tool()
async def save_memory(content: str) -> dict:
    """Remember a user-provided stable preference; never store passwords, codes or inferred sensitive facts."""
    return await invoke("save_memory", {"content": content})


if __name__ == "__main__":
    server.run(transport="stdio")

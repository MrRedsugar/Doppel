import json
import os
import subprocess
import sys
import threading
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest
from mcp import ClientSession
from mcp.client.stdio import StdioServerParameters, stdio_client

from doppel import mcp_server
from doppel.model_proxy import TOOL_NAMES


@contextmanager
def gateway_fixture():
    state = {"model_requests": [], "tool_requests": [], "tool_status": 200}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            if self.path.endswith("/tool"):
                state["tool_requests"].append({
                    "path": self.path,
                    "authorization": self.headers.get("Authorization"),
                    "body": payload,
                })
                if state["tool_status"] == 200:
                    response = {"status": "ok", "screen": "App fixture.app; screen screen-1; 100x200\nn1 Counter: 42 (text)", "evidence_id": "fixture-evidence"}
                else:
                    response = {"detail": "Rejected bearer offline-test-task-token; sk-test-upstream-secret"}
                self.respond(state["tool_status"], json.dumps(response).encode(), "application/json")
                return
            state["model_requests"].append(payload)
            if len(state["model_requests"]) == 1:
                delta = {"role": "assistant", "tool_calls": [{
                    "index": 0, "id": "fixture-observe-call", "type": "function",
                    "function": {"name": "mcp__android__observe", "arguments": "{}"},
                }]}
                finish = "tool_calls"
            else:
                delta = {"role": "assistant", "content": "Offline fixture finished."}
                finish = "stop"
            chunks = [
                {"choices": [{"index": 0, "delta": delta, "finish_reason": None}]},
                {"choices": [{"index": 0, "delta": {}, "finish_reason": finish}]},
            ]
            body = "".join("data: " + json.dumps(chunk) + "\n\n" for chunk in chunks)
            self.respond(200, (body + "data: [DONE]\n\n").encode(), "text/event-stream")

        def respond(self, status, body, content_type):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", state
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def bridge_environment(gateway):
    return {
        "DOPPEL_GATEWAY": gateway,
        "DOPPEL_RUN_ID": "offline-mcp-run",
        "DOPPEL_RUN_TOKEN": "offline-test-task-token",
    }


async def call_stdio_tool(env, tmp_path, name="observe", arguments=None):
    parameters = StdioServerParameters(
        command=sys.executable,
        args=[str(Path(mcp_server.__file__).resolve())],
        env=env,
        cwd=str(tmp_path),
    )
    async with stdio_client(parameters) as (read, write):
        async with ClientSession(read, write, read_timeout_seconds=10) as session:
            await session.initialize()
            return await session.call_tool(name, arguments or {})


@pytest.mark.asyncio
async def test_payment_mcp_reuses_act_without_exposing_host_authority(tmp_path):
    arguments = {"action": "pay", "target": "button", "screen_id": "checkout"}
    with gateway_fixture() as (gateway, state):
        result = await call_stdio_tool(bridge_environment(gateway), tmp_path, "act", arguments)
    assert not result.is_error
    forwarded = state["tool_requests"][0]["body"]["arguments"]
    assert all(forwarded[key] == value for key, value in arguments.items())
    assert not {"mode", "payment_consent_id", "safety"}.intersection(forwarded)


@pytest.mark.parametrize("model", ["deepseek-v4-pro", "mimo-v2.5-pro"])
def test_worker_preserves_task_authorization_through_official_mcp_child(tmp_path, model):
    """Removing the explicit MCP task-token environment must break broker access."""
    with gateway_fixture() as (gateway, state):
        allowed = ("SYSTEMROOT", "WINDIR", "COMSPEC", "PATH", "TEMP", "TMP", "PATHEXT")
        env = {key: os.environ[key] for key in allowed if key in os.environ}
        env.update(bridge_environment(gateway))
        env.update({
            "PYTHONPATH": str(Path(mcp_server.__file__).resolve().parents[1]),
            "PYTHONIOENCODING": "utf-8",
            "DOPPEL_HARNESS_HOME": str(tmp_path / "harness"),
            "DOPPEL_MODEL": model,
            "DOPPEL_MAX_OUTPUT": "128",
            "DOPPEL_GOAL": "Read the fixture screen.",
        })
        result = subprocess.run(
            [sys.executable, "-m", "doppel.harness_worker"],
            env=env, capture_output=True, text=True, encoding="utf-8", timeout=35,
        )
    assert result.returncode == 0, result.stderr
    assert state["tool_requests"] == [{
        "path": "/v1/internal/runs/offline-mcp-run/tool",
        "authorization": "Bearer offline-test-task-token",
        "body": {"name": "observe", "arguments": {}},
    }]
    assert len(state["model_requests"]) == 2
    assert all(request["model"] == model for request in state["model_requests"])
    assert {tool["function"]["name"] for tool in state["model_requests"][0]["tools"]} == {
        "mcp__android__" + name for name in TOOL_NAMES
    }
    replay = [item for item in state["model_requests"][1]["messages"] if item["role"] == "tool"]
    assert len(replay) == 1
    assert "Counter: 42" in replay[0]["content"]
    tool_result = json.loads(replay[0]["content"])
    assert tool_result["screen"].startswith("App fixture.app; screen screen-1;")
    assert tool_result["evidence_id"] == "fixture-evidence"
    assistant = next(item for item in state["model_requests"][1]["messages"] if item.get("tool_calls"))
    assert replay[0]["tool_call_id"] == assistant["tool_calls"][0]["id"]
    assert "offline-test-task-token" not in result.stdout + result.stderr
    assert "offline-test-task-token" not in (tmp_path / "harness" / "android.patch.yml").read_text()


@pytest.mark.asyncio
async def test_missing_mcp_configuration_reports_variable_name_without_crashing(tmp_path):
    env = bridge_environment("http://127.0.0.1:1")
    del env["DOPPEL_RUN_TOKEN"]
    result = await call_stdio_tool(env, tmp_path)
    assert result.is_error
    message = " ".join(item.text for item in result.content if item.type == "text")
    assert "DOPPEL_RUN_TOKEN" in message
    assert "configuration" in message.lower()
    assert message != "Error executing tool observe"


@pytest.mark.asyncio
async def test_mcp_gateway_rejection_is_an_error_without_response_secrets(tmp_path):
    with gateway_fixture() as (gateway, state):
        state["tool_status"] = 403
        result = await call_stdio_tool(bridge_environment(gateway), tmp_path)
    assert result.is_error
    message = " ".join(item.text for item in result.content if item.type == "text")
    assert "403" in message
    assert "observe" in message
    assert "offline-test-task-token" not in message
    assert "sk-test-upstream-secret" not in message


@pytest.mark.asyncio
async def test_mcp_gateway_connection_failure_reports_transport_type(tmp_path):
    with gateway_fixture() as (gateway, _):
        pass
    result = await call_stdio_tool(bridge_environment(gateway), tmp_path)
    assert result.is_error
    message = " ".join(item.text for item in result.content if item.type == "text")
    assert "ConnectError" in message
    assert "gateway" in message.lower()
    assert "offline-test-task-token" not in message


@pytest.mark.asyncio
@pytest.mark.parametrize("arguments, expected_ids", [
    ({"outcome": "completed", "summary": "Observed the counter", "evidence_ids": ["host-observation-1"]}, ["host-observation-1"]),
    ({"outcome": "failed", "summary": "Device disconnected"}, []),
])
async def test_finish_task_forwards_outcome_and_host_evidence_over_stdio(tmp_path, arguments, expected_ids):
    with gateway_fixture() as (gateway, state):
        parameters = StdioServerParameters(
            command=sys.executable,
            args=[str(Path(mcp_server.__file__).resolve())],
            env=bridge_environment(gateway),
            cwd=str(tmp_path),
        )
        async with stdio_client(parameters) as (read, write):
            async with ClientSession(read, write, read_timeout_seconds=10) as session:
                await session.initialize()
                catalog = await session.list_tools()
                assert "finish_task" in [tool.name for tool in catalog.tools]
                assert not {"list_skills", "read_skill", "load_skill", "read_skill_resource"}.intersection(tool.name for tool in catalog.tools)
                result = await session.call_tool("finish_task", arguments)
    assert not result.is_error
    assert state["tool_requests"] == [{
        "path": "/v1/internal/runs/offline-mcp-run/tool",
        "authorization": "Bearer offline-test-task-token",
        "body": {"name": "finish_task", "arguments": {
            "outcome": arguments["outcome"],
            "summary": arguments["summary"],
            "evidence_ids": expected_ids,
        }},
    }]


@pytest.mark.asyncio
@pytest.mark.parametrize("name, arguments", [
    ("list_extensions", {}),
    ("call_extension", {"name": "mcp.fixture.read", "arguments": {"value": 7}}),
])
async def test_extension_mcp_wrappers_forward_to_authenticated_host(tmp_path, name, arguments):
    with gateway_fixture() as (gateway, state):
        result = await call_stdio_tool(bridge_environment(gateway), tmp_path, name, arguments)
    assert not result.is_error
    assert state["tool_requests"][0]["body"] == {"name": name, "arguments": arguments}
    assert state["tool_requests"][0]["authorization"] == "Bearer offline-test-task-token"

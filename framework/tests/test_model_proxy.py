import copy
import json

import httpx
from fastapi import FastAPI
from fastapi.testclient import TestClient

from doppel import DoppelRuntime, RuntimeConfig, create_router


class BillingRecorder:
    def __init__(self):
        self.reservations = []
        self.receipts = []
        self.releases = []

    def reserve(self, *args):
        self.reservations.append(args)

    def settle(self, *args):
        self.receipts.append(args)

    def release(self, *args):
        self.releases.append(args)


def setup_proxy(tmp_path):
    key = tmp_path / "key.txt"
    key.write_text("sk-fixture-not-a-real-provider-key")
    billing = BillingRecorder()
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path / "data", api_key_file=key, auto_start=False), billing)
    device = runtime.register_device("alice", "a", "A")
    run = runtime.create_run("alice", device.id, "Inspect", "full")
    app = FastAPI()
    app.include_router(create_router(runtime, lambda: "alice"))
    return runtime, run, billing, TestClient(app)


def test_proxy_rejects_unapproved_tools_before_spending_and_meters_actual_usage(tmp_path):
    runtime, run, billing, client = setup_proxy(tmp_path)
    calls = []

    def upstream(request):
        calls.append(request)
        return httpx.Response(200, json={"id": "fixture", "object": "chat.completion", "model": "deepseek-v4-pro", "choices": [{"index": 0, "message": {"role": "assistant", "content": "done"}, "finish_reason": "stop"}], "usage": {"prompt_tokens": 200, "completion_tokens": 12, "total_tokens": 212}})

    runtime.upstream_transport = httpx.MockTransport(upstream)
    url = f"/v1/internal/runs/{run.id}/chat/completions"
    headers = {"Authorization": "Bearer " + runtime.run_tokens[run.id]}
    body = {"model": "deepseek-v4-pro", "messages": [{"role": "user", "content": "Inspect"}], "tools": [{"type": "function", "function": {"name": "shell", "parameters": {"type": "object"}}}]}
    assert client.post(url, headers=headers, json=body).status_code == 403
    assert not calls and not billing.reservations
    assert not runtime.active_model_calls
    body["tools"] = []
    response = client.post(url, headers=headers, json=body)
    assert response.status_code == 200
    assert response.json()["choices"][0]["message"]["content"] == "done"
    assert len(calls) == 1
    assert billing.receipts[0][0] == "alice"
    assert billing.receipts[0][2:] == (200, 12)
    assert billing.receipts[0][1] == billing.reservations[0][1]


def test_model_proxy_never_accepts_a_different_run_token(tmp_path):
    runtime, run, billing, client = setup_proxy(tmp_path)
    response = client.post(f"/v1/internal/runs/{run.id}/chat/completions", headers={"Authorization": "Bearer wrong"}, json={"model": "deepseek-v4-pro", "messages": []})
    assert response.status_code == 403
    assert not billing.reservations


def test_image_estimate_does_not_count_base64_as_text():
    from doppel.model_proxy import estimate_input
    body = {"messages": [{"role": "user", "content": [{"type": "text", "text": "Describe"}, {"type": "image_url", "image_url": {"url": "data:image/png;base64," + "A" * 400000}}]}]}
    assert 1000 < estimate_input(body) < 10000


def test_proxy_requires_tool_protocol_when_finish_task_is_available(tmp_path):
    runtime, run, billing, client = setup_proxy(tmp_path)
    payloads = []

    def upstream(request):
        import json
        payloads.append(json.loads(request.content))
        return httpx.Response(200, json={"choices": [], "usage": {"prompt_tokens": 10, "completion_tokens": 1}})

    runtime.upstream_transport = httpx.MockTransport(upstream)
    response = client.post(f"/v1/internal/runs/{run.id}/chat/completions", headers={"Authorization": "Bearer " + runtime.run_tokens[run.id]}, json={
        "messages": [], "tools": [{"type": "function", "function": {"name": "mcp__android__finish_task", "parameters": {"type": "object"}}}],
        "tool_choice": "auto", "parallel_tool_calls": True,
    })
    assert response.status_code == 200
    assert payloads[0]["tool_choice"] == "required"
    assert "parallel_tool_calls" not in payloads[0]


def screen_turn(index, name="observe", **extra):
    call_id = f"call-{index}"
    return [
        {"role": "assistant", "content": "", "reasoning_content": "Retain this reasoning.", "tool_calls": [{
            "id": call_id, "type": "function", "function": {"name": "mcp__android__" + name, "arguments": "{}"},
        }]},
        {"role": "tool", "tool_call_id": call_id, "content": json.dumps({
            "status": "ok", "screen": f"App fixture.app; screen screen-{index}; 1080x2400\n" + f"n{index} visible text (button)\n" * 450,
            "evidence_id": f"evidence-{index}", **extra,
        }, indent=2)},
    ]


def test_proxy_compacts_old_screens_before_limits_and_billing(tmp_path):
    runtime, run, billing, client = setup_proxy(tmp_path)
    payloads = []

    def upstream(request):
        payloads.append(json.loads(request.content))
        return httpx.Response(200, json={"choices": [], "usage": {"prompt_tokens": 10, "completion_tokens": 1}})

    runtime.upstream_transport = httpx.MockTransport(upstream)
    messages = [{"role": "user", "content": "Keep all task requirements."}]
    for index in range(220):
        messages.extend(screen_turn(index, "act" if index % 2 else "observe", message="Preserve action status"))
    body = {"messages": messages}
    original = copy.deepcopy(body)
    assert len(json.dumps(body).encode()) > 2_000_000
    response = client.post(f"/v1/internal/runs/{run.id}/chat/completions", headers={"Authorization": "Bearer " + runtime.run_tokens[run.id]}, json=body)
    assert response.status_code == 200
    sent = payloads[0]["messages"]
    assert body == original
    assert len(sent) == len(messages)
    assert sent[-6:] == messages[-6:]
    assert sent[0] == messages[0]
    assert sent[1::2] == messages[1::2]
    for index in range(217):
        result = json.loads(sent[2 + 2 * index]["content"])
        assert result["status"] == "ok" and result["message"] == "Preserve action status"
        assert result["evidence_id"] == f"evidence-{index}"
        assert f"screen screen-{index}" in result["screen"]
        assert f"n{index} visible text" not in result["screen"]
        assert sent[2 + 2 * index]["tool_call_id"] == messages[2 + 2 * index]["tool_call_id"]
    assert len(json.dumps(sent).encode()) < len(json.dumps(messages).encode()) / 10
    assert billing.reservations[0][2] < 240000
    event = next(event for event in runtime.events("alice", run.id) if event.kind == "model_start")
    assert event.data["context"]["screens_compacted"] == 217
    assert event.data["context"]["bytes_saved"] > 2_000_000
    assert event.data["context"]["bytes_after"] < event.data["context"]["bytes_before"]


def test_context_compaction_preserves_user_documents_unknown_shapes_and_protocol():
    from doppel.model_context import compact_screen_history
    protected = [{"role": "user", "content": screen_turn(0)[1]["content"]}]
    for index, name in enumerate(("inspect_document", "read_memory", "ask_user", "call_extension")):
        protected.extend(screen_turn(f"protected-{index}", name))
    protected.extend([
        {"role": "tool", "tool_call_id": "unmatched", "content": screen_turn(0)[1]["content"]},
        {"role": "assistant", "tool_calls": None},
        {"role": "tool", "tool_call_id": [], "content": screen_turn(0)[1]["content"]},
        {"role": "assistant", "tool_calls": [{"id": "invalid", "function": {"name": "mcp__android__observe"}}]},
        {"role": "tool", "tool_call_id": "invalid", "content": "not JSON"},
    ])
    messages = protected + screen_turn("old", "list_apps", apps=[{"label": "App", "package_name": "fixture.app"}])
    messages += sum((screen_turn(i) for i in range(3)), [])
    original = copy.deepcopy(messages)
    compacted, metrics = compact_screen_history(messages)
    assert messages == original
    assert compacted[:len(protected)] == protected
    assert compacted[-6:] == messages[-6:]
    result = json.loads(compacted[len(protected) + 1]["content"])
    assert result["apps"] == [{"label": "App", "package_name": "fixture.app"}]
    assert result["evidence_id"] == "evidence-old"
    assert metrics["screens_compacted"] == 1
    repeated, second_metrics = compact_screen_history(compacted)
    assert repeated == compacted
    assert second_metrics["bytes_saved"] == 0


def test_context_compaction_keeps_screen_storage_bounded_over_one_thousand_steps():
    from doppel.model_context import compact_screen_history
    messages = sum((screen_turn(i) for i in range(1000)), [])
    compacted, metrics = compact_screen_history(messages)
    assert len(compacted) == 2000
    assert metrics["screens_compacted"] == 997
    assert metrics["bytes_after"] < metrics["bytes_before"] / 15
    assert compacted[-6:] == messages[-6:]


def semantic_turn(index, screen, action="tap"):
    return [
        {"role": "assistant", "content": "", "tool_calls": [{"id": f"semantic-{index}", "type": "function", "function": {
            "name": "mcp__android__act", "arguments": json.dumps({"action": action, "target": "opaque-target", "screen_id": f"before-{index}"}),
        }}]},
        {"role": "tool", "tool_call_id": f"semantic-{index}", "content": json.dumps({
            "status": "ok", "message": "Action accepted; inspect the resulting screen", "evidence_id": f"proof-{index}",
            "screen": f"App fixture.app; screen result-{index}; 1080x2400\n" + screen,
        })},
    ]


def test_small_history_keeps_all_cross_screen_confirmation_text():
    from doppel.model_context import compact_screen_history
    messages = [{"role": "user", "content": "Update the three records and verify each result."}]
    for index in range(6):
        messages.extend(semantic_turn(index, f"opaque-label Record {index} saved (TextView,read-only)\n" * 20))
    compacted, metrics = compact_screen_history(messages)
    assert compacted == messages
    assert metrics["screens_compacted"] == 0 and metrics["bytes_saved"] == 0


def test_pressure_compaction_preserves_visible_action_changes_and_protocol():
    from doppel.model_context import compact_screen_history
    messages = [{"role": "user", "content": "Preserve these user constraints. " * 3200}]
    unchanged = "\n".join(f"static-{index} Existing value {index} (TextView,read-only)" for index in range(20))
    messages.extend(semantic_turn(0, unchanged + "\nopaque-title Report (TextView,read-only)\nold-id Draft (TextView,read-only)"))
    messages.extend(semantic_turn(1, unchanged + "\nnew-title Report (TextView,read-only)\nnew-id Draft (TextView,read-only)\nreceipt-id Record saved successfully (TextView,read-only)"))
    for index in range(2, 5):
        messages.extend(semantic_turn(index, f"opaque-{index} Another view {index} (TextView,read-only)"))
    original = copy.deepcopy(messages)
    compacted, metrics = compact_screen_history(messages)
    assert messages == original
    assert compacted[0] == messages[0]
    assert compacted[1::2] == messages[1::2]
    result = json.loads(compacted[4]["content"])
    assert result["screen_changes"] == ["Record saved successfully (TextView,read-only)"]
    assert result["status"] == "ok" and result["evidence_id"] == "proof-1"
    assert result["message"] == "Action accepted; inspect the resulting screen"
    assert compacted[4]["tool_call_id"] == "semantic-1"
    assert compacted[-6:] == messages[-6:]
    assert metrics["screens_compacted"] == 2
    assert metrics["receipt_bytes"] > 0
    assert compact_screen_history(compacted)[0] == compacted


def test_pressure_receipts_are_bounded_across_many_large_screen_changes():
    from doppel.model_context import compact_screen_history
    messages = []
    for index in range(120):
        screen = "\n".join(f"opaque-{row} Result {index}-{row} " + "changed value " * 20 + "(TextView,read-only)" for row in range(30))
        messages.extend(semantic_turn(index, screen))
    compacted, metrics = compact_screen_history(messages)
    receipts = [json.loads(item["content"]).get("screen_changes", []) for item in compacted if item["role"] == "tool"]
    assert any(receipts)
    assert all(len(json.dumps(receipt, ensure_ascii=False).encode()) <= 1024 for receipt in receipts)
    assert sum(len(json.dumps(receipt, ensure_ascii=False).encode()) for receipt in receipts if receipt) <= 24000
    assert metrics["bytes_after"] < metrics["bytes_before"] / 5
    assert compacted[-6:] == messages[-6:]

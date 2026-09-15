import json

import httpx
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from doppel import DoppelRuntime, RuntimeConfig, create_router
from doppel.model_proxy import call_model


def proxy(tmp_path, **options):
    key = tmp_path / "provider-key.txt"
    key.write_text("sk-test-provider-key-123")
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path / "runtime", api_key_file=key,
                                          auto_start=False, **options))
    device = runtime.register_device("fixture", "local", "Synthetic")
    run = runtime.create_run("fixture", device.id, "Read synthetic screen", "assist")
    sent = []

    def upstream(request):
        sent.append(request)
        return httpx.Response(200, json={"id": "provider-fixture", "model": json.loads(request.content)["model"],
            "choices": [{"index": 0, "message": {"role": "assistant", "content": "fixture result"}, "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 100, "completion_tokens": 20,
                      "prompt_tokens_details": {"cached_tokens": 40, "image_tokens": 8},
                      "completion_tokens_details": {"reasoning_tokens": 3}}})

    runtime.upstream_transport = httpx.MockTransport(upstream)
    app = FastAPI()
    app.include_router(create_router(runtime, lambda: "fixture"))
    client = TestClient(app)
    client.headers["Authorization"] = "Bearer " + runtime.run_tokens[run.id]
    return runtime, run, sent, client


def test_default_mimo_request_uses_official_endpoint_and_supported_parameters(tmp_path):
    runtime, run, sent, client = proxy(tmp_path)
    assert runtime.config.model == "mimo-v2.5-pro"
    response = client.post(f"/v1/internal/runs/{run.id}/chat/completions", json={
        "messages": [{"role": "user", "content": "Read synthetic screen"}],
        "tools": [{"type": "function", "function": {"name": "mcp__android__finish_task", "parameters": {"type": "object"}}}],
        "tool_choice": "required", "parallel_tool_calls": True,
        "max_tokens": 5000, "thinking": {"type": "enabled"}, "reasoning_effort": "high", "stream": True,
    })
    assert response.status_code == 200, response.text
    assert len(sent) == 1
    assert str(sent[0].url) == "https://api.xiaomimimo.com/v1/chat/completions"
    assert sent[0].headers["authorization"] == "Bearer sk-test-provider-key-123"
    body = json.loads(sent[0].content)
    assert body["model"] == "mimo-v2.5-pro"
    assert body["thinking"] == {"type": "disabled"}
    assert body["tool_choice"] == "auto"
    assert body["max_completion_tokens"] == 1600
    assert not {"max_tokens", "parallel_tool_calls", "reasoning_effort"} & body.keys()
    assert "data: [DONE]" in response.text
    assert "mimo-v2.5-pro" in response.text
    assert "sk-test-provider-key-123" not in response.text
    event = next(e for e in runtime.events("fixture", run.id) if e.kind == "usage")
    assert event.data["input_tokens"] == 100 and event.data["output_tokens"] == 20
    assert event.data["provider"] == "xiaomi-mimo"
    assert event.data["cached_tokens"] == 40 and event.data["reasoning_tokens"] == 3


@pytest.mark.asyncio
async def test_auxiliary_vision_uses_mimo_multimodal_model_not_text_only_pro(tmp_path):
    runtime, run, sent, _ = proxy(tmp_path)
    content = [{"type": "text", "text": "Locate the synthetic square"},
               {"type": "image_url", "image_url": {"url": "data:image/png;base64,fixture"}}]
    response = await call_model(runtime, run.id, {"messages": [{"role": "user", "content": content}]}, auxiliary=True)
    assert response.status_code == 200
    body = json.loads(sent[0].content)
    assert body["model"] == "mimo-v2.5"
    assert body["messages"][0]["content"] == content
    assert body["thinking"] == {"type": "disabled"}
    await runtime.close()


@pytest.mark.parametrize("body", [
    {"model": "deepseek-v4-pro", "messages": []},
    {"messages": [{"role": "user", "content": [{"type": "image_url", "image_url": {"url": "data:image/png;base64,fixture"}}]}]},
])
def test_mimo_rejects_wrong_model_or_pro_image_before_upstream(tmp_path, body):
    runtime, run, sent, client = proxy(tmp_path)
    response = client.post(f"/v1/internal/runs/{run.id}/chat/completions", json=body)
    assert response.status_code in {403, 422}
    assert not sent
    assert not any(e.kind == "model_start" for e in runtime.events("fixture", run.id))


def test_explicit_deepseek_keeps_its_existing_endpoint_and_required_tool_protocol(tmp_path):
    runtime, run, sent, client = proxy(tmp_path, provider="deepseek")
    response = client.post(f"/v1/internal/runs/{run.id}/chat/completions", json={
        "messages": [], "max_completion_tokens": 123,
        "tools": [{"type": "function", "function": {"name": "mcp__android__finish_task", "parameters": {"type": "object"}}}],
    })
    assert response.status_code == 200
    assert str(sent[0].url) == "https://api.deepseek.com/chat/completions"
    body = json.loads(sent[0].content)
    assert body["model"] == "deepseek-v4-pro" and body["max_tokens"] == 123
    assert body["tool_choice"] == "required"
    assert runtime.config.vision_model == "deepseek-v4-flash-vision-exp"


def test_provider_config_refuses_mixed_model_pairs_and_arbitrary_destinations(tmp_path):
    for options in ({"provider": "other"}, {"model": "deepseek-v4-pro"},
                    {"vision_model": "mimo-v2.5-pro"}, {"provider": "https://example.invalid"}):
        with pytest.raises(ValueError):
            RuntimeConfig(data_dir=tmp_path, **options)


def test_token_plan_key_is_not_sent_to_pay_as_you_go_endpoint(tmp_path):
    runtime, run, sent, client = proxy(tmp_path)
    runtime.config.api_key_file.write_text("tp-synthetic-token-plan-key-123456789")
    response = client.post(f"/v1/internal/runs/{run.id}/chat/completions", json={"messages": []})
    assert response.status_code == 503
    assert not sent


def test_mimo_auto_text_reply_with_null_tool_calls_streams_without_crashing(tmp_path):
    runtime, run, sent, client = proxy(tmp_path)
    runtime.upstream_transport = httpx.MockTransport(lambda request: httpx.Response(200, json={
        "model": "mimo-v2.5-pro", "choices": [{"index": 0, "finish_reason": "stop", "message": {
            "role": "assistant", "content": "Needs a current observation.", "tool_calls": None}}],
        "usage": {"prompt_tokens": 10, "completion_tokens": 6}}))
    response = client.post(f"/v1/internal/runs/{run.id}/chat/completions", json={"messages": [], "stream": True})
    assert response.status_code == 200
    chunks = [json.loads(line[6:]) for line in response.text.splitlines() if line.startswith("data: {")]
    assert chunks[0]["choices"][0]["delta"]["content"] == "Needs a current observation."
    assert not chunks[0]["choices"][0]["delta"].get("tool_calls")
    assert "data: [DONE]" in response.text
    assert runtime.get_run("fixture", run.id).status == "running"


def test_qwen_primary_accepts_images_and_applies_low_thinking(tmp_path):
    runtime, run, sent, client = proxy(tmp_path, provider="qwen")
    response = client.post(f"/v1/internal/runs/{run.id}/chat/completions", json={
        "_doppel_role": "primary", "messages": [{"role": "user", "content": [
            {"type": "text", "text": "Read the synthetic shape"},
            {"type": "image_url", "image_url": {"url": "data:image/png;base64,fixture"}}]}],
        "thinking": {"type": "disabled"}, "reasoning_effort": "high"})
    assert response.status_code == 200, response.text
    body = json.loads(sent[0].content)
    assert body["model"] == "qwen3.8-flash"
    assert body["enable_thinking"] is True and body["reasoning_effort"] == "low"
    assert "thinking" not in body and "_doppel_role" not in body
    assert str(sent[0].url) == "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"


@pytest.mark.asyncio
async def test_qwen_grounding_has_no_thinking_or_injected_conversation_history(tmp_path):
    runtime, run, sent, _ = proxy(tmp_path, provider="qwen")
    runtime.conversation = lambda owner, run_id: [{"id": "old-run", "content": "PRIVATE_HISTORY_MARKER"}]
    messages = [{"role": "user", "content": [{"type": "text", "text": "Locate the synthetic square"},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,fixture"}}]}]
    await call_model(runtime, run.id, {"_doppel_role": "grounding", "messages": messages}, auxiliary=True)
    body = json.loads(sent[0].content)
    assert body["model"] == "qwen3.8-max" and body["enable_thinking"] is False
    assert "reasoning_effort" not in body and "_doppel_role" not in body
    assert body["messages"] == messages and "PRIVATE_HISTORY_MARKER" not in sent[0].content.decode()
    await runtime.close()


@pytest.mark.asyncio
async def test_separate_vision_provider_uses_its_own_key_and_endpoint(tmp_path):
    key = tmp_path / "vision-key.txt"
    key.write_text("sk-vision-fixture-1234")
    runtime, run, sent, _ = proxy(tmp_path, provider="qwen", vision_provider="deepseek", vision_api_key_file=key)
    await call_model(runtime, run.id, {"messages": []}, auxiliary=True)
    assert str(sent[0].url) == "https://api.deepseek.com/chat/completions"
    assert sent[0].headers["authorization"] == "Bearer sk-vision-fixture-1234"
    assert json.loads(sent[0].content)["model"] == "deepseek-v4-flash-vision-exp"
    await runtime.close()


def test_separate_provider_never_inherits_primary_credentials(tmp_path):
    with pytest.raises(ValueError, match="separate"):
        RuntimeConfig(data_dir=tmp_path, provider="qwen", api_key_file=tmp_path / "primary", vision_provider="deepseek")


@pytest.mark.asyncio
async def test_disabled_enhancement_follows_primary_and_custom_host_headers(tmp_path):
    headers = tmp_path / "headers.json"
    headers.write_text('{"X-Tenant":"synthetic-private-tenant"}')
    runtime, run, sent, _ = proxy(tmp_path, provider="custom", model="host-vision-model", provider_endpoint="https://fixture.invalid/v1",
                                   provider_headers_file=headers, vision_enhancement_enabled=False)
    runtime.config.api_key_file.write_text("vendor_fixture_key_without_sk_prefix")
    await call_model(runtime, run.id, {"messages": []}, auxiliary=True)
    body = json.loads(sent[0].content)
    assert body["model"] == "host-vision-model"
    assert str(sent[0].url) == "https://fixture.invalid/v1/chat/completions"
    assert sent[0].headers["X-Tenant"] == "synthetic-private-tenant"
    assert "thinking" not in body and "enable_thinking" not in body and "reasoning_effort" not in body
    await runtime.close()


@pytest.mark.parametrize("endpoint", ["http://example.com/v1", "https://user:secret@example.com/v1", "https://example.com/v1?key=secret"])
def test_custom_provider_refuses_unsafe_or_credential_bearing_endpoints(tmp_path, endpoint):
    with pytest.raises(ValueError):
        RuntimeConfig(data_dir=tmp_path, provider="custom", model="vision-model", provider_endpoint=endpoint)

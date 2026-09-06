import asyncio
import json
import re
import secrets

import httpx
from fastapi import HTTPException
from fastapi.responses import JSONResponse, StreamingResponse

from .models import Run
from .model_context import compact_screen_history
from .runtime import TERMINAL

TOOL_NAMES = {"observe", "act", "ask_user", "list_apps", "describe_screen", "list_skills", "read_skill", "read_skill_resource", "list_documents", "inspect_document", "transform_document", "read_memory", "save_memory", "list_extensions", "call_extension", "finish_task"}


def estimate_input(payload):
    images = 0

    def text_only(value):
        nonlocal images
        if isinstance(value, dict):
            if value.get("type") == "image_url":
                images += 1
                return {"type": "image_url"}
            return {key: text_only(item) for key, item in value.items()}
        if isinstance(value, list):
            return [text_only(item) for item in value]
        return value

    text = json.dumps(text_only({"messages": payload.get("messages", []), "tools": payload.get("tools", [])}), ensure_ascii=False).encode()
    if len(text) > 240000 or images > 4:
        raise HTTPException(413, "Context limit reached")
    # Byte count bounds text; a separate image allowance avoids charging base64 as prose.
    return len(text) + images * 4096


def provider_key(config):
    if not config.api_key_file:
        raise HTTPException(503, "尚未配置模型密钥文件")
    try:
        raw = config.api_key_file.read_text(encoding="utf-8-sig")
    except OSError:
        raise HTTPException(503, "模型密钥文件无法读取") from None
    match = re.search(r"sk-[A-Za-z0-9_-]{16,}", raw)
    if not match:
        raise HTTPException(503, "模型密钥文件格式无效")
    return match.group()


def as_events(reply):
    base = {"id": reply.get("id", "completion"), "object": "chat.completion.chunk", "created": reply.get("created", 0), "model": reply.get("model", "deepseek-v4-pro")}
    for choice in reply.get("choices", []):
        message = dict(choice.get("message", {}))
        if "tool_calls" in message:
            message["tool_calls"] = [dict(call, index=i) for i, call in enumerate(message["tool_calls"])]
        chunk = dict(base, choices=[{"index": choice.get("index", 0), "delta": message, "finish_reason": None}])
        yield "data: " + json.dumps(chunk, ensure_ascii=False) + "\n\n"
        yield "data: " + json.dumps(dict(base, choices=[{"index": choice.get("index", 0), "delta": {}, "finish_reason": choice.get("finish_reason", "stop")}])) + "\n\n"
    yield "data: " + json.dumps(dict(base, choices=[], usage=reply.get("usage", {}))) + "\n\n"
    yield "data: [DONE]\n\n"


async def call_model(runtime, run_id, body, *, auxiliary=False):
    with runtime.store.lock:
        runtime._row(run_id)
        runtime.active_model_calls[run_id] = runtime.active_model_calls.get(run_id, 0) + 1
    try:
        return await _call_model(runtime, run_id, body, auxiliary=auxiliary)
    finally:
        with runtime.store.lock:
            remaining = runtime.active_model_calls[run_id] - 1
            if remaining:
                runtime.active_model_calls[run_id] = remaining
            else:
                del runtime.active_model_calls[run_id]


async def _call_model(runtime, run_id, body, *, auxiliary=False):
    row = runtime._row(run_id)
    owner = row["owner"]
    run = Run.model_validate_json(row["payload"])
    for tool in body.get("tools", []):
        name = tool.get("function", {}).get("name", "")
        if name not in {f"mcp__android__{name}" for name in TOOL_NAMES}:
            raise HTTPException(403, "Unapproved model tool")
    expected = "deepseek-v4-flash-vision-exp" if auxiliary else runtime.config.model
    if body.get("model", expected) != expected:
        raise HTTPException(403, "Model is outside this task configuration")
    while run.status in {"paused", "awaiting_approval", "awaiting_input"}:
        await asyncio.sleep(0.2)
        run = Run.model_validate_json(runtime._row(run_id)["payload"])
    if run.status in TERMINAL:
        reply = {"id": "host-finish-" + run_id, "model": expected, "choices": [{"index": 0, "message": {"role": "assistant", "content": run.message or run.status}, "finish_reason": "stop"}], "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}}
        return StreamingResponse(as_events(reply), media_type="text/event-stream") if body.get("stream") else JSONResponse(reply)
    payload = dict(body)
    payload["messages"], context = compact_screen_history(payload.get("messages", []))
    requested_stream = bool(payload.get("stream", False))
    payload["stream"] = False
    payload.pop("stream_options", None)
    payload["model"] = expected
    if not auxiliary and any(tool.get("function", {}).get("name") == "mcp__android__finish_task" for tool in payload.get("tools", [])):
        payload["tool_choice"] = "required"
        payload.pop("parallel_tool_calls", None)
    payload["max_tokens"] = min(int(payload.pop("max_completion_tokens", payload.get("max_tokens", runtime.config.max_output_tokens))), runtime.config.max_output_tokens)
    if payload["max_tokens"] <= 0:
        raise HTTPException(422, "Invalid output limit")
    if len(json.dumps(payload).encode()) > 8_000_000:
        raise HTTPException(413, "Model payload too large")
    try:
        estimate = estimate_input(payload)
    except HTTPException:
        runtime.set_status(run_id, "failed", "上下文已达到本次任务上限，请拆分任务")
        raise
    key = provider_key(runtime.config)
    call_id = secrets.token_hex(20)
    with runtime.store.transaction() as db:
        count = db.execute("SELECT COUNT(*) FROM events WHERE run_id=? AND kind='model_start'", (run_id,)).fetchone()[0]
        if count >= runtime.config.max_model_calls:
            raise HTTPException(429, "Model call limit reached for this task")
        runtime.store.event(db, run_id, "model_start", "正在理解任务与界面", {"call_id": call_id, "model": expected, "context": context})
    if runtime.billing:
        try:
            runtime.billing.reserve(owner, call_id, estimate, payload["max_tokens"])
        except RuntimeError as error:
            runtime.set_status(run_id, "failed", "可用积分不足以完成下一次模型调用")
            raise HTTPException(getattr(error, "status_code", 402), "Insufficient quota") from None
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(90, connect=15), transport=getattr(runtime, "upstream_transport", None)) as client:
            response = await client.post("https://api.deepseek.com/chat/completions", headers={"Authorization": f"Bearer {key}"}, json=payload)
        reply = response.json()
    except (httpx.HTTPError, ValueError, asyncio.CancelledError) as error:
        with runtime.store.transaction() as db:
            runtime.store.event(db, run_id, "usage_unknown", "模型连接中断，用量待核对，已保留额度预留", {"call_id": call_id})
        runtime.set_status(run_id, "failed", "模型连接中断，任务停止；未自动重试计费请求")
        if isinstance(error, asyncio.CancelledError):
            raise
        raise HTTPException(502, "Provider response unavailable; usage requires reconciliation") from None
    usage = reply.get("usage")
    if usage and "prompt_tokens" in usage and "completion_tokens" in usage:
        incoming, outgoing = int(usage["prompt_tokens"]), int(usage["completion_tokens"])
        if runtime.billing:
            runtime.billing.settle(owner, call_id, incoming, outgoing)
        with runtime.store.transaction() as db:
            runtime.store.event(db, run_id, "usage", "模型用量已结算", {"call_id": call_id, "input_tokens": incoming, "output_tokens": outgoing, "model": expected})
    elif response.is_error:
        if runtime.billing:
            runtime.billing.release(owner, call_id)
    else:
        runtime.set_status(run_id, "failed", "模型未返回用量，额度预留等待核对")
        raise HTTPException(502, "Provider omitted metering usage")
    if response.is_error:
        raise HTTPException(502, f"Model provider rejected request (HTTP {response.status_code})")
    if requested_stream:
        return StreamingResponse(as_events(reply), media_type="text/event-stream")
    return JSONResponse(reply)

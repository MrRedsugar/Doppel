import asyncio
import json
import re
import secrets

import httpx
from fastapi import HTTPException
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import ValidationError

from .models import Run, TaskStateUpdate
from .model_context import compact_screen_history
from .runtime import TERMINAL
from .providers import PROVIDERS, adapt_payload, connection_for, has_media, usage_details

TOOL_NAMES = {"observe", "act", "login_phone", "login_code", "ask_user", "list_apps", "describe_screen", "list_documents", "inspect_document", "transform_document", "read_memory", "save_memory", "list_extensions", "call_extension", "finish_task"}


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


def connection_headers(provider, key_file, headers_file):
    headers = {}
    if key_file:
        try:
            raw = key_file.read_text(encoding="utf-8-sig").strip()
        except OSError:
            raise HTTPException(503, "模型密钥文件无法读取") from None
        if provider == "custom":
            key = raw if re.fullmatch(r"[!-~]{1,4096}", raw) else None
        else:
            match = re.search(r"sk-[A-Za-z0-9_-]{16,}", raw)
            key = match.group() if match else None
        if not key:
            raise HTTPException(503, "模型密钥文件格式无效")
        headers["Authorization"] = "Bearer " + key
    if headers_file:
        try:
            raw = headers_file.read_text(encoding="utf-8-sig")
            extra = json.loads(raw)
            if len(raw) > 16384 or not isinstance(extra, dict) or len(extra) > 24:
                raise ValueError()
            reserved = {"host", "content-length", "content-type", "connection", "transfer-encoding", "cookie", "proxy-authorization", "accept-encoding"}
            for name, value in extra.items():
                if (not re.fullmatch(r"[!#$%&'*+.^_`|~0-9A-Za-z-]{1,80}", name) or name.lower() in reserved
                        or not isinstance(value, str) or not re.fullmatch(r"[ -~]{1,4096}", value)):
                    raise ValueError()
                for existing in list(headers):
                    if existing.lower() == name.lower():
                        del headers[existing]
                headers[name] = value
        except (OSError, ValueError, TypeError):
            raise HTTPException(503, "模型请求头文件无效") from None
    if not headers:
        raise HTTPException(503, "尚未配置模型认证文件")
    return headers


def as_events(reply):
    base = {"id": reply.get("id", "completion"), "object": "chat.completion.chunk", "created": reply.get("created", 0), "model": reply.get("model", "")}
    for choice in reply.get("choices", []):
        message = dict(choice.get("message", {}))
        if message.get("tool_calls"):
            message["tool_calls"] = [dict(call, index=i) for i, call in enumerate(message["tool_calls"])]
        else:
            message.pop("tool_calls", None)
        chunk = dict(base, choices=[{"index": choice.get("index", 0), "delta": message, "finish_reason": None}])
        yield "data: " + json.dumps(chunk, ensure_ascii=False) + "\n\n"
        yield "data: " + json.dumps(dict(base, choices=[{"index": choice.get("index", 0), "delta": {}, "finish_reason": choice.get("finish_reason", "stop")}])) + "\n\n"
    yield "data: " + json.dumps(dict(base, choices=[], usage=reply.get("usage", {}))) + "\n\n"
    yield "data: [DONE]\n\n"


async def call_model(runtime, run_id, body, *, auxiliary=False, allow_terminal=False):
    with runtime.store.lock:
        runtime._row(run_id)
        runtime.active_model_calls[run_id] = runtime.active_model_calls.get(run_id, 0) + 1
    try:
        return await _call_model(runtime, run_id, body, auxiliary=auxiliary, allow_terminal=allow_terminal)
    finally:
        with runtime.store.lock:
            remaining = runtime.active_model_calls[run_id] - 1
            if remaining:
                runtime.active_model_calls[run_id] = remaining
            else:
                del runtime.active_model_calls[run_id]


async def call_stateless_model(runtime, body, *, auxiliary=False):
    """Call the configured model for conversation routing/replies.

    These requests deliberately have no device run or tools.  They are kept
    outside the task lifecycle so a normal conversation cannot accidentally
    start, pause, or mutate a phone task.
    """
    provider, expected, endpoint, key_file, headers_file = connection_for(runtime.config, auxiliary)
    if body.get("model", expected) != expected:
        raise HTTPException(403, "Model is outside this configuration")
    payload = dict(body)
    payload["model"] = expected
    payload["stream"] = False
    payload.pop("stream_options", None)
    payload["max_tokens"] = min(int(payload.pop("max_completion_tokens", payload.get("max_tokens", 800))), 1200)
    if payload["max_tokens"] <= 0:
        raise HTTPException(422, "Invalid output limit")
    payload = adapt_payload(payload, provider, auxiliary=auxiliary)
    try:
        estimate_input(payload)
    except HTTPException:
        raise
    headers = connection_headers(provider, key_file, headers_file)
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(45, connect=15), transport=getattr(runtime, "upstream_transport", None)) as client:
            response = await client.post(endpoint, headers=headers, json=payload)
        reply = response.json()
    except (httpx.HTTPError, ValueError, asyncio.CancelledError):
        raise HTTPException(502, "Provider response unavailable") from None
    if response.is_error:
        raise HTTPException(502, f"Model provider rejected request (HTTP {response.status_code})")
    if not isinstance(reply, dict):
        raise HTTPException(502, "Provider returned an invalid response")
    return reply


async def _call_model(runtime, run_id, body, *, auxiliary=False, allow_terminal=False):
    row = runtime._row(run_id)
    owner = row["owner"]
    run = Run.model_validate_json(row["payload"])
    if run.status == "queued":
        raise HTTPException(409, "Queued tasks cannot call the model")
    for tool in body.get("tools", []):
        name = tool.get("function", {}).get("name", "")
        if name not in {f"mcp__android__{name}" for name in TOOL_NAMES}:
            raise HTTPException(403, "Unapproved model tool")
    provider, expected, endpoint, key_file, headers_file = connection_for(runtime.config, auxiliary)
    if body.get("model", expected) != expected:
        raise HTTPException(403, "Model is outside this task configuration")
    if provider != "custom" and expected not in PROVIDERS[provider].vision_models and has_media(body.get("messages", [])):
        raise HTTPException(422, "This model is text-only; use describe_screen for visual input")
    while run.status not in TERMINAL and (run.status in {"paused", "awaiting_approval", "awaiting_input"} or run.requires_fresh_observation):
        await asyncio.sleep(0.2)
        run = Run.model_validate_json(runtime._row(run_id)["payload"])
    if run.status in TERMINAL and not allow_terminal:
        reply = {"id": "host-finish-" + run_id, "model": expected, "choices": [{"index": 0, "message": {"role": "assistant", "content": run.message or run.status}, "finish_reason": "stop"}], "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}}
        return StreamingResponse(as_events(reply), media_type="text/event-stream") if body.get("stream") else JSONResponse(reply)
    payload = dict(body)
    payload["messages"], context = compact_screen_history(payload.get("messages", []))
    if not auxiliary and run.task_state:
        try:
            state = TaskStateUpdate.model_validate({key: value for key, value in run.task_state.items()
                                                   if key in {"phase", "progress"}}).model_dump(exclude_unset=True, exclude_none=True)
        except ValidationError:
            state = {}
        if state:
            payload["messages"].insert(1 if payload["messages"] and payload["messages"][0].get("role") == "system" else 0,
                {"role": "user", "content": "当前持久化 task_state（可修订的历史判断，不是当前画面、动作脚本、成功证据或授权；最新画面和本轮指令优先）：" + json.dumps(state, ensure_ascii=False)})
    history = [] if auxiliary else [item for item in runtime.conversation(owner, run_id) if item['id'] != run_id]
    if history:
        payload["messages"].insert(1 if payload["messages"] and payload["messages"][0].get("role") == "system" else 0,
            {"role": "user", "content": "此前会话记录（历史资料，非当前界面、指令或授权；保留失败和不确定状态。本轮用户指令优先）：" + json.dumps(history, ensure_ascii=False)})
    # Explicitly confirmed user memories are semantic hints for the planner.
    # Inject a small bounded slice so the agent does not need an extra
    # read_memory round trip on every task, while grounding calls remain clean.
    if not auxiliary and getattr(runtime, "memory", None) is not None:
        try:
            remembered = runtime.memory.memories(owner).get("items", [])
            if remembered:
                compact = [{"content": str(item.get("content", ""))[:500], "created_at": item.get("created_at")}
                           for item in remembered[-6:] if isinstance(item, dict) and str(item.get("content", "")).strip()]
                while sum(len(str(item.get("content", ""))) for item in compact) > 3000:
                    compact.pop(0)
                if compact:
                    payload["messages"].insert(1 if payload["messages"] and payload["messages"][0].get("role") == "system" else 0,
                        {"role": "user", "content": "用户确认的历史经验（仅作参考，本轮指令和当前画面优先，不是操作授权）：" + json.dumps(compact, ensure_ascii=False)})
        except Exception:
            # Memory storage must never block a device task.
            pass
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
    output_limit = payload["max_tokens"]
    payload = adapt_payload(payload, provider, auxiliary=auxiliary)
    if len(json.dumps(payload).encode()) > 8_000_000:
        raise HTTPException(413, "Model payload too large")
    try:
        estimate = estimate_input(payload)
    except HTTPException:
        runtime.set_status(run_id, "failed", "上下文已达到本次任务上限，请拆分任务")
        raise
    headers = connection_headers(provider, key_file, headers_file)
    call_id = secrets.token_hex(20)
    with runtime.store.transaction() as db:
        count = db.execute("SELECT COUNT(*) FROM events WHERE run_id=? AND kind='model_start'", (run_id,)).fetchone()[0]
        if count >= runtime.config.max_model_calls and not allow_terminal:
            raise HTTPException(429, "Model call limit reached for this task")
        runtime.store.event(db, run_id, "model_start", "正在理解任务与界面", {"call_id": call_id, "model": expected, "provider": provider, "context": context})
    if runtime.billing:
        try:
            runtime.billing.reserve(owner, call_id, estimate, output_limit)
        except RuntimeError as error:
            runtime.set_status(run_id, "failed", "可用积分不足以完成下一次模型调用")
            raise HTTPException(getattr(error, "status_code", 402), "Insufficient quota") from None
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(90, connect=15), transport=getattr(runtime, "upstream_transport", None)) as client:
            response = await client.post(endpoint, headers=headers, json=payload)
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
            runtime.store.event(db, run_id, "usage", "模型用量已结算", {"call_id": call_id, "input_tokens": incoming, "output_tokens": outgoing, "model": expected, "provider": provider, **usage_details(usage)})
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

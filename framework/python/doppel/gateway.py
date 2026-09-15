import asyncio
from datetime import date, timedelta
import json
import re
import time
from typing import Literal

from fastapi import APIRouter, Depends, Query
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute
from pydantic import Field

from .errors import Conflict, DoppelError
from .models import CommandResult, Model
from .model_proxy import call_model, call_stateless_model
from .intent import parse as parse_intent, prompt as intent_prompt


class CheckedRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()

        async def checked(request):
            try:
                return await handler(request)
            except DoppelError as error:
                return JSONResponse({"detail": str(error)}, status_code=error.status_code)
            except ValueError as error:
                return JSONResponse({"detail": str(error)}, status_code=422)

        return checked


class DeviceInput(Model):
    installation_id: str = Field(min_length=1, max_length=256)
    name: str = Field(min_length=1, max_length=120)


class RunInput(Model):
    device_id: str
    goal: str = Field(min_length=1, max_length=12000)
    mode: str = "assist"
    allowed_packages: list[str] = Field(default_factory=list, max_length=40)
    parent_run_id: str | None = Field(default=None, min_length=1, max_length=128)
    # Set false for system-created background work (schedules/triggers). Such
    # work remains a task record but is excluded from conversation history.
    conversation_enabled: bool = True
    source: Literal["user", "schedule", "trigger"] = "user"


class AnswerInput(Model):
    request_id: str
    text: str | None = Field(default=None, max_length=12000)
    approve: bool | None = None


class ReviewInput(Model):
    """A bounded, read-only question about a finished task."""
    question: str = Field(min_length=1, max_length=2000)


class ConversationInput(Model):
    message: str = Field(min_length=1, max_length=12000)
    history: list[dict] = Field(default_factory=list, max_length=12)
    device_available: bool = False


def create_router(runtime, owner_dependency):
    router = APIRouter(prefix="/v1", route_class=CheckedRoute)

    @router.post("/devices")
    def register(body: DeviceInput, owner=Depends(owner_dependency)):
        return runtime.register_device(owner, body.installation_id, body.name)

    @router.get("/devices")
    def devices(owner=Depends(owner_dependency)):
        return {"items": runtime.devices(owner)}

    @router.post("/conversation/intent")
    async def conversation_intent(body: ConversationInput, owner=Depends(owner_dependency)):
        """Classify one composer message without creating a device run."""
        history = []
        for item in body.history[-12:]:
            if not isinstance(item, dict) or item.get("role") not in {"user", "assistant"}:
                continue
            content = item.get("content")
            if isinstance(content, str) and content.strip():
                history.append({"role": item["role"], "content": content.strip()[:2000]})
        history.append({"role": "user", "content": body.message.strip()})
        request = {
            "messages": intent_prompt(history),
            "stream": False,
            "max_completion_tokens": 240,
            "response_format": {"type": "json_object"},
        }
        reply = await call_stateless_model(runtime, request)
        content = (((reply.get("choices") or [{}])[0].get("message") or {}).get("content") or "")
        decision = parse_intent(content)
        if decision.intent == "task" and not body.device_available:
            # A task cannot be started without a bound device; keep the
            # distinction explicit so the UI can guide the user to Settings.
            return {"intent": "task", "confidence": decision.confidence, "title": decision.title,
                    "task_goal": decision.task_goal, "message": "这是手机任务，但当前还没有绑定设备。"}
        if decision.intent == "uncertain":
            return {"intent": "uncertain", "confidence": decision.confidence, "title": decision.title,
                    "question": decision.question, "message": decision.question}
        if decision.intent == "conversation":
            chat_request = {
                "messages": [{"role": "system", "content": "你是 Doppel 的手机操作助手。用户当前是在对话中提问，不要操作设备，不要输出工具调用。简洁、准确地回答，并结合给出的历史上下文。"},
                              *history],
                "stream": False,
                "max_completion_tokens": 800,
            }
            chat_reply = await call_stateless_model(runtime, chat_request)
            answer = (((chat_reply.get("choices") or [{}])[0].get("message") or {}).get("content") or "").strip()
            return {"intent": "conversation", "confidence": decision.confidence, "title": decision.title,
                    "reply": answer or "我暂时没有生成可用的回答，请再试一次。"}
        return {"intent": "task", "confidence": decision.confidence, "title": decision.title,
                "task_goal": decision.task_goal}

    @router.post("/runs", status_code=201)
    async def create_run(body: RunInput, owner=Depends(owner_dependency)):
        run = runtime.create_run(owner, body.device_id, body.goal, body.mode, body.allowed_packages,
                                 parent_run_id=body.parent_run_id,
                                 conversation_enabled=body.conversation_enabled,
                                 source=body.source)
        if runtime.config.auto_start:
            runtime.start_run(run.id)
        return run

    @router.get("/runs")
    def runs(owner=Depends(owner_dependency)):
        return {"items": runtime.runs(owner)}

    @router.get("/usage")
    def usage(owner=Depends(owner_dependency), days: int = Query(14, ge=7, le=31)):
        """Return a bounded daily token/request/screenshot series for charts."""
        start = date.today() - timedelta(days=days - 1)
        labels = [(start + timedelta(days=i)).isoformat() for i in range(days)]
        series = {label: {"input_tokens": 0, "output_tokens": 0, "requests": 0, "screenshots": 0} for label in labels}
        rows = runtime.store.all(
            "SELECT substr(e.created_at,1,10) AS day, "
            "SUM(CASE WHEN e.kind='usage' THEN COALESCE(json_extract(e.data,'$.input_tokens'),0) ELSE 0 END) AS input_tokens, "
            "SUM(CASE WHEN e.kind='usage' THEN COALESCE(json_extract(e.data,'$.output_tokens'),0) ELSE 0 END) AS output_tokens, "
            "SUM(CASE WHEN e.kind='model_start' THEN 1 ELSE 0 END) AS requests "
            "FROM events e JOIN runs r ON r.id=e.run_id WHERE r.owner=? AND substr(e.created_at,1,10)>=? GROUP BY day",
            (owner, labels[0]),
        )
        for row in rows:
            if row["day"] in series:
                series[row["day"]].update(input_tokens=int(row["input_tokens"] or 0), output_tokens=int(row["output_tokens"] or 0), requests=int(row["requests"] or 0))
        shots = runtime.store.all(
            "SELECT substr(c.created_at,1,10) AS day, COUNT(*) AS screenshots FROM commands c JOIN runs r ON r.id=c.run_id "
            "WHERE r.owner=? AND c.result IS NOT NULL AND json_extract(c.result,'$.data.image_base64') IS NOT NULL AND substr(c.created_at,1,10)>=? GROUP BY day",
            (owner, labels[0]),
        )
        for row in shots:
            if row["day"] in series:
                series[row["day"]]["screenshots"] = int(row["screenshots"] or 0)
        return {"days": labels, "input_tokens": [series[x]["input_tokens"] for x in labels],
                "output_tokens": [series[x]["output_tokens"] for x in labels], "requests": [series[x]["requests"] for x in labels],
                "screenshots": [series[x]["screenshots"] for x in labels]}

    @router.get("/runs/{run_id}")
    def get_run(run_id: str, owner=Depends(owner_dependency)):
        return runtime.get_run(owner, run_id)

    @router.post("/runs/{run_id}/pause")
    def pause(run_id: str, owner=Depends(owner_dependency)):
        return runtime.pause_run(owner, run_id)

    @router.post("/runs/{run_id}/resume")
    def resume(run_id: str, owner=Depends(owner_dependency)):
        return runtime.resume_run(owner, run_id)

    @router.post("/runs/{run_id}/cancel")
    def cancel(run_id: str, owner=Depends(owner_dependency)):
        return runtime.cancel_run(owner, run_id)

    @router.post("/runs/{run_id}/answer")
    def answer(run_id: str, body: AnswerInput, owner=Depends(owner_dependency)):
        return runtime.answer(owner, run_id, body.request_id, body.text, body.approve)

    @router.get("/runs/{run_id}/events")
    def events(run_id: str, after: int = Query(0, ge=0), owner=Depends(owner_dependency)):
        return {"items": runtime.events(owner, run_id, after)}

    @router.get("/runs/{run_id}/conversation")
    def conversation(run_id: str, owner=Depends(owner_dependency)):
        return {"items": runtime.conversation(owner, run_id)}

    @router.post("/runs/{run_id}/review")
    async def review(run_id: str, body: ReviewInput, owner=Depends(owner_dependency)):
        # Reviews are deliberately a model call without tools.  The terminal
        # run is used as evidence, but the request cannot enqueue a device
        # command or change task state.
        run = runtime.get_run(owner, run_id)
        if run.status not in {"paused", "completed", "failed", "cancelled"}:
            raise Conflict("Pause the task before opening a review")
        def redact(value: str, limit: int):
            value = re.sub(r"(?i)(password|passwd|密码|验证码|token|api[_ -]?key|secret)\s*[:：=]?\s*[^\s,，;；]{1,120}", "[已隐藏]", value)
            value = re.sub(r"(?i)(?:x|y|坐标|points?)\s*[:：=]\s*[-+]?\d+(?:\.\d+)?", "[位置已隐藏]", value)
            return re.sub(r"\s+", " ", value).strip()[:limit]

        evidence = {
            "run_id": run.id,
            "goal": redact(run.goal, 1500),
            "status": run.status,
            "message": redact(run.message, 1800),
            "pending_request": None,
        }
        events = runtime.events(owner, run_id, 0)
        evidence["events"] = [
            {"kind": item.kind, "message": redact(str(item.message), 700)}
            for item in events[-36:]
        ]
        prompt = (
            "你是手机任务的只读复盘助手。只能根据提供的任务证据回答用户问题，"
            "不得创建任务、调用工具、提出可直接执行的点击/滑动指令，也不要声称看到了未提供的截图。"
            "保留不确定性，指出事实与推测的区别。请用简洁中文回答，不输出 JSON 或 Markdown。\n"
            "用户问题：" + body.question.strip() + "\n任务证据：" + json.dumps(evidence, ensure_ascii=False)
        )
        request = {
            "messages": [{"role": "system", "content": prompt}],
            "stream": False,
            "max_completion_tokens": 1000,
        }
        response = await call_model(runtime, run_id, request, auxiliary=False, allow_terminal=True)
        # call_model returns a JSONResponse for HTTP providers.  Preserve the
        # OpenAI response shape for the route layer; the Android client accepts
        # either this shape or the direct runtime's {answer: ...} shape.
        return response

    @router.get("/devices/{device_id}/commands")
    async def command(device_id: str, timeout: float = Query(20, ge=0, le=25), owner=Depends(owner_dependency)):
        deadline = time.monotonic() + timeout
        while True:
            item = runtime.next_command(owner, device_id)
            if item is not None or time.monotonic() >= deadline:
                return {"command": item}
            await asyncio.sleep(0.1)

    @router.post("/devices/{device_id}/results")
    def result(device_id: str, body: CommandResult, owner=Depends(owner_dependency)):
        runtime.submit_result(owner, device_id, body)
        return {"ok": True}

    from .internal import create_internal_router
    from .document_api import create_document_router
    from .retention import create_retention_router
    from .extension_api import create_extension_router
    from .speech_api import create_speech_router
    from .scheduler import create_schedule_router, get_scheduler
    router.include_router(create_internal_router(runtime))
    router.include_router(create_document_router(runtime, owner_dependency))
    router.include_router(create_retention_router(runtime, owner_dependency))
    router.include_router(create_extension_router(runtime, owner_dependency))
    router.include_router(create_speech_router(runtime, owner_dependency))
    router.include_router(create_schedule_router(get_scheduler(runtime), owner_dependency))
    return router

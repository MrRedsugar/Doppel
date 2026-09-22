"""Model-backed routing for the conversation composer.

The router deliberately does not inspect words such as ``click`` or ``chat``.
Those heuristics break as soon as a user describes a task indirectly.  A small
structured model response is used instead; malformed or ambiguous responses
are returned as ``uncertain`` so the client can ask one clarifying question.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Any, Literal


Intent = Literal["task", "conversation", "uncertain"]


@dataclass(frozen=True)
class IntentDecision:
    intent: Intent
    confidence: float
    task_goal: str = ""
    title: str = ""
    question: str = ""
    reason: str = ""


SYSTEM_PROMPT = """你是 Doppel 的消息路由器。判断用户这条消息是：
task（需要操作手机才能完成）、conversation（只需要回答或讨论，不操作手机）、
uncertain（信息不足，必须先向用户澄清）。不要根据关键词机械判断，要结合上下文和
用户真正想要的结果。只输出一个 JSON 对象，不要 Markdown、解释或额外字段：
{"intent":"task|conversation|uncertain","confidence":0到1之间的数字,
"task_goal":"若为 task，整理成可执行目标；否则为空",
"title":"不超过30字的会话标题",
"question":"若 uncertain，向用户提出一个简短澄清问题；否则为空",
"reason":"不超过80字的判断依据"}
"""


def prompt(messages: list[dict[str, Any]], *, max_items: int = 12) -> list[dict[str, str]]:
    """Build a bounded, provider-neutral classification request.

    Only recent plain text is sent. Screenshots, tool calls and huge history do
    not belong in routing and would needlessly consume tokens.
    """
    recent: list[dict[str, str]] = []
    start = max(0, len(messages) - max_items)
    latest_user = next((i for i in range(len(messages) - 1, start - 1, -1)
                        if isinstance(messages[i], dict) and messages[i].get("role") == "user"
                        and isinstance(messages[i].get("content"), str)), None)
    for i in range(start, len(messages)):
        item = messages[i]
        if not isinstance(item, dict) or item.get("role") not in {"user", "assistant"}:
            continue
        value = item.get("content", "")
        if isinstance(value, str) and value.strip():
            text = value.strip()
            if i == latest_user and len(text) > 12000:
                raise ValueError("消息长度无效")
            recent.append({"role": str(item["role"]), "content": text if i == latest_user else text[:2000]})
    return [{"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": json.dumps({"messages": recent}, ensure_ascii=False)}]


def parse(raw: str | dict[str, Any]) -> IntentDecision:
    """Validate strict router output; never guess on malformed output."""
    value: Any = raw
    if isinstance(raw, str):
        text = raw.strip()
        if text.startswith("```"):
            text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text, flags=re.I | re.S).strip()
        try:
            value = json.loads(text)
        except (TypeError, ValueError):
            return IntentDecision("uncertain", 0.0, question="你希望我直接操作手机，还是只回答这个问题？", reason="路由结果不是有效 JSON")
    if not isinstance(value, dict):
        return IntentDecision("uncertain", 0.0, question="你希望我直接操作手机，还是只回答这个问题？", reason="路由结果格式无效")
    intent = value.get("intent")
    try:
        confidence = float(value.get("confidence", 0))
    except (TypeError, ValueError):
        confidence = 0.0
    confidence = max(0.0, min(1.0, confidence))
    if intent not in {"task", "conversation", "uncertain"}:
        intent = "uncertain"
        confidence = 0.0
    goal = str(value.get("task_goal", "") or "").strip()[:8000]
    title = str(value.get("title", "") or "").strip()[:120]
    question = str(value.get("question", "") or "").strip()[:500]
    reason = str(value.get("reason", "") or "").strip()[:200]
    # A task without a usable goal cannot be dispatched safely.
    if intent == "task" and not goal:
        return IntentDecision("uncertain", min(confidence, 0.49), title=title,
                              question="请说明你希望我在手机上完成什么？", reason="任务缺少可执行目标")
    if intent == "uncertain" and not question:
        question = "你希望我直接操作手机，还是只回答这个问题？"
    return IntentDecision(intent, confidence, goal, title, question, reason)


def should_dispatch(decision: IntentDecision, threshold: float = 0.72) -> bool:
    """Only high-confidence task decisions may create a device run."""
    return decision.intent == "task" and decision.confidence >= threshold and bool(decision.task_goal)

from doppel.intent import parse, prompt, should_dispatch
import json
import pytest


def test_latest_message_keeps_tail_constraints_while_history_stays_bounded():
    latest = "x" * 11900 + " 不要修改权限。"
    messages = [{"role": "user", "content": "h" * 3000},
                {"role": "assistant", "content": "a" * 3000},
                {"role": "user", "content": latest}]
    rows = json.loads(prompt(messages)[1]["content"])["messages"]
    assert [len(row["content"]) for row in rows[:2]] == [2000, 2000]
    assert rows[2]["content"] == latest
    messages[2]["content"] = "x" * 12001
    with pytest.raises(ValueError, match="消息长度无效"):
        prompt(messages)


def test_structured_task_is_dispatchable_without_keyword_rules():
    decision = parse({"intent": "task", "confidence": 0.93, "task_goal": "把订单页面保存为草稿", "title": "保存订单"})
    assert decision.intent == "task"
    assert should_dispatch(decision)


def test_conversation_is_never_dispatched():
    decision = parse('{"intent":"conversation","confidence":0.99,"title":"复盘","reason":"回答说明即可"}')
    assert decision.intent == "conversation"
    assert not should_dispatch(decision)


def test_invalid_or_incomplete_model_output_fails_closed():
    assert parse("not json").intent == "uncertain"
    missing_goal = parse({"intent": "task", "confidence": 1})
    assert missing_goal.intent == "uncertain"
    assert not should_dispatch(missing_goal)


def test_prompt_is_bounded_and_excludes_images_and_tools():
    request = prompt([
        {"role": "user", "content": "first"},
        {"role": "tool", "content": "secret"},
        {"role": "user", "content": "last"},
        {"role": "user", "content": [{"type": "image_url", "image_url": {"url": "x"}}]},
    ])
    assert request[0]["role"] == "system"
    assert "first" in request[1]["content"] and "last" in request[1]["content"]
    assert "secret" not in request[1]["content"]
    assert "image_url" not in request[1]["content"]

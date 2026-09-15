"""Compact obsolete Android trees without rewriting the conversation protocol."""

import json
import re


SCREEN_TOOLS = {"mcp__android__observe", "mcp__android__act", "mcp__android__list_apps", "mcp__android__describe_screen"}
SCREEN_HEADER = re.compile(r"App [^;\r\n]{0,256}; screen [^;\r\n]{1,128}; [1-9][0-9]{0,4}x[1-9][0-9]{0,4}")
HISTORY_MARKER = "[older screen nodes omitted; use the latest observation for targets]"
PRESSURE_BYTES = 96000
RECEIPT_BYTES = 1024
TOTAL_RECEIPT_BYTES = 24000
MUTATIONS = {"tap", "long_press", "type", "scroll", "back", "home", "launch", "open_document", "recents", "notifications", "quick_settings", "split_screen"}


def _visible_text(screen):
    labels = []
    for line in screen.splitlines()[1:]:
        _, separator, label = line.partition(" ")
        if separator and not line.startswith("[") and not label.startswith("unlabeled at "):
            labels.append(label)
    return list(dict.fromkeys(labels))


def _mutation(function):
    if function.get("name") != "mcp__android__act":
        return False
    try:
        arguments = json.loads(function.get("arguments", "{}"))
    except (ValueError, TypeError):
        return False
    action = arguments.get("action") if isinstance(arguments, dict) else None
    return isinstance(action, str) and action in MUTATIONS


def _bounded_changes(changes, budget):
    if budget < 32:
        return []
    result = []
    for label in changes:
        if len(json.dumps(result + [label], ensure_ascii=False).encode()) > budget:
            if not result:
                label = label.encode()[:budget - 16].decode("utf-8", errors="ignore")
                while label and len(json.dumps([label], ensure_ascii=False).encode()) > budget:
                    label = label[:-1]
                if label:
                    result.append(label)
            break
        result.append(label)
    return result


def compact_screen_history(messages):
    before = len(json.dumps(messages, ensure_ascii=False).encode())
    metrics = {"screens_compacted": 0, "bytes_before": before, "bytes_after": before, "bytes_saved": 0, "receipt_bytes": 0}
    if before < PRESSURE_BYTES:
        return list(messages), metrics
    calls, candidates = {}, []
    previous_labels = set()
    for index, message in enumerate(messages):
        if not isinstance(message, dict):
            continue
        tool_calls = message.get("tool_calls")
        if message.get("role") == "assistant" and isinstance(tool_calls, list):
            for call in tool_calls:
                if isinstance(call, dict) and isinstance(call.get("id"), str):
                    function = call.get("function")
                    calls[call["id"]] = function if isinstance(function, dict) else {}
        call_id = message.get("tool_call_id")
        if message.get("role") != "tool" or not isinstance(call_id, str):
            continue
        function = calls.get(call_id, {})
        name = function.get("name")
        if not isinstance(name, str) or name not in SCREEN_TOOLS:
            continue
        content = message.get("content")
        if not isinstance(content, str):
            continue
        try:
            result = json.loads(content)
        except ValueError:
            continue
        if not isinstance(result, dict) or not isinstance(result.get("screen"), str):
            continue
        header, separator, _ = result["screen"].partition("\n")
        if separator and SCREEN_HEADER.fullmatch(header):
            labels = _visible_text(result["screen"])
            changes = [label for label in labels if label not in previous_labels] if result.get("status") == "ok" and _mutation(function) else []
            candidates.append((index, result, header, changes))
            previous_labels = set(labels)

    compacted = list(messages)
    count = receipt_bytes = 0
    # Preserve three complete results even when they share a screen ID.
    for index, result, header, changes in reversed(candidates[:-3]):
        screen = header + "\n" + HISTORY_MARKER
        if len(screen) >= len(result["screen"]) or result["screen"] == screen:
            continue
        replacement = dict(result, screen=screen)
        receipt = _bounded_changes(changes, min(RECEIPT_BYTES, TOTAL_RECEIPT_BYTES - receipt_bytes))
        if receipt and "screen_changes" not in result:
            replacement["screen_changes"] = receipt
            if receipt != changes:
                replacement["screen_changes_truncated"] = True
        content = json.dumps(replacement, ensure_ascii=False, separators=(",", ":"))
        if len(content.encode()) >= len(messages[index]["content"].encode()):
            continue
        compacted[index] = dict(messages[index], content=content)
        if "screen_changes" not in result and receipt:
            receipt_bytes += len(json.dumps(receipt, ensure_ascii=False).encode())
        count += 1
    after = len(json.dumps(compacted, ensure_ascii=False).encode())
    return compacted, dict(metrics, screens_compacted=count, bytes_after=after, bytes_saved=before - after, receipt_bytes=receipt_bytes)

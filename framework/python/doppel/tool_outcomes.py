"""Stop unchanged failing tool requests before they consume another completion."""

import hashlib
import json


INSPECTIONS = {"observe", "describe_screen", "list_apps", "list_documents", "inspect_document", "read_memory", "list_extensions"}


def record_tool_outcome(runtime, run_id, name, arguments, failed, outcome_status=None):
    fingerprint = hashlib.sha256(json.dumps([name, arguments], sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()
    consecutive = 1 if failed else 0
    observation = runtime.observation(run_id)
    package = observation.package_name if observation else None
    package_hash = hashlib.sha256(json.dumps(package).encode()).hexdigest()
    stale_hash, stale_failures, stale_package = None, 0, None
    is_stale_action = name == "act" and failed and outcome_status == "stale"
    if is_stale_action:
        identity = [name, {key: value for key, value in arguments.items() if key != "screen_id" and value is not None},
                    package]
        stale_hash = hashlib.sha256(json.dumps(identity, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()
        stale_failures, stale_package = 1, package_hash
    with runtime.store.transaction() as db:
        previous = db.execute("SELECT kind,data FROM events WHERE run_id=? AND kind IN ('tool_outcome','user_answer') ORDER BY sequence DESC LIMIT 1", (run_id,)).fetchone()
        if previous and previous["kind"] == "tool_outcome":
            data = json.loads(previous["data"])
            if failed and not is_stale_action and data.get("request_hash") == fingerprint:
                consecutive += data.get("consecutive_failures", 0)
            if is_stale_action and data.get("stale_request_hash") == stale_hash:
                stale_failures += data.get("stale_failures", 0)
            elif (name in INSPECTIONS or (name == "act" and arguments.get("action") == "wait")) and data.get("stale_package_hash") == package_hash:
                # Reading the same moving screen is not evidence of action progress.
                stale_hash, stale_failures = data.get("stale_request_hash"), data.get("stale_failures", 0)
                stale_package = package_hash
        runtime.store.event(db, run_id, "tool_outcome", "Tool request failed" if failed else "Tool request returned", {
            "tool": name, "request_hash": fingerprint, "failed": failed, "consecutive_failures": consecutive,
            "stale_request_hash": stale_hash, "stale_failures": stale_failures, "stale_package_hash": stale_package,
        })
    if consecutive >= 3 or stale_failures >= 3:
        row = runtime._row(run_id)
        if json.loads(row["payload"])["status"] == "running":
            message = ("同一操作连续三次因界面过期而未执行，任务已停止以避免继续消耗额度"
                       if stale_failures >= 3 else "相同工具请求连续失败三次，任务已停止以避免继续消耗额度")
            runtime.set_status(run_id, "failed", message)

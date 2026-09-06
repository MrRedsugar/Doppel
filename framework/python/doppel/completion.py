"""Host-issued evidence binds a task finish to actual tool results."""

import hashlib
import json
import secrets

from .errors import Conflict
from .models import Observation, Run


def record_evidence(runtime, run_id, tool, arguments, result):
    if not isinstance(result, dict) or result.get("status", "ok") != "ok" or result.get("available") is False or result.get("isError"):
        return result
    detail = {"id": secrets.token_hex(12), "tool": tool}
    if tool in {"observe", "act", "list_apps", "describe_screen"}:
        observation = runtime.observation(run_id)
        if observation is None or (tool != "describe_screen" and not result.get("screen")):
            return result
        detail["screen_id"] = observation.screen_id
        command = runtime.store.one("SELECT id FROM commands WHERE run_id=? ORDER BY rowid DESC LIMIT 1", (run_id,))
        if command is None:
            return result
        detail["command_id"] = command["id"]
    elif tool in {"inspect_document", "transform_document"}:
        owner = runtime._row(run_id)["owner"]
        name = arguments.get("output") if tool == "transform_document" else arguments.get("name")
        path = runtime.config.data_dir / "documents" / owner / name
        detail.update(document=name, digest=hashlib.sha256(path.read_bytes()).hexdigest())
    elif tool not in {"read_memory", "save_memory", "call_extension"}:
        return result
    with runtime.store.transaction() as db:
        runtime.store.event(db, run_id, "evidence", "Tool result recorded", detail)
    return dict(result, evidence_id=detail["id"])


def finish_task(runtime, run_id, outcome, summary, evidence_ids):
    if outcome not in {"completed", "failed"} or not isinstance(summary, str) or not summary.strip() or len(summary) > 12000:
        raise ValueError("A valid outcome and nonblank summary are required")
    if not isinstance(evidence_ids, list) or len(evidence_ids) > 20 or any(not isinstance(item, str) for item in evidence_ids):
        raise ValueError("Invalid evidence IDs")
    with runtime.store.transaction() as db:
        row = db.execute("SELECT * FROM runs WHERE id=?", (run_id,)).fetchone()
        run = Run.model_validate_json(row["payload"])
        if run.status != "running":
            raise Conflict("Resolve the current task state before finishing")
        if db.execute("SELECT 1 FROM commands WHERE run_id=? AND result IS NULL", (run_id,)).fetchone():
            raise Conflict("Wait for the pending device result")
        if outcome == "completed":
            if not evidence_ids:
                raise ValueError("Completion requires evidence_ids from successful tools")
            evidence = {item["id"]: item for r in db.execute("SELECT data FROM events WHERE run_id=? AND kind='evidence'", (run_id,)) for item in [json.loads(r["data"]) ]}
            for evidence_id in evidence_ids:
                item = evidence.get(evidence_id)
                if not item:
                    raise ValueError("Unknown evidence for this task")
                if "screen_id" in item:
                    observation = Observation.model_validate_json(row["observation"]) if row["observation"] else None
                    if observation is None or observation.screen_id != item["screen_id"]:
                        raise ValueError("Screen evidence is stale; verify the result again")
                    latest = db.execute("SELECT id FROM commands WHERE run_id=? ORDER BY rowid DESC LIMIT 1", (run_id,)).fetchone()
                    if latest is None or latest["id"] != item.get("command_id"):
                        raise ValueError("Device command changed; verify the result again")
                if "document" in item:
                    path = runtime.config.data_dir / "documents" / row["owner"] / item["document"]
                    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != item["digest"]:
                        raise ValueError("Document evidence changed; inspect the result again")
        run.status, run.message, run.pending_request = outcome, summary.strip(), None
        runtime.store.event(db, run_id, "finish", summary.strip(), {"evidence_ids": evidence_ids, "outcome": outcome})
        runtime._update(db, run)
    return {"status": outcome, "message": summary.strip()}

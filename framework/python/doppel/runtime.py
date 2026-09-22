import asyncio
import hashlib
import json
import re
import secrets
import time
from dataclasses import dataclass
from pathlib import Path
from typing import get_args

from .errors import Conflict, NotFound, PermissionDenied, ScopeDenied
from .models import Command, CommandResult, Device, Event, Observation, Run, RunStatus, ConversationMessage, TaskStateUpdate, utc_now
from .policy import ActionPolicy
from .store import Store
from .providers import DEFAULT_PROVIDER, resolve_configuration
from . import submission

TERMINAL = {"completed", "failed", "cancelled"}
TAKEOVER_REASONS = {"verification", "login", "payment", "interruption"}


@dataclass
class RuntimeConfig:
    data_dir: Path
    api_key_file: Path | None = None
    model: str | None = None
    host_url: str = "http://127.0.0.1:8765"
    auto_start: bool = True
    command_timeout: float = 90
    max_model_calls: int = 40
    max_output_tokens: int = 1600
    provider: str = DEFAULT_PROVIDER
    vision_model: str | None = None
    provider_endpoint: str | None = None
    provider_headers_file: Path | None = None
    vision_provider: str | None = None
    vision_endpoint: str | None = None
    vision_api_key_file: Path | None = None
    vision_headers_file: Path | None = None
    vision_enhancement_enabled: bool | None = None

    def __post_init__(self):
        for name, value in resolve_configuration(self).items():
            setattr(self, name, value)


class DoppelRuntime:
    def __init__(self, config: RuntimeConfig, billing=None):
        self.config = config
        self.config.data_dir = Path(config.data_dir)
        self.store = Store(self.config.data_dir / "runtime.sqlite3")
        self.billing = billing
        self.policy = ActionPolicy()
        self.driver = None
        self.tasks: dict[str, asyncio.Task] = {}
        self.active_model_calls: dict[str, int] = {}
        self.run_tokens: dict[str, str] = {}
        self._recover_interrupted_runs()

    def _recover_interrupted_runs(self):
        # A data directory has one runtime owner; persisted workers cannot resume.
        with self.store.transaction() as db:
            for row in db.execute("SELECT rowid,payload FROM runs ORDER BY rowid").fetchall():
                run = Run.model_validate_json(row["payload"])
                if not run.queue_sequence:
                    run.queue_sequence = row["rowid"]
                    db.execute("UPDATE runs SET payload=? WHERE id=?", (run.model_dump_json(),run.id))
                if run.status in TERMINAL or run.status == "queued":
                    continue
                run.status = "paused"
                run.message = "Service restarted; verify the previous result, then resume or cancel. Old actions were not replayed."
                run.requires_fresh_observation = True
                if not (run.pending_request or {}).get("manual_only"):
                    run.pending_request = None
                self._update(db, run, "interrupted")
                self._finish_commands(db, run, "service_restart")

    def register_device(self, owner, installation_id, name):
        if not installation_id or len(installation_id) > 256 or not name or len(name) > 120:
            raise ValueError("Invalid device registration")
        with self.store.transaction() as db:
            row = db.execute("SELECT * FROM devices WHERE owner=? AND installation=?", (owner, installation_id)).fetchone()
            device_id = row["id"] if row else secrets.token_hex(16)
            now = utc_now()
            db.execute("INSERT INTO devices VALUES(?,?,?,?,?) ON CONFLICT(owner,installation) DO UPDATE SET name=excluded.name,last_seen=excluded.last_seen",
                       (device_id, owner, installation_id, name, now))
        return Device(id=device_id, name=name, last_seen=now)

    def _device(self, owner, device_id):
        row = self.store.one("SELECT * FROM devices WHERE id=? AND owner=?", (device_id, owner))
        if row is None:
            raise NotFound("Device not found")
        return row

    def devices(self, owner):
        return [Device(id=r["id"], name=r["name"], last_seen=r["last_seen"]) for r in self.store.all("SELECT * FROM devices WHERE owner=?", (owner,))]

    def _row(self, run_id):
        row = self.store.one("SELECT * FROM runs WHERE id=?", (run_id,))
        if row is None:
            raise NotFound("Task not found")
        return row

    def get_run(self, owner, run_id):
        row = self._row(run_id)
        if row["owner"] != owner:
            raise NotFound("Task not found")
        return self._present(Run.model_validate_json(row["payload"]))

    def _queue(self, db, device_id):
        return [Run.model_validate_json(row["payload"]) for row in db.execute("SELECT payload FROM runs WHERE device_id=? ORDER BY rowid", (device_id,)).fetchall()
                if json.loads(row["payload"])["status"] not in TERMINAL]

    def _present(self, run):
        run.queue_position = 0
        if run.status == "queued":
            with self.store.lock:
                queued = [item.id for item in self._queue(self.store.db, run.device_id) if item.status == "queued"]
            if run.id in queued:
                run.queue_position = queued.index(run.id) + 1
        return run

    def queue_snapshot(self, owner, device_id):
        self._device(owner, device_id)
        with self.store.lock:
            waiting = 0
            runs = self._queue(self.store.db, device_id)
            for run in runs:
                if run.status == "queued":
                    waiting += 1
                    run.queue_position = waiting
            return runs

    def runs(self, owner):
        return [self._present(Run.model_validate_json(r["payload"])) for r in self.store.all("SELECT payload FROM runs WHERE owner=? ORDER BY rowid DESC LIMIT 200", (owner,))]

    def create_run(self, owner, device_id, goal, mode, allowed_packages=None, *, parent_run_id=None,
                   conversation_enabled=True, source="user", title=None, request_id=None, source_metadata=None, defer_start=False):
        self._device(owner, device_id)
        if not goal.strip() or len(goal) > 12000 or mode not in {"ask", "assist", "full"}:
            raise ValueError("Invalid goal or mode")
        if title is not None and (not isinstance(title, str) or len(title) > 120):
            raise ValueError("Invalid title")
        supplied_title = " ".join((title or "").split())
        if not isinstance(defer_start,bool):
            raise ValueError("Invalid deferred start")
        if request_id is not None and (not isinstance(request_id,str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,160}",request_id)):
            raise ValueError("Invalid submission id")
        source_metadata = {} if source_metadata is None else source_metadata
        if not isinstance(source_metadata,dict) or len(json.dumps(source_metadata,ensure_ascii=False)) > 4000:
            raise ValueError("Invalid task source metadata")
        fingerprint = hashlib.sha256(json.dumps(dict(goal=goal,mode=mode,allowed_packages=allowed_packages or [],parent_run_id=parent_run_id,
            conversation_enabled=conversation_enabled,source=source,title=title,source_metadata=source_metadata,defer_start=defer_start),sort_keys=True,ensure_ascii=False).encode()).hexdigest()
        token = secrets.token_urlsafe(32)
        if not isinstance(conversation_enabled, bool):
            raise ValueError("conversation_enabled must be boolean")
        if source not in {"user", "schedule", "trigger"}:
            raise ValueError("Invalid run source")
        if source != "user" and conversation_enabled:
            raise ValueError("Background tasks cannot create conversations")
        if not conversation_enabled and parent_run_id is not None:
            if source != "user":
                raise ValueError("Background task cannot be attached to a conversation")
            raise Conflict("Background task cannot be attached to a conversation")
        conversation_id = "conversation-" + secrets.token_hex(12) if conversation_enabled and parent_run_id is None else None
        title = " ".join(goal.strip().split())[:36]
        for prefix in ("请帮我 ", "帮我 ", "请 "):
            if title.startswith(prefix):
                title = title[len(prefix):]
                break
        run = Run(id=secrets.token_hex(16), device_id=device_id, goal=goal.strip(), title=supplied_title or title or "新任务",
                  conversation_id=conversation_id, conversation_enabled=conversation_enabled,
                  source=source, source_metadata=source_metadata,
                  mode=mode, status="running", created_at=utc_now(),
                  allowed_packages=allowed_packages or [], parent_run_id=parent_run_id,
                  conversation_messages=[ConversationMessage(id=secrets.token_hex(8), role="user", text=goal.strip(), kind="request", created_at=utc_now())] if conversation_enabled else [])
        with self.store.transaction() as db:
            if request_id is not None:
                retention=db.execute("SELECT minimum_issued_at FROM submission_retention WHERE owner=? AND device_id=?",(owner,device_id)).fetchone()
                retention_floor=max(retention[0] if retention else 0,submission.floor(int(time.time()*1000)))
                issued_at=submission.validate(request_id,int(time.time()*1000),retention_floor)
                prior = db.execute("SELECT run_id,fingerprint,summary FROM run_submissions WHERE owner=? AND device_id=? AND request_id=?", (owner,device_id,request_id)).fetchone()
                if prior:
                    if prior["fingerprint"] != fingerprint:
                        raise Conflict("Submission id was already used for different task content")
                    try:
                        return self.get_run(owner,prior["run_id"])
                    except NotFound:
                        return Run.model_validate_json(prior["summary"])
                removed=db.execute("DELETE FROM run_submissions WHERE owner=? AND device_id=? AND issued_at_ms<?",(owner,device_id,retention_floor)).rowcount
                if removed:
                    db.execute("INSERT INTO submission_retention VALUES(?,?,?) ON CONFLICT(owner,device_id) DO UPDATE SET minimum_issued_at=excluded.minimum_issued_at",
                               (owner,device_id,retention_floor))
                if db.execute("SELECT COUNT(*) FROM run_submissions WHERE owner=? AND device_id=? AND (issued_at_ms IS NULL)=?",(owner,device_id,issued_at is None)).fetchone()[0] >= 2048:
                    raise Conflict("Legacy submission receipt capacity reached; update submission keys" if issued_at is None
                                   else "Submission receipt capacity for the last 30 days reached; retry later. Task was not created")
            if parent_run_id is not None:
                parent = self.get_run(owner, parent_run_id)
                if parent.device_id != device_id:
                    raise Conflict("Previous conversation must belong to the same device")
                if not parent.conversation_enabled:
                    raise Conflict("Previous task has no conversation")
                run.conversation_id = parent.conversation_id or parent.id
            active = self._queue(db,device_id)
            if len(active) >= 50:
                raise Conflict("This device queue is full; cancel or finish an existing task")
            run.status = "queued" if active or source != "user" or defer_start else "running"
            run.queue_sequence = db.execute("SELECT COALESCE(MAX(json_extract(payload,'$.queue_sequence')),0)+1 FROM runs WHERE device_id=?", (device_id,)).fetchone()[0]
            if run.status == "queued":
                run.message = "任务已加入队列，等待前面的任务结束"
            db.execute("INSERT INTO runs(id,owner,device_id,payload,token_hash) VALUES(?,?,?,?,?)",
                       (run.id, owner, device_id, run.model_dump_json(), hashlib.sha256(token.encode()).hexdigest()))
            if request_id is not None:
                db.execute("INSERT INTO run_submissions(owner,device_id,request_id,run_id,fingerprint,summary,issued_at_ms) VALUES(?,?,?,?,?,?,?)",
                           (owner,device_id,request_id,run.id,fingerprint,self._submission_summary(run),issued_at))
            self.store.event(db, run.id, "created", goal.strip())
        self.run_tokens[run.id] = token
        return self._present(run)

    def conversation(self, owner, run_id):
        """Bounded source history, resolved afresh so deletion revokes future context."""
        first = self.get_run(owner, run_id)
        entries, seen, remaining = [], set(), 24000
        current = first
        while current and current.id not in seen and len(entries) < 12 and remaining > 0:
            if current.device_id != first.device_id:
                break
            seen.add(current.id)
            if not current.conversation_enabled:
                break
            goal = current.goal[:min(3000, remaining)]; remaining -= len(goal)
            message = current.message[:min(4000, remaining)]; remaining -= len(message)
            entries.append(dict(id=current.id, conversation_id=current.conversation_id or current.id,
                                title=current.title or goal.splitlines()[0][:36], goal=goal, message=message,
                                status=current.status, created_at=current.created_at,
                                messages=[item.model_dump() for item in current.conversation_messages]))
            if not current.parent_run_id:
                break
            try:
                current = self.get_run(owner, current.parent_run_id)
            except NotFound:
                break
        return list(reversed(entries))

    def authorize_run_token(self, run_id, token):
        row = self._row(run_id)
        if not secrets.compare_digest(row["token_hash"], hashlib.sha256(token.encode()).hexdigest()):
            raise PermissionDenied("Invalid task token")
        return row["owner"]

    @staticmethod
    def _submission_summary(run):
        return Run(id=run.id,device_id=run.device_id,goal="",mode=run.mode,status=run.status,source=run.source,
                   created_at=run.created_at,conversation_enabled=False,queue_sequence=run.queue_sequence).model_dump_json()

    def _update(self, db, run, kind="state"):
        if run.status == "completed":
            run.task_state["phase"] = "completed"
            progress = run.task_state.get("progress")
            if progress and progress.get("plan"):
                run.task_state["progress"] = dict(progress, completed=len(progress["plan"]), total_known=True)
        if run.message and run.conversation_enabled:
            duplicate = run.conversation_messages and run.conversation_messages[-1].role == "assistant" and run.conversation_messages[-1].text == run.message
            if not duplicate:
                run.conversation_messages.append(ConversationMessage(id=secrets.token_hex(8), role="assistant",
                    text=run.message[:12000], kind="progress", created_at=utc_now()))
                del run.conversation_messages[:-80]
        db.execute("UPDATE runs SET payload=? WHERE id=?", (run.model_dump_json(), run.id))
        db.execute("UPDATE run_submissions SET summary=? WHERE run_id=?",(self._submission_summary(run),run.id))
        self.store.event(db, run.id, kind, run.message, {"status": run.status})

    def update_task_state(self, run_id, value):
        """Merge bounded model display metadata without queuing commands or changing run status."""
        update = TaskStateUpdate.model_validate(value).model_dump(exclude_unset=True, exclude_none=True)
        with self.store.transaction() as db:
            run = Run.model_validate_json(self._row(run_id)["payload"])
            if run.status != "running":
                raise Conflict("Task state changed before progress update")
            if any(run.task_state.get(key) != item for key, item in update.items()):
                run.task_state.update(update)
                db.execute("UPDATE runs SET payload=? WHERE id=?", (run.model_dump_json(), run.id))
                self.store.event(db, run.id, "task_progress", "", {"task_state": run.task_state})
        return run

    def _finish_commands(self, db, run, reason="task_ended"):
        for row in db.execute("SELECT id FROM commands WHERE run_id=? AND result IS NULL", (run.id,)).fetchall():
            result = CommandResult(
                command_id=row["id"], run_id=run.id,
                status="cancelled" if run.status == "cancelled" else "error",
                message="Device command outcome is uncertain; verify the previous result before retrying.",
                data={"uncertain": True, "reason": reason},
            )
            db.execute("UPDATE commands SET state='finished',result=? WHERE id=?", (result.model_dump_json(), row["id"]))
            self.store.event(db, run.id, "command_result", result.message,
                             {"command_id": row["id"], "status": result.status, **result.data})

    def set_status(self, run_id, status, message="", pending_request=None):
        with self.store.transaction() as db:
            row = db.execute("SELECT payload FROM runs WHERE id=?", (run_id,)).fetchone()
            if row is None:
                raise NotFound("Task not found")
            run = Run.model_validate_json(row["payload"])
            if run.status in TERMINAL:
                self._finish_commands(db, run)
                return run
            if run.status == "queued" and status not in TERMINAL and status != "queued":
                raise Conflict("Queued tasks must start through the device queue")
            run.status, run.message, run.pending_request = status, message, pending_request
            if status in TERMINAL:
                run.pending_request = None
                self._finish_commands(db, run)
            self._update(db, run)
        return run

    def pause_run(self, owner, run_id):
        with self.store.transaction() as db:
            row = db.execute("SELECT payload FROM runs WHERE id=? AND owner=?", (run_id, owner)).fetchone()
            if row is None:
                raise NotFound("Task not found")
            run = Run.model_validate_json(row["payload"])
            if run.status in TERMINAL:
                raise Conflict("Task already ended")
            if run.status == "paused":
                return run
            if run.status == "queued":
                raise Conflict("Queued tasks can only be cancelled")
            run.status, run.message = "paused", "你已暂停任务，继续时将重新观察屏幕"
            self._update(db, run)
        return run

    def resume_run(self, owner, run_id):
        with self.store.transaction() as db:
            row = db.execute("SELECT payload FROM runs WHERE id=? AND owner=?", (run_id, owner)).fetchone()
            if row is None:
                raise NotFound("Task not found")
            run = Run.model_validate_json(row["payload"])
            if run.status != "paused":
                raise Conflict("Task is not paused")
            if self._queue(db,run.device_id)[0].id != run.id:
                raise Conflict("Task is not at the head of the device queue")
            if run.pending_request and run.pending_request.get("reason") in TAKEOVER_REASONS:
                run.pending_request = None
                run.requires_fresh_observation = True
                db.execute("UPDATE runs SET observation=NULL WHERE id=?", (run_id,))
            run.status = "awaiting_approval" if run.pending_request and run.pending_request["kind"] == "approval" else "awaiting_input" if run.pending_request else "running"
            run.message = "已继续"
            self._update(db, run)
        return run

    def cancel_run(self, owner, run_id, expected_status=None):
        if expected_status is not None and expected_status not in get_args(RunStatus):
            raise ValueError("Invalid cancellation precondition")
        self.get_run(owner, run_id)
        with self.store.transaction() as db:
            row = db.execute("SELECT payload FROM runs WHERE id=?", (run_id,)).fetchone()
            run = Run.model_validate_json(row["payload"])
            if expected_status is not None and run.status != expected_status:
                raise Conflict("Task state changed; refresh before cancelling")
            if run.status in TERMINAL:
                return run
            run.status, run.message, run.pending_request = "cancelled", "已取消，已发生的操作不会自动撤销", None
            self._update(db, run)
            self._finish_commands(db, run)
        task = self.tasks.get(run_id)
        if task and not task.done():
            task.cancel()
        return run

    def observation(self, run_id):
        value = self._row(run_id)["observation"]
        return Observation.model_validate_json(value) if value else None

    def _pause_for_takeover(self, db, run, reason):
        run = Run.model_validate_json(db.execute("SELECT payload FROM runs WHERE id=?", (run.id,)).fetchone()["payload"])
        if run.status in TERMINAL:
            return run
        if run.status == "paused" and run.pending_request and run.pending_request.get("reason") == reason:
            return run
        message = {
            "verification": "请在手机上手动完成安全验证，再点击继续。",
            "login": "请在手机上完成登录设置或手动登录，再点击继续。",
            "payment": "付款操作需要你在手机上手动处理。确认结果后再继续，AI 不会重放这次操作。",
            "interruption": "来电、响铃或系统窗口中断了操作。请在手机上处理后点击继续，任务将重新观察屏幕。",
        }[reason]
        run.status, run.message = "paused", message
        run.requires_fresh_observation = True
        run.pending_request = {"id": secrets.token_hex(16), "kind": "input", "reason": reason,
                               "message": message, "manual_only": True}
        self._update(db, run, "human_takeover")
        return run

    def queue_command(self, run_id, kind, **fields):
        if "payment_consent_id" in fields or "mode" in fields:
            raise PermissionDenied("mode and payment_consent_id are host-only fields")
        command = Command(id=secrets.token_hex(16), run_id=run_id, kind=kind, **fields)
        with self.store.transaction() as db:
            row = db.execute("SELECT payload,observation FROM runs WHERE id=?", (run_id,)).fetchone()
            if row is None:
                raise NotFound("Task not found")
            run = Run.model_validate_json(row["payload"])
            command.mode = run.mode
            if run.status != "running":
                raise Conflict("Task is not running")
            if run.requires_fresh_observation and kind not in {"observe", "screenshot"}:
                raise Conflict("A fresh observation is required after human takeover")
            if db.execute("SELECT 1 FROM commands WHERE run_id=? AND result IS NULL", (run_id,)).fetchone():
                raise Conflict("Wait for the previous device command")
            observation = Observation.model_validate_json(row["observation"]) if row["observation"] else None
            if run.allowed_packages:
                if kind == "launch" and command.package_name not in run.allowed_packages:
                    raise PermissionDenied("Application is outside this task's authorized packages")
                if kind in {"tap", "pay", "long_press", "type", "login_phone", "login_code", "scroll", "back"} and (observation is None or observation.package_name not in run.allowed_packages):
                    error = ScopeDenied if observation is not None else PermissionDenied
                    raise error("Current screen is outside this task's authorized packages")
                if kind == "open_document":
                    if "cn.wps.moffice_eng" not in run.allowed_packages:
                        raise PermissionDenied("Opening documents requires WPS in the authorized packages")
                    command.package_name = "cn.wps.moffice_eng"
            node = next((node for node in observation.nodes if node.id == command.target), None) if kind in {"tap", "pay"} and observation else None
            if (kind == "pay" and run.mode == "full" and node is not None and
                    command.screen_id == observation.screen_id):
                command.payment_consent_id = observation.payment_consent_id
            verdict = self.policy.evaluate(run.mode, command, observation)
            if verdict.decision == "deny":
                raise Conflict(verdict.reason)
            checkable = node is not None and node.checkable is True
            if checkable and command.desired_checked is None and verdict.decision != "manual":
                raise ValueError("Target is checkable; provide desired_checked=true or false for the intended final state. Use observe to verify state instead of repeating a tap.")
            if verdict.decision == "manual":
                if verdict.human_takeover:
                    self._pause_for_takeover(db, run, verdict.human_takeover)
                else:
                    run.status = "awaiting_input"
                    run.message = verdict.reason
                    run.pending_request = {"id": command.id, "kind": "input", "message": verdict.reason, "manual_only": True}
                    self._update(db, run)
                result = CommandResult(command_id=command.id, run_id=run.id, status="blocked", message=verdict.reason,
                                       data={"human_takeover": verdict.human_takeover} if verdict.human_takeover else {})
                db.execute("INSERT INTO commands VALUES(?,?,?,?,?,?)", (command.id, run_id, command.model_dump_json(), "finished", result.model_dump_json(), utc_now()))
            else:
                state = "held" if verdict.decision == "approve" else "queued"
                db.execute("INSERT INTO commands VALUES(?,?,?,?,?,?)", (command.id, run_id, command.model_dump_json(), state, None, utc_now()))
                if state == "held":
                    run.status = "awaiting_approval"
                    run.message = verdict.reason
                    run.pending_request = {"id": command.id, "kind": "approval", "message": verdict.reason, "command": command.model_dump()}
                    self._update(db, run)
            self.store.event(db, run_id, "command", kind, {"id": command.id, "kind": kind, "decision": verdict.decision})
        return command

    def next_command(self, owner, device_id):
        self._device(owner, device_id)
        with self.store.transaction() as db:
            db.execute("UPDATE devices SET last_seen=? WHERE id=?", (utc_now(), device_id))
            rows = db.execute("SELECT c.payload,r.payload AS run,r.observation FROM commands c JOIN runs r ON c.run_id=r.id WHERE r.device_id=? AND c.result IS NULL AND c.state='queued' ORDER BY c.rowid", (device_id,)).fetchall()
            for row in rows:
                run = Run.model_validate_json(row["run"])
                if run.status == "running":
                    command = Command.model_validate_json(row["payload"])
                    observation = Observation.model_validate_json(row["observation"]) if row["observation"] else None
                    command.mode = run.mode
                    if (command.kind == "pay" or command.payment_consent_id) and self.policy.evaluate(run.mode, command, observation).decision != "allow":
                        result = CommandResult(command_id=command.id, run_id=run.id, status="blocked", observation=observation,
                                               message="付款授权或页面已变化，未派发付款操作", data={"human_takeover": "payment"})
                        db.execute("UPDATE commands SET state='finished',result=? WHERE id=?", (result.model_dump_json(), command.id))
                        self._pause_for_takeover(db, run, "payment")
                        self.store.event(db, run.id, "command_result", result.message,
                                         {"command_id": command.id, "status": result.status, "human_takeover": "payment"})
                        continue
                    return command
        return None

    def submit_result(self, owner, device_id, result: CommandResult):
        self._device(owner, device_id)
        with self.store.transaction() as db:
            row = db.execute("SELECT c.*,r.device_id,r.owner FROM commands c JOIN runs r ON c.run_id=r.id WHERE c.id=?", (result.command_id,)).fetchone()
            if row is None or row["owner"] != owner or row["device_id"] != device_id:
                raise NotFound("Command not found")
            if row["run_id"] != result.run_id:
                raise Conflict("Command belongs to another task")
            if row["result"]:
                previous = CommandResult.model_validate_json(row["result"])
                if previous != result:
                    raise Conflict("Command already has a different result")
                return
            if row["state"] != "queued":
                raise Conflict("Command has not been approved")
            db.execute("UPDATE commands SET state='finished',result=? WHERE id=?", (result.model_dump_json(), result.command_id))
            if result.observation:
                db.execute("UPDATE runs SET observation=? WHERE id=?", (result.observation.model_dump_json(), result.run_id))
            run_row = db.execute("SELECT payload FROM runs WHERE id=?", (result.run_id,)).fetchone()
            run = Run.model_validate_json(run_row["payload"])
            reason = result.data.get("human_takeover") if result.status == "blocked" else None
            if self.policy.requires_verification(result.observation):
                reason = "verification"
            if reason in TAKEOVER_REASONS:
                self._pause_for_takeover(db, run, reason)
            elif run.status == "running" and run.requires_fresh_observation and result.status == "ok" and result.observation and json.loads(row["payload"])["kind"] in {"observe", "screenshot"}:
                run.requires_fresh_observation = False
                self._update(db, run, "fresh_observation")
            state_data = {key: result.data[key] for key in ("no_op", "checked", "desired_checked") if type(result.data.get(key)) is bool}
            self.store.event(db, result.run_id, "command_result", result.message or result.status,
                             {"command_id": result.command_id, "status": result.status, **state_data})

    def command_result(self, command_id):
        row = self.store.one("SELECT result FROM commands WHERE id=?", (command_id,))
        return CommandResult.model_validate_json(row["result"]) if row and row["result"] else None

    def events(self, owner, run_id, after=0):
        self.get_run(owner, run_id)
        return [Event(sequence=r["sequence"], kind=r["kind"], message=r["message"], data=json.loads(r["data"]), created_at=r["created_at"])
                for r in self.store.all("SELECT * FROM events WHERE run_id=? AND sequence>? ORDER BY sequence LIMIT 500", (run_id, after))]

    def answer(self, owner, run_id, request_id, text=None, approve=None):
        with self.store.transaction() as db:
            row = db.execute("SELECT * FROM runs WHERE id=? AND owner=?", (run_id, owner)).fetchone()
            if row is None:
                raise NotFound("Task not found")
            run = Run.model_validate_json(row["payload"])
            pending = run.pending_request
            if self._queue(db,run.device_id)[0].id != run.id:
                raise Conflict("Task is not at the head of the device queue")
            if run.status not in {"awaiting_approval", "awaiting_input"} or not pending or pending["id"] != request_id:
                raise Conflict("This request is no longer current")
            if pending["kind"] == "approval":
                if approve is None:
                    raise ValueError("approve is required")
                if "extension" in pending and approve:
                    from .extension_runtime import get_extension_manager
                    extension = pending["extension"]
                    server = extension["name"].split(".", 2)[1]
                    config = get_extension_manager(self).get_config(owner, server)
                    if config["revision"] != extension["revision"]:
                        raise Conflict("Extension configuration changed; review a new approval")
                elif "extension" not in pending:
                    command = Command.model_validate(pending["command"])
                    observation = Observation.model_validate_json(row["observation"]) if row["observation"] else None
                    verdict = self.policy.evaluate(run.mode if command.kind == "pay" or command.payment_consent_id else "full", command, observation)
                    if approve and verdict.decision != "allow":
                        raise Conflict("Approval target changed; observe again")
                    if approve:
                        db.execute("UPDATE commands SET state='queued' WHERE id=?", (request_id,))
                    else:
                        result = CommandResult(command_id=request_id, run_id=run_id, status="blocked", message="User declined the action")
                        db.execute("UPDATE commands SET state='finished',result=? WHERE id=?", (result.model_dump_json(), request_id))
            elif not text or not text.strip():
                raise ValueError("A response is required")
            if pending.get("reason") == "application_scope":
                db.execute("UPDATE runs SET observation=NULL WHERE id=?", (run_id,))
            declined = pending["kind"] == "approval" and approve is False
            run.status = "paused" if declined else "running"
            run.message = "已拒绝本次操作，任务已暂停" if declined else "已收到你的回复"
            run.pending_request = None
            self._update(db, run)
            self.store.event(db, run_id, "user_answer", text or ("approved" if approve else "declined"), {"request_id": request_id, "approved": approve})
        return run

    async def perform(self, run_id, kind, **fields):
        while json.loads(self._row(run_id)["payload"])["status"] == "paused":
            await asyncio.sleep(0.2)
        command = self.queue_command(run_id, kind, **fields)
        active_seconds = 0.0
        previous = time.monotonic()
        previous_running = False
        while True:
            result = self.command_result(command.id)
            if result is not None:
                return await self._resolve_takeover(run_id, result)
            run = Run.model_validate_json(self._row(run_id)["payload"])
            if run.status in TERMINAL:
                self.set_status(run_id, run.status, run.message)
                return self.command_result(command.id)
            now = time.monotonic()
            if previous_running and run.status == "running":
                active_seconds += now - previous
            previous = now
            previous_running = run.status == "running"
            if active_seconds >= self.config.command_timeout:
                self.set_status(run_id, "failed", "设备未及时返回执行结果，任务已停止以避免重复操作")
                return self.command_result(command.id)
            await asyncio.sleep(0.1)

    async def _resolve_takeover(self, run_id, result):
        run = Run.model_validate_json(self._row(run_id)["payload"])
        reason = result.data.get("human_takeover") if result.status == "blocked" else None
        if self.policy.requires_verification(result.observation):
            reason = "verification"
        reason = reason or (run.pending_request or {}).get("reason")
        if reason not in TAKEOVER_REASONS:
            return result
        while True:
            while run.status == "paused":
                await asyncio.sleep(0.2)
                run = Run.model_validate_json(self._row(run_id)["payload"])
            if run.status in TERMINAL:
                return result.model_copy(update={"status": "cancelled", "observation": None,
                                                 "message": "Task ended during human takeover", "data": {}})
            fresh = await self.perform(run_id, "observe")
            run = Run.model_validate_json(self._row(run_id)["payload"])
            if run.status in TERMINAL:
                continue
            if fresh.observation and not run.requires_fresh_observation:
                return result.model_copy(update={"status": "blocked", "observation": fresh.observation,
                                                 "message": "Human takeover finished. Inspect the fresh screen; the refused action was not replayed.",
                                                 "data": {"human_takeover": reason, "resumed": True}})
            # A failed refresh needs another explicit continuation, never an automatic retry.
            with self.store.transaction() as db:
                run = self._pause_for_takeover(db, run, reason)

    def start_queued(self, owner, run_id):
        with self.store.transaction() as db:
            run=self.get_run(owner,run_id)
            if run.status in TERMINAL:
                return run
            if self._queue(db,run.device_id)[0].id != run.id:
                raise Conflict("Task is not at the head of the device queue")
            if run.status == "running":
                return run
            if run.status != "queued":
                raise Conflict("Paused tasks require explicit resume")
            if any(id != run_id and not task.done() and Run.model_validate_json(self._row(id)["payload"]).device_id == run.device_id for id,task in self.tasks.items()):
                raise Conflict("Previous task worker is still stopping")
            run.status,run.queue_position,run.message="running",0,"正在查看手机，准备执行任务"
            self._update(db,run,"started")
        return run

    def start_run(self, run_id):
        with self.store.transaction() as db:
            run = Run.model_validate_json(self._row(run_id)["payload"])
            if run.status != "running" or self._queue(db,run.device_id)[0].id != run.id:
                return False
            existing = self.tasks.get(run_id)
            if existing and not existing.done():
                return False
            # Tokens are process-local. Reissue only for an admitted head after a restart.
            token = self.run_tokens.get(run_id) or secrets.token_urlsafe(32)
            db.execute("UPDATE runs SET token_hash=? WHERE id=?", (hashlib.sha256(token.encode()).hexdigest(),run_id))
        self.run_tokens[run_id]=token
        self.tasks[run_id] = asyncio.create_task(self._drive(run_id))
        return True

    async def _drive(self, run_id):
        try:
            if self.driver is None:
                from .harness import HarnessPort
                self.driver = HarnessPort()
            await self.driver.run(self, run_id)
        except asyncio.CancelledError:
            raise
        except Exception as error:
            self.set_status(run_id, "failed", f"执行器不可用：{type(error).__name__}")

    async def close(self):
        pending = [task for task in self.tasks.values() if not task.done()]
        for task in pending:
            task.cancel()
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)
        with self.store.lock:
            self.store.db.close()

import asyncio
import hashlib
import json
import secrets
import time
from dataclasses import dataclass
from pathlib import Path

from .errors import Conflict, NotFound, PermissionDenied, ScopeDenied
from .models import Command, CommandResult, Device, Event, Observation, Run, utc_now
from .policy import ActionPolicy
from .store import Store

TERMINAL = {"completed", "failed", "cancelled"}


@dataclass
class RuntimeConfig:
    data_dir: Path
    api_key_file: Path | None = None
    model: str = "deepseek-v4-pro"
    host_url: str = "http://127.0.0.1:8765"
    auto_start: bool = True
    command_timeout: float = 90
    max_model_calls: int = 40
    max_output_tokens: int = 1600


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
            for row in db.execute("SELECT payload FROM runs").fetchall():
                run = Run.model_validate_json(row["payload"])
                if run.status in TERMINAL:
                    continue
                run.status = "failed"
                run.message = "Service restarted; verify the previous result before retrying."
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
        return Run.model_validate_json(row["payload"])

    def runs(self, owner):
        return [Run.model_validate_json(r["payload"]) for r in self.store.all("SELECT payload FROM runs WHERE owner=? ORDER BY rowid DESC LIMIT 200", (owner,))]

    def create_run(self, owner, device_id, goal, mode, allowed_packages=None):
        self._device(owner, device_id)
        if not goal.strip() or len(goal) > 12000 or mode not in {"ask", "assist", "full"}:
            raise ValueError("Invalid goal or mode")
        token = secrets.token_urlsafe(32)
        run = Run(id=secrets.token_hex(16), device_id=device_id, goal=goal.strip(), mode=mode, status="running", created_at=utc_now(), allowed_packages=allowed_packages or [])
        with self.store.transaction() as db:
            active = db.execute("SELECT payload FROM runs WHERE device_id=?", (device_id,)).fetchall()
            if any(json.loads(r["payload"])["status"] not in TERMINAL for r in active):
                raise Conflict("This device already has an active task")
            db.execute("INSERT INTO runs(id,owner,device_id,payload,token_hash) VALUES(?,?,?,?,?)",
                       (run.id, owner, device_id, run.model_dump_json(), hashlib.sha256(token.encode()).hexdigest()))
            self.store.event(db, run.id, "created", goal.strip())
        self.run_tokens[run.id] = token
        return run

    def authorize_run_token(self, run_id, token):
        row = self._row(run_id)
        if not secrets.compare_digest(row["token_hash"], hashlib.sha256(token.encode()).hexdigest()):
            raise PermissionDenied("Invalid task token")
        return row["owner"]

    def _update(self, db, run, kind="state"):
        db.execute("UPDATE runs SET payload=? WHERE id=?", (run.model_dump_json(), run.id))
        self.store.event(db, run.id, kind, run.message, {"status": run.status})

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
            run.status, run.message = "paused", "已暂停"
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
            if run.pending_request and run.pending_request.get("reason") in {"verification", "login"}:
                run.pending_request = None
                run.requires_fresh_observation = True
                db.execute("UPDATE runs SET observation=NULL WHERE id=?", (run_id,))
            run.status = "awaiting_approval" if run.pending_request and run.pending_request["kind"] == "approval" else "awaiting_input" if run.pending_request else "running"
            run.message = "已继续"
            self._update(db, run)
        return run

    def cancel_run(self, owner, run_id):
        self.get_run(owner, run_id)
        with self.store.transaction() as db:
            row = db.execute("SELECT payload FROM runs WHERE id=?", (run_id,)).fetchone()
            run = Run.model_validate_json(row["payload"])
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
        message = "请在手机上手动完成安全验证，再点击继续。" if reason == "verification" else "请在手机上完成登录设置或手动登录，再点击继续。"
        run.status, run.message = "paused", message
        run.requires_fresh_observation = True
        run.pending_request = {"id": secrets.token_hex(16), "kind": "input", "reason": reason,
                               "message": message, "manual_only": True}
        self._update(db, run, "human_takeover")
        return run

    def queue_command(self, run_id, kind, **fields):
        command = Command(id=secrets.token_hex(16), run_id=run_id, kind=kind, **fields)
        with self.store.transaction() as db:
            row = db.execute("SELECT payload,observation FROM runs WHERE id=?", (run_id,)).fetchone()
            if row is None:
                raise NotFound("Task not found")
            run = Run.model_validate_json(row["payload"])
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
                if kind in {"tap", "long_press", "type", "login_phone", "login_code", "scroll", "back"} and (observation is None or observation.package_name not in run.allowed_packages):
                    error = ScopeDenied if observation is not None else PermissionDenied
                    raise error("Current screen is outside this task's authorized packages")
                if kind == "open_document":
                    if "cn.wps.moffice_eng" not in run.allowed_packages:
                        raise PermissionDenied("Opening documents requires WPS in the authorized packages")
                    command.package_name = "cn.wps.moffice_eng"
            verdict = self.policy.evaluate(run.mode, command, observation)
            if verdict.decision == "deny":
                raise Conflict(verdict.reason)
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
            rows = db.execute("SELECT c.payload,r.payload AS run FROM commands c JOIN runs r ON c.run_id=r.id WHERE r.device_id=? AND c.result IS NULL AND c.state='queued' ORDER BY c.rowid", (device_id,)).fetchall()
            for row in rows:
                if json.loads(row["run"])["status"] == "running":
                    return Command.model_validate_json(row["payload"])
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
            if reason in ("verification", "login"):
                self._pause_for_takeover(db, run, reason)
            elif run.status == "running" and run.requires_fresh_observation and result.status == "ok" and result.observation and json.loads(row["payload"])["kind"] in {"observe", "screenshot"}:
                run.requires_fresh_observation = False
                self._update(db, run, "fresh_observation")
            self.store.event(db, result.run_id, "command_result", result.message or result.status, {"command_id": result.command_id, "status": result.status})

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
                    verdict = self.policy.evaluate("full", command, observation)
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
            run.status, run.message, run.pending_request = "running", "已收到你的回复", None
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
        if reason not in ("verification", "login"):
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

    def start_run(self, run_id):
        existing = self.tasks.get(run_id)
        if existing and not existing.done():
            raise Conflict("Task worker is already active")
        self.tasks[run_id] = asyncio.create_task(self._drive(run_id))

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

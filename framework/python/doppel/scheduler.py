"""Host-owned schedules. A durable dispatch claim is never automatically replayed.

Single runtime owner per data directory, as with DoppelRuntime itself. The gateway
owns timing; Android still checks foreground/lock state and every action policy.
"""

import asyncio
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta, timezone as utc_timezone
import json
import re
import secrets
import threading
import time
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from .errors import Conflict, NotFound

TERMINAL = {"completed", "failed", "cancelled"}
MAX_MS = 253402214400000  # Keep arithmetic/serialization within datetime's domain.
GRACE_MS = 300000


def _integer(value, low, high, label):
    if type(value) is not int or not low <= value <= high:
        raise ValueError(f"Invalid {label}")
    return value


def _field(value, low, high):
    result = set()
    for part in value.split(","):
        match = re.fullmatch(r"(\*|\d+(?:-\d+)?)(?:/(\d+))?", part)
        if not match:
            raise ValueError("Invalid five-field cron expression")
        base, stride = match.groups()
        step = int(stride or 1)
        if not 1 <= step <= high - low + 1:
            raise ValueError("Invalid cron step")
        if base == "*":
            first, last = low, high
        elif "-" in base:
            first, last = map(int, base.split("-"))
        else:
            first, last = int(base), high if stride else int(base)
        if not low <= first <= last <= high:
            raise ValueError("Invalid cron range")
        result.update(range(first, last + 1, step))
    return result


@dataclass(frozen=True)
class ScheduleRule:
    kind: str
    timezone: str = "UTC"
    at_ms: int | None = None
    every_ms: int | None = None
    anchor_ms: int | None = None
    expression: str | None = None

    def __post_init__(self):
        if self.kind not in {"once", "interval", "cron"}:
            raise ValueError("Invalid schedule kind")
        if not isinstance(self.timezone, str) or len(self.timezone) > 100:
            raise ValueError("Invalid IANA timezone")
        try:
            ZoneInfo(self.timezone)
        except (ValueError, ZoneInfoNotFoundError):
            raise ValueError("Unknown IANA timezone; install tzdata on Windows") from None
        if self.kind == "once":
            _integer(self.at_ms, 1, MAX_MS, "once time")
            if any(v is not None for v in (self.every_ms, self.anchor_ms, self.expression)):
                raise ValueError("Unexpected once fields")
        elif self.kind == "interval":
            _integer(self.every_ms, 60000, 31536000000, "interval")
            _integer(self.anchor_ms, 0, MAX_MS, "interval anchor")
            if self.at_ms is not None or self.expression is not None:
                raise ValueError("Unexpected interval fields")
        else:
            if any(v is not None for v in (self.at_ms, self.every_ms, self.anchor_ms)):
                raise ValueError("Unexpected cron fields")
            self._cron()

    def _cron(self):
        if not isinstance(self.expression, str) or len(self.expression) > 120:
            raise ValueError("Invalid cron expression")
        parts = self.expression.split()
        if len(parts) != 5:
            raise ValueError("Cron requires five fields: minute hour day month weekday")
        fields = [_field(part, *bounds) for part, bounds in zip(parts, [(0, 59), (0, 23), (1, 31), (1, 12), (0, 7)])]
        fields[4] = {day % 7 for day in fields[4]}
        return parts, fields

    def next_after(self, after_ms: int) -> int | None:
        _integer(after_ms, 0, MAX_MS - 1, "reference time")
        if self.kind == "once":
            return self.at_ms if self.at_ms > after_ms else None
        if self.kind == "interval":
            result = self.anchor_ms if after_ms < self.anchor_ms else self.anchor_ms + ((after_ms - self.anchor_ms) // self.every_ms + 1) * self.every_ms
            return result if result < MAX_MS else None
        parts, (minutes, hours, days, months, weekdays) = self._cron()
        zone = ZoneInfo(self.timezone)
        date = datetime.fromtimestamp(after_ms / 1000, zone).date()
        for offset in range(366 * 8 + 1):
            try:
                candidate_date = date + timedelta(days=offset)
            except OverflowError:
                break
            if candidate_date.month not in months:
                continue
            day_match = candidate_date.day in days
            weekday_match = (candidate_date.weekday() + 1) % 7 in weekdays
            match = (day_match or weekday_match) if parts[2] != "*" and parts[4] != "*" else day_match and weekday_match
            if not match:
                continue
            for hour in sorted(hours):
                for minute in sorted(minutes):
                    local = datetime(candidate_date.year, candidate_date.month, candidate_date.day, hour, minute, tzinfo=zone, fold=0)
                    stamp = int(local.timestamp() * 1000)
                    # Round-trip rejects invented spring-gap instants; fold=0 is first overlap.
                    back = datetime.fromtimestamp(stamp / 1000, zone)
                    if back.replace(tzinfo=None) == local.replace(tzinfo=None) and stamp > after_ms:
                        return stamp
        raise ValueError("Cron has no occurrence in the next eight years")

    def to_dict(self):
        return {key: value for key, value in asdict(self).items() if value is not None}


class Scheduler:
    def __init__(self, runtime, *, clock=None, readiness=None):
        self.runtime = runtime
        self.clock = clock or (lambda: int(time.time() * 1000))
        self.readiness = readiness or self._device_readiness
        self.lock = threading.RLock()
        self._loop_task = None
        with runtime.store.transaction() as db:
            db.execute("CREATE TABLE IF NOT EXISTS schedules(id TEXT PRIMARY KEY,owner TEXT NOT NULL,device_id TEXT NOT NULL,payload TEXT NOT NULL)")
        self._recover()

    def _device_readiness(self, owner, device_id):
        device = self.runtime._device(owner, device_id)
        try:
            seen = datetime.fromisoformat(device["last_seen"]).timestamp() * 1000
        except (TypeError, ValueError):
            return "device_offline"
        return None if self.clock() - seen <= 90000 else "device_offline"

    def _save(self, owner, job):
        with self.runtime.store.transaction() as db:
            db.execute("UPDATE schedules SET device_id=?,payload=? WHERE id=? AND owner=?",
                       (job["device_id"], json.dumps(job, ensure_ascii=False), job["id"], owner))

    def _all(self):
        return [(row["owner"], json.loads(row["payload"])) for row in self.runtime.store.all("SELECT owner,payload FROM schedules ORDER BY rowid")]

    def _recover(self):
        with self.lock:
            for owner, job in self._all():
                interrupted = False
                for entry in job["history"]:
                    if entry["status"] == "dispatching":
                        entry.update(status="uncertain", reason="host_restarted_during_dispatch")
                        interrupted = True
                if interrupted:
                    job.update(enabled=False, next_due_ms=None, waiting_reason="review_uncertain_dispatch")
                    self._save(owner, job)

    def list(self, owner):
        return [job for job_owner, job in self._all() if job_owner == owner]

    def get(self, owner, job_id):
        row = self.runtime.store.one("SELECT payload FROM schedules WHERE id=? AND owner=?", (job_id, owner))
        if row is None:
            raise NotFound("Schedule not found")
        return json.loads(row["payload"])

    def _validate(self, body):
        if not isinstance(body, dict) or set(body) - {"device_id", "goal", "mode", "allowed_packages", "rule", "enabled"}:
            raise ValueError("Invalid schedule fields")
        goal = body.get("goal", "")
        mode = body.get("mode", "ask")
        packages = body.get("allowed_packages", [])
        if not isinstance(goal, str) or not goal.strip() or len(goal) > 12000 or not isinstance(mode, str) or mode not in {"ask", "assist", "full"}:
            raise ValueError("Invalid schedule goal or mode")
        if not isinstance(packages, list) or len(packages) > 40 or any(not isinstance(x, str) or not re.fullmatch(r"[A-Za-z][\w]*(?:\.[A-Za-z][\w]*)+", x, re.ASCII) or len(x) > 256 for x in packages):
            raise ValueError("Invalid allowed packages")
        enabled = body.get("enabled", True)
        if type(enabled) is not bool:
            raise ValueError("enabled must be boolean")
        try:
            rule = ScheduleRule(**body["rule"])
        except (KeyError, TypeError):
            raise ValueError("Invalid schedule rule") from None
        return dict(device_id=body.get("device_id"), goal=goal.strip(), mode=mode, allowed_packages=packages,
                    rule=rule.to_dict(), enabled=enabled)

    def create(self, owner, body):
        with self.lock:
            job = self._validate(body)
            self.runtime._device(owner, job["device_id"])
            if len(self.list(owner)) >= 100:
                raise ValueError("At most 100 schedules per owner")
            now = self.clock()
            next_due = ScheduleRule(**job["rule"]).next_after(now)
            if next_due is None:
                raise ValueError("Schedule time must be in the future")
            job.update(id=secrets.token_hex(16), created_at_ms=now, next_due_ms=next_due if job["enabled"] else None,
                       waiting_reason=None, history=[])
            with self.runtime.store.transaction() as db:
                db.execute("INSERT INTO schedules VALUES(?,?,?,?)", (job["id"], owner, job["device_id"], json.dumps(job, ensure_ascii=False)))
            return job

    def update(self, owner, job_id, body):
        with self.lock:
            job = self.get(owner, job_id)
            payload = {key: job[key] for key in ("device_id", "goal", "mode", "allowed_packages", "rule", "enabled")}
            payload.update(body)
            fresh = self._validate(payload)
            self.runtime._device(owner, fresh["device_id"])
            changed_time = fresh["rule"] != job["rule"] or fresh["enabled"] != job["enabled"]
            next_due = ScheduleRule(**fresh["rule"]).next_after(self.clock()) if changed_time and fresh["enabled"] else job["next_due_ms"]
            if fresh["enabled"] and next_due is None:
                raise ValueError("Choose a future schedule time")
            job.update(fresh, next_due_ms=next_due if fresh["enabled"] else None, waiting_reason=None)
            self._save(owner, job)
            return job

    def delete(self, owner, job_id):
        with self.lock:
            self.get(owner, job_id)
            with self.runtime.store.transaction() as db:
                db.execute("DELETE FROM schedules WHERE id=? AND owner=?", (job_id, owner))
            return {"deleted": True}

    def tick(self):
        with self.lock:
            now = self.clock()
            for owner, job in self._all():
                if any(entry["status"] == "dispatching" for entry in job["history"]):
                    # Storage may have failed after creation while this process stayed alive.
                    for entry in job["history"]:
                        if entry["status"] == "dispatching":
                            entry.update(status="uncertain", reason="dispatch_unconfirmed")
                    job.update(enabled=False, next_due_ms=None, waiting_reason="review_uncertain_dispatch")
                    self._save(owner, job)
                    continue
                changed = False
                for entry in job["history"]:
                    if entry["status"] == "started" and entry.get("run_id"):
                        try:
                            status = self.runtime.get_run(owner, entry["run_id"]).status
                            if status in TERMINAL:
                                entry.update(status=status, finished_at_ms=now)
                                changed = True
                        except NotFound:
                            entry.update(status="unavailable", reason="run_history_removed")
                            changed = True
                due = job.get("next_due_ms")
                if not job["enabled"] or due is None or due > now:
                    if changed:
                        self._save(owner, job)
                    continue
                if now - due <= GRACE_MS:
                    rows = self.runtime.store.all("SELECT payload FROM runs WHERE device_id=?", (job["device_id"],))
                    reason = "device_busy" if any(json.loads(row["payload"])["status"] not in TERMINAL for row in rows) else self.readiness(owner, job["device_id"])
                    if reason:
                        job["waiting_reason"] = reason
                        self._save(owner, job)
                        continue
                missed = now - due > GRACE_MS
                entry = {"scheduled_at_ms": due, "at_ms": now, "status": "missed" if missed else "dispatching", "run_id": None}
                job["history"] = (job["history"] + [entry])[-100:]
                job["waiting_reason"] = None
                job["next_due_ms"] = ScheduleRule(**job["rule"]).next_after(now)
                job["enabled"] = job["next_due_ms"] is not None
                self._save(owner, job)  # Durable claim BEFORE creating any potentially acting task.
                if missed:
                    continue
                try:
                    # Scheduled work is an execution record only. It must not
                    # advance or create the user's conversational thread.
                    run = self.runtime.create_run(owner, job["device_id"], job["goal"], job["mode"], job["allowed_packages"],
                                                  conversation_enabled=False, source="schedule")
                    entry.update(status="started", run_id=run.id)
                    self._save(owner, job)
                    with self.runtime.store.transaction() as db:
                        self.runtime.store.event(db, run.id, "schedule", "Created by schedule", {"schedule_id": job["id"], "scheduled_at_ms": due})
                    if self.runtime.config.auto_start:
                        self.runtime.start_run(run.id)
                except Conflict:
                    entry.update(status="skipped", reason="device_busy")
                    self._save(owner, job)
                except Exception:
                    # An unknown create/start outcome is not proof that no side effect happened.
                    entry.update(status="uncertain", reason="dispatch_unconfirmed")
                    job.update(enabled=False, next_due_ms=None, waiting_reason="review_uncertain_dispatch")
                    self._save(owner, job)

    def start(self):
        if self._loop_task is not None and not self._loop_task.done():
            return
        async def loop():
            while True:
                try:
                    # Runtime.start_run requires the current event loop; no thread-pool dispatch.
                    self.tick()
                except Exception:
                    # A storage failure cannot turn into a second occurrence claim.
                    import logging
                    logging.getLogger(__name__).error("Schedule tick unavailable; retained state requires inspection")
                await asyncio.sleep(15)
        self._loop_task = asyncio.create_task(loop(), name="doppel-scheduler")

    async def close(self):
        if self._loop_task is not None:
            self._loop_task.cancel()
            try:
                await self._loop_task
            except asyncio.CancelledError:
                pass
            self._loop_task = None


def get_scheduler(runtime):
    """One scheduler per runtime owner; public router lifecycle starts and closes it."""
    existing = getattr(runtime, "_doppel_scheduler", None)
    if existing is None:
        existing = Scheduler(runtime)
        runtime._doppel_scheduler = existing
    return existing


def create_schedule_router(scheduler, owner_dependency):
    from contextlib import asynccontextmanager
    from fastapi import APIRouter, Body, Depends
    from .gateway import CheckedRoute

    @asynccontextmanager
    async def lifespan(app):
        scheduler.start()
        try:
            yield
        finally:
            await scheduler.close()

    router = APIRouter(prefix="/schedules", route_class=CheckedRoute, lifespan=lifespan)

    @router.get("")
    def list_schedules(owner=Depends(owner_dependency)):
        return {"items": scheduler.list(owner)}

    @router.post("", status_code=201)
    def create_schedule(body: dict = Body(...), owner=Depends(owner_dependency)):
        return scheduler.create(owner, body)

    @router.get("/{schedule_id}")
    def get_schedule(schedule_id: str, owner=Depends(owner_dependency)):
        return scheduler.get(owner, schedule_id)

    @router.patch("/{schedule_id}")
    def update_schedule(schedule_id: str, body: dict = Body(...), owner=Depends(owner_dependency)):
        return scheduler.update(owner, schedule_id, body)

    @router.delete("/{schedule_id}")
    def delete_schedule(schedule_id: str, owner=Depends(owner_dependency)):
        return scheduler.delete(owner, schedule_id)

    return router

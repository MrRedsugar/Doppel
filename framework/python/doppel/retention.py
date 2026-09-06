"""Recoverable owner-scoped task cleanup and screenshot access."""

import asyncio
import base64
import binascii
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import re
import secrets
import shutil
import sqlite3
import threading

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse, Response
from pydantic import Field, StrictInt

from .errors import Conflict, NotFound, PermissionDenied
from .gateway import CheckedRoute
from .models import Model, utc_now
from .runtime import TERMINAL
from .skills import contained_path

MAX_IMAGE_BYTES = 8 * 1024 * 1024


class RetentionManager:
    def __init__(self, runtime):
        self.runtime = runtime
        self.store = runtime.store
        self.root = Path(runtime.config.data_dir).resolve()
        if not hasattr(runtime, '_retention_lock'):
            runtime._retention_lock = threading.RLock()
        self.lock = runtime._retention_lock
        with self.store.lock:
            self.store.db.execute('PRAGMA secure_delete=ON')
        with self.store.transaction() as db:
            db.execute('CREATE TABLE IF NOT EXISTS retention_settings(owner TEXT PRIMARY KEY, days INTEGER NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS retention_cleanup(run_id TEXT PRIMARY KEY, owner TEXT NOT NULL, kind TEXT NOT NULL, digests TEXT NOT NULL, phase TEXT NOT NULL, created_at TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT)')
            db.execute('CREATE TABLE IF NOT EXISTS retention_device_cleanup(id TEXT PRIMARY KEY, owner TEXT NOT NULL, device_id TEXT NOT NULL, run_id TEXT NOT NULL, kind TEXT NOT NULL, command_ids TEXT NOT NULL, created_at TEXT NOT NULL)')

    @staticmethod
    def _owner(owner):
        if not isinstance(owner, str) or not owner or len(owner) > 256:
            raise PermissionDenied('Authenticated owner required')
        return owner

    def settings(self, owner):
        row = self.store.one('SELECT days FROM retention_settings WHERE owner=?', (self._owner(owner),))
        pending = self.store.one('SELECT COUNT(*) AS count FROM retention_cleanup WHERE owner=?', (owner,))['count']
        devices = self.store.one('SELECT COUNT(*) AS count FROM retention_device_cleanup WHERE owner=?', (owner,))['count']
        return {'days': row['days'] if row else 7, 'cleanup_pending': pending, 'device_cleanup_pending': devices}

    def update_settings(self, owner, days):
        if type(days) is not int or not 0 <= days <= 3650:
            raise ValueError('Retention days must be an integer from zero to 3650')
        with self.store.transaction() as db:
            db.execute('INSERT INTO retention_settings VALUES(?,?) ON CONFLICT(owner) DO UPDATE SET days=excluded.days', (self._owner(owner), days))
        return self.settings(owner)

    def _run(self, owner, run_id, *, allow_pending=False):
        run = self.runtime.get_run(self._owner(owner), run_id)
        if not allow_pending and self.store.one('SELECT 1 FROM retention_cleanup WHERE run_id=?', (run_id,)):
            raise NotFound('Task data is pending deletion')
        return run

    def _idle(self, run):
        worker = self.runtime.tasks.get(run.id)
        if run.status not in TERMINAL or (worker is not None and not worker.done()) or self.runtime.active_model_calls.get(run.id, 0):
            raise Conflict('Finish or cancel the task and wait for its worker and model accounting to stop before deleting data')

    @staticmethod
    def _image(value):
        if not isinstance(value, str) or not value or len(value) > ((MAX_IMAGE_BYTES + 2) // 3) * 4:
            return None
        try:
            data = base64.b64decode(value, validate=True)
        except (ValueError, binascii.Error):
            return None
        if len(data) > MAX_IMAGE_BYTES:
            return None
        if data.startswith(b'\x89PNG\r\n\x1a\n'):
            mime = 'image/png'
        elif data.startswith(b'\xff\xd8\xff'):
            mime = 'image/jpeg'
        elif data.startswith(b'RIFF') and data[8:12] == b'WEBP':
            mime = 'image/webp'
        else:
            return None
        return data, mime

    def _images(self, run_id):
        result = []
        for row in self.store.all('SELECT id,result,created_at FROM commands WHERE run_id=? AND result IS NOT NULL ORDER BY created_at,id', (run_id,)):
            payload = json.loads(row['result'])
            value = payload.get('data', {}).get('image_base64')
            image = self._image(value)
            if image:
                data, mime = image
                result.append({'id': row['id'], 'command_id': row['id'], 'created_at': row['created_at'],
                               'mime_type': mime, 'size': len(data), 'digest': hashlib.sha256(data).hexdigest(),
                               '_value': value, '_bytes': data})
        return result

    def screenshots(self, owner, run_id):
        self._run(owner, run_id)
        return [{key: value for key, value in item.items() if not key.startswith('_')} for item in self._images(run_id)][:200]

    def screenshot(self, owner, run_id, image_id):
        self._run(owner, run_id)
        item = next((item for item in self._images(run_id) if item['id'] == image_id), None)
        if item is None:
            raise NotFound('Screenshot not found')
        return item['_bytes'], item['mime_type']

    def _enqueue(self, owner, run_id, kind, digests):
        with self.store.transaction() as db:
            run = self._run(owner, run_id, allow_pending=True)
            self._idle(run)
            existing = db.execute('SELECT kind,digests FROM retention_cleanup WHERE run_id=?', (run_id,)).fetchone()
            if existing:
                if existing['kind'] != kind or json.loads(existing['digests']) != digests:
                    raise Conflict('Another cleanup operation is pending for this task')
            else:
                db.execute('INSERT INTO retention_cleanup(run_id,owner,kind,digests,phase,created_at) VALUES(?,?,?,?,?,?)',
                           (run_id, owner, kind, json.dumps(digests), 'filesystem', utc_now()))
            db.execute('UPDATE runs SET token_hash=? WHERE id=?', (hashlib.sha256(secrets.token_bytes(32)).hexdigest(), run_id))
        self.runtime.run_tokens.pop(run_id, None)

    def _remove_session(self, run_id):
        if not re.fullmatch(r'[a-f0-9]{32}', run_id):
            raise ValueError('Invalid task folder identifier')
        folder = contained_path(self.root, 'harness/' + run_id)
        if folder.exists():
            if not folder.is_dir():
                raise ValueError('Harness session path is not a directory')
            shutil.rmtree(folder)

    @staticmethod
    def _redact(item, values):
        if isinstance(item, dict):
            return {key: RetentionManager._redact(value, values) for key, value in item.items()}
        if isinstance(item, list):
            return [RetentionManager._redact(value, values) for value in item]
        if isinstance(item, str):
            if item in values:
                return None
            for value in values:
                item = item.replace(value, '[deleted screenshot]')
        return item

    def _flush_deleted_pages(self):
        with self.store.lock:
            result = self.store.db.execute('PRAGMA wal_checkpoint(TRUNCATE)').fetchone()
            if result[0] != 0:
                raise OSError('Database checkpoint is busy')

    def _process(self, run_id):
        with self.lock:
            return self._process_locked(run_id)

    def _process_locked(self, run_id):
        job = self.store.one('SELECT * FROM retention_cleanup WHERE run_id=?', (run_id,))
        if job is None:
            return True
        try:
            if job['phase'] == 'filesystem':
                run = self._run(job['owner'], run_id, allow_pending=True)
                self._idle(run)
                self._remove_session(run_id)
                with self.store.transaction() as db:
                    images = self._images(run_id)
                    digests = set(json.loads(job['digests']))
                    values = {item['_value'] for item in images if item['digest'] in digests}
                    commands = [row['id'] for row in db.execute('SELECT id FROM commands WHERE run_id=?', (run_id,))]
                    cleanup_id = secrets.token_hex(16)
                    db.execute('INSERT INTO retention_device_cleanup VALUES(?,?,?,?,?,?,?)',
                               (cleanup_id, job['owner'], run.device_id, run_id, job['kind'], json.dumps(commands), utc_now()))
                    if job['kind'] == 'run':
                        db.execute('DELETE FROM events WHERE run_id=?', (run_id,))
                        db.execute('DELETE FROM commands WHERE run_id=?', (run_id,))
                        db.execute('DELETE FROM runs WHERE id=?', (run_id,))
                    else:
                        for row in db.execute('SELECT id,payload,result FROM commands WHERE run_id=?', (run_id,)).fetchall():
                            payload = json.dumps(self._redact(json.loads(row['payload']), values))
                            result = json.dumps(self._redact(json.loads(row['result']), values)) if row['result'] else None
                            db.execute('UPDATE commands SET payload=?,result=? WHERE id=?', (payload, result, row['id']))
                        for row in db.execute('SELECT sequence,message,data FROM events WHERE run_id=?', (run_id,)).fetchall():
                            db.execute('UPDATE events SET message=?,data=? WHERE sequence=?',
                                       (self._redact(row['message'], values) or '', json.dumps(self._redact(json.loads(row['data']), values)), row['sequence']))
                        row = db.execute('SELECT payload,observation FROM runs WHERE id=?', (run_id,)).fetchone()
                        observation = json.dumps(self._redact(json.loads(row['observation']), values)) if row['observation'] else None
                        db.execute('UPDATE runs SET payload=?,observation=? WHERE id=?', (json.dumps(self._redact(json.loads(row['payload']), values)), observation, run_id))
                    db.execute("UPDATE retention_cleanup SET phase='checkpoint',last_error=NULL WHERE run_id=?", (run_id,))
            self._flush_deleted_pages()
            with self.store.transaction() as db:
                db.execute('DELETE FROM retention_cleanup WHERE run_id=?', (run_id,))
            worker = self.runtime.tasks.get(run_id)
            if worker is not None and worker.done():
                self.runtime.tasks.pop(run_id, None)
            return True
        except (OSError, ValueError, Conflict, sqlite3.Error) as error:
            with self.store.transaction() as db:
                db.execute('UPDATE retention_cleanup SET attempts=attempts+1,last_error=? WHERE run_id=?', (type(error).__name__, run_id))
            return False

    def delete_run(self, owner, run_id):
        existing = self.store.one('SELECT owner,kind FROM retention_cleanup WHERE run_id=?', (run_id,))
        if existing and existing['owner'] == self._owner(owner) and existing['kind'] == 'run':
            completed = self._process(run_id)
        else:
            self._enqueue(owner, run_id, 'run', [])
            completed = self._process(run_id)
        return {'status': 'deleted' if completed else 'pending', 'cleanup_pending': not completed, 'device_cleanup_required': True}

    def delete_screenshot(self, owner, run_id, image_id):
        run = self._run(owner, run_id, allow_pending=True)
        self._idle(run)
        image = next((image for image in self._images(run_id) if image['id'] == image_id), None)
        if image is None:
            raise NotFound('Screenshot not found')
        self._enqueue(owner, run_id, 'screenshots', [image['digest']])
        completed = self._process(run_id)
        return {'status': 'deleted' if completed else 'pending', 'cleanup_pending': not completed, 'device_cleanup_required': True}

    def recover(self):
        jobs = self.store.all('SELECT run_id FROM retention_cleanup ORDER BY created_at LIMIT 200')
        complete = sum(self._process(row['run_id']) for row in jobs)
        return {'completed': complete, 'pending': len(jobs) - complete}

    def prune(self, now=None):
        now = now or datetime.now(timezone.utc)
        completed = pending = 0
        rows = self.store.all("SELECT r.id,r.owner,r.payload,MAX(e.created_at) AS updated FROM runs r LEFT JOIN events e ON e.run_id=r.id LEFT JOIN retention_settings s ON s.owner=r.owner WHERE COALESCE(s.days,7)>0 AND json_extract(r.payload,'$.status') IN ('completed','failed','cancelled') GROUP BY r.id ORDER BY updated LIMIT 1000")
        for row in rows:
            run = json.loads(row['payload'])
            days = self.settings(row['owner'])['days']
            if not days or run['status'] not in TERMINAL:
                continue
            stamp = datetime.fromisoformat(row['updated'] or run['created_at'])
            if stamp.tzinfo is None:
                stamp = stamp.replace(tzinfo=timezone.utc)
            if stamp >= now - timedelta(days=days):
                continue
            try:
                outcome = self.delete_run(row['owner'], row['id'])
                completed += not outcome['cleanup_pending']
                pending += outcome['cleanup_pending']
            except Conflict:
                continue
        return {'completed': completed, 'pending': pending}

    def device_cleanup(self, owner, device_id):
        self.runtime._device(self._owner(owner), device_id)
        return [{**dict(row), 'command_ids': json.loads(row['command_ids'])} for row in
                self.store.all('SELECT id,run_id,kind,command_ids,created_at FROM retention_device_cleanup WHERE owner=? AND device_id=? ORDER BY created_at LIMIT 200', (owner, device_id))]

    def ack_device_cleanup(self, owner, device_id, cleanup_id):
        self.runtime._device(self._owner(owner), device_id)
        with self.store.transaction() as db:
            row = db.execute('SELECT 1 FROM retention_device_cleanup WHERE owner=? AND device_id=? AND id=?', (owner, device_id, cleanup_id)).fetchone()
            if row is None:
                raise NotFound('Device cleanup request not found')
            db.execute('DELETE FROM retention_device_cleanup WHERE id=?', (cleanup_id,))


def get_retention_manager(runtime):
    manager = getattr(runtime, 'retention_manager', None)
    if manager is None:
        manager = runtime.retention_manager = RetentionManager(runtime)
    return manager


class RetentionInput(Model):
    days: StrictInt = Field(ge=0, le=3650)


def create_retention_router(runtime, owner_dependency):
    manager = get_retention_manager(runtime)

    async def cleanup_loop():
        while True:
            for action in (manager.recover, manager.prune):
                active = asyncio.create_task(asyncio.to_thread(action))
                try:
                    await asyncio.shield(active)
                except asyncio.CancelledError:
                    await active
                    raise
            await asyncio.sleep(60)

    @asynccontextmanager
    async def lifespan(app):
        task = asyncio.create_task(cleanup_loop())
        try:
            yield
        finally:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)

    router = APIRouter(route_class=CheckedRoute, lifespan=lifespan)

    @router.get('/data-retention')
    def settings(owner=Depends(owner_dependency)):
        return manager.settings(owner)

    @router.patch('/data-retention')
    def update(body: RetentionInput, owner=Depends(owner_dependency)):
        return manager.update_settings(owner, body.days)

    @router.delete('/runs/{run_id}')
    def delete(run_id: str, owner=Depends(owner_dependency)):
        result = manager.delete_run(owner, run_id)
        return JSONResponse(result, status_code=202 if result['cleanup_pending'] else 200)

    @router.get('/runs/{run_id}/screenshots')
    def images(run_id: str, owner=Depends(owner_dependency)):
        return {'items': manager.screenshots(owner, run_id)}

    @router.get('/runs/{run_id}/screenshots/{image_id}')
    def image(run_id: str, image_id: str, owner=Depends(owner_dependency)):
        data, mime = manager.screenshot(owner, run_id, image_id)
        return Response(data, media_type=mime, headers={'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff'})

    @router.delete('/runs/{run_id}/screenshots/{image_id}')
    def delete_image(run_id: str, image_id: str, owner=Depends(owner_dependency)):
        result = manager.delete_screenshot(owner, run_id, image_id)
        return JSONResponse(result, status_code=202 if result['cleanup_pending'] else 200)

    @router.get('/devices/{device_id}/data-cleanup')
    def device_cleanup(device_id: str, owner=Depends(owner_dependency)):
        return {'items': manager.device_cleanup(owner, device_id)}

    @router.post('/devices/{device_id}/data-cleanup/{cleanup_id}/ack')
    def acknowledge(device_id: str, cleanup_id: str, owner=Depends(owner_dependency)):
        manager.ack_device_cleanup(owner, device_id, cleanup_id)
        return {'ok': True}

    return router

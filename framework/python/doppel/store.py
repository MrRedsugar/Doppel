import json
import sqlite3
import threading
from contextlib import contextmanager
from pathlib import Path

from .models import utc_now


class Store:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.db = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.execute("PRAGMA busy_timeout=5000")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, owner TEXT NOT NULL, installation TEXT NOT NULL, name TEXT NOT NULL, last_seen TEXT, UNIQUE(owner,installation));
            CREATE TABLE IF NOT EXISTS runs(id TEXT PRIMARY KEY, owner TEXT NOT NULL, device_id TEXT NOT NULL REFERENCES devices(id), payload TEXT NOT NULL, observation TEXT, token_hash TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS commands(id TEXT PRIMARY KEY, run_id TEXT NOT NULL REFERENCES runs(id), payload TEXT NOT NULL, state TEXT NOT NULL, result TEXT, created_at TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS commands_run ON commands(run_id,state);
            CREATE TABLE IF NOT EXISTS events(sequence INTEGER PRIMARY KEY AUTOINCREMENT,run_id TEXT NOT NULL REFERENCES runs(id),kind TEXT NOT NULL,message TEXT NOT NULL,data TEXT NOT NULL,created_at TEXT NOT NULL);
            PRAGMA user_version=1;
        """)

    @contextmanager
    def transaction(self):
        with self.lock:
            self.db.execute("BEGIN IMMEDIATE")
            try:
                yield self.db
                self.db.execute("COMMIT")
            except BaseException:
                self.db.execute("ROLLBACK")
                raise

    def one(self, sql, args=()):
        with self.lock:
            return self.db.execute(sql, args).fetchone()

    def all(self, sql, args=()):
        with self.lock:
            return self.db.execute(sql, args).fetchall()

    @staticmethod
    def event(db, run_id, kind, message, data=None):
        db.execute("INSERT INTO events(run_id,kind,message,data,created_at) VALUES(?,?,?,?,?)",
                   (run_id, kind, message, json.dumps(data or {}, ensure_ascii=False), utc_now()))


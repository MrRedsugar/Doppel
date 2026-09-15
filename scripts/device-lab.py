"""Explicit-device operator CLI for screenshots and bounded, frame-checked input.

This tool prepares/observes experiments. Operator inputs are never agent success.
It has no generic shell, text input, task submission, account, or payment API.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import time
import uuid


MAX_FRAME_AGE = 30.0
PACKAGE = "dev.doppel.developer"
KEYS = {"back": "4", "home": "3", "enter": "66"}


def utc(timestamp):
    return datetime.fromtimestamp(timestamp, timezone.utc).isoformat()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def png_size(data):
    if (len(data) < 45 or len(data) > 64 * 1024 * 1024
            or data[:8] != b"\x89PNG\r\n\x1a\n" or data[8:16] != b"\0\0\0\rIHDR"
            or data[-12:] != b"\0\0\0\0IEND\xaeB`\x82"):
        raise ValueError("Expected a bounded, complete screencap PNG")
    width, height = struct.unpack(">II", data[16:24])
    if not (0 < width <= 20000 and 0 < height <= 20000 and width * height <= 50_000_000):
        raise ValueError("Invalid screenshot dimensions")
    return width, height


class Lab:
    def __init__(self, adb, serial, output, *, append=False, runner=None, clock=None):
        self.adb = Path(adb).resolve()
        if not self.adb.is_file():
            raise ValueError("--adb must name an existing executable file")
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", serial):
            raise ValueError("An explicit, valid --serial is required")
        self.serial = serial
        self.output = Path(output).resolve()
        self.runner = runner or subprocess.run
        self.clock = clock or time.time
        marker = self.output / ".device-lab.json"
        if self.output.exists():
            if not append or not marker.is_file():
                raise ValueError("Use a new --output directory, or --append for existing device-lab evidence")
            previous = json.loads(marker.read_text(encoding="utf-8"))
            if previous.get("version") != 1 or previous.get("serial") != serial:
                raise ValueError("Existing evidence belongs to another device or format")
        else:
            if append:
                raise ValueError("--append requires an existing device-lab evidence directory")
            self.output.mkdir(parents=True, exist_ok=False)
            marker.write_text(json.dumps({"version": 1, "serial": serial, "created_at": utc(self.clock())}), encoding="utf-8")

    @contextmanager
    def session(self):
        """One process per evidence directory; never delete another process's lock."""
        lock = self.output / ".operator.lock"
        try:
            handle = lock.open("x", encoding="utf-8")
        except FileExistsError:
            raise ValueError("This evidence directory is in use; inspect .operator.lock") from None
        try:
            with handle:
                handle.write(str(os.getpid()))
            yield
        finally:
            lock.unlink(missing_ok=True)

    def _event(self, event, *, operator=False, **fields):
        row = {"at": utc(self.clock()), "serial": self.serial, "event": event, **fields}
        name = "operator-actions.jsonl" if operator else "events.jsonl"
        with (self.output / name).open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(row, ensure_ascii=False, allow_nan=False) + "\n")
        return row

    def _adb(self, *command, timeout=30):
        argv = [str(self.adb), "-s", self.serial, *command]
        start = self.clock()
        try:
            result = self.runner(argv, capture_output=True, timeout=timeout, shell=False)
        except (subprocess.TimeoutExpired, OSError) as exc:
            self._event("adb", argv=argv, outcome=type(exc).__name__, elapsed_s=self.clock() - start)
            raise RuntimeError("ADB did not complete; process output omitted") from None
        self._event("adb", argv=argv, returncode=result.returncode, elapsed_s=self.clock() - start)
        if result.returncode:
            raise RuntimeError("ADB failed; process output omitted")
        return result.stdout

    def _name(self, prefix, suffix):
        return self.output / (prefix + "-" + uuid.uuid4().hex + suffix)

    def screenshot(self):
        started = self.clock()
        data = self._adb("exec-out", "screencap", "-p")
        width, height = png_size(data)
        path = self._name("frame", ".png")
        path.write_bytes(data)
        frame = {"version": 1, "serial": self.serial, "captured_at": utc(started),
                 "captured_unix": started, "received_at": utc(self.clock()),
                 "width": width, "height": height, "sha256": digest(data), "png": path.name}
        metadata = path.with_suffix(".frame.json")
        metadata.write_text(json.dumps(frame, indent=2), encoding="utf-8")
        self._event("screenshot", frame=metadata.name, sha256=frame["sha256"], width=width, height=height)
        return metadata

    def _age(self, frame):
        captured = frame.get("captured_unix")
        if isinstance(captured, bool) or not isinstance(captured, (int, float)) or not math.isfinite(captured):
            raise ValueError("Frame has an invalid timestamp")
        if not 0 <= self.clock() - captured <= MAX_FRAME_AGE:
            raise ValueError("Frame is older than 30 seconds or has a future timestamp; capture again")

    def _load_frame(self, path):
        path = Path(path).resolve()
        frame = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(frame, dict) or frame.get("version") != 1 or frame.get("serial") != self.serial:
            raise ValueError("Frame format/device does not match")
        self._age(frame)
        name = frame.get("png")
        if not isinstance(name, str) or not re.fullmatch(r"frame-[a-f0-9]{32}\.png", name):
            raise ValueError("Frame PNG must be a saved device-lab screenshot")
        image = (path.parent / name).resolve()
        if image.parent != path.parent:
            raise ValueError("Frame PNG is outside its evidence directory")
        data = image.read_bytes()
        if png_size(data) != (frame.get("width"), frame.get("height")) or digest(data) != frame.get("sha256"):
            raise ValueError("Saved frame dimensions or SHA256 do not match")
        return frame

    @staticmethod
    def _point(x, y, frame):
        for value in (x, y):
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not 0 <= value < 1:
                raise ValueError("Coordinates must be finite normalized values in [0, 1)")
        return math.floor(x * frame["width"]), math.floor(y * frame["height"])

    def _preflight(self, frame):
        current_path = self.screenshot()
        current = json.loads(current_path.read_text(encoding="utf-8"))
        if (current["width"], current["height"], current["sha256"]) != (frame["width"], frame["height"], frame["sha256"]):
            raise ValueError("Current screenshot differs from the saved frame; inspect the new capture")
        # A slow screencap must not make an expired source frame actionable.
        self._age(frame)
        return current_path

    def _input(self, path, operator, build_command):
        if operator is not True:
            raise ValueError("Input requires explicit --operator; it is not an agent action")
        command = None
        try:
            frame = self._load_frame(path)
            command = build_command(frame)
            current = self._preflight(frame)
            self._adb("shell", "input", *command)
        except (ValueError, RuntimeError, OSError, json.JSONDecodeError):
            self._event("input_rejected_or_failed", operator=True, source="operator", agent_success=False,
                        action=command[0] if command else "unparsed")
            raise
        return self._event("input_accepted_by_adb", operator=True, source="operator", agent_success=False,
                           command=command, source_frame=str(Path(path).resolve()), source_sha256=frame["sha256"],
                           checked_frame=current.name)

    def tap(self, frame, x, y, *, operator=False):
        return self._input(frame, operator, lambda f: ["tap", *map(str, self._point(x, y, f))])

    def swipe(self, frame, x1, y1, x2, y2, duration=400, *, operator=False):
        def command(f):
            if isinstance(duration, bool) or not isinstance(duration, int) or not 50 <= duration <= 2000:
                raise ValueError("Swipe duration must be an integer from 50 to 2000 ms")
            return ["swipe", *map(str, self._point(x1, y1, f)), *map(str, self._point(x2, y2, f)), str(duration)]
        return self._input(frame, operator, command)

    def key(self, frame, key, *, operator=False):
        def command(_):
            if key not in KEYS:
                raise ValueError("Only back, home and enter are supported")
            return ["keyevent", KEYS[key]]
        return self._input(frame, operator, command)

    def status(self):
        data = self._adb("exec-out", "run-as", PACKAGE, "cat", "no_backup/direct-runs-v1.json")
        if len(data) > 32 * 1024 * 1024:
            raise ValueError("Developer status exceeds 32 MiB")
        json.loads(data)
        path = self._name("direct-runs", ".json")
        path.write_bytes(data)
        self._event("status", file=path.name, sha256=digest(data), bytes=len(data))
        return path

    def record(self, seconds):
        if isinstance(seconds, bool) or not isinstance(seconds, int) or not 1 <= seconds <= 60:
            raise ValueError("Recording duration must be an integer from 1 to 60 seconds")
        path = self._name("recording", ".mp4")
        remote = "/sdcard/doppel-lab-" + uuid.uuid4().hex + ".mp4"
        self._adb("shell", "screenrecord", "--time-limit", str(seconds), remote, timeout=seconds + 15)
        self._adb("pull", remote, str(path), timeout=60)
        if not path.is_file() or path.stat().st_size == 0:
            raise ValueError("No recording was saved")
        self._adb("shell", "rm", "--", remote)
        hasher = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                hasher.update(chunk)
        self._event("record", file=path.name, seconds=seconds, sha256=hasher.hexdigest(), bytes=path.stat().st_size)
        return path

    def preview(self, scrcpy):
        executable = Path(scrcpy).resolve()
        if not executable.is_file():
            raise ValueError("--scrcpy must name your existing official scrcpy executable")
        argv = [str(executable), "--serial", self.serial, "--no-control"]
        env = os.environ.copy()
        env["ADB"] = str(self.adb)
        process = subprocess.Popen(argv, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        self._event("preview", argv=argv, pid=process.pid, control=False)
        return {"pid": process.pid, "control": False}


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--adb", required=True, type=Path)
    result.add_argument("--serial", required=True)
    result.add_argument("--output", required=True, type=Path)
    result.add_argument("--append", action="store_true", help="Append to an existing evidence directory for the same serial")
    commands = result.add_subparsers(dest="command", required=True)
    commands.add_parser("screenshot")
    commands.add_parser("status")
    record = commands.add_parser("record")
    record.add_argument("--seconds", required=True, type=int)
    preview = commands.add_parser("preview")
    preview.add_argument("--scrcpy", required=True, type=Path)
    for name in ("tap", "swipe", "key"):
        cmd = commands.add_parser(name)
        cmd.add_argument("--frame", required=True, type=Path, help="Saved .frame.json from this device, at most 30 seconds old")
        cmd.add_argument("--operator", action="store_true", required=True)
        if name == "tap":
            cmd.add_argument("--x", required=True, type=float)
            cmd.add_argument("--y", required=True, type=float)
        elif name == "swipe":
            for axis in ("x1", "y1", "x2", "y2"):
                cmd.add_argument("--" + axis, required=True, type=float)
            cmd.add_argument("--duration", type=int, default=400)
        else:
            cmd.add_argument("--key", required=True, choices=KEYS)
    return result


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        lab = Lab(args.adb, args.serial, args.output, append=args.append)
        with lab.session():
            if args.command in {"screenshot", "status"}:
                outcome = getattr(lab, args.command)()
            elif args.command == "record":
                outcome = lab.record(args.seconds)
            elif args.command == "preview":
                outcome = lab.preview(args.scrcpy)
            elif args.command == "tap":
                outcome = lab.tap(args.frame, args.x, args.y, operator=args.operator)
            elif args.command == "swipe":
                outcome = lab.swipe(args.frame, args.x1, args.y1, args.x2, args.y2, args.duration, operator=args.operator)
            else:
                outcome = lab.key(args.frame, args.key, operator=args.operator)
        print(json.dumps({"result": str(outcome) if isinstance(outcome, Path) else outcome}, ensure_ascii=False))
        return 0
    except (ValueError, RuntimeError, OSError) as exc:
        print(json.dumps({"error": str(exc)}, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

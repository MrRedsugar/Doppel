"""Local operator screenshot panel; optional read-only scrcpy for smooth preview.

No network listener or generic shell. Every input is recorded as operator-only,
requires a short-lived frame token and rechecks current focus, geometry and pixels.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import io
import json
import math
from pathlib import Path
import re
import threading
import time
import uuid

_spec = importlib.util.spec_from_file_location("doppel_device_lab", Path(__file__).with_name("device-lab.py"))
lab_module = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(lab_module)

TOKEN_TTL = 3.0


def decode_rgb(data):
    from PIL import Image
    lab_module.png_size(data)
    with Image.open(io.BytesIO(data)) as image:
        image.load()
        return image.convert("RGB")


def focus_identity(data):
    value = data.decode("utf-8", errors="replace")
    match = re.search(r"mCurrentFocus=Window\{[^\r\n}]*\bu\d+\s+([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)", value)
    return match.group(1) if match else None


def stable_regions(before, after, points):
    """Check every pixel in cores and context; permit unrelated animations only.

    No localization occurs here: operator coordinates may not move between frames.
    A continuous swipe corridor is checked so an obstacle cannot hide between endpoints.
    """
    if before.size != after.size:
        return False
    width, height = before.size
    radius = max(8, min(64, int(min(width, height) * .018)))
    centers = list(points)
    if len(points) == 2:
        (x1, y1), (x2, y2) = points
        length = math.hypot(x2 - x1, y2 - y1)
        count = max(2, min(512, math.ceil(length / radius) + 1))
        centers = [(round(x1 + (x2 - x1) * i / (count - 1)), round(y1 + (y2 - y1) * i / (count - 1))) for i in range(count)]
    for x, y in centers:
        for scale, mean_limit, fraction_limit in ((1, 12.0, .08), (3, 30.0, .25)):
            distance = radius * scale
            region = (max(0, x - distance), max(0, y - distance), min(width, x + distance), min(height, y + distance))
            old = before.crop(region).tobytes(); new = after.crop(region).tobytes()
            total = changed = count = 0
            for index in range(0, len(old), 3):
                delta = max(abs(old[index + channel] - new[index + channel]) for channel in range(3))
                total += delta; changed += delta > 40; count += 1
            if not count or total / count > mean_limit or changed / count > fraction_limit:
                return False
    return True


class LiveController:
    """Single serial, serialized reads/inputs, one-use frame tokens, operator evidence."""
    def __init__(self, lab, *, operator=False):
        self.lab = lab
        self.operator = operator
        self.lock = threading.RLock()
        self.frames = {}
        self.closed = threading.Event()
        self.capture_count = 0
        self.capture_seconds = 0.0

    def _focus(self):
        return focus_identity(self.lab._adb("shell", "dumpsys", "window", "windows"))

    def capture(self):
        with self.lock:
            if self.closed.is_set():
                raise ValueError("调试窗口已关闭")
            started = self.lab.clock()
            previous_focus = self._focus()
            path = self.lab.screenshot()
            focus = self._focus()
            metadata = json.loads(path.read_text(encoding="utf-8"))
            if previous_focus != focus:
                focus = None
            token = uuid.uuid4().hex
            frame = {"token": token, "path": str(path), "png": str(path.parent / metadata["png"]),
                     "width": metadata["width"], "height": metadata["height"],
                     "captured_unix": metadata["captured_unix"], "focus": focus}
            self.frames[token] = frame
            while len(self.frames) > 8:
                del self.frames[next(iter(self.frames))]
            elapsed = self.lab.clock() - started
            self.capture_count += 1; self.capture_seconds += elapsed
            self.lab._event("preview_sample", frame=path.name, elapsed_s=elapsed,
                            measured_capture_fps=self.capture_count / self.capture_seconds if self.capture_seconds > 0 else 0)
            return frame

    def close(self):
        self.closed.set()

    def _recent(self, metadata):
        age = self.lab.clock() - metadata["captured_unix"]
        if not 0 <= age <= TOKEN_TTL:
            raise ValueError("画面已过期，请等待刷新后再操作")

    def input(self, token, kind, start=None, end=None, duration=400, key=None):
        with self.lock:
            frame = self.frames.pop(token, None)
            command = None
            try:
                if self.operator is not True:
                    raise ValueError("未启用 --operator，当前窗口为只读")
                if self.closed.is_set():
                    raise ValueError("调试窗口已关闭")
                if frame is None:
                    raise ValueError("截图标识无效或已使用")
                self._recent(frame)
                source = self.lab._load_frame(frame["path"])
                if not frame["focus"]:
                    raise ValueError("来源窗口不明确，请重新观察")
                if kind not in {"tap", "long_press", "swipe", "key"}:
                    raise ValueError("不支持的操作")
                if isinstance(duration, bool) or not isinstance(duration, int):
                    raise ValueError("手势时长必须为整数")
                if kind == "key":
                    if key not in lab_module.KEYS:
                        raise ValueError("只支持返回、主页、回车")
                    command = ["keyevent", lab_module.KEYS[key]]; points = []
                else:
                    if not isinstance(start, (list, tuple)) or len(start) != 2:
                        raise ValueError("缺少来源坐标")
                    first = self.lab._point(*start, source); points = [first]
                    if kind == "swipe":
                        if not isinstance(end, (list, tuple)) or len(end) != 2 or not 150 <= duration <= 2000:
                            raise ValueError("滑动需要终点，时长150至2000毫秒")
                        last = self.lab._point(*end, source); points.append(last)
                        command = ["swipe", *map(str, first), *map(str, last), str(duration)]
                    elif kind == "long_press":
                        if not 500 <= duration <= 2000:
                            raise ValueError("长按时长500至2000毫秒")
                        command = ["swipe", *map(str, first), *map(str, first), str(duration)]
                    else:
                        command = ["tap", *map(str, first)]
                if self._focus() != frame["focus"]:
                    raise ValueError("前台窗口已变化")
                checked_path = self.lab.screenshot()
                checked = self.lab._load_frame(checked_path)
                if self._focus() != frame["focus"]:
                    raise ValueError("预检期间前台窗口变化")
                before = decode_rgb(Path(frame["png"]).read_bytes())
                after = decode_rgb((checked_path.parent / checked["png"]).read_bytes())
                stable = before.size == after.size and (before.tobytes() == after.tobytes() if kind == "key" else stable_regions(before, after, points))
                if not stable:
                    raise ValueError("目标、滑动路径或周围界面已变化，请重新观察")
                self._recent(frame); self._recent(checked)
                if self.closed.is_set():
                    raise ValueError("调试窗口已关闭")
                self.lab._adb("shell", "input", *command)
            except (ValueError, RuntimeError, OSError, json.JSONDecodeError):
                self.lab._event("panel_input_rejected_or_failed", operator=True, source="operator", agent_success=False, action=kind)
                raise
            return self.lab._event("panel_input_accepted_by_adb", operator=True, source="operator", agent_success=False,
                                   command=command, source_frame=frame["path"], source_sha256=source["sha256"],
                                   checked_frame=checked_path.name, check="focus_geometry_local_rgb", token_ttl_s=TOKEN_TTL)


def panel(controller, seconds, fps, scrcpy=None):
    import tkinter as tk
    from tkinter import ttk
    from PIL import Image, ImageTk
    root = tk.Tk(); root.title("Doppel · 设备调试（操作员）"); root.geometry("560x920")
    root.configure(bg="#10141e")
    toolbar = ttk.Frame(root, padding=8); toolbar.pack(fill="x")
    status = tk.StringVar(value="连接并采集当前画面…")
    canvas = tk.Canvas(root, bg="#10141e", highlightthickness=0); canvas.pack(fill="both", expand=True)
    ttk.Label(root, textvariable=status, padding=8, wraplength=520).pack(fill="x")
    executor = ThreadPoolExecutor(max_workers=1)
    state = {"frame": None, "down": None, "image": None, "photo": None, "closed": False, "busy": False,
             "start": time.monotonic(), "count": 0, "offset": (0, 0), "size": (1, 1)}

    def render():
        if state["image"] is None:
            return
        image = state["image"].copy()
        image.thumbnail((max(1, canvas.winfo_width()), max(1, canvas.winfo_height())), Image.Resampling.LANCZOS)
        x = (canvas.winfo_width() - image.width) // 2; y = (canvas.winfo_height() - image.height) // 2
        state["offset"] = (x, y); state["size"] = image.size; state["photo"] = ImageTk.PhotoImage(image)
        canvas.delete("screen"); canvas.create_image(x, y, anchor="nw", image=state["photo"], tags="screen")

    def finish(future, action=False):
        if state["closed"]:
            return
        if not future.done():
            root.after(30, lambda: finish(future, action)); return
        state["busy"] = False
        try:
            result = future.result()
            if action:
                status.set("操作已交给ADB；请观察实际结果。该操作不计入Agent成绩。")
            else:
                state["frame"] = result; state["image"] = decode_rgb(Path(result["png"]).read_bytes()); render(); state["count"] += 1
                elapsed = time.monotonic() - state["start"]
                status.set(f"{controller.lab.serial} · {result['width']}×{result['height']} · 实际刷新 {state['count'] / max(.001, elapsed):.2f} fps\n"
                           + ("点击 / 拖动 / 按住后松开；目标变化会拒绝操作。" if controller.operator else "只读模式。"))
        except Exception as exc:
            status.set(str(exc))
        root.after(max(20, round(1000 / fps)), refresh)

    def refresh():
        if state["closed"] or state["busy"]:
            return
        if time.monotonic() - state["start"] > seconds or state["count"] >= 600:
            status.set("本次有界采集结束。可关闭窗口后重新启动；证据已保存。"); return
        if state["down"]:
            root.after(100, refresh); return
        state["busy"] = True
        finish(executor.submit(controller.capture))

    def position(event):
        x, y = state["offset"]; width, height = state["size"]
        nx = (event.x - x) / width; ny = (event.y - y) / height
        return (nx, ny) if 0 <= nx < 1 and 0 <= ny < 1 else None

    def submit(kind, start=None, end=None, duration=400, key=None, frame=None):
        frame = frame or state["frame"]
        if state["busy"] or not frame:
            return
        state["busy"] = True
        finish(executor.submit(controller.input, frame["token"], kind, start, end, duration, key), action=True)

    def down(event):
        point = position(event)
        if point and not state["busy"] and state["frame"]:
            state["down"] = (point, time.monotonic(), state["frame"])

    def up(event):
        previous = state["down"]; state["down"] = None
        end = position(event)
        if not previous or end is None:
            return
        start, at, frame = previous; elapsed = round((time.monotonic() - at) * 1000)
        if math.hypot(end[0] - start[0], end[1] - start[1]) > .025:
            submit("swipe", start, end, min(2000, max(150, elapsed)), frame=frame)
        elif elapsed >= 500:
            submit("long_press", start, duration=min(2000, elapsed), frame=frame)
        else:
            submit("tap", start, frame=frame)

    for label, key in (("返回", "back"), ("主页", "home"), ("回车", "enter")):
        ttk.Button(toolbar, text=label, command=lambda key=key: submit("key", key=key)).pack(side="left", padx=3)
    if scrcpy:
        ttk.Button(toolbar, text="流畅只读预览", command=lambda: controller.lab.preview(scrcpy)).pack(side="right", padx=3)
    canvas.bind("<ButtonPress-1>", down); canvas.bind("<ButtonRelease-1>", up)
    canvas.bind("<Configure>", lambda _: render())

    def close():
        state["closed"] = True; controller.close(); executor.shutdown(wait=False, cancel_futures=True); root.destroy()
    root.protocol("WM_DELETE_WINDOW", close); root.after(10, refresh); root.mainloop()
    # Keep the evidence-directory lock until our pending read/input process has exited.
    executor.shutdown(wait=True, cancel_futures=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=Path, required=True); parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True); parser.add_argument("--append", action="store_true")
    parser.add_argument("--operator", action="store_true"); parser.add_argument("--scrcpy", type=Path)
    parser.add_argument("--seconds", type=int, default=60); parser.add_argument("--fps", type=int, default=3)
    parser.add_argument("--benchmark", action="store_true", help="Bounded screenshot/focus sampling only; no window or input")
    args = parser.parse_args(argv)
    if not 1 <= args.seconds <= 300 or not 1 <= args.fps <= 10:
        parser.error("--seconds must be 1..300 and --fps 1..10 (requested, not guaranteed)")
    lab = lab_module.Lab(args.adb, args.serial, args.output, append=args.append)
    with lab.session():
        controller = LiveController(lab, operator=args.operator)
        if args.benchmark:
            started = time.monotonic()
            while time.monotonic() - started < args.seconds and controller.capture_count < 600:
                before = time.monotonic(); controller.capture()
                time.sleep(max(0, 1 / args.fps - (time.monotonic() - before)))
            elapsed = time.monotonic() - started
            report = {"serial": args.serial, "frames": controller.capture_count, "elapsed_s": elapsed,
                      "actual_fps": controller.capture_count / elapsed, "requested_fps": args.fps,
                      "operator_inputs": 0, "agent_success": False}
            (lab.output / "preview-benchmark.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
            print(json.dumps(report))
        else:
            panel(controller, args.seconds, args.fps, args.scrcpy)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

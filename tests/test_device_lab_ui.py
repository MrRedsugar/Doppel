"""Offline UI-controller contracts: no device commands are executed by these tests."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("device_lab_ui", ROOT / "scripts" / "device-lab-ui.py")
ui = importlib.util.module_from_spec(spec); spec.loader.exec_module(ui)
base_spec = importlib.util.spec_from_file_location("device_lab_tests", ROOT / "tests" / "test_device_lab.py")
base = importlib.util.module_from_spec(base_spec); base_spec.loader.exec_module(base)


class FakeFocusAdb(base.FakeAdb):
    def __init__(self):
        super().__init__()
        self.focus = "example.app/.MainActivity"

    def __call__(self, argv, **kwargs):
        if argv[3:] == ["shell", "dumpsys", "window", "windows"]:
            self.calls.append((argv, kwargs))
            return subprocess.CompletedProcess(argv, 0, f"mCurrentFocus=Window{{abcdef u0 {self.focus}}}".encode(), b"")
        return super().__call__(argv, **kwargs)


class LiveControllerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name); self.adb = self.path / "adb.exe"; self.adb.touch()
        self.fake = FakeFocusAdb(); self.now = 1000.0
        self.lab = ui.lab_module.Lab(self.adb, "emulator-5554", self.path / "evidence", runner=self.fake, clock=lambda: self.now)
        self.controller = ui.LiveController(self.lab, operator=True)

    def actions(self):
        return [argv[3:] for argv, _ in self.fake.calls if argv[3:5] == ["shell", "input"]]

    def test_short_lived_token_maps_to_original_pixels_and_is_single_use(self):
        frame = self.controller.capture()
        result = self.controller.input(frame["token"], "tap", (.25, .5))
        self.assertEqual(self.actions(), [["shell", "input", "tap", "30", "120"]])
        self.assertEqual(result["source"], "operator"); self.assertFalse(result["agent_success"])
        with self.assertRaises(ValueError):
            self.controller.input(frame["token"], "tap", (.25, .5))
        self.assertEqual(len(self.actions()), 1)

    def test_readonly_panel_cannot_inject(self):
        self.controller.operator = False
        frame = self.controller.capture()
        with self.assertRaises(ValueError): self.controller.input(frame["token"], "tap", (.5, .5))
        self.assertEqual(self.actions(), [])

    def test_expiry_unknown_token_and_changed_focus_never_inject(self):
        frame = self.controller.capture(); self.now += 3.01
        with self.assertRaises(ValueError): self.controller.input(frame["token"], "tap", (.5, .5))
        with self.assertRaises(ValueError): self.controller.input("invented", "tap", (.5, .5))
        frame = self.controller.capture(); self.fake.focus = "other.app/.MainActivity"
        with self.assertRaises(ValueError): self.controller.input(frame["token"], "tap", (.5, .5))
        self.assertEqual(self.actions(), [])

    def test_orientation_target_change_and_unknown_keys_never_inject(self):
        frame = self.controller.capture(); self.fake.image = base.png(240, 120)
        with self.assertRaises(ValueError): self.controller.input(frame["token"], "tap", (.5, .5))
        frame = self.controller.capture(); self.fake.image = base.png(240, 120, 120)
        with self.assertRaises(ValueError): self.controller.input(frame["token"], "tap", (.5, .5))
        frame = self.controller.capture()
        with self.assertRaises(ValueError): self.controller.input(frame["token"], "key", key="power")
        self.assertEqual(self.actions(), [])

    def test_preflight_latency_cannot_make_an_expired_token_actionable(self):
        frame = self.controller.capture(); original = self.lab.runner
        def slow(argv, **kwargs):
            result = original(argv, **kwargs)
            if argv[3:] == ["exec-out", "screencap", "-p"]: self.now += 3.1
            return result
        self.lab.runner = slow
        with self.assertRaises(ValueError): self.controller.input(frame["token"], "tap", (.5, .5))
        self.assertEqual(self.actions(), [])

    def test_close_during_preflight_cancels_pending_input(self):
        frame = self.controller.capture(); original = self.lab.runner
        def close_during_capture(argv, **kwargs):
            result = original(argv, **kwargs)
            if argv[3:] == ["exec-out", "screencap", "-p"]: self.controller.close()
            return result
        self.lab.runner = close_during_capture
        with self.assertRaises(ValueError): self.controller.input(frame["token"], "tap", (.5, .5))
        self.assertEqual(self.actions(), [])

    def test_swipe_longpress_and_key_use_only_bounded_argv(self):
        frame = self.controller.capture()
        self.controller.input(frame["token"], "swipe", (.2, .8), (.7, .3), 150)
        self.assertEqual(self.actions()[-1], ["shell", "input", "swipe", "24", "192", "84", "72", "150"])
        frame = self.controller.capture(); self.controller.input(frame["token"], "long_press", (.5, .5), duration=600)
        self.assertEqual(self.actions()[-1], ["shell", "input", "swipe", "60", "120", "60", "120", "600"])
        frame = self.controller.capture(); self.controller.input(frame["token"], "key", key="back")
        self.assertEqual(self.actions()[-1], ["shell", "input", "keyevent", "4"])
        self.assertTrue(all(argv[1:3] == ["-s", "emulator-5554"] and not kw["shell"] for argv, kw in self.fake.calls))

    def test_every_rejection_and_success_is_marked_operator_not_agent(self):
        frame = self.controller.capture(); self.controller.input(frame["token"], "tap", (.3, .4))
        with self.assertRaises(ValueError): self.controller.input("invalid", "tap", (.3, .4))
        rows = [json.loads(line) for line in (self.lab.output / "operator-actions.jsonl").read_text().splitlines()]
        self.assertEqual(len(rows), 2)
        self.assertTrue(all(row["source"] == "operator" and row["agent_success"] is False for row in rows))


class PixelGuardTest(unittest.TestCase):
    def test_unrelated_animation_is_allowed_but_core_and_context_overlay_are_rejected(self):
        before = Image.new("RGB", (240, 180), (20, 30, 40)); after = before.copy()
        for y in range(120, 180):
            for x in range(180, 240): after.putpixel((x, y), (220, 10, 50))
        self.assertTrue(ui.stable_regions(before, after, [(60, 50)]))
        after.putpixel((60, 50), (255, 255, 255))
        for y in range(42, 58):
            for x in range(52, 68): after.putpixel((x, y), (200, 200, 200))
        self.assertFalse(ui.stable_regions(before, after, [(60, 50)]))

    def test_swipe_checks_corridor_not_only_endpoints(self):
        before = Image.new("RGB", (240, 180), (20, 30, 40)); after = before.copy()
        for y in range(84, 98):
            for x in range(67, 79): after.putpixel((x, y), (200, 200, 200))
        self.assertFalse(ui.stable_regions(before, after, [(30, 90), (210, 90)]))

    def test_focus_parser_requires_concrete_component_and_does_not_invent_unknown_focus(self):
        self.assertEqual(ui.focus_identity(b"mCurrentFocus=Window{a1 u0 com.example/.MainActivity}"), "com.example/.MainActivity")
        self.assertIsNone(ui.focus_identity(b"mCurrentFocus=null"))
        self.assertIsNone(ui.focus_identity(b"mFocusedApp=someone"))


if __name__ == "__main__":
    unittest.main()

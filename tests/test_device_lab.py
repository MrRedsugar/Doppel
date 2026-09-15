"""Device-lab contract tests. No real ADB process or device is used."""
import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zlib

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "device-lab.py"
lab_module = None
if SCRIPT.exists():
    spec = importlib.util.spec_from_file_location("device_lab", SCRIPT)
    lab_module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(lab_module)


def png(width=120, height=240, color=0):
    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress((b"\0" + bytes([color]) * width * 3) * height)) + chunk(b"IEND", b""))


class FakeAdb:
    def __init__(self):
        self.image = png()
        self.calls = []
        self.failure = False

    def __call__(self, argv, **kwargs):
        self.calls.append((argv, kwargs))
        assert isinstance(argv, list) and not kwargs.get("shell")
        if self.failure:
            return subprocess.CompletedProcess(argv, 1, b"secret", b"secret")
        command = argv[3:]
        if command == ["exec-out", "screencap", "-p"]:
            data = self.image
        elif command == ["exec-out", "run-as", "dev.doppel.developer", "cat", "no_backup/direct-runs-v1.json"]:
            data = b'{"runs": []}'
        elif command[0] == "pull":
            Path(command[2]).write_bytes(b"private recording")
            data = b""
        else:
            data = b""
        return subprocess.CompletedProcess(argv, 0, data, b"")


class DeviceLabTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(lab_module, "device-lab implementation is required")
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.adb = self.base / "adb.exe"
        self.adb.touch()
        self.fake = FakeAdb()
        self.now = 1000.0
        self.lab = lab_module.Lab(self.adb, "emulator-5554", self.base / "evidence", runner=self.fake, clock=lambda: self.now)

    def actions(self):
        return [argv for argv, _ in self.fake.calls if argv[3:5] == ["shell", "input"]]

    def test_capture_records_original_size_hash_time_and_unique_files(self):
        a = self.lab.screenshot()
        b = self.lab.screenshot()
        frame = json.loads(a.read_text())
        self.assertNotEqual(a, b)
        self.assertEqual((frame["width"], frame["height"]), (120, 240))
        self.assertEqual(frame["captured_unix"], 1000)
        self.assertEqual(len(frame["sha256"]), 64)
        self.assertTrue((a.parent / frame["png"]).is_file())

    def test_tap_maps_normalized_position_to_original_frame(self):
        frame = self.lab.screenshot()
        result = self.lab.tap(frame, .25, .5, operator=True)
        self.assertEqual(self.actions()[-1][3:], ["shell", "input", "tap", "30", "120"])
        self.assertEqual(result["source"], "operator")
        self.assertFalse(result["agent_success"])

    def test_operator_and_frame_required(self):
        frame = self.lab.screenshot()
        with self.assertRaises(ValueError):
            self.lab.tap(frame, .5, .5, operator=False)
        self.assertEqual(self.actions(), [])

    def test_stale_future_or_cross_device_frame_never_dispatches(self):
        for change in ({"captured_unix": 960}, {"captured_unix": 1001}, {"serial": "other"}):
            frame = self.lab.screenshot()
            content = json.loads(frame.read_text())
            content.update(change)
            frame.write_text(json.dumps(content))
            with self.assertRaises(ValueError):
                self.lab.tap(frame, .5, .5, operator=True)
        self.assertEqual(self.actions(), [])

    def test_changed_current_pixels_or_orientation_never_dispatches(self):
        frame = self.lab.screenshot()
        for image in (png(color=1), png(240, 120)):
            self.fake.image = image
            with self.assertRaises(ValueError):
                self.lab.tap(frame, .5, .5, operator=True)
        self.assertEqual(self.actions(), [])

    def test_tampered_saved_png_never_dispatches(self):
        frame = self.lab.screenshot()
        metadata = json.loads(frame.read_text())
        (frame.parent / metadata["png"]).write_bytes(png(color=3))
        with self.assertRaises(ValueError):
            self.lab.tap(frame, .5, .5, operator=True)
        self.assertEqual(self.actions(), [])

    def test_frame_path_cannot_reference_an_unrelated_file(self):
        frame = self.lab.screenshot()
        metadata = json.loads(frame.read_text())
        metadata["png"] = "../adb.exe"
        frame.write_text(json.dumps(metadata))
        with self.assertRaises(ValueError):
            self.lab.tap(frame, .5, .5, operator=True)
        self.assertEqual(self.actions(), [])

    def test_age_checked_again_after_slow_preflight(self):
        frame = self.lab.screenshot()
        original = self.lab.runner
        def slow(argv, **kwargs):
            result = original(argv, **kwargs)
            if argv[3:] == ["exec-out", "screencap", "-p"]:
                self.now += 31
            return result
        self.lab.runner = slow
        with self.assertRaises(ValueError):
            self.lab.tap(frame, .5, .5, operator=True)
        self.assertEqual(self.actions(), [])

    def test_nonfinite_and_outside_coordinates_rejected_without_device_input(self):
        frame = self.lab.screenshot()
        for x in (float("nan"), float("inf"), -.001, 1, True):
            with self.assertRaises(ValueError):
                self.lab.tap(frame, x, .5, operator=True)
        self.assertEqual(self.actions(), [])

    def test_swipe_checks_both_endpoints_and_duration(self):
        frame = self.lab.screenshot()
        with self.assertRaises(ValueError):
            self.lab.swipe(frame, .5, .7, 1, .2, 400, operator=True)
        with self.assertRaises(ValueError):
            self.lab.swipe(frame, .5, .7, .5, .2, 10000, operator=True)
        self.lab.swipe(frame, .5, .7, .5, .2, 400, operator=True)
        self.assertEqual(self.actions()[-1][3:], ["shell", "input", "swipe", "60", "168", "60", "48", "400"])

    def test_only_allowlisted_keys_supported(self):
        frame = self.lab.screenshot()
        for key in ("power", "66;reboot", "text"):
            with self.assertRaises(ValueError):
                self.lab.key(frame, key, operator=True)
        self.lab.key(frame, "back", operator=True)
        self.assertEqual(self.actions()[-1][3:], ["shell", "input", "keyevent", "4"])

    def test_append_requires_explicit_flag_and_same_device(self):
        with self.assertRaises(ValueError):
            lab_module.Lab(self.adb, "emulator-5554", self.lab.output, runner=self.fake)
        with self.assertRaises(ValueError):
            lab_module.Lab(self.adb, "other", self.lab.output, append=True, runner=self.fake)
        append = lab_module.Lab(self.adb, "emulator-5554", self.lab.output, append=True, runner=self.fake)
        self.assertEqual(append.output, self.lab.output)

    def test_status_reads_only_fixed_developer_file(self):
        path = self.lab.status()
        self.assertEqual(json.loads(path.read_text()), {"runs": []})
        self.assertEqual(self.fake.calls[-1][0][3:], ["exec-out", "run-as", "dev.doppel.developer", "cat", "no_backup/direct-runs-v1.json"])

    def test_recording_is_bounded_and_uses_generated_remote_file(self):
        for seconds in (0, 61, True):
            with self.assertRaises(ValueError):
                self.lab.record(seconds)
        video = self.lab.record(2)
        self.assertTrue(video.is_file())
        calls = [argv[3:] for argv, _ in self.fake.calls]
        self.assertEqual(calls[0][:4], ["shell", "screenrecord", "--time-limit", "2"])
        self.assertTrue(calls[0][-1].startswith("/sdcard/doppel-lab-"))

    def test_errors_omit_process_output_and_argv_never_use_shell(self):
        self.fake.failure = True
        with self.assertRaises(RuntimeError) as caught:
            self.lab.screenshot()
        self.assertNotIn("secret", str(caught.exception))
        self.assertNotIn("secret", (self.lab.output / "events.jsonl").read_text())
        self.assertTrue(all(argv[1:3] == ["-s", "emulator-5554"] and not kw.get("shell") for argv, kw in self.fake.calls))

    def test_lock_does_not_steal_an_existing_session(self):
        with self.lab.session():
            with self.assertRaises(ValueError):
                with self.lab.session():
                    self.fail("Concurrent session must be rejected")
            self.assertTrue((self.lab.output / ".operator.lock").exists())
        self.assertFalse((self.lab.output / ".operator.lock").exists())

    def test_preview_passes_explicit_adb_and_disables_input(self):
        executable = self.base / "scrcpy.exe"
        executable.touch()
        with patch.object(lab_module.subprocess, "Popen") as start:
            start.return_value.pid = 123
            result = self.lab.preview(executable)
        argv = start.call_args.args[0]
        self.assertEqual(argv[1:], ["--serial", "emulator-5554", "--no-control"])
        self.assertEqual(start.call_args.kwargs["env"]["ADB"], str(self.adb))
        self.assertFalse(result["control"])

    def test_cli_rejects_missing_device_or_unavailable_commands(self):
        parser = lab_module.parser()
        with patch("sys.stderr"):
            for argv in (["screenshot"], ["--adb", str(self.adb), "--serial", "emulator-5554", "--output", str(self.base / "x"), "shell"]):
                with self.assertRaises(SystemExit):
                    parser.parse_args(argv)


if __name__ == "__main__":
    unittest.main()

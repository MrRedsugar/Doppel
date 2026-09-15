"""Isolated process-tree tests; never attach a job to the test runner."""
import ctypes
import importlib.util
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import threading
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def guard_helper():
    from process_guard import install_process_guard

    try:
        guard = install_process_guard()
        same_guard = guard is install_process_guard()
    except OSError as error:
        print(json.dumps({"error": str(error), "errno": error.errno}), flush=True)
        return 93
    child = subprocess.Popen(
        [sys.executable, "-B", "-c",
         "import os,time; print(os.getpid(), flush=True); time.sleep(60)"],
        stdout=subprocess.PIPE, text=True, close_fds=True,
        creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0)
    try:
        child_pid = int(child.stdout.readline())
        print(json.dumps({"active": guard.active, "platform": guard.platform,
                          "same_guard": same_guard, "helper_pid": os.getpid(),
                          "child_pid": child_pid}), flush=True)
        sys.stdin.buffer.read(1)
    finally:
        if child.poll() is None:
            child.terminate()
        child.wait(timeout=5)
        child.stdout.close()
    return 0


class ProcessGuardTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(importlib.util.find_spec("process_guard"),
                             "The process lifetime guard has not been implemented")

    @unittest.skipUnless(sys.platform == "win32", "Windows Job Object integration test")
    def test_forced_helper_termination_also_terminates_its_sleeping_child(self):
        from ctypes import wintypes

        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.OpenProcess.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.WaitForSingleObject.argtypes = (wintypes.HANDLE, wintypes.DWORD)
        kernel.WaitForSingleObject.restype = wintypes.DWORD
        kernel.TerminateProcess.argtypes = (wintypes.HANDLE, wintypes.UINT)
        kernel.TerminateProcess.restype = wintypes.BOOL
        kernel.CloseHandle.argtypes = (wintypes.HANDLE,)
        kernel.CloseHandle.restype = wintypes.BOOL

        helper = subprocess.Popen(
            [sys.executable, "-B", str(Path(__file__).resolve()), "--guard-helper"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, close_fds=True, creationflags=subprocess.CREATE_NO_WINDOW)
        helper_handle = child_handle = None
        try:
            ready_lines = queue.Queue()
            reader = threading.Thread(target=lambda: ready_lines.put(helper.stdout.readline()),
                                      daemon=True)
            reader.start()
            line = ready_lines.get(timeout=15)
            self.assertTrue(line, "Helper exited before reporting job installation")
            ready = json.loads(line)
            self.assertNotIn("error", ready,
                             "Job assignment failed; nested-job restrictions must be reported, not bypassed")
            self.assertTrue(ready["active"])
            self.assertEqual("win32", ready["platform"])
            self.assertTrue(ready["same_guard"], "Repeated installation must retain one job")
            access = 0x00100000 | 0x0001  # SYNCHRONIZE | PROCESS_TERMINATE
            helper_handle = kernel.OpenProcess(access, False, ready["helper_pid"])
            child_handle = kernel.OpenProcess(access, False, ready["child_pid"])
            self.assertTrue(helper_handle, "Cannot acquire the owned helper process handle")
            self.assertTrue(child_handle, "Cannot acquire the owned child process handle")
            self.assertEqual(258, kernel.WaitForSingleObject(child_handle, 0),
                             "The child must be alive before terminating the helper")
            self.assertTrue(kernel.TerminateProcess(helper_handle, 91))
            self.assertEqual(0, kernel.WaitForSingleObject(helper_handle, 5000))
            self.assertEqual(0, kernel.WaitForSingleObject(child_handle, 5000),
                             "Forced parent termination left its owned child running")
            helper.wait(timeout=5)
        finally:
            # Stable handles identify only processes created by this test, avoiding PID reuse.
            for handle in (child_handle, helper_handle):
                if handle:
                    if kernel.WaitForSingleObject(handle, 0) == 258:
                        kernel.TerminateProcess(handle, 92)
                        kernel.WaitForSingleObject(handle, 5000)
                    kernel.CloseHandle(handle)
            if helper.poll() is None:
                helper.terminate()
            helper.wait(timeout=5)
            for stream in (helper.stdin, helper.stdout, helper.stderr):
                stream.close()

    @unittest.skipIf(sys.platform == "win32", "Explicit non-Windows fallback")
    def test_non_windows_returns_an_explicit_inactive_guard(self):
        from process_guard import install_process_guard

        guard = install_process_guard()
        self.assertFalse(guard.active)
        self.assertEqual(sys.platform, guard.platform)
        self.assertIs(guard, install_process_guard())


if __name__ == "__main__":
    if "--guard-helper" in sys.argv:
        raise SystemExit(guard_helper())
    unittest.main()

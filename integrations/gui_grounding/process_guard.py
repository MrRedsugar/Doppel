"""Keep a Windows service and its future workers in one process-lifetime job.

Call ``install_process_guard()`` in the service's main process before spawning
any workers. The anonymous Job Object has KILL_ON_JOB_CLOSE, and its handle is
not inheritable. Windows closes that handle even when the service is forcibly
terminated, terminating the workers that inherited its job membership.

The handle intentionally stays open for the entire process lifetime: closing
it manually would also terminate the calling service. There is no close method
or atexit handler. The operating system owns final handle cleanup. Other
platforms return an explicitly inactive guard; no process-tree guarantee is
claimed there. Nested-job assignment failures raise without attempting to
break away from an existing job or modify its restrictions.
"""
from dataclasses import dataclass, field
import sys
import threading


@dataclass(frozen=True)
class ProcessGuard:
    active: bool
    platform: str
    _job_handle: int | None = field(default=None, repr=False, compare=False)


_guard: ProcessGuard | None = None
_guard_lock = threading.Lock()


def _install_windows_guard():
    import ctypes
    from ctypes import wintypes

    class BasicLimitInformation(ctypes.Structure):
        _fields_ = [
            ("PerProcessUserTimeLimit", ctypes.c_longlong),
            ("PerJobUserTimeLimit", ctypes.c_longlong),
            ("LimitFlags", wintypes.DWORD),
            ("MinimumWorkingSetSize", ctypes.c_size_t),
            ("MaximumWorkingSetSize", ctypes.c_size_t),
            ("ActiveProcessLimit", wintypes.DWORD),
            ("Affinity", ctypes.c_size_t),
            ("PriorityClass", wintypes.DWORD),
            ("SchedulingClass", wintypes.DWORD),
        ]

    class IoCounters(ctypes.Structure):
        _fields_ = [(name, ctypes.c_ulonglong) for name in (
            "ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
            "ReadTransferCount", "WriteTransferCount", "OtherTransferCount")]

    class ExtendedLimitInformation(ctypes.Structure):
        _fields_ = [
            ("BasicLimitInformation", BasicLimitInformation),
            ("IoInfo", IoCounters),
            ("ProcessMemoryLimit", ctypes.c_size_t),
            ("JobMemoryLimit", ctypes.c_size_t),
            ("PeakProcessMemoryUsed", ctypes.c_size_t),
            ("PeakJobMemoryUsed", ctypes.c_size_t),
        ]

    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.CreateJobObjectW.argtypes = (ctypes.c_void_p, wintypes.LPCWSTR)
    kernel.CreateJobObjectW.restype = wintypes.HANDLE
    kernel.SetInformationJobObject.argtypes = (
        wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD)
    kernel.SetInformationJobObject.restype = wintypes.BOOL
    kernel.SetHandleInformation.argtypes = (wintypes.HANDLE, wintypes.DWORD, wintypes.DWORD)
    kernel.SetHandleInformation.restype = wintypes.BOOL
    kernel.GetCurrentProcess.argtypes = ()
    kernel.GetCurrentProcess.restype = wintypes.HANDLE
    kernel.AssignProcessToJobObject.argtypes = (wintypes.HANDLE, wintypes.HANDLE)
    kernel.AssignProcessToJobObject.restype = wintypes.BOOL
    kernel.CloseHandle.argtypes = (wintypes.HANDLE,)
    kernel.CloseHandle.restype = wintypes.BOOL

    def failed(operation):
        error = ctypes.get_last_error()
        return OSError(error, f"{operation} failed: {ctypes.FormatError(error).strip()}. "
                       "The current environment may restrict nested jobs; no breakaway or policy changes were attempted.")

    job = kernel.CreateJobObjectW(None, None)
    if not job:
        raise failed("CreateJobObjectW")
    guard = ProcessGuard(active=True, platform="win32", _job_handle=job)
    try:
        # HANDLE_FLAG_INHERIT = 1. Clearing it keeps workers from retaining the
        # last job handle after their parent has been forcibly terminated.
        if not kernel.SetHandleInformation(job, 1, 0):
            raise failed("SetHandleInformation")
        limits = ExtendedLimitInformation()
        limits.BasicLimitInformation.LimitFlags = 0x00002000  # KILL_ON_JOB_CLOSE
        if not kernel.SetInformationJobObject(job, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
            raise failed("SetInformationJobObject")
        if not kernel.AssignProcessToJobObject(job, kernel.GetCurrentProcess()):
            raise failed("AssignProcessToJobObject")
    except Exception:
        # Assignment has failed or has not occurred; no process belongs to this
        # new job, so closing its handle cannot terminate the caller.
        kernel.CloseHandle(job)
        raise
    return guard


def install_process_guard() -> ProcessGuard:
    """Idempotently guard this process before it spawns any worker processes."""
    global _guard
    with _guard_lock:
        if _guard is None:
            _guard = (_install_windows_guard() if sys.platform == "win32"
                      else ProcessGuard(active=False, platform=sys.platform))
        return _guard

import pytest
from sse_starlette.sse import AppStatus


@pytest.fixture(autouse=True)
def isolate_sse_shutdown_state(monkeypatch):
    # In-process Uvicorn shutdown can leave this process-wide SSE flag set.
    # Each test starts a new application and must not inherit a stopped one.
    monkeypatch.setattr(AppStatus, "should_exit", False)

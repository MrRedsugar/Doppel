from fastapi import FastAPI
from fastapi.testclient import TestClient
import pytest

from doppel import DoppelRuntime, RuntimeConfig, create_router
from doppel.errors import PermissionDenied
from doppel.models import CommandResult, Node, Observation


def scoped_run(tmp_path, allowed=None):
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False))
    device = runtime.register_device("alice", "fixture", "Fixture")
    app = FastAPI()
    app.include_router(create_router(runtime, lambda: "alice"))
    with TestClient(app) as client:
        response = client.post("/v1/runs", json={
            "device_id": device.id, "goal": "Use the authorized app", "mode": "full",
            "allowed_packages": allowed if allowed is not None else ["dev.doppel.testapp"],
        })
    assert response.status_code == 201
    run = runtime.get_run("alice", response.json()["id"])
    return runtime, device, run


def set_screen(runtime, device, run, package):
    command = runtime.queue_command(run.id, "observe")
    runtime.submit_result("alice", device.id, CommandResult(
        command_id=command.id, run_id=run.id, status="ok",
        observation=Observation(screen_id="current", package_name=package, width=100, height=100,
                                nodes=[Node(id="node", text="Field", bounds=[0, 0, 100, 100], clickable=True, editable=True, scrollable=True)]),
    ))


def test_scoped_task_cannot_launch_unapproved_package(tmp_path):
    runtime, device, run = scoped_run(tmp_path)
    assert run.allowed_packages == ["dev.doppel.testapp"]
    with pytest.raises(PermissionDenied):
        runtime.queue_command(run.id, "launch", package_name="com.tencent.mm")
    assert runtime.next_command("alice", device.id) is None
    assert runtime.queue_command(run.id, "launch", package_name="dev.doppel.testapp").package_name == "dev.doppel.testapp"


@pytest.mark.parametrize("kind, fields", [
    ("tap", {"screen_id": "current", "target": "node"}),
    ("type", {"screen_id": "current", "target": "node", "text": "fixture"}),
    ("scroll", {"screen_id": "current", "target": "node", "direction": "down"}),
    ("back", {}),
])
def test_scoped_task_cannot_interact_with_foreign_screen(tmp_path, kind, fields):
    runtime, device, run = scoped_run(tmp_path)
    set_screen(runtime, device, run, "com.tencent.mm")
    with pytest.raises(PermissionDenied):
        runtime.queue_command(run.id, kind, **fields)
    assert runtime.next_command("alice", device.id) is None


@pytest.mark.parametrize("kind", ["observe", "wait", "home"])
def test_scoped_task_retains_observation_and_recovery_actions(tmp_path, kind):
    runtime, device, run = scoped_run(tmp_path)
    assert runtime.queue_command(run.id, kind).kind == kind


def test_scoped_document_open_requires_wps_and_pins_its_package(tmp_path):
    runtime, device, run = scoped_run(tmp_path / "denied")
    with pytest.raises(PermissionDenied):
        runtime.queue_command(run.id, "open_document", uri="doppel-document://report.xlsx")
    runtime, device, run = scoped_run(tmp_path / "allowed", ["cn.wps.moffice_eng"])
    command = runtime.queue_command(run.id, "open_document", uri="doppel-document://report.xlsx")
    assert command.package_name == "cn.wps.moffice_eng"


def test_package_scope_cannot_exceed_forty_entries(tmp_path):
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False))
    device = runtime.register_device("alice", "fixture", "Fixture")
    app = FastAPI()
    app.include_router(create_router(runtime, lambda: "alice"))
    with TestClient(app) as client:
        response = client.post("/v1/runs", json={"device_id": device.id, "goal": "Inspect", "allowed_packages": [f"dev.fixture.app{i}" for i in range(41)]})
    assert response.status_code == 422
    assert runtime.runs("alice") == []

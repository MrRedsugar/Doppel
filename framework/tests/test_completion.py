import asyncio

from fastapi import FastAPI
from fastapi.testclient import TestClient

from doppel import DoppelRuntime, RuntimeConfig, create_router
from doppel.models import CommandResult, Observation


def setup(tmp_path):
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False))
    device = runtime.register_device("alice", "fixture", "Fixture")
    run = runtime.create_run("alice", device.id, "Read the screen", "full")
    app = FastAPI()
    app.include_router(create_router(runtime, lambda: "alice"))
    client = TestClient(app)
    client.headers["Authorization"] = "Bearer " + runtime.run_tokens[run.id]
    return runtime, device, run, client


def call(client, run, name, **arguments):
    return client.post(f"/v1/internal/runs/{run.id}/tool", json={"name": name, "arguments": arguments})


def observe(runtime, device, run, client, screen="one"):
    async def perform(run_id, kind):
        command = runtime.queue_command(run_id, kind)
        runtime.next_command("alice", device.id)
        result = CommandResult(command_id=command.id, run_id=run_id, status="ok", observation=Observation(screen_id=screen, package_name="fixture", width=100, height=100))
        runtime.submit_result("alice", device.id, result)
        return result
    runtime.perform = perform
    return call(client, run, "observe").json()


def test_completion_requires_real_current_run_evidence(tmp_path):
    runtime, device, run, client = setup(tmp_path)
    response = call(client, run, "finish_task", outcome="completed", summary="Done", evidence_ids=[])
    assert response.status_code == 422
    assert runtime.get_run("alice", run.id).status == "running"
    evidence = observe(runtime, device, run, client)["evidence_id"]
    assert call(client, run, "finish_task", outcome="completed", summary="Done", evidence_ids=["forged"]).status_code == 422
    assert call(client, run, "finish_task", outcome="completed", summary="Read fixture", evidence_ids=[evidence]).status_code == 200
    assert runtime.get_run("alice", run.id).status == "completed"


def test_stale_device_evidence_cannot_complete(tmp_path):
    runtime, device, run, client = setup(tmp_path)
    old = observe(runtime, device, run, client)["evidence_id"]
    observe(runtime, device, run, client, "two")
    assert call(client, run, "finish_task", outcome="completed", summary="Done", evidence_ids=[old]).status_code == 422
    assert call(client, run, "finish_task", outcome="failed", summary="Unable to verify", evidence_ids=[]).status_code == 200
    assert runtime.get_run("alice", run.id).status == "failed"


def test_finish_does_not_charge_another_model_call(tmp_path):
    runtime, device, run, client = setup(tmp_path)
    evidence = observe(runtime, device, run, client)["evidence_id"]
    call(client, run, "finish_task", outcome="completed", summary="Verified fixture", evidence_ids=[evidence])
    response = client.post(f"/v1/internal/runs/{run.id}/chat/completions", json={"model": runtime.config.model, "messages": [], "stream": True})
    assert response.status_code == 200
    assert "Verified fixture" in response.text and "[DONE]" in response.text
    assert not any(event.kind == "model_start" for event in runtime.events("alice", run.id))


def test_failed_action_invalidates_previous_screen_evidence(tmp_path):
    runtime, device, run, client = setup(tmp_path)
    old = observe(runtime, device, run, client)["evidence_id"]
    command = runtime.queue_command(run.id, "home")
    runtime.submit_result("alice", device.id, CommandResult(command_id=command.id, run_id=run.id, status="error", message="Device action failed"))
    response = call(client, run, "finish_task", outcome="completed", summary="Done", evidence_ids=[old])
    assert response.status_code == 422
    assert runtime.get_run("alice", run.id).status == "running"


def test_failed_external_result_cannot_mint_evidence(tmp_path):
    from doppel.completion import record_evidence
    runtime, device, run, client = setup(tmp_path)
    result = record_evidence(runtime, run.id, "call_extension", {}, {"isError": True, "content": [{"type": "text", "text": "Operation failed"}]})
    assert "evidence_id" not in result
    assert not any(event.kind == "evidence" for event in runtime.events("alice", run.id))

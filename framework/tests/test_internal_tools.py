import base64
import json
import struct
import zlib

import httpx
from openpyxl import Workbook

from doppel.models import CommandResult, Node, Observation
from test_completion import call, observe, setup


def test_retired_skills_are_unavailable_and_existing_files_stay(tmp_path):
    runtime, device, run, client = setup(tmp_path)
    from doppel.model_proxy import TOOL_NAMES
    old = tmp_path / "skills" / "guide" / "SKILL.md"
    old.parent.mkdir(parents=True)
    old.write_bytes(b"existing user data")
    for name in ("list_skills", "read_skill", "load_skill", "read_skill_resource"):
        assert name not in TOOL_NAMES
        response = client.post(f"/v1/internal/runs/{run.id}/tool", json={"name": name, "arguments": {"name": "guide"}})
        assert response.status_code == 422
        assert response.json()["detail"] == "Unknown tool"
    for method, path in (("get", "/skills"), ("get", "/skills/guide"), ("post", "/skills/import"), ("delete", "/skills/guide")):
        assert getattr(client, method)("/v1" + path).status_code == 404
    assert old.read_bytes() == b"existing user data"


def workbook(runtime):
    folder = runtime.config.data_dir / "documents" / "alice"
    folder.mkdir(parents=True)
    book = Workbook()
    book.active.append(["Amount"])
    book.active.append([12])
    book.save(folder / "source.xlsx")
    book.close()
    return folder


def test_document_tool_returns_version_conflict_and_hides_host_path(tmp_path):
    runtime, device, run, client = setup(tmp_path)
    folder = workbook(runtime)
    arguments = {"source": "source.xlsx", "output": "result.xlsx", "operations": []}
    response = call(client, run, "transform_document", **arguments)
    assert response.status_code == 200
    assert response.json()["download_uri"] == "doppel-document://result.xlsx"
    assert "output_path" not in response.json()
    assert str(folder) not in response.text
    before = (folder / "result.xlsx").read_bytes()
    response = call(client, run, "transform_document", **arguments)
    assert response.status_code == 200
    assert response.json()["status"] == "error"
    assert response.json()["error"] == "output_exists"
    assert "evidence_id" not in response.json()
    assert (folder / "result.xlsx").read_bytes() == before


def test_three_identical_failed_tool_calls_stop_before_another_model_charge(tmp_path):
    runtime, device, run, client = setup(tmp_path)
    workbook(runtime)
    arguments = {"source": "source.xlsx", "output": "source.xlsx", "operations": []}
    for _ in range(3):
        response = call(client, run, "transform_document", **arguments)
        assert response.status_code == 200
        assert response.json()["status"] == "error"
    assert runtime.get_run("alice", run.id).status == "failed"
    response = client.post(f"/v1/internal/runs/{run.id}/chat/completions", json={"messages": []})
    assert response.status_code == 200
    events = runtime.events("alice", run.id)
    assert not any(event.kind == "model_start" for event in events)
    outcomes = [event for event in events if event.kind == "tool_outcome"]
    assert [event.data["consecutive_failures"] for event in outcomes] == [1, 2, 3]
    assert len({event.data["request_hash"] for event in outcomes}) == 1
    assert not any("source.xlsx" in json.dumps(event.data) for event in outcomes)


def test_changed_arguments_or_success_reset_repeated_failure_guard(tmp_path):
    runtime, device, run, client = setup(tmp_path)
    for name in ("missing.xlsx", "different.xlsx", "missing.xlsx", "missing.xlsx"):
        assert client.post(f"/v1/internal/runs/{run.id}/tool", json={"name": "inspect_document", "arguments": {"name": name}}).json()["status"] == "error"
    assert runtime.get_run("alice", run.id).status == "running"
    assert call(client, run, "list_documents").status_code == 200
    assert client.post(f"/v1/internal/runs/{run.id}/tool", json={"name": "inspect_document", "arguments": {"name": "missing.xlsx"}}).json()["status"] == "error"
    assert runtime.get_run("alice", run.id).status == "running"


def test_repeated_validation_errors_are_audited_and_do_not_loop_forever(tmp_path):
    runtime, device, run, client = setup(tmp_path)
    for _ in range(3):
        assert call(client, run, "act", action="unsupported").status_code == 422
    assert runtime.get_run("alice", run.id).status == "failed"


def png_image(width, height):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
    data += chunk(b"IDAT", zlib.compress((b"\0" + b"\0\0\0" * width) * height)) + chunk(b"IEND", b"")
    return base64.b64encode(data).decode()


def test_vision_uses_paired_observation_and_explicit_image_to_screen_coordinates(tmp_path):
    runtime, device, run, client = setup(tmp_path)
    observe(runtime, device, run, client, "old")
    image = png_image(540, 1080)
    device_calls, model_calls = [], []

    async def perform(run_id, kind, **fields):
        device_calls.append((kind, fields))
        command = runtime.queue_command(run_id, kind, **fields)
        result = CommandResult(command_id=command.id, run_id=run_id, status="ok", observation=Observation(
            screen_id="fresh", package_name="fixture", width=1080, height=2160,
            nodes=[Node(id="icon", clickable=True, bounds=[100, 200, 200, 300])],
        ), data={"image_base64": image, "mime_type": "image/png"})
        runtime.submit_result("alice", device.id, result)
        return result

    def upstream(request):
        model_calls.append(json.loads(request.content))
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": "Visible icon."}}], "usage": {"prompt_tokens": 12, "completion_tokens": 3}})

    key = tmp_path / "key.txt"
    key.write_text("sk-fixture-not-a-real-provider-key")
    runtime.config.api_key_file = key
    runtime.perform = perform
    runtime.upstream_transport = httpx.MockTransport(upstream)
    response = call(client, run, "describe_screen", question="Locate the unlabeled icon.")
    assert response.status_code == 200
    assert device_calls == [("observe", {"include_screenshot": True})]
    result = response.json()
    assert result["description"] == "Visible icon."
    assert result["coordinates"] == {
        "screen_id": "fresh", "screen_width": 1080, "screen_height": 2160,
        "image_width": 540, "image_height": 1080,
        "image_to_screen_scale_x": 2.0, "image_to_screen_scale_y": 2.0,
    }
    text = model_calls[0]["messages"][0]["content"][0]["text"]
    assert "screen fresh" in text and "screen old" not in text
    assert "image_to_screen_scale_x" in text and "540" in text
    assert model_calls[0]["messages"][0]["content"][1]["image_url"]["url"].endswith(image)
    evidence = next(event for event in reversed(runtime.events("alice", run.id)) if event.kind == "evidence")
    assert evidence.data["screen_id"] == "fresh"
    assert result["evidence_id"] == evidence.data["id"]


def test_vision_refuses_unpaired_screenshot_instead_of_using_old_observation(tmp_path):
    runtime, device, run, client = setup(tmp_path)
    observe(runtime, device, run, client, "old")

    async def perform(run_id, kind, **fields):
        return CommandResult(command_id="fixture", run_id=run_id, status="ok", data={"image_base64": png_image(1, 1)})

    runtime.perform = perform
    response = call(client, run, "describe_screen", question="Find icon")
    assert response.status_code == 200
    assert response.json()["status"] == "error"
    assert "evidence_id" not in response.json()
    assert not any(event.kind == "model_start" for event in runtime.events("alice", run.id))

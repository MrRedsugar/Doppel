import copy
import json

import httpx
import pytest

from doppel.model_context import compact_screen_history
from doppel.models import CommandResult, Node, Observation
from test_completion import call
from test_internal_tools import png_image
from test_model_proxy import screen_turn, setup_proxy


def vision_fixture(tmp_path, choice, *, output_limit=1600, output_tokens=800):
    runtime, run, billing, client = setup_proxy(tmp_path)
    runtime.config.max_output_tokens = output_limit
    client.headers["Authorization"] = "Bearer " + runtime.run_tokens[run.id]
    requests = []

    async def perform(run_id, kind, **fields):
        command = runtime.queue_command(run_id, kind, **fields)
        result = CommandResult(command_id=command.id, run_id=run_id, status="ok",
                               observation=Observation(screen_id="paired", package_name="fixture", width=100, height=200,
                                                       nodes=[Node(id="current", text="Current control", clickable=True, bounds=[0, 0, 40, 40])]),
                               data={"image_base64": png_image(100, 200), "mime_type": "image/png"})
        runtime.submit_result("alice", run.device_id, result)
        return result

    def upstream(request):
        requests.append(json.loads(request.content))
        return httpx.Response(200, json={"choices": [choice], "usage": {"prompt_tokens": 899, "completion_tokens": output_tokens}})

    runtime.perform = perform
    runtime.upstream_transport = httpx.MockTransport(upstream)
    return runtime, run, billing, client, requests


@pytest.mark.parametrize("content", ["", " \n\t", None])
def test_empty_vision_response_is_an_error_without_evidence_or_retry(tmp_path, content):
    runtime, run, billing, client, requests = vision_fixture(tmp_path, {
        "finish_reason": "stop", "message": {"role": "assistant", "content": content},
    })
    response = call(client, run, "describe_screen", question="Describe the unlabeled control")
    result = response.json()
    assert response.status_code == 200 and result["status"] == "error"
    assert result["error"] == "vision_empty"
    assert "evidence_id" not in result and "description" not in result
    assert not any(event.kind == "evidence" for event in runtime.events("alice", run.id))
    assert len(requests) == len(billing.reservations) == len(billing.receipts) == 1
    assert billing.receipts[0][2:] == (899, 800)
    assert not billing.releases
    assert any(event.kind == "usage" and event.data["output_tokens"] == 800 for event in runtime.events("alice", run.id))


@pytest.mark.parametrize("reason,error", [
    ("length", "vision_truncated"),
    ("content_filter", "vision_incomplete"),
    ("insufficient_system_resource", "vision_incomplete"),
    (None, "vision_incomplete"),
])
def test_unfinished_vision_text_is_not_exposed_as_verified_description(tmp_path, reason, error):
    runtime, run, billing, client, requests = vision_fixture(tmp_path, {
        "finish_reason": reason, "message": {"role": "assistant", "content": "The icon might be"},
    })
    result = call(client, run, "describe_screen", question="Locate the control").json()
    assert result["status"] == "error" and result["error"] == error
    assert "description" not in result and "evidence_id" not in result
    assert not any(event.kind == "evidence" for event in runtime.events("alice", run.id))
    assert len(requests) == len(billing.reservations) == len(billing.receipts) == 1
    assert billing.receipts[0][2:] == (899, 800)
    assert not billing.releases


@pytest.mark.parametrize("limit,expected_max", [(1600, 1600), (300, 300)])
def test_complete_vision_description_disables_thinking_and_respects_host_output_limit(tmp_path, limit, expected_max):
    runtime, run, billing, client, requests = vision_fixture(tmp_path, {
        "finish_reason": "stop", "message": {"role": "assistant", "content": "A close icon is below the dialog."},
    }, output_limit=limit, output_tokens=120)
    old = runtime.queue_command(run.id, "observe")
    runtime.submit_result("alice", run.device_id, CommandResult(command_id=old.id, run_id=run.id, status="ok",
                          observation=Observation(screen_id="old", package_name="fixture", width=100, height=200)))
    result = call(client, run, "describe_screen", question="Locate the control").json()
    assert result["status"] == "ok"
    assert result["description"] == "A close icon is below the dialog."
    assert result["coordinates"]["screen_id"] == "paired"
    assert "screen paired" in result["screen"] and "screen old" not in result["screen"]
    assert "current Current control" in result["screen"]
    assert result["evidence_id"]
    assert requests[0]["thinking"] == {"type": "disabled"}
    assert requests[0]["max_tokens"] == expected_max
    assert billing.reservations[0][3] == expected_max
    assert len(requests) == len(billing.receipts) == 1
    assert billing.receipts[0][2:] == (899, 120)
    usage = next(event for event in runtime.events("alice", run.id) if event.kind == "usage")
    assert (usage.data["input_tokens"], usage.data["output_tokens"]) == (899, 120)


@pytest.mark.parametrize("pressure", [False, True])
def test_vision_history_compaction_preserves_description_coordinates_and_recent_screens(pressure):
    coordinates = {"screen_id": "screen-vision-old", "screen_width": 1080, "screen_height": 2400,
                   "image_width": 540, "image_height": 1200,
                   "image_to_screen_scale_x": 2.0, "image_to_screen_scale_y": 2.0}
    messages = [{"role": "user", "content": "Preserve task constraints. " * (4500 if pressure else 1)}]
    messages += screen_turn("vision-old", "describe_screen", description="The icon is below the dialog.", coordinates=coordinates)
    messages += screen_turn(1)
    messages += screen_turn("vision-fresh", "describe_screen", description="A fresh visual result.", coordinates={"screen_id": "screen-vision-fresh"})
    messages += screen_turn(3)
    original = copy.deepcopy(messages)
    compacted, metrics = compact_screen_history(messages)
    assert messages == original
    assert compacted[0] == original[0] and compacted[1::2] == original[1::2]
    assert compacted[-6:] == original[-6:]
    result = json.loads(compacted[2]["content"])
    assert result["description"] == "The icon is below the dialog."
    assert result["coordinates"] == coordinates
    assert result["evidence_id"] == "evidence-vision-old" and result["status"] == "ok"
    assert compacted[2]["tool_call_id"] == "call-vision-old"
    if pressure:
        assert metrics["screens_compacted"] == 1
        assert metrics["bytes_saved"] > 10000
        assert "screen screen-vision-old" in result["screen"]
        assert "nvision-old visible text" not in result["screen"]
        assert compact_screen_history(compacted)[0] == compacted
    else:
        assert compacted == original and metrics["screens_compacted"] == 0

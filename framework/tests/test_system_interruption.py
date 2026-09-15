import pytest

from doppel import DoppelRuntime, RuntimeConfig
from doppel.errors import Conflict
from doppel.models import Command, CommandResult, Observation
from doppel.policy import ActionPolicy


@pytest.mark.parametrize("kind", ["recents", "notifications", "quick_settings", "split_screen"])
def test_system_actions_are_explicit_and_respect_approval_mode(kind):
    command = Command(id="c", run_id="r", kind=kind)
    assert ActionPolicy().evaluate("ask", command, None).decision == "approve"
    assert ActionPolicy().evaluate("full", command, None).decision == "allow"


def test_interruption_pauses_and_requires_fresh_screen_before_next_mutation(tmp_path):
    runtime = DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False))
    device = runtime.register_device("alice", "phone", "Phone")
    run = runtime.create_run("alice", device.id, "Read enabled alarms", "full")
    command = runtime.queue_command(run.id, "observe")
    runtime.submit_result("alice", device.id, CommandResult(command_id=command.id, run_id=run.id,
        status="blocked", data={"human_takeover": "interruption", "interruption": "call"}))
    paused = runtime.get_run("alice", run.id)
    assert paused.status == "paused"
    assert paused.requires_fresh_observation
    assert paused.pending_request["manual_only"]
    assert runtime.next_command("alice", device.id) is None
    runtime.resume_run("alice", run.id)
    with pytest.raises(Conflict, match="fresh observation"):
        runtime.queue_command(run.id, "back")
    fresh = runtime.queue_command(run.id, "observe")
    runtime.submit_result("alice", device.id, CommandResult(command_id=fresh.id, run_id=run.id, status="ok",
        observation=Observation(screen_id="new", package_name="clock", width=100, height=200)))
    assert not runtime.get_run("alice", run.id).requires_fresh_observation
    assert runtime.queue_command(run.id, "back").id != command.id

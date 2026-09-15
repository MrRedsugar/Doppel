import asyncio

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import ValidationError

from doppel import DoppelRuntime, RuntimeConfig, create_router
from doppel.errors import Conflict, PermissionDenied
from doppel.models import Command, CommandResult, Node, Observation
from doppel.perception import compact_observation
from doppel.policy import ActionPolicy


CONSENT = "payment-v1:11111111-1111-4111-8111-111111111111"
NEW_CONSENT = "payment-v1:22222222-2222-4222-8222-222222222222"


def screen(label="京东快付", consent=None, *, screen_id="checkout", editable=False, heading=None):
    nodes = [Node(id="target", text=label, clickable=True, editable=editable,
                  long_clickable=True, bounds=[0, 0, 100, 50])]
    if heading:
        nodes.append(Node(id="heading", text=heading, bounds=[0, 50, 100, 100]))
    return Observation(screen_id=screen_id, package_name="test.shop", width=100, height=200,
                       nodes=nodes, payment_consent_id=consent)


@pytest.fixture
def runtime(tmp_path):
    value = DoppelRuntime(RuntimeConfig(data_dir=tmp_path, auto_start=False, command_timeout=2))
    yield value
    value.store.db.close()


def begin(runtime, observation, mode="full"):
    device = runtime.register_device("alice", "payment-phone", "Test phone")
    run = runtime.create_run("alice", device.id, "Complete the ordinary test purchase", mode, ["test.shop"])
    command = runtime.queue_command(run.id, "observe")
    runtime.submit_result("alice", device.id, CommandResult(command_id=command.id, run_id=run.id,
                          status="ok", observation=observation))
    return device, run


def replace_snapshot(runtime, run_id, observation):
    # Simulate a newly synchronized device state at the approval/dispatch boundary.
    with runtime.store.transaction() as db:
        db.execute("UPDATE runs SET observation=? WHERE id=?", (observation.model_dump_json(), run_id))


@pytest.mark.parametrize("invalid", [True, 1, [], "", " ", "ordinary-id", CONSENT.upper(),
                                    CONSENT.replace("payment-v1:", "payment-v2:"), CONSENT + "\n", "x" * 129])
@pytest.mark.parametrize("model", ["observation", "command"])
def test_consent_protocol_rejects_malformed_or_unsupported_generations(invalid, model):
    with pytest.raises(ValidationError):
        if model == "observation":
            screen(consent=invalid)
        else:
            Command(id="c", run_id="r", kind="tap", target="target", screen_id="checkout", payment_consent_id=invalid)


def test_consent_schema_accepts_supported_device_id_and_legacy_disabled_observation():
    observation = screen(consent=CONSENT)
    assert observation.payment_consent_id == CONSENT
    legacy = observation.model_dump(exclude={"payment_consent_id"})
    assert Observation.model_validate(legacy).payment_consent_id is None
    assert screen().payment_consent_id is None
    with pytest.raises(ValidationError):
        Command(id="c", run_id="r", kind="observe", payment_consent_id=CONSENT)


@pytest.mark.parametrize("mode", ["ask", "assist", "full"])
@pytest.mark.parametrize("label", ["京东快付", "快付", "Paynow", "Pay now", "立即支付"])
def test_disabled_payment_is_manual_and_cannot_be_enabled_by_mode(runtime, mode, label):
    device, run = begin(runtime, screen(label), mode)
    command = runtime.queue_command(run.id, "tap", target="target", screen_id="checkout")
    assert command.payment_consent_id is None
    assert runtime.command_result(command.id).status == "blocked"
    assert runtime.get_run("alice", run.id).pending_request["reason"] == "payment"
    assert runtime.next_command("alice", device.id) is None


@pytest.mark.parametrize("mode,decision", [("ask", "approve"), ("assist", "approve"), ("full", "allow")])
def test_enabled_payment_is_host_stamped_and_respects_operation_mode(runtime, mode, decision):
    device, run = begin(runtime, screen(consent=CONSENT), mode)
    command = runtime.queue_command(run.id, "tap", target="target", screen_id="checkout")
    assert command.payment_consent_id == CONSENT
    assert runtime.command_result(command.id) is None
    if decision == "approve":
        assert runtime.get_run("alice", run.id).status == "awaiting_approval"
        assert runtime.next_command("alice", device.id) is None
        runtime.answer("alice", run.id, command.id, approve=True)
    delivered = runtime.next_command("alice", device.id)
    assert delivered.id == command.id and delivered.payment_consent_id == CONSENT


@pytest.mark.parametrize("supplied", [None, CONSENT, NEW_CONSENT, True])
def test_caller_cannot_supply_or_clear_host_only_consent_field(runtime, supplied):
    device, run = begin(runtime, screen(consent=CONSENT))
    with pytest.raises((ValueError, PermissionDenied), match="host|device"):
        runtime.queue_command(run.id, "tap", target="target", screen_id="checkout", payment_consent_id=supplied)
    assert runtime.next_command("alice", device.id) is None


def test_internal_act_rejects_null_consent_field_before_filtering(runtime):
    device, run = begin(runtime, screen(consent=CONSENT))
    app = FastAPI()
    app.include_router(create_router(runtime, lambda: "alice"))
    with TestClient(app) as client:
        response = client.post(f"/v1/internal/runs/{run.id}/tool",
                               headers={"Authorization": "Bearer " + runtime.run_tokens[run.id]},
                               json={"name": "act", "arguments": {"action": "tap", "target": "target",
                                     "screen_id": "checkout", "payment_consent_id": None}})
    assert response.status_code in {403, 422}
    assert "host" in response.json()["detail"].lower() or "device" in response.json()["detail"].lower()
    assert runtime.next_command("alice", device.id) is None


@pytest.mark.parametrize("label", ["Browse menu", "Back", "Orders"])
def test_consent_does_not_stamp_unrelated_controls(runtime, label):
    device, run = begin(runtime, screen(label, CONSENT, heading="收银台"))
    command = runtime.queue_command(run.id, "tap", target="target", screen_id="checkout")
    assert command.payment_consent_id is None
    assert runtime.next_command("alice", device.id).id == command.id


@pytest.mark.parametrize("label", ["", "确认", "Continue"])
def test_ambiguous_checkout_control_gets_stamp_only_with_payment_context(runtime, label):
    device, run = begin(runtime, screen(label, CONSENT, heading="应付总额"))
    command = runtime.queue_command(run.id, "tap", target="target", screen_id="checkout")
    assert runtime.next_command("alice", device.id).payment_consent_id == CONSENT


@pytest.mark.parametrize("label", ["转账", "汇款", "Transfer", "Remittance", "支付密码", "付款验证码",
                                  "Payment OTP", "开通免密支付", "设置自动扣款", "授权自动续费",
                                  "免密支付立即开启", "自动扣款授权", "已开启免密，授权自动扣款",
                                  "关闭自动续费", "取消自动扣款", "已成功开启免密支付，关闭自动续费",
                                  "Enable autopay", "Enable automatic renewal", "Authorize auto-pay",
                                  "Set up automatic payments", "Cancel automatic billing",
                                  "Direct debit", "Standing order", "Recurring payment"])
def test_restricted_financial_actions_remain_manual_with_consent(runtime, label):
    device, run = begin(runtime, screen(label, CONSENT))
    command = runtime.queue_command(run.id, "tap", target="target", screen_id="checkout")
    assert runtime.command_result(command.id).status == "blocked"
    assert runtime.next_command("alice", device.id) is None


@pytest.mark.parametrize("label", ["免密支付 · 已开启", "已开通免密支付", "自动扣款已授权", "自动续费已设置",
                                  "已成功开启免密支付", "免密支付已成功开通", "已成功关闭自动续费",
                                  "自动扣款已成功取消"])
def test_existing_payment_mandate_status_does_not_block_ordinary_consented_payment(runtime, label):
    device, run = begin(runtime, screen(consent=CONSENT, heading=label))
    command = runtime.queue_command(run.id, "tap", target="target", screen_id="checkout")
    assert command.payment_consent_id == CONSENT
    assert runtime.next_command("alice", device.id).id == command.id


@pytest.mark.parametrize("label", ["返回", "取消", "关闭", "Back", "Cancel", "Close"])
def test_manual_financial_context_allows_exit_controls_without_payment_consent(runtime, label):
    device, run = begin(runtime, screen(label, heading="支付密码"))
    command = runtime.queue_command(run.id, "tap", target="target", screen_id="checkout")
    assert command.payment_consent_id is None
    assert runtime.next_command("alice", device.id).id == command.id


@pytest.mark.parametrize("kind", ["tap", "type", "login_code"])
def test_split_checkout_and_code_input_context_requires_manual_handoff(runtime, kind):
    device, run = begin(runtime, screen("验证码", CONSENT, editable=True, heading="收银台"))
    fields = {"text": "synthetic"} if kind == "type" else {"package_name": "test.shop"} if kind == "login_code" else {}
    command = runtime.queue_command(run.id, kind, target="target", screen_id="checkout", **fields)
    assert runtime.command_result(command.id).status == "blocked"
    assert runtime.next_command("alice", device.id) is None


def test_redacted_payment_password_target_uses_payment_resume_flow(runtime):
    observation = screen("", CONSENT, editable=True, heading="收银台")
    observation.nodes[0].password = True
    device, run = begin(runtime, observation)
    command = runtime.queue_command(run.id, "tap", target="target", screen_id="checkout")
    assert runtime.command_result(command.id).status == "blocked"
    assert runtime.next_command("alice", device.id) is None
    assert runtime.get_run("alice", run.id).pending_request["reason"] == "payment"
    runtime.resume_run("alice", run.id)
    assert runtime.observation(run.id) is None
    assert runtime.get_run("alice", run.id).requires_fresh_observation


@pytest.mark.parametrize("kind", ["long_press", "type", "login_phone", "login_code"])
def test_payment_consent_authorizes_only_tap(runtime, kind):
    device, run = begin(runtime, screen("支付", CONSENT, editable=True))
    fields = {"text": "synthetic"} if kind == "type" else {"package_name": "test.shop"} if kind.startswith("login_") else {}
    command = runtime.queue_command(run.id, kind, target="target", screen_id="checkout", **fields)
    assert runtime.command_result(command.id).status == "blocked"
    assert runtime.next_command("alice", device.id) is None


@pytest.mark.parametrize("new_consent,new_screen", [(None, "checkout"), (NEW_CONSENT, "checkout"), (CONSENT, "changed")])
def test_approval_cannot_reuse_revoked_or_stale_payment_authority(runtime, new_consent, new_screen):
    device, run = begin(runtime, screen(consent=CONSENT), "assist")
    command = runtime.queue_command(run.id, "tap", target="target", screen_id="checkout")
    replace_snapshot(runtime, run.id, screen(consent=new_consent, screen_id=new_screen))
    with pytest.raises(Conflict):
        runtime.answer("alice", run.id, command.id, approve=True)
    assert runtime.next_command("alice", device.id) is None


@pytest.mark.parametrize("new_consent", [None, NEW_CONSENT])
def test_queued_payment_is_withheld_when_observed_consent_changes(runtime, new_consent):
    device, run = begin(runtime, screen(consent=CONSENT))
    command = runtime.queue_command(run.id, "tap", target="target", screen_id="checkout")
    replace_snapshot(runtime, run.id, screen(consent=new_consent))
    assert runtime.next_command("alice", device.id) is None
    assert runtime.command_result(command.id).status == "blocked"
    assert runtime.get_run("alice", run.id).pending_request["reason"] == "payment"


def test_stamped_command_cannot_be_reused_for_nonpayment_target():
    command = Command(id="c", run_id="r", kind="tap", target="target", screen_id="checkout", payment_consent_id=CONSENT)
    assert ActionPolicy().evaluate("full", command, screen("Browse menu", CONSENT)).decision != "allow"


def test_compact_model_observation_reports_setting_without_consent_generation():
    enabled = compact_observation(screen(consent=CONSENT))
    disabled = compact_observation(screen())
    assert "delegated_payment=enabled" in enabled
    assert "delegated_payment=disabled" in disabled
    assert CONSENT not in enabled and "payment-v1:" not in enabled


def test_checkable_payment_never_returns_cached_success(runtime):
    observation = screen(consent=CONSENT)
    observation.nodes[0].checkable = True
    observation.nodes[0].checked = True
    device, run = begin(runtime, observation)
    command = runtime.queue_command(run.id, "tap", target="target", screen_id="checkout", desired_checked=True)
    assert runtime.command_result(command.id) is None
    assert runtime.next_command("alice", device.id).payment_consent_id == CONSENT
    assert not any(event.kind == "evidence" for event in runtime.events("alice", run.id))


async def test_device_payment_handoff_requires_resume_and_fresh_observation_without_replay(runtime):
    device, run = begin(runtime, screen("Browse menu"))
    task = asyncio.create_task(runtime.perform(run.id, "tap", target="target", screen_id="checkout"))
    try:
        async with asyncio.timeout(2):
            while (command := runtime.next_command("alice", device.id)) is None:
                await asyncio.sleep(0.01)
        runtime.submit_result("alice", device.id, CommandResult(command_id=command.id, run_id=run.id,
                              status="blocked", observation=screen(), data={"human_takeover": "payment"}))
        paused = runtime.get_run("alice", run.id)
        assert paused.status == "paused" and paused.pending_request["reason"] == "payment"
        assert "登录" not in paused.message
        runtime.resume_run("alice", run.id)
        assert runtime.observation(run.id) is None and runtime.get_run("alice", run.id).requires_fresh_observation
        async with asyncio.timeout(2):
            while (refresh := runtime.next_command("alice", device.id)) is None:
                await asyncio.sleep(0.01)
        assert refresh.kind == "observe"
        runtime.submit_result("alice", device.id, CommandResult(command_id=refresh.id, run_id=run.id,
                              status="ok", observation=screen("Order status", screen_id="after-payment")))
        result = await asyncio.wait_for(task, 2)
        assert result.status == "blocked" and result.data == {"human_takeover": "payment", "resumed": True}
        assert result.observation.screen_id == "after-payment"
        assert runtime.next_command("alice", device.id) is None
    finally:
        runtime.cancel_run("alice", run.id)
        await asyncio.gather(task, return_exceptions=True)

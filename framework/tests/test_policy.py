import pytest

from doppel.models import Command, Node, Observation
from doppel.policy import ActionPolicy


def screen(text="发送", password=False):
    return Observation(screen_id="s1", package_name="test.app", width=100, height=100, nodes=[
        Node(id="n1", text=text, role="button", bounds=[0, 0, 100, 50], clickable=True, password=password),
    ])


@pytest.mark.parametrize("mode", ["ask", "assist", "full"])
def test_payment_cannot_be_approved_in_any_mode(mode):
    command = Command(id="c1", run_id="r1", kind="tap", screen_id="s1", target="n1")
    assert ActionPolicy().evaluate(mode, command, screen("确认支付 ¥28.00")).decision == "manual"


def test_full_access_still_rejects_stale_target():
    command = Command(id="c1", run_id="r1", kind="tap", screen_id="old", target="n1")
    assert ActionPolicy().evaluate("full", command, screen()).decision == "deny"


def test_approval_mode_gates_send_but_full_mode_executes_current_nonpayment_target():
    command = Command(id="c1", run_id="r1", kind="tap", screen_id="s1", target="n1")
    policy = ActionPolicy()
    assert policy.evaluate("ask", command, screen()).decision == "approve"
    assert policy.evaluate("assist", command, screen()).decision == "approve"
    assert policy.evaluate("full", command, screen()).decision == "allow"


def test_password_text_is_not_sent_to_model_and_cannot_be_typed_automatically():
    observation = screen("123456", password=True)
    command = Command(id="c1", run_id="r1", kind="type", screen_id="s1", target="n1", text="secret")
    assert ActionPolicy().evaluate("full", command, observation).decision == "manual"


@pytest.mark.parametrize("mode", ["ask", "assist", "full"])
@pytest.mark.parametrize("label", ["Pay", "Pay now", "Paynow", "Pay $28.00", "Make payment", "确认支付", "立即付款", "去付款"])
def test_payment_labels_always_require_manual_handoff(mode, label):
    command = Command(id="c", run_id="r", kind="tap", screen_id="s1", target="n1")
    assert ActionPolicy().evaluate(mode, command, screen(label)).decision == "manual"


@pytest.mark.parametrize("mode", ["ask", "assist", "full"])
@pytest.mark.parametrize("label", ["Confirm", "Continue", "确认", "确定", ""])
@pytest.mark.parametrize("heading", ["Confirm payment", "收银台", "支付金额 ¥28.00"])
def test_ambiguous_confirmation_on_payment_screen_is_manual(mode, label, heading):
    observation = screen(label)
    observation.nodes.append(Node(id="heading", text=heading, bounds=[0, 50, 100, 80]))
    command = Command(id="c", run_id="r", kind="tap", screen_id="s1", target="n1")
    assert ActionPolicy().evaluate(mode, command, observation).decision == "manual"


@pytest.mark.parametrize("mode", ["ask", "assist", "full"])
@pytest.mark.parametrize("label", ["Browse menu", "Back", "Confirm"])
def test_payment_promotion_does_not_block_ordinary_navigation(mode, label):
    observation = screen(label)
    observation.nodes.append(Node(id="promotion", text="微信支付优惠满减", bounds=[0, 50, 100, 80]))
    command = Command(id="c", run_id="r", kind="tap", screen_id="s1", target="n1")
    assert ActionPolicy().evaluate(mode, command, observation).decision == ("approve" if mode == "ask" else "allow")
    scroll = Command(id="scroll", run_id="r", kind="scroll", direction="down")
    assert ActionPolicy().evaluate(mode, scroll, observation).decision == "allow"


def test_navigation_away_from_payment_screen_remains_available():
    observation = screen("Back")
    observation.nodes.append(Node(id="heading", text="Confirm payment", bounds=[0, 50, 100, 80]))
    command = Command(id="c", run_id="r", kind="tap", screen_id="s1", target="n1")
    assert ActionPolicy().evaluate("full", command, observation).decision == "allow"

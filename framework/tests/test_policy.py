import pytest

from doppel.models import Command, Node, Observation
from doppel.policy import ActionPolicy


def screen(text="发送", password=False):
    return Observation(screen_id="s1", package_name="test.app", width=100, height=100, nodes=[
        Node(id="n1", text=text, role="button", bounds=[0, 0, 100, 50], clickable=True, password=password),
    ])


@pytest.mark.parametrize("mode", ["ask", "assist", "full"])
def test_declared_payment_without_consent_cannot_be_approved(mode):
    command = Command(id="c1", run_id="r1", kind="pay", screen_id="s1", target="n1")
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
    assert observation.nodes[0].text == ""
    command = Command(id="c1", run_id="r1", kind="type", screen_id="s1", target="n1", text="secret")
    assert ActionPolicy().evaluate("full", command, observation).decision == "manual"


@pytest.mark.parametrize("mode", ["ask", "assist", "full"])
@pytest.mark.parametrize("label", ["待付款", "全部订单", "Confirm", "继续", "支付历史", "Pay now"])
@pytest.mark.parametrize("heading", ["收银台", "支付金额 ¥28.00", "开通免密支付", "支付密码"])
def test_page_or_target_words_cannot_reclassify_ai_navigation_as_payment(mode, label, heading):
    observation = screen(label)
    observation.nodes.append(Node(id="heading", text=heading, bounds=[0, 50, 100, 80]))
    command = Command(id="c", run_id="r", kind="tap", screen_id="s1", target="n1")
    assert ActionPolicy().evaluate(mode, command, observation).decision == ("approve" if mode == "ask" else "allow")
    scroll = Command(id="scroll", run_id="r", kind="scroll", direction="down")
    assert ActionPolicy().evaluate(mode, scroll, observation).decision == "allow"

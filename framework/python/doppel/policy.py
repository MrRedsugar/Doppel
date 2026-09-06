from dataclasses import dataclass
import re

from .models import Command, Observation


@dataclass(frozen=True)
class Verdict:
    decision: str
    reason: str


class ActionPolicy:
    PAYMENT_ACTION = re.compile(r"支付|付款|扣款|(?<![a-z])(?:pay(?:\s*now)?|payment)(?![a-z])")
    PAYMENT_CONTEXT = re.compile(r"收银台|支付金额|付款金额|确认支付|确认付款|待支付|\b(?:confirm payment|payment confirmation|payment amount|amount due|total to pay|checkout)\b")
    CONFIRM_ACTION = re.compile(r"^(?:confirm|continue|ok|submit|proceed)\b|^(?:确认|确定|继续|提交|完成)")
    SENSITIVE_WORDS = ("发送", "删除", "提交订单", "确认下单", "send", "delete", "submit order")

    def evaluate(self, mode: str, command: Command, observation: Observation | None) -> Verdict:
        if command.kind in {"observe", "screenshot", "wait"}:
            return Verdict("allow", "Read-only observation")
        node = None
        if command.kind in {"tap", "type"} or command.target:
            if observation is None or command.screen_id != observation.screen_id:
                return Verdict("deny", "Screen changed; observe again before acting")
            node = next((n for n in observation.nodes if n.id == command.target), None)
            if node is None or not node.enabled:
                return Verdict("deny", "Target missing or disabled")
            label = f"{node.text} {node.description} {node.resource_id}".lower()
            if node.password:
                return Verdict("manual", "Sensitive input requires manual interaction")
            if command.kind == "tap":
                visible_label = f"{node.text} {node.description}".lower().strip()
                ambiguous = not visible_label or self.CONFIRM_ACTION.search(visible_label)
                payment_screen = ambiguous and any(
                    self.PAYMENT_CONTEXT.search(f"{n.text} {n.description}".lower())
                    for n in observation.nodes if not n.password
                )
                if self.PAYMENT_ACTION.search(label) or payment_screen:
                    return Verdict("manual", "请由用户在手机上亲自完成支付操作")
            if command.kind == "type" and not node.editable:
                return Verdict("deny", "Target is not editable")
            if command.kind == "tap" and not node.clickable:
                return Verdict("deny", "Target is not clickable")
        if mode == "ask" and command.kind in {"tap", "type", "launch", "open_document"}:
            return Verdict("approve", "该操作需要你的批准")
        if mode == "assist" and node is not None and command.kind == "tap":
            label = f"{node.text} {node.description}".lower().strip()
            if not label or any(word in label for word in self.SENSITIVE_WORDS):
                return Verdict("approve", "请确认这次操作的目标与内容")
        return Verdict("allow", "Within current execution mode")

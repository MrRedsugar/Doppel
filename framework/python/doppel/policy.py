from dataclasses import dataclass
import re

from .models import Command, Observation


@dataclass(frozen=True)
class Verdict:
    decision: str
    reason: str
    human_takeover: str | None = None


class ActionPolicy:
    SENSITIVE_WORDS = ("发送", "删除", "提交订单", "确认下单", "send", "delete", "submit order")
    VERIFICATION_HEADING = re.compile(r"^(?:安全验证|人机验证|图形验证|滑块验证|验证码安全校验|security verification|human verification|verify (?:that )?you are (?:a )?human|i['’]?m not a robot|captcha|recaptcha|hcaptcha)[.!。！]?$", re.IGNORECASE)
    VERIFICATION_INSTRUCTION = re.compile(r"(?:请|需要).{0,12}(?:完成|进行).{0,12}(?:人机|安全)验证|请验证[您你]是真人|(?:拖动|滑动).{0,12}滑块.{0,20}(?:验证|拼图)|(?:drag|slide).{0,24}slider.{0,40}(?:verif|puzzle)|(?:select|click) all (?:images|squares) (?:with|containing)|^verify (?:that )?you are (?:a )?human[.!]?$|^i['’]?m not a robot[.!]?$", re.IGNORECASE)

    def requires_verification(self, observation: Observation | None) -> bool:
        if observation is None:
            return False
        return any(self.VERIFICATION_INSTRUCTION.search(label.strip()) or
                   (not (node.clickable or node.long_clickable or node.editable) and self.VERIFICATION_HEADING.fullmatch(label.strip()))
                   for node in observation.nodes if not node.password
                   for label in (node.text, node.description))

    def evaluate(self, mode: str, command: Command, observation: Observation | None) -> Verdict:
        if command.kind in {"observe", "screenshot", "wait"}:
            return Verdict("allow", "Read-only observation")
        if self.requires_verification(observation):
            return Verdict("manual", "请在手机上手动完成安全验证，再点击继续。", "verification")
        node = None
        payment = command.kind == "pay"
        if command.payment_consent_id and not payment:
            return Verdict("manual", "旧付款指令已失效，请重新观察后决策", "payment")
        if payment and (mode != "full" or observation is None or not command.payment_consent_id or
                        command.payment_consent_id != observation.payment_consent_id):
            return Verdict("manual", "付款需要完全访问权限和有效的付款授权，请手动处理", "payment")
        if command.kind in {"tap", "pay", "long_press", "type", "login_phone", "login_code"} or command.target:
            if observation is None or command.screen_id != observation.screen_id:
                return Verdict("deny", "Screen changed; observe again before acting")
            node = next((n for n in observation.nodes if n.id == command.target), None)
            if node is None or not node.enabled:
                return Verdict("deny", "Target missing or disabled")
            if node.password:
                return Verdict("manual", "Sensitive input requires manual interaction", "payment" if payment else None)
            if command.kind in {"type", "login_phone", "login_code"} and not node.editable:
                return Verdict("deny", "Target is not editable")
            if command.kind in {"login_phone", "login_code"} and command.package_name != observation.package_name:
                return Verdict("deny", "Local login is scoped to the current application")
            if command.kind in {"tap", "pay"} and not node.clickable:
                return Verdict("deny", "Target is not clickable")
            if command.kind == "long_press" and not node.long_clickable:
                return Verdict("deny", "Target does not support long press")
        if payment:
            return Verdict("allow", "本次付款已取得完全访问与本机付款授权")
        if mode == "ask" and command.kind in {"tap", "pay", "long_press", "type", "login_phone", "login_code", "launch", "open_document", "recents", "notifications", "quick_settings", "split_screen"}:
            return Verdict("approve", "该操作需要你的批准")
        if mode == "assist" and node is not None and command.kind in {"tap", "long_press"}:
            label = f"{node.text} {node.description}".lower().strip()
            if not label or any(word in label for word in self.SENSITIVE_WORDS):
                return Verdict("approve", "请确认这次操作的目标与内容")
        return Verdict("allow", "Within current execution mode")

from dataclasses import dataclass
import re

from .models import Command, Observation


@dataclass(frozen=True)
class Verdict:
    decision: str
    reason: str
    human_takeover: str | None = None


class ActionPolicy:
    PAYMENT_ACTION = re.compile(r"支付|付款|扣款|快付|免密|转账|汇款|(?<![a-z])(?:pay(?:\s*now)?|payment|purchase|checkout|transfer)(?![a-z])", re.IGNORECASE)
    PAYMENT_CONTEXT = re.compile(r"收银台|应付总额|支付金额|付款金额|确认支付|确认付款|待支付|\b(?:cashier|confirm payment|payment confirmation|payment amount|amount due|total to pay|checkout)\b", re.IGNORECASE)
    MANUAL_FINANCIAL = re.compile(r"转账|汇款|\btransfer\b|remittance|支付密码|付款密码|支付验证码|付款验证码|(?:开通|开启|设置|授权|关闭|取消).{0,10}(?:免密|自动扣款|自动续费)|(?:免密|自动扣款|自动续费).{0,10}(?:开通|开启|设置|授权|关闭|取消)|payment.{0,12}(?:password|passcode|verification code|otp)|direct debit|standing order|recurring payment", re.IGNORECASE)
    ENGLISH_MANDATE = re.compile(r"\b(?:enable|activate|authorize|set\s*up|disable|cancel)\b.{0,20}\bauto(?:matic)?[\s-]*(?:pay(?:ment)?s?|renew(?:al)?|debit|billing)\b|\bauto(?:matic)?[\s-]*(?:pay(?:ment)?s?|renew(?:al)?|debit|billing)\b.{0,20}\b(?:enable|activate|authorize|set\s*up|disable|cancel)\b", re.IGNORECASE)
    FINANCIAL_STATUS = re.compile(r"已(?:成功)?(?:开通|开启|设置|授权|关闭|取消)")
    LEAVE_FINANCIAL = re.compile(r"^(?:返回|取消|关闭)|^(?:back|cancel|close)\b", re.IGNORECASE)
    PAYMENT_CREDENTIAL = re.compile(r"密码|口令|验证码|校验码|动态码|\b(?:password|passcode|pin|otp)\b|one.?time|verification.?code", re.IGNORECASE)
    CONFIRM_ACTION = re.compile(r"^(?:confirm|continue|ok|submit|proceed)\b|^(?:确认|确定|继续|提交|完成)")
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

    def payment_target(self, node, observation: Observation) -> bool:
        visible_label = f"{node.text} {node.description}".strip()
        label = f"{visible_label} {node.resource_id}"
        if self.PAYMENT_ACTION.search(label):
            return True
        return bool((not visible_label or self.CONFIRM_ACTION.search(visible_label.lower())) and any(
            self.PAYMENT_CONTEXT.search(f"{other.text} {other.description}")
            for other in observation.nodes if not other.password))

    def manual_financial(self, label: str) -> bool:
        return bool(self.MANUAL_FINANCIAL.search(self.FINANCIAL_STATUS.sub("状态生效", label)) or self.ENGLISH_MANDATE.search(label))

    def requires_manual_financial(self, observation: Observation) -> bool:
        if any(self.manual_financial(f"{node.text} {node.description} {node.resource_id}")
               for node in observation.nodes if not node.password):
            return True
        payment_context = any(self.PAYMENT_CONTEXT.search(f"{node.text} {node.description}")
                              for node in observation.nodes if not node.password)
        return payment_context and any(node.password or node.editable and self.PAYMENT_CREDENTIAL.search(
            f"{node.text} {node.description} {node.resource_id}") for node in observation.nodes)

    def evaluate(self, mode: str, command: Command, observation: Observation | None) -> Verdict:
        if command.kind in {"observe", "screenshot", "wait"}:
            return Verdict("allow", "Read-only observation")
        if self.requires_verification(observation):
            return Verdict("manual", "请在手机上手动完成安全验证，再点击继续。", "verification")
        node = None
        delegated_payment = False
        if command.kind in {"tap", "long_press", "type", "login_phone", "login_code"} or command.target:
            if observation is None or command.screen_id != observation.screen_id:
                return Verdict("deny", "Screen changed; observe again before acting")
            node = next((n for n in observation.nodes if n.id == command.target), None)
            if node is None or not node.enabled:
                return Verdict("deny", "Target missing or disabled")
            if node.password:
                if self.requires_manual_financial(observation):
                    return Verdict("manual", "支付验证需由用户操作", "payment")
                return Verdict("manual", "Sensitive input requires manual interaction")
            if command.kind in {"tap", "long_press", "type", "login_phone", "login_code"}:
                label = f"{node.text} {node.description}".strip()
                leaving = command.kind == "tap" and self.LEAVE_FINANCIAL.search(label) and not self.manual_financial(f"{label} {node.resource_id}")
                if self.requires_manual_financial(observation) and not leaving:
                    return Verdict("manual", "支付验证、转账及长期扣款授权需由用户操作", "payment")
                payment = self.payment_target(node, observation)
                if command.payment_consent_id and not payment:
                    return Verdict("manual", "付款授权与当前操作不匹配，请重新观察", "payment")
                if payment:
                    if command.kind != "tap" or not command.payment_consent_id or command.payment_consent_id != observation.payment_consent_id:
                        return Verdict("manual", "付款授权未开启、已撤销或与当前操作不匹配，请由用户手动处理", "payment")
                    delegated_payment = True
            if command.kind in {"type", "login_phone", "login_code"} and not node.editable:
                return Verdict("deny", "Target is not editable")
            if command.kind in {"login_phone", "login_code"} and command.package_name != observation.package_name:
                return Verdict("deny", "Local login is scoped to the current application")
            if command.kind == "tap" and not node.clickable:
                return Verdict("deny", "Target is not clickable")
            if command.kind == "long_press" and not node.long_clickable:
                return Verdict("deny", "Target does not support long press")
        if delegated_payment:
            return Verdict("allow" if mode == "full" else "approve", "当前付款操作将产生真实扣费，请确认本次操作")
        if mode == "ask" and command.kind in {"tap", "long_press", "type", "login_phone", "login_code", "launch", "open_document", "recents", "notifications", "quick_settings", "split_screen"}:
            return Verdict("approve", "该操作需要你的批准")
        if mode == "assist" and node is not None and command.kind in {"tap", "long_press"}:
            label = f"{node.text} {node.description}".lower().strip()
            if not label or any(word in label for word in self.SENSITIVE_WORDS):
                return Verdict("approve", "请确认这次操作的目标与内容")
        return Verdict("allow", "Within current execution mode")

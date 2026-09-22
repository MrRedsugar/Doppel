package dev.doppel.sdk

import java.security.MessageDigest

data class Target(val label: String, val password: Boolean, val enabled: Boolean)

object Policy {
    private val payment = Regex("支付|付款|扣款|快付|免密|转账|汇款|\\bpay(?:now)?\\b|\\bpayment\\b|purchase|checkout|transfer", RegexOption.IGNORE_CASE)
    private val manualFinancial = Regex("转账|汇款|\\btransfer\\b|remittance|支付密码|付款密码|支付验证码|付款验证码|(?:开通|开启|设置|授权|关闭|取消).{0,10}(?:免密|自动扣款|自动续费)|(?:免密|自动扣款|自动续费).{0,10}(?:开通|开启|设置|授权|关闭|取消)|payment.{0,12}(?:password|passcode|verification code|otp)|direct debit|standing order|recurring payment", RegexOption.IGNORE_CASE)
    private val verificationHeading = Regex("(?:人机验证|安全验证|安全校验|滑块验证|点选验证|行为验证|图形验证|图形验证码|图片验证码|captcha|recaptcha|hcaptcha|security verification|security check|human verification)[.!。！:：]?", RegexOption.IGNORE_CASE)
    private val verificationInstruction = Regex("(?:请|需要).{0,12}(?:完成|进行).{0,12}(?:安全|人机)验证|请验证[您你]是真人|(?:拖动|滑动).{0,12}滑块.{0,20}(?:验证|拼图)|完成拼图|(?:drag|slide).{0,24}slider.{0,40}(?:verif|puzzle)|(?:select|click) all (?:images|squares) (?:with|containing)|^verify (?:that )?you are (?:a )?human[.!]?$|^i['’]?m not a robot[.!]?$|complete.{0,12}(?:security verification|captcha)", RegexOption.IGNORE_CASE)
    private val registrationTarget = Regex("(?:注册|立即注册|免费注册|新用户|sign\\s*up|register)", RegexOption.IGNORE_CASE)
    private val registrationPage = Regex("(?:注册账号|注册帐户|创建账号|创建帐户|注册新账号|注册新帐户|新用户注册|create\\s+(?:a\\s+)?(?:new\\s+)?account|sign\\s*up|register\\s+(?:an?\\s+)?account)", RegexOption.IGNORE_CASE)
    fun sensitive(label: String) = payment.containsMatchIn(label)
    fun manualFinancial(label: String): Boolean {
        val normalized = Regex("已(?:成功)?(?:开通|开启|授权|关闭|设置|取消)").replace(label, "状态生效")
        val mandate = "auto(?:matic)?[\\s-]*(?:pay(?:ment)?s?|renew(?:al)?|debit|billing)"
        val action = "(?:enable|activate|authorize|set\\s*up|disable|cancel)"
        return manualFinancial.containsMatchIn(normalized) || Regex("\\b$action\\b.{0,20}\\b$mandate\\b|\\b$mandate\\b.{0,20}\\b$action\\b", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
    }
    fun financialCredential(label: String) = Regex("密码|口令|验证码|校验码|动态码|\\b(?:password|passcode|pin|otp)\\b|one.?time|verification.?code", RegexOption.IGNORE_CASE).containsMatchIn(label)
    fun verificationRequired(labels: Iterable<String>) = labels.any { verificationLabel(it, false) }
    /** Strict product rule: the agent may never create a new third-party account. */
    fun registrationTarget(label: String): Boolean = registrationTarget.containsMatchIn(label.take(4000))
    fun registrationContext(labels: Iterable<String>): Boolean = registrationPage.containsMatchIn(labels.joinToString(" ").take(12000))
    fun verificationLabel(label: String, interactive: Boolean): Boolean {
        val text = label.take(4000).trim()
        return verificationInstruction.containsMatchIn(text) || !interactive && verificationHeading.matches(text)
    }
    fun codeInput(label: String) = Regex("验证码|校验码|动态码|one.?time|otp|verification.?code", RegexOption.IGNORE_CASE).containsMatchIn(label)
    fun phoneInput(label: String) = Regex("手机|电话|(?:^|[^a-z])(?:mobile|phone|telephone)(?:[^a-z]|$)", RegexOption.IGNORE_CASE).containsMatchIn(label)
    fun redactLoginLabel(label: String) = Regex("\\p{Nd}").replace(label, "*")
    /** Payment is an explicit planner action; the host checks authority, never page words. */
    fun canPay(mode: String, paymentAuthorized: Boolean, packageName: String) =
        mode == "full" && paymentAuthorized && packageName.isNotBlank() && !packageName.startsWith("window:")
    fun validate(kind: String, expected: String?, actual: String, target: Target?, mode: String = "assist"): String {
        if (mode !in setOf("ask", "assist", "full")) return "blocked"
        if (kind in setOf("tap", "long_press", "type", "login_phone", "login_code", "login_username", "login_password", "scroll")) {
            if (expected.isNullOrBlank() || expected != actual) return "stale"
            if (target == null) return "stale"
            if (target.password && kind != "login_password" && !(kind == "login_code" && codeInput(target.label)) || !target.enabled) return "blocked"
            // Credential field restrictions do not classify ordinary navigation as a payment.
            if (kind in setOf("login_phone", "login_code", "login_username", "login_password") && manualFinancial(target.label)) return "blocked"
        }
        return "ok"
    }
    fun hash(content: String): String = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

class CommandLedger(private val read: (String) -> String?, private val write: (String, String) -> Boolean) {
    @Synchronized fun cached(id: String) = read(id)
    @Synchronized fun claim(id: String, uncertainResult: String): Boolean = read(id) == null && write(id, uncertainResult)
    @Synchronized fun finish(id: String, result: String): Boolean = write(id, result)
}

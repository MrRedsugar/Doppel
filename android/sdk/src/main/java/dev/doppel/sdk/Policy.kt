package dev.doppel.sdk

import java.security.MessageDigest

data class Target(val label: String, val password: Boolean, val enabled: Boolean)

object Policy {
    private val payment = Regex("支付|付款|扣款|免密|转账|汇款|\\bpay\\b|\\bpayment\\b|purchase|checkout|transfer", RegexOption.IGNORE_CASE)
    private val verificationHeading = Regex("(?:人机验证|安全验证|安全校验|滑块验证|点选验证|行为验证|图形验证|图形验证码|图片验证码|captcha|recaptcha|hcaptcha|security verification|security check|human verification)[.!。！:：]?", RegexOption.IGNORE_CASE)
    private val verificationInstruction = Regex("(?:请|需要).{0,12}(?:完成|进行).{0,12}(?:安全|人机)验证|请验证[您你]是真人|(?:拖动|滑动).{0,12}滑块.{0,20}(?:验证|拼图)|完成拼图|(?:drag|slide).{0,24}slider.{0,40}(?:verif|puzzle)|(?:select|click) all (?:images|squares) (?:with|containing)|^verify (?:that )?you are (?:a )?human[.!]?$|^i['’]?m not a robot[.!]?$|complete.{0,12}(?:security verification|captcha)", RegexOption.IGNORE_CASE)
    fun sensitive(label: String) = payment.containsMatchIn(label)
    fun verificationRequired(labels: Iterable<String>) = labels.any { verificationLabel(it, false) }
    fun verificationLabel(label: String, interactive: Boolean): Boolean {
        val text = label.take(4000).trim()
        return verificationInstruction.containsMatchIn(text) || !interactive && verificationHeading.matches(text)
    }
    fun codeInput(label: String) = Regex("验证码|校验码|动态码|one.?time|otp|verification.?code", RegexOption.IGNORE_CASE).containsMatchIn(label)
    fun phoneInput(label: String) = Regex("手机|电话|(?:^|[^a-z])(?:mobile|phone|telephone)(?:[^a-z]|$)", RegexOption.IGNORE_CASE).containsMatchIn(label)
    fun redactLoginLabel(label: String) = Regex("\\p{Nd}").replace(label, "*")
    fun validate(kind: String, expected: String?, actual: String, target: Target?, mode: String = "assist"): String {
        if (mode !in setOf("ask", "assist", "full")) return "blocked"
        if (kind in setOf("tap", "long_press", "type", "login_phone", "login_code", "scroll")) {
            if (expected.isNullOrBlank() || expected != actual) return "stale"
            if (target == null) return "stale"
            if (target.password || (kind != "scroll" && sensitive(target.label)) || !target.enabled) return "blocked"
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

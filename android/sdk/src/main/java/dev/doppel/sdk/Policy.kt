package dev.doppel.sdk

import java.security.MessageDigest

data class Target(val label: String, val password: Boolean, val enabled: Boolean)

object Policy {
    private val payment = Regex("支付|付款|扣款|免密|转账|汇款|\\bpay\\b|\\bpayment\\b|purchase|checkout|transfer", RegexOption.IGNORE_CASE)
    fun sensitive(label: String) = payment.containsMatchIn(label)
    fun validate(kind: String, expected: String?, actual: String, target: Target?, mode: String = "assist"): String {
        if (mode !in setOf("ask", "assist", "full")) return "blocked"
        if (kind in setOf("tap", "type", "scroll")) {
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

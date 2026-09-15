package dev.doppel.sdk

/** An in-memory, one-use capability for a single app's login notification. */
class LoginSession(private val clock: () -> Long = System::currentTimeMillis) {
    private var app = ""
    private var run = ""
    private var signature = ""
    private var started = 0L
    private var expires = 0L
    private var code: String? = null
    private var consumed = false
    private val privateValues = linkedMapOf<String, Long>()

    @Synchronized fun begin(packageName: String, runId: String, phone: String, marker: String) {
        clear()
        app = packageName; run = runId; signature = marker
        started = clock(); expires = started + 300_000
        privateValues[phone] = maxOf(privateValues[phone] ?: 0, started + 600_000)
    }

    @Synchronized fun active(): Boolean = app.isNotEmpty() && clock() < expires

    internal data class Readiness(val state: String, val expiresInMs: Long) {
        val active get() = state != "inactive"
        val codeReady get() = state == "ready"
    }
    @Synchronized internal fun readiness(packageName: String, runId: String): Readiness =
        if (runId.isBlank() || packageName != app || runId != run || !active()) Readiness("inactive", 0)
        else Readiness(if (consumed) "consumed" else if (code == null) "waiting" else "ready", (expires - clock()).coerceAtLeast(0))

    @Synchronized fun receive(source: String, smsPackage: String, foreground: String, message: String, postedAt: Long): Boolean {
        if (!active() || consumed || source != smsPackage || smsPackage.isBlank() || foreground != app || postedAt < started || postedAt > clock() + 5000) return false
        if (message.length > 4096 || !Regex("(?i)(验证码|校验码|动态码|\\bcode\\b|\\bOTP\\b)").containsMatchIn(message)) return false
        if (Regex("(?i)支付|付款|转账|扣款|交易|银行卡|payment|transfer|transaction|purchase").containsMatchIn(message)) return false
        val marker = Regex("(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(signature) + "(?![\\p{L}\\p{N}])")
        if (!marker.containsMatchIn(message)) return false
        val candidates = Regex("(?<![0-9])[0-9]{4,8}(?![0-9])").findAll(message).map { it.value }.toSet()
        if (candidates.size != 1) return false
        code = candidates.single()
        privateValues[code!!] = maxOf(privateValues[code!!] ?: 0, clock() + 600_000)
        return true
    }

    @Synchronized fun consume(packageName: String, runId: String): String? {
        if (!active() || consumed || packageName != app || runId != run) return null
        val value = code ?: return null
        consumed = true; code = null
        protectPassword(value)
        return value
    }

    @Synchronized fun redact(packageName: String, value: String): String {
        // Mask also on app transitions: notifications and confirmation screens may echo values.
        privateValues.entries.removeAll { it.value < clock() }
        var output = value
        for (secret in privateValues.keys.sortedByDescending { it.length }) output = output.replace(secret, "[private]")
        return output
    }

    /** A filled password may remain visible after the OTP timeout or a task ends. Never expire its mask. */
    @Synchronized internal fun protectPassword(value: String) {
        if (value.isNotEmpty()) privateValues[value] = Long.MAX_VALUE
    }

    @Synchronized internal fun containsPrivateValue(value: String): Boolean =
        value.isNotEmpty() && privateValues.any { (secret, until) -> secret.isNotEmpty() && until >= clock() && value.contains(secret) }

    @Synchronized fun clear() {
        app = ""; run = ""; signature = ""; started = 0; expires = 0; code = null; consumed = false
    }

    @Synchronized fun expire(): Boolean {
        if (!active()) clear()
        privateValues.entries.removeAll { it.value <= clock() }
        return app.isNotEmpty() || privateValues.any { it.value != Long.MAX_VALUE }
    }
}

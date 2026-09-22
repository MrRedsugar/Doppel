package dev.doppel.sdk

import java.util.UUID

/** An in-memory, one-use capability for a single app's login notification. */
class LoginSession(private val clock: () -> Long = System::currentTimeMillis) {
    private var app = ""
    private var run = ""
    private var requestId = ""
    private var started = 0L
    private var expires = 0L
    private data class PendingCode(val id: String, val value: String, val context: String, val receivedAt: Long)
    internal data class Candidate(val id: String, val context: String, val ageMs: Long)
    private val codes = mutableListOf<PendingCode>()
    private var consumed = false
    private val privateValues = linkedMapOf<String, Long>()

    @Synchronized fun begin(packageName: String, runId: String, phone: String): String {
        clear()
        app = packageName; run = runId; requestId = UUID.randomUUID().toString()
        started = clock(); expires = started + 300_000
        if (phone.isNotBlank()) privateValues[phone] = maxOf(privateValues[phone] ?: 0, started + 600_000)
        return requestId
    }

    @Synchronized internal fun cancelRequest(id: String) { if (id == requestId) clear() }

    @Synchronized fun active(): Boolean = app.isNotEmpty() && clock() < expires

    internal data class Readiness(val state: String, val expiresInMs: Long) {
        val active get() = state != "inactive"
        val codeReady get() = state == "ready" || state == "selection_required"
    }
    @Synchronized internal fun readiness(packageName: String, runId: String): Readiness =
        if (runId.isBlank() || packageName != app || runId != run || !active()) Readiness("inactive", 0)
        else Readiness(when { consumed -> "consumed"; codes.isEmpty() -> "waiting"; codes.size == 1 -> "ready"; else -> "selection_required" }, (expires - clock()).coerceAtLeast(0))

    @Synchronized internal fun candidates(packageName: String, runId: String): List<Candidate> {
        if (!readiness(packageName, runId).codeReady) return emptyList()
        return codes.map { Candidate(it.id, redact(packageName, it.context), (clock() - it.receivedAt).coerceAtLeast(0)) }
    }

    @Synchronized fun receive(source: String, smsPackage: String, foreground: String, message: String, postedAt: Long): Boolean {
        if (!active() || consumed || source != smsPackage || smsPackage.isBlank() || foreground != app || postedAt < started || postedAt > clock() + 5000) return false
        if (message.length > 4096) return false
        if (Regex("(?i)支付|付款|转账|扣款|交易|银行卡|注册|重置|找回|修改密码|更换手机号|绑定|注销|payment|transfer|transaction|purchase|register|registration|reset|recover").containsMatchIn(message)) return false
        val value = LoginCodeExtractor.extract(message) ?: return false
        privateValues[value] = maxOf(privateValues[value] ?: 0, clock() + 600_000)
        if (codes.any { it.value == value }) return true // Notification updates must not duplicate the same code.
        if (codes.size >= 5) return false
        val context = redact(app, message).replace(Regex("[0-9]+"), "[数字已隐藏]").take(600)
        codes += PendingCode(UUID.randomUUID().toString(), value, context, postedAt)
        return true
    }

    @Synchronized fun consume(packageName: String, runId: String, candidateId: String? = null): String? {
        if (!active() || consumed || packageName != app || runId != run) return null
        val value = (if (candidateId.isNullOrBlank()) codes.singleOrNull() else codes.singleOrNull { it.id == candidateId })?.value ?: return null
        consumed = true; codes.clear()
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
        app = ""; run = ""; requestId = ""; started = 0; expires = 0; codes.clear(); consumed = false
    }

    @Synchronized fun expire(): Boolean {
        if (!active()) clear()
        privateValues.entries.removeAll { it.value <= clock() }
        return app.isNotEmpty() || privateValues.any { it.value != Long.MAX_VALUE }
    }
}

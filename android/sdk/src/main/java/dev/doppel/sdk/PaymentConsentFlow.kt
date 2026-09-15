package dev.doppel.sdk

import java.security.MessageDigest

internal class PaymentConsentFlow {
    var stage = 0; private set
    var active = false; private set
    private var stageStarted = 0L
    val complete: Boolean get() = active && stage == 3
    fun begin(now: Long) { stage = 0; active = true; stageStarted = now }
    fun remainingMillis(now: Long): Long = if (!active || complete) 0 else (5000L - (now - stageStarted).coerceAtLeast(0)).coerceAtLeast(0)
    fun advance(now: Long, acknowledged: Boolean): Boolean {
        if (!active || complete || !acknowledged || remainingMillis(now) > 0) return false
        stage++; stageStarted = now
        return true
    }
    fun consume(): Boolean { if (!complete) return false; reset(); return true }
    fun reset() { stage = 0; active = false; stageStarted = 0 }
}

data class PaymentAttempt(val status: String, val accepted: Boolean = false)
internal data class PaymentConsentRecord(val version: Int, val id: String?, val grantedAt: Long)
internal interface PaymentConsentStorage {
    fun readConsent(): PaymentConsentRecord?
    fun writeConsent(value: PaymentConsentRecord): Boolean
    fun hasAttempt(key: String): Boolean
    fun claimAttempt(key: String, at: Long): Boolean
}

internal interface PaymentConsentGrant {
    fun read(): String?
    fun clear(): Boolean
    fun write(id: String): Boolean
}

internal class PaymentConsentStorageWithGrant(private val database: PaymentConsentStorage, private val grant: PaymentConsentGrant) : PaymentConsentStorage {
    override fun readConsent(): PaymentConsentRecord? {
        val record = database.readConsent() ?: return null
        return if (record.id == null || grant.read() == record.id) record else null
    }
    override fun writeConsent(value: PaymentConsentRecord): Boolean {
        val removed = try { grant.clear() } catch (_: Exception) { false }
        if (value.id == null) {
            val saved = try { database.writeConsent(value) } catch (_: Exception) { false }
            return removed || saved
        }
        if (!removed) return false
        if (!(try { database.writeConsent(value) } catch (_: Exception) { false })) return false
        if (try { grant.write(value.id) } catch (_: Exception) { false }) return true
        try { grant.clear() } catch (_: Exception) { }
        try { database.writeConsent(PaymentConsentRecord(1, null, 0)) } catch (_: Exception) { }
        return false
    }
    override fun hasAttempt(key: String) = database.hasAttempt(key)
    override fun claimAttempt(key: String, at: Long) = database.claimAttempt(key, at)
}

internal class PaymentConsentGate(
    private val storage: PaymentConsentStorage,
    private val lock: Any,
    private val settingsVisible: () -> Boolean,
    private val newId: () -> String,
    private val clock: () -> Long
) {
    private var storageFailed = false
    private val supportedId = Regex("payment-v1:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private fun configuredId(): String? {
        if (storageFailed) return null
        return try {
            storage.readConsent()?.takeIf { it.version == 1 && it.grantedAt > 0 }?.id?.takeIf { supportedId.matches(it) }
        } catch (_: Exception) { storageFailed = true; null }
    }
    fun currentId(): String? = synchronized(lock) { if (settingsVisible()) null else configuredId() }
    fun isEnabledForSettings(): Boolean = synchronized(lock) { configuredId() != null }
    fun hasStorageFailure(): Boolean = synchronized(lock) { storageFailed }
    fun enable(flow: PaymentConsentFlow): Boolean = synchronized(lock) {
        if (!settingsVisible() || !flow.consume()) return@synchronized false
        try {
            val id = newId()
            if (!supportedId.matches(id)) return@synchronized false
            val saved = storage.writeConsent(PaymentConsentRecord(1, id, clock()))
            storageFailed = !saved
            saved
        } catch (_: Exception) { storageFailed = true; false }
    }
    fun disable(): Boolean = synchronized(lock) {
        storageFailed = true
        val saved = try { storage.writeConsent(PaymentConsentRecord(1, null, 0)) } catch (_: Exception) { false }
        storageFailed = !saved
        saved
    }
    fun runPayment(expectedId: String, runId: String, fingerprint: String, action: () -> Boolean): PaymentAttempt = synchronized(lock) {
        if (runId.isBlank() || runId.length > 128 || fingerprint.isBlank() || fingerprint.length > 4096 || currentId() != expectedId)
            return@synchronized PaymentAttempt("denied")
        // The consent ID is deliberately excluded: switching the setting cannot erase an attempt.
        val key = MessageDigest.getInstance("SHA-256").digest("${runId.length}:$runId:$fingerprint".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        try {
            if (storage.hasAttempt(key)) return@synchronized PaymentAttempt("duplicate")
            if (!storage.claimAttempt(key, clock())) { storageFailed = true; return@synchronized PaymentAttempt("storage_error") }
        } catch (_: Exception) { storageFailed = true; return@synchronized PaymentAttempt("storage_error") }
        PaymentAttempt("attempted", try { action() } catch (_: Exception) { false })
    }
}

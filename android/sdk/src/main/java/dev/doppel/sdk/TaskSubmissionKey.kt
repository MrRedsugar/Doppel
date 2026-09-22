package dev.doppel.sdk

import java.util.UUID

/** Expiring retry identity. Expiry rejects submission; it never expires an accepted task. */
internal object TaskSubmissionKey {
    const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    private const val FUTURE_SKEW_MS = 5L * 60 * 1000
    private val pattern = Regex("q2:(0|[1-9][0-9]{0,15}):[A-Za-z0-9_.:-]{1,120}")

    fun create(at: Long = System.currentTimeMillis(), nonce: String = UUID.randomUUID().toString()): String =
        "q2:$at:$nonce".also { require(it.length <= 160 && pattern.matches(it)) { "任务提交编号无效" } }

    fun issuedAt(key: String?): Long? {
        if (key == null || !key.startsWith("q2:")) return null
        return requireNotNull(pattern.matchEntire(key)) { "任务提交编号无效" }.groupValues[1].toLong()
    }

    fun floor(now: Long): Long = (now - RETENTION_MS + 1).coerceAtLeast(0)

    fun validate(key: String?, now: Long, minimumIssuedAt: Long = 0): Long? = issuedAt(key)?.also {
        require(it >= maxOf(minimumIssuedAt, floor(now))) { "任务提交凭据已过期，请检查设备时间并重新创建任务；原任务不会重放" }
        require(it - now <= FUTURE_SKEW_MS) { "任务提交时间超前，请检查设备时间" }
    }
}

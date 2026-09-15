package dev.doppel.sdk

import java.util.ArrayDeque

/** One accepted action per observed appearance. Call absent only after a complete node scan. */
internal class AutoTriggerOccurrence {
    class Attempt internal constructor(internal val epoch: Long)
    private var epoch = 0L
    private var pending: Attempt? = null
    private var consumed = false
    private var lastAttempt: Long? = null
    private var blockedUntil = 0L
    private val history = ArrayDeque<Long>()

    fun absent() { epoch++; consumed = false }

    fun canTrigger(now: Long, cooldownMs: Long): Boolean =
        pending == null && !consumed && now >= blockedUntil && (lastAttempt == null || now - lastAttempt!! >= cooldownMs)

    fun begin(now: Long, cooldownMs: Long): Attempt? {
        if (!canTrigger(now, cooldownMs)) return null
        lastAttempt = now
        return Attempt(epoch).also { pending = it }
    }

    /** True exactly when this accepted action reaches the burst limit (not on a later fourth event). */
    fun resolve(attempt: Attempt, accepted: Boolean, now: Long, burstLimit: Int, burstWindowMs: Long): Boolean {
        if (pending !== attempt) return false
        pending = null
        lastAttempt = now
        if (!accepted) return false
        // A countdown control may disappear while its request is in flight.
        // Count the real acceptance, but never consume a later appearance.
        if (attempt.epoch == epoch) consumed = true
        while (history.isNotEmpty() && now - history.first() > burstWindowMs) history.removeFirst()
        history.addLast(now)
        if (history.size < burstLimit) return false
        blockedUntil = now + BURST_PAUSE_MS
        history.clear()
        return true
    }

    companion object { const val BURST_PAUSE_MS = 60_000L }
}

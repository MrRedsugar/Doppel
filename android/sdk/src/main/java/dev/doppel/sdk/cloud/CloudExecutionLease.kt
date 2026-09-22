package dev.doppel.sdk.cloud

/** One socket's remote-execution lease. The supplied clock must include sleep (elapsedRealtime on Android). */
internal class CloudExecutionLease(private val clock: () -> Long) {
    private val started = clock()
    private val pending = linkedMapOf<String, Pair<Long, Long>>()
    private var sequence = 0L
    private var acknowledged = 0L
    private var deadline = started + 30_000
    private var serverTime = 0L
    private var sentTime = 0L
    private var closed = false

    @Synchronized fun heartbeat(id: String): Boolean {
        if (expired()) return false
        pending[id] = ++sequence to clock()
        while (pending.size > 4) pending.remove(pending.keys.first())
        return true
    }

    @Synchronized fun acknowledge(id: String, leaseMs: Long, serverTimeMs: Long): Boolean {
        val (number, sent) = pending.remove(id) ?: return false
        if (expired() || number <= acknowledged || leaseMs !in 1..30_000 ||
            serverTimeMs !in 1..253402300799999L || clock() >= sent + leaseMs) return false
        acknowledged = number
        deadline = sent + leaseMs
        sentTime = sent
        serverTime = serverTimeMs
        pending.entries.removeAll { it.value.first <= number }
        return true
    }

    @Synchronized fun valid(): Boolean = !expired() && acknowledged > 0

    @Synchronized fun mayAccept(expiresAtMs: Long): Boolean {
        if (!valid() || expiresAtMs !in 1..253402300799999L) return false
        // Anchor server time at request send, conservatively including the acknowledgement's transit time.
        return expiresAtMs > serverTime + (clock() - sentTime)
    }

    @Synchronized fun expired(): Boolean {
        if (clock() >= deadline) closed = true
        return closed
    }

    @Synchronized fun close() { closed = true; pending.clear() }
}

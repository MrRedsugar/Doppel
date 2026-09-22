package dev.doppel.sdk

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Admission and registration are atomic with stop confirmation; platform completion drains a ticket. */
internal class DeviceActionDrain {
    private val lock = ReentrantLock()
    private val completed = lock.newCondition()
    private val pending = mutableMapOf<Long, String>()
    private val deferred = mutableListOf<Pair<Set<String>?, () -> Unit>>()
    private var sequence = 0L
    private var disconnected = false

    private fun collectReady(): List<() -> Unit> = deferred.filter { (runs, _) ->
        pending.values.none { runs == null || it in runs }
    }.also { deferred.removeAll(it.toSet()) }.map { it.second }

    fun dispatch(runId: String, allowed: () -> Boolean, submit: (() -> Unit) -> Boolean): Boolean = lock.withLock {
        if (disconnected || !allowed()) return false
        val ticket = ++sequence
        pending[ticket] = runId
        val finish = {
            val ready = lock.withLock {
                if (pending.remove(ticket) != null) completed.signalAll()
                collectReady()
            }
            ready.forEach { it() }
        }
        try { submit(finish).also { if (!it) finish() } }
        catch (failure: Exception) { finish(); throw failure }
    }

    /** Overlay leases follow the real platform touch, even when its caller already returned. */
    fun afterStopped(runIds: Set<String>? = null, release: () -> Unit) {
        val ready = lock.withLock { deferred += runIds to release; collectReady() }
        ready.forEach { it() }
    }

    /** Called only when Android destroys this service; a new instance owns a new injector lifetime. */
    fun onDisconnected() {
        val ready = lock.withLock {
            disconnected = true; pending.clear(); completed.signalAll(); collectReady()
        }
        ready.forEach { it() }
    }

    /** The caller closes admission first. Other runs never delay this run's acknowledgement. */
    fun awaitStopped(runIds: Set<String>? = null, timeoutMs: Long = 20_000): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        if (!lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) return false
        try {
            while (pending.values.any { runIds == null || it in runIds }) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                completed.awaitNanos(remaining)
            }
            return true
        } finally { lock.unlock() }
    }
}

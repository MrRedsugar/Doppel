package dev.doppel.sdk

import java.util.concurrent.ConcurrentHashMap

/** In-process admission only. The host owns durable task/session state. */
internal class TaskExecutionGate(private val generation: () -> Long) {
    private class Permit(val current: () -> Boolean) { @Volatile var closed = false }
    private val permits = ConcurrentHashMap<String, Permit>()

    /** Callbacks must be short, nonblocking and must not acquire the engine lock. */
    fun install(runId: String, current: () -> Boolean): AutoCloseable {
        require(runId.isNotBlank())
        val permit = Permit(current)
        permits.put(runId, permit)?.closed = true
        // ponytail: one tombstone per remote run until process exit; reclaim only with durable owner lookup.
        // A later capture must not turn a revoked run into a local task.
        return AutoCloseable { permit.closed = true }
    }

    fun capture(runId: String, ticket: Long, current: () -> Boolean = { true }): () -> Boolean {
        val permit = permits[runId]
        fun valid() = generation() == ticket && permits[runId] === permit && permit?.closed != true
        return {
            valid() && runCatching { (permit?.current?.invoke() ?: true) && current() }.getOrDefault(false) && valid()
        }
    }
}

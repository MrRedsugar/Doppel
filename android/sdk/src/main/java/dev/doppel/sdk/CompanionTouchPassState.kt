package dev.doppel.sdk

/** A late callback/finally may release only the temporary touch pass that it acquired. */
internal class CompanionTouchPassState {
    data class Ticket internal constructor(val id: Long, val generation: Long)
    private var sequence = 0L
    private var current: Ticket? = null
    val passing: Boolean @Synchronized get() = current != null

    @Synchronized fun acquire(generation: Long, currentGeneration: Long): Ticket? {
        if (generation != currentGeneration || current != null) return null
        return Ticket(++sequence, generation).also { current = it }
    }

    @Synchronized fun owns(ticket: Ticket) = current == ticket

    @Synchronized fun release(ticket: Ticket): Boolean {
        if (current != ticket) return false
        current = null
        return true
    }

    @Synchronized fun clear() { current = null }

    @Synchronized fun clearBefore(generation: Long): Boolean {
        val ticket = current ?: return false
        if (ticket.generation >= generation) return false
        current = null
        return true
    }
}

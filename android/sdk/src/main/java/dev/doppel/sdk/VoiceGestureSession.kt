package dev.doppel.sdk

import android.content.Context

/** Main-thread bridge; the overlay retains ownership of the original pointer. */
object VoiceGestureSession {
    const val EXTRA_SESSION_ID = "voice_gesture_session"
    private var generation = 0L
    private var current: Session? = null

    internal class Session(val id: Long, val state: VoiceHoldState = VoiceHoldState()) {
        var onChanged: (() -> Unit)? = null
        var cancelDirection = 0
    }

    @Suppress("UNUSED_PARAMETER")
    fun begin(context: Context): Long {
        current?.let { cancel(it.id) }
        return (++generation).also { current = Session(it) }
    }

    fun finish(id: Long) {
        val session = current?.takeIf { it.id == id } ?: return
        session.state.release()
        session.onChanged?.invoke()
    }

    fun cancel(id: Long, direction: Int = 0) {
        val session = current?.takeIf { it.id == id } ?: return
        if (session.state.phase == VoiceHoldState.Phase.CANCELLED) return
        session.cancelDirection = direction.compareTo(0)
        session.state.cancel()
        session.onChanged?.invoke()
    }

    internal fun find(id: Long): Session? = current?.takeIf { it.id == id }

    internal fun detach(id: Long) {
        if (current?.id == id) {
            current?.state?.cancel()
            current?.onChanged = null
            current = null
        }
    }
}

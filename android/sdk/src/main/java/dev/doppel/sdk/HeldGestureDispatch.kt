package dev.doppel.sdk

/** One DOWN-to-UP operation. Unconfirmed release faults the owner without claiming the touch drained. */
internal class HeldGestureDispatch(
    private val allowed: () -> Boolean,
    private val submit: (Phase, (Boolean) -> Unit) -> Boolean,
    private val finished: (Boolean) -> Unit,
    private val unreleased: () -> Unit
) {
    enum class Phase { HOLD, MOVE, RELEASE }
    private var phase: Phase? = null
    private var ended = false
    private var attempt = 0
    private var releaseAttempts = 0
    private fun mayMove() = runCatching(allowed).getOrDefault(false)

    fun start(): Boolean {
        check(phase == null && !ended)
        if (!mayMove()) return false
        return send(Phase.HOLD)
    }

    private fun send(next: Phase): Boolean {
        phase = next
        val sentAttempt = ++attempt
        if (next == Phase.RELEASE) releaseAttempts++
        val accepted = try { submit(next) { complete(next, sentAttempt, it) } } catch (_: Exception) { false }
        if (!accepted && !ended && attempt == sentAttempt) {
            when (next) {
                Phase.HOLD -> ended = true // No DOWN was accepted.
                Phase.MOVE -> send(Phase.RELEASE) // DOWN remains held when continuation was rejected.
                // Retry only the same stationary UP, never business movement or a new DOWN.
                Phase.RELEASE -> if (releaseAttempts < 2) send(Phase.RELEASE) else fault()
            }
        }
        return accepted
    }

    /** The owner calls this at the native gesture deadline; silence is not an UP receipt. */
    fun timeout() {
        if (!ended && phase != null) fault()
    }

    private fun fault() {
        ended = true
        unreleased()
    }

    private fun complete(expected: Phase, sentAttempt: Int, completed: Boolean) {
        if (ended || attempt != sentAttempt || phase != expected) return
        if (completed && expected == Phase.HOLD) {
            send(if (mayMove()) Phase.MOVE else Phase.RELEASE)
            return
        }
        ended = true
        finished(completed && expected == Phase.MOVE && mayMove())
    }
}

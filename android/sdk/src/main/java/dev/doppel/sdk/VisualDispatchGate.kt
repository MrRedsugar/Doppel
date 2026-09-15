package dev.doppel.sdk

/** Status conversion is only for a nonempty dispatchBlock, never a general action result. */
internal object VisualDispatchGate {
    fun actionState(submitted: Boolean, accepted: Boolean): String = when {
        accepted -> "accepted"
        submitted -> "unconfirmed"
        else -> "not_dispatched"
    }

    fun blockedStatus(actionState: String): String = if (actionState == "not_dispatched") "stale" else "error"
}

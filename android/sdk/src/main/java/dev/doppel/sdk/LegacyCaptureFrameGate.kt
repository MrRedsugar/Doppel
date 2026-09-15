package dev.doppel.sdk

/** Surface timestamps use a monotonic clock; an unprovable timestamp must never yield an old frame. */
internal data class LegacyCaptureFrameGate(val generation: Long, val startedNanos: Long, val drainedTimestamp: Long) {
    fun accepts(currentGeneration: Long, imageTimestamp: Long, receivedNanos: Long): Boolean =
        currentGeneration == generation && imageTimestamp > 0 && imageTimestamp > startedNanos &&
            imageTimestamp > drainedTimestamp && imageTimestamp <= receivedNanos
}

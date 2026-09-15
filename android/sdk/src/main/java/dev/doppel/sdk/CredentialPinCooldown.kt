package dev.doppel.sdk

/** Monotonic within a boot; a bounded wall-clock remainder when a device reboots. */
internal object CredentialPinCooldown {
    fun delay(failures: Int) = (30_000L * (1L shl (failures - 1).coerceIn(0, 8))).coerceAtMost(600_000L)

    fun remaining(failures: Int, elapsedUntil: Long, wallUntil: Long?, savedBoot: Int,
                  elapsedNow: Long, wallNow: Long, currentBoot: Int): Long {
        if (failures <= 0) return 0L
        val maximum = delay(failures)
        val remaining = when {
            currentBoot >= 0 && currentBoot == savedBoot -> elapsedUntil - elapsedNow
            wallUntil != null -> wallUntil - wallNow
            // Older versions persisted only an uptime deadline. Its boot is unknown:
            // migrate once to a finite penalty instead of trusting a previous uptime.
            else -> maximum
        }
        return remaining.coerceIn(0L, maximum)
    }
}

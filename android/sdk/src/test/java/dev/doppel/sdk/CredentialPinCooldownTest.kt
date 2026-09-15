package dev.doppel.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class CredentialPinCooldownTest {
    @Test fun processRestartAndClockEditsCannotBypassTheSameBootCooldown() {
        for (wall in listOf(1L, 1_000_000L, 9_000_000L)) {
            assertEquals(20_000L, CredentialPinCooldown.remaining(1, 530_000L, 1_030_000L, 8,
                510_000L, wall, 8))
        }
        assertEquals(0L, CredentialPinCooldown.remaining(1, 530_000L, 1_030_000L, 8,
            530_000L, 1L, 8))
    }

    @Test fun rebootUsesTheRemainingWallTimeAndReanchorsRollbackToAFiniteDelay() {
        assertEquals(20_000L, CredentialPinCooldown.remaining(1, 86_430_000L, 1_030_000L, 8,
            2_000L, 1_010_000L, 9))
        assertEquals(0L, CredentialPinCooldown.remaining(1, 86_430_000L, 1_030_000L, 8,
            2_000L, 1_040_000L, 9))
        val capped = CredentialPinCooldown.remaining(10, 86_430_000L, 1_030_000L, 8,
            2_000L, 10L, 9)
        assertEquals(600_000L, capped)
        // Production persists the capped remainder against this boot exactly once.
        assertEquals(0L, CredentialPinCooldown.remaining(10, 2_000L + capped, 10L + capped, 9,
            602_000L, 10L, 9))
    }

    @Test fun legacyUptimeDeadlinesMigrateOnceWithoutErasingFailureHistory() {
        for (oldDeadline in listOf(1L, 86_430_000L)) {
            assertEquals(30_000L, CredentialPinCooldown.remaining(1, oldDeadline, null, -1,
                2_000L, 1_000_000L, 9))
        }
        assertEquals(60_000L, CredentialPinCooldown.delay(2))
        assertEquals(600_000L, CredentialPinCooldown.delay(10))
        assertEquals(0L, CredentialPinCooldown.remaining(0, 86_430_000L, null, -1,
            2_000L, 1_000_000L, 9))
        assertEquals(20_000L, CredentialPinCooldown.remaining(1, 86_430_000L, 1_030_000L, -1,
            2_000L, 1_010_000L, -1))
    }
}

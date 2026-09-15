package dev.doppel.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class AutoTriggerOccurrenceTest {
    private fun AutoTriggerOccurrence.accepted(now: Long, burstLimit: Int, burstWindowMs: Long): Boolean =
        resolve(requireNotNull(begin(now, 500)), true, now, burstLimit, burstWindowMs)

    @Test fun skippedNoticeConsumesAppearanceUntilControlDisappears() {
        val gate = AutoTriggerOccurrence()
        assertTrue(gate.canTrigger(1_000, 500))
        gate.accepted(1_000, 3, 15_000) // The notice was accepted, then the user pressed Skip.
        assertFalse(gate.canTrigger(90_000, 500)) // A later content event is still the same appearance.
        gate.absent()
        assertTrue(gate.canTrigger(90_100, 500))
    }

    @Test fun rejectedActionDoesNotConsumeAppearanceOrCountTowardBurst() {
        val gate = AutoTriggerOccurrence()
        repeat(10) { assertTrue(gate.canTrigger(it * 1_000L, 500)) }
        assertFalse(gate.accepted(10_000, 3, 15_000))
    }

    @Test fun thirdAcceptedAppearancePausesImmediatelyAndExpiresAfterSixtySeconds() {
        val gate = AutoTriggerOccurrence()
        assertFalse(gate.accepted(1_000, 3, 15_000))
        gate.absent()
        assertFalse(gate.accepted(4_000, 3, 15_000))
        gate.absent()
        assertTrue(gate.accepted(7_000, 3, 15_000))
        gate.absent()
        assertFalse(gate.canTrigger(66_999, 500))
        assertTrue(gate.canTrigger(67_000, 500))
        assertFalse(gate.accepted(67_000, 3, 15_000))
    }

    @Test fun timePassingAloneNeverRearmsConsumedAppearance() {
        val gate = AutoTriggerOccurrence()
        assertTrue(gate.accepted(1_000, 1, 15_000))
        assertFalse(gate.canTrigger(100_000, 500))
        gate.absent()
        assertTrue(gate.canTrigger(100_000, 500))
    }

    @Test fun burstPauseDoesNotDisableUnrelatedRule() {
        val first = AutoTriggerOccurrence()
        val other = AutoTriggerOccurrence()
        assertTrue(first.accepted(1_000, 1, 15_000))
        first.absent()
        assertFalse(first.canTrigger(2_000, 500))
        assertTrue(other.canTrigger(2_000, 500))
    }

    @Test fun separatedAppearancesKeepCooldownButOldBurstHistoryExpires() {
        val gate = AutoTriggerOccurrence()
        assertFalse(gate.accepted(1_000, 3, 15_000))
        gate.absent()
        assertFalse(gate.canTrigger(3_999, 3_000))
        assertTrue(gate.canTrigger(4_000, 3_000))
        assertFalse(gate.accepted(4_000, 3, 15_000))
        gate.absent()
        assertFalse(gate.accepted(20_000, 3, 15_000))
    }

    @Test fun admissionPendingBlocksDuplicatesAndFailureRetainsSameAppearance() {
        val gate = AutoTriggerOccurrence()
        val first = requireNotNull(gate.begin(1_000, 500))
        assertNull(gate.begin(10_000, 500))
        assertFalse(gate.resolve(first, false, 10_000, 3, 15_000))
        assertNull(gate.begin(10_499, 500))
        val retry = requireNotNull(gate.begin(10_500, 500))
        assertFalse(gate.resolve(retry, true, 10_500, 3, 15_000))
        assertFalse(gate.canTrigger(20_000, 500))
    }

    @Test fun rejectedAdmissionsNeverContributeToBurstLimit() {
        val gate = AutoTriggerOccurrence()
        repeat(6) { index ->
            val now = index * 500L
            val attempt = requireNotNull(gate.begin(now, 500))
            assertFalse(gate.resolve(attempt, false, now, 3, 15_000))
        }
        assertFalse(gate.accepted(3_000, 3, 15_000))
        gate.absent()
        assertFalse(gate.accepted(3_500, 3, 15_000))
        gate.absent()
        assertTrue(gate.accepted(4_000, 3, 15_000))
    }

    @Test fun priorAppearanceAdmissionDoesNotConsumeNewAppearance() {
        val gate = AutoTriggerOccurrence()
        val previous = requireNotNull(gate.begin(1_000, 500))
        gate.absent()
        assertNull(gate.begin(2_000, 500)) // Still only one asynchronous request in flight.
        assertFalse(gate.resolve(previous, true, 2_000, 3, 15_000))
        assertNotNull(gate.begin(2_500, 500))
    }

    @Test fun duplicateOldCallbackCannotReleaseOrConsumeANewerPendingRequest() {
        val gate = AutoTriggerOccurrence()
        val previous = requireNotNull(gate.begin(1_000, 500))
        gate.absent()
        assertFalse(gate.resolve(previous, false, 2_000, 3, 15_000))
        val current = requireNotNull(gate.begin(2_500, 500))
        assertFalse(gate.resolve(previous, true, 3_000, 3, 15_000))
        assertNull(gate.begin(3_500, 500))
        assertFalse(gate.resolve(current, true, 3_500, 3, 15_000))
        assertFalse(gate.canTrigger(4_000, 500))
    }
}

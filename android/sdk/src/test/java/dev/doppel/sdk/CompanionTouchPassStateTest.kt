package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class CompanionTouchPassStateTest {
    @Test fun staleHostCannotDisableTheCompanionEntry() {
        val state = CompanionTouchPassState()
        assertNull(state.acquire(3, 4))
        assertFalse(state.passing)
    }

    @Test fun gestureOwnsPassUntilReleaseAndDuplicateAcquisitionFails() {
        val state = CompanionTouchPassState()
        val ticket = checkNotNull(state.acquire(3, 3))
        assertTrue(state.passing)
        assertNull(state.acquire(3, 3))
        assertTrue(state.release(ticket))
        assertFalse(state.passing)
        assertFalse(state.release(ticket))
    }

    @Test fun lateFinallyCannotRestoreAReplacementGestureWindow() {
        val state = CompanionTouchPassState()
        val old = checkNotNull(state.acquire(3, 3))
        state.clear()
        val fresh = checkNotNull(state.acquire(4, 4))
        assertFalse(state.release(old))
        assertTrue(state.passing)
        assertTrue(state.owns(fresh))
        assertTrue(state.release(fresh))
        assertFalse(state.passing)
    }

    @Test fun failureAndCancellationRestoreTouchInterception() {
        for (failure in listOf(false, true)) {
            val state = CompanionTouchPassState()
            val ticket = checkNotNull(state.acquire(5, 5))
            try {
                if (failure) throw IllegalStateException("Gesture rejected")
                state.clear() // Pause or overlay destruction while dispatch is in progress.
            } catch (_: IllegalStateException) {
            } finally { state.release(ticket) }
            assertFalse(state.passing)
        }
    }

    @Test fun delayedStopCannotClearANewerHostGeneration() {
        val state = CompanionTouchPassState()
        checkNotNull(state.acquire(3, 3))
        assertTrue(state.clearBefore(4))
        val fresh = checkNotNull(state.acquire(4, 4))
        assertFalse(state.clearBefore(4))
        assertTrue(state.owns(fresh))
        assertTrue(state.clearBefore(5))
        assertFalse(state.passing)
    }
}

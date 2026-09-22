package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class HeldGestureDispatchTest {
    private class Platform {
        val drain = DeviceActionDrain()
        var allowed = true
        val calls = mutableListOf<HeldGestureDispatch.Phase>()
        val results = mutableListOf<Boolean>()
        val callbacks = mutableMapOf<HeldGestureDispatch.Phase, (Boolean) -> Unit>()
        val submittedCallbacks = mutableListOf<(Boolean) -> Unit>()
        val reject = mutableSetOf<HeldGestureDispatch.Phase>()
        var rejectReleases = 0
        var faults = 0
        lateinit var operation: HeldGestureDispatch
        fun start() = drain.dispatch("drag", { allowed }) { finish ->
            operation = HeldGestureDispatch({ allowed }, { phase, callback ->
                calls += phase
                submittedCallbacks += callback
                if (phase in reject) false
                else if (phase == HeldGestureDispatch.Phase.RELEASE && rejectReleases > 0) { rejectReleases--; false }
                else { callbacks[phase] = callback; true }
            }, { completed -> finish(); results += completed }, { faults++ })
            operation.start()
        }
        fun complete(phase: HeldGestureDispatch.Phase, success: Boolean = true) = callbacks.getValue(phase)(success)
        fun drained() = drain.awaitStopped(setOf("drag"), 1)
    }

    @Test fun holdAndMoveHaveOneDrainLifetimeAndOnlyMoveCompletesTheAction() {
        val p = Platform()
        assertTrue(p.start())
        assertFalse(p.drained())
        p.complete(HeldGestureDispatch.Phase.HOLD)
        assertEquals(listOf(HeldGestureDispatch.Phase.HOLD, HeldGestureDispatch.Phase.MOVE), p.calls)
        assertFalse("HOLD callback does not release the device", p.drained())
        assertTrue(p.results.isEmpty())
        p.complete(HeldGestureDispatch.Phase.MOVE)
        assertTrue(p.drained())
        assertEquals(listOf(true), p.results)
        p.complete(HeldGestureDispatch.Phase.HOLD)
        p.complete(HeldGestureDispatch.Phase.MOVE)
        assertEquals("Late callbacks cannot replay MOVE", 2, p.calls.size)
        assertEquals(listOf(true), p.results)
    }

    @Test fun pauseDuringHoldReleasesAtStartWithoutMovingOrEarlyDrain() {
        val p = Platform()
        assertTrue(p.start())
        p.allowed = false
        p.complete(HeldGestureDispatch.Phase.HOLD)
        assertEquals(listOf(HeldGestureDispatch.Phase.HOLD, HeldGestureDispatch.Phase.RELEASE), p.calls)
        assertFalse(p.drained())
        p.complete(HeldGestureDispatch.Phase.RELEASE)
        assertTrue(p.drained())
        assertEquals(listOf(false), p.results)
        p.allowed = true
        p.complete(HeldGestureDispatch.Phase.HOLD)
        assertEquals(2, p.calls.size)
    }

    @Test fun rejectedMovementReleasesHeldPointerAndCannotReportSuccess() {
        val p = Platform().apply { reject += HeldGestureDispatch.Phase.MOVE }
        assertTrue(p.start())
        p.complete(HeldGestureDispatch.Phase.HOLD)
        assertEquals(listOf(HeldGestureDispatch.Phase.HOLD, HeldGestureDispatch.Phase.MOVE, HeldGestureDispatch.Phase.RELEASE), p.calls)
        assertFalse(p.drained())
        p.complete(HeldGestureDispatch.Phase.RELEASE)
        assertTrue(p.drained())
        assertEquals(listOf(false), p.results)
    }

    @Test fun platformCancellationFinishesWithoutDispatchingMoreCoordinates() {
        for (cancelDuringMove in listOf(false, true)) {
            val p = Platform()
            assertTrue(p.start())
            if (cancelDuringMove) p.complete(HeldGestureDispatch.Phase.HOLD)
            p.allowed = false
            p.complete(if (cancelDuringMove) HeldGestureDispatch.Phase.MOVE else HeldGestureDispatch.Phase.HOLD, false)
            assertTrue(p.drained())
            assertEquals(listOf(false), p.results)
            assertFalse(p.calls.contains(HeldGestureDispatch.Phase.RELEASE))
        }
    }

    @Test fun rejectedHoldHasNoPendingTouchButRepeatedlyRejectedReleaseFaultsOnce() {
        val first = Platform().apply { reject += HeldGestureDispatch.Phase.HOLD }
        assertFalse(first.start())
        assertTrue(first.drained())
        first.operation.timeout()
        assertEquals(0, first.faults)
        assertTrue(first.results.isEmpty())
        val held = Platform().apply { reject += HeldGestureDispatch.Phase.RELEASE }
        assertTrue(held.start())
        held.allowed = false
        held.complete(HeldGestureDispatch.Phase.HOLD)
        assertFalse("No UP callback means device stop is not yet confirmed", held.drained())
        assertTrue(held.results.isEmpty())
        assertFalse(held.calls.contains(HeldGestureDispatch.Phase.MOVE))
        assertEquals(2, held.calls.count { it == HeldGestureDispatch.Phase.RELEASE })
        assertEquals(1, held.faults)
        held.allowed = true
        held.submittedCallbacks.forEach { it(true); it(false) }
        held.operation.timeout()
        assertEquals(1, held.faults)
        assertEquals(3, held.calls.size)
        assertTrue(held.results.isEmpty())
        assertFalse(held.drained())
    }

    @Test fun oneRejectedReleaseRetriesSamePhaseAndIgnoresCallbackFromRejectedAttempt() {
        val p = Platform().apply { rejectReleases = 1 }
        assertTrue(p.start())
        p.allowed = false
        p.complete(HeldGestureDispatch.Phase.HOLD)
        assertEquals(listOf(HeldGestureDispatch.Phase.HOLD, HeldGestureDispatch.Phase.RELEASE, HeldGestureDispatch.Phase.RELEASE), p.calls)
        p.submittedCallbacks[1](true)
        p.submittedCallbacks[1](false)
        assertFalse(p.drained())
        assertTrue(p.results.isEmpty())
        p.complete(HeldGestureDispatch.Phase.RELEASE)
        assertTrue(p.drained())
        assertEquals(listOf(false), p.results)
        p.operation.timeout()
        assertEquals(0, p.faults)
    }

    @Test fun eachMissingNativeTerminalCallbackFaultsWithoutFinishOrLateMovement() {
        for (phase in HeldGestureDispatch.Phase.values()) {
            val p = Platform()
            assertTrue(p.start())
            if (phase != HeldGestureDispatch.Phase.HOLD) {
                p.allowed = phase == HeldGestureDispatch.Phase.MOVE
                p.complete(HeldGestureDispatch.Phase.HOLD)
            }
            val before = p.calls.toList()
            p.operation.timeout()
            p.operation.timeout()
            assertEquals(1, p.faults)
            assertTrue(p.results.isEmpty())
            assertFalse(p.drained())
            p.allowed = true
            p.submittedCallbacks.forEach { it(true); it(false) }
            assertEquals(before, p.calls)
            assertEquals(1, p.faults)
            assertTrue(p.results.isEmpty())
            assertFalse(p.drained())
        }
    }

    @Test fun acceptedReleaseCancellationConfirmsTouchEndedWithoutBusinessSuccess() {
        val p = Platform()
        assertTrue(p.start())
        p.allowed = false
        p.complete(HeldGestureDispatch.Phase.HOLD)
        p.complete(HeldGestureDispatch.Phase.RELEASE, false)
        assertTrue(p.drained())
        assertEquals(listOf(false), p.results)
        p.operation.timeout()
        assertEquals(0, p.faults)
    }
}

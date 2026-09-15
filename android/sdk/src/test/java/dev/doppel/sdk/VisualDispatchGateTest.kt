package dev.doppel.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class VisualDispatchGateTest {
    @Test fun neitherSubmittedNorAcceptedIsKnownNotDispatched() {
        assertEquals("not_dispatched", VisualDispatchGate.actionState(submitted = false, accepted = false))
    }

    @Test fun submittedWithoutAcceptanceRemainsUnconfirmed() {
        assertEquals("unconfirmed", VisualDispatchGate.actionState(submitted = true, accepted = false))
    }

    @Test fun acceptedReceiptTakesPriorityWhenSubmittedIsTrue() {
        assertEquals("accepted", VisualDispatchGate.actionState(submitted = true, accepted = true))
    }

    @Test fun acceptedReceiptTakesPriorityEvenWhenSubmittedIsFalse() {
        assertEquals("accepted", VisualDispatchGate.actionState(submitted = false, accepted = true))
    }

    @Test fun onlyExactNotDispatchedCanBeStaleForADispatchBlock() {
        assertEquals("stale", VisualDispatchGate.blockedStatus("not_dispatched"))
    }

    @Test fun acceptedUnconfirmedFailedAndUnknownStatesNeverBecomeStale() {
        for (state in listOf("accepted", "unconfirmed", "failed", "unknown", "", " ", "null", "NOT_DISPATCHED", "not_dispatched ", " not_dispatched")) {
            assertEquals("Unsafe stale conversion for state '$state'", "error", VisualDispatchGate.blockedStatus(state))
        }
    }
}

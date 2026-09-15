package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class PaymentConsentFlowTest {
    @Test fun everyStageRequiresItsOwnDelayAndAcknowledgement() {
        val flow = PaymentConsentFlow()
        flow.begin(100)
        repeat(3) { stage ->
            val start = 100L + stage * 5000L
            assertEquals(stage, flow.stage)
            assertFalse(flow.advance(start + 4999, true))
            assertFalse(flow.advance(start + 5000, false))
            assertTrue(flow.advance(start + 5000, true))
        }
        assertTrue(flow.complete)
        assertTrue(flow.consume())
        assertFalse(flow.consume())
    }

    @Test fun backgroundCancelAndRecreationLoseUnfinishedConsent() {
        val flow = PaymentConsentFlow().apply { begin(0); advance(5000, true); reset() }
        assertFalse(flow.active)
        assertFalse(flow.advance(100000, true))
        assertFalse(flow.complete)
        assertFalse(PaymentConsentFlow().consume())
        flow.begin(100000)
        assertEquals(5000L, flow.remainingMillis(100000))
        assertFalse(flow.advance(100001, true))
    }

    @Test fun repeatedClicksAndClockRollbackCannotSkipTheNextStage() {
        val flow = PaymentConsentFlow().apply { begin(1000) }
        assertTrue(flow.advance(6000, true))
        assertFalse(flow.advance(6000, true))
        assertFalse(flow.advance(0, true))
        assertEquals(1, flow.stage)
    }
}

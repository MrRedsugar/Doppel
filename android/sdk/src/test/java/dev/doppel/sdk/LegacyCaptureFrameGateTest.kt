package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class LegacyCaptureFrameGateTest {
    @Test fun queuedAndPreviouslySeenFramesCannotSatisfyANewRequest() {
        val gate = LegacyCaptureFrameGate(7, 200, 150)
        assertFalse(gate.accepts(7, 150, 250))
        assertFalse(gate.accepts(7, 199, 250))
        assertFalse(gate.accepts(7, 200, 250))
        assertTrue(gate.accepts(7, 201, 250))
    }

    @Test fun oldReaderGenerationCannotDeliverAfterRotation() {
        val gate = LegacyCaptureFrameGate(8, 200, 150)
        assertFalse(gate.accepts(7, 220, 250))
        assertTrue(gate.accepts(8, 220, 250))
    }

    @Test fun unknownFutureAndRegressingTimestampsFailClosed() {
        val gate = LegacyCaptureFrameGate(8, 200, 230)
        assertFalse(gate.accepts(8, 0, 250))
        assertFalse(gate.accepts(8, -1, 250))
        assertFalse(gate.accepts(8, 220, 250))
        assertFalse(gate.accepts(8, 260, 250))
        assertTrue(gate.accepts(8, 240, 250))
    }
}

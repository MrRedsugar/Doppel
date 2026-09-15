package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class HorizontalHoldCancelTest {
    private fun crossed(start: Float, end: Float) = HorizontalHoldCancel.crossed(start, end, 1080f, 216f, 12f)

    @Test fun centeredHoldCancelsInBothHorizontalDirections() {
        assertFalse(crossed(540f, 755f)); assertTrue(crossed(540f, 756f))
        assertFalse(crossed(540f, 325f)); assertTrue(crossed(540f, 324f))
    }

    @Test fun rightDockCanCancelTowardItsNearbyScreenEdge() {
        assertFalse(crossed(1000f, 1045f)); assertTrue(crossed(1000f, 1060f))
        assertFalse(crossed(1000f, 810f)); assertTrue(crossed(1000f, 780f))
    }

    @Test fun leftDockCanCancelTowardItsNearbyScreenEdge() {
        assertFalse(crossed(80f, 35f)); assertTrue(crossed(80f, 20f))
        assertFalse(crossed(80f, 270f)); assertTrue(crossed(80f, 300f))
    }

    @Test fun verticalMovementAndSmallHorizontalJitterDoNotCancel() {
        assertFalse(crossed(1000f, 1000f))
        assertFalse(crossed(1000f, 1012f)); assertFalse(crossed(80f, 68f))
    }

    @Test fun extremelyNarrowEdgeDistanceRemainsReachable() {
        assertFalse(crossed(1074f, 1076f)); assertTrue(crossed(1074f, 1079f))
        assertFalse(crossed(5f, 3f)); assertTrue(crossed(5f, 0f))
    }

    @Test fun invalidGeometryNeverCancels() {
        assertFalse(crossed(Float.NaN, 100f)); assertFalse(crossed(100f, Float.NaN))
        assertFalse(HorizontalHoldCancel.crossed(100f, 200f, 0f, 216f, 12f))
    }
}

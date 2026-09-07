package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class ActionFeedbackGeometryTest {
    @Test fun targetCenterUsesVisibleBoundsRatherThanUnclippedOffscreenCenter() {
        val geometry = ActionFeedbackGeometry.create("tap", listOf(-100, 20, 60, 100), 200, 200)!!
        assertEquals(FeedbackPoint(30f, 60f), geometry.center)
        assertEquals(listOf(0, 20, 60, 100), geometry.bounds)
    }
    @Test fun invisibleMalformedAndEmptyTargetsNeverProduceFeedback() {
        for (bounds in listOf(listOf(2, 3), listOf(40, 20, 40, 90), listOf(60, 20, 40, 90), listOf(-90, 0, -20, 30))) {
            assertNull(ActionFeedbackGeometry.create("tap", bounds, 200, 200))
        }
        assertNull(ActionFeedbackGeometry.create("tap", listOf(0, 0, 30, 40), 0, 200))
    }
    @Test fun scrollPathStaysInsideNodeAndPointsInRequestedDirection() {
        val bounds = listOf(40, 60, 140, 260)
        for (direction in listOf("up", "down", "left", "right")) {
            val geometry = ActionFeedbackGeometry.create("scroll", bounds, 300, 400, direction)!!
            val start = geometry.start!!; val end = geometry.end!!
            for (point in listOf(start, end)) {
                assertTrue(point.x > 40 && point.x < 140)
                assertTrue(point.y > 60 && point.y < 260)
            }
            when (direction) {
                "up" -> assertTrue(end.y < start.y)
                "down" -> assertTrue(end.y > start.y)
                "left" -> assertTrue(end.x < start.x)
                "right" -> assertTrue(end.x > start.x)
            }
        }
        assertNull(ActionFeedbackGeometry.create("scroll", bounds, 300, 400, "diagonal"))
    }
    @Test fun longPressKeepsExactTargetCenterWithoutInventingDurationOrGesture() {
        val geometry = ActionFeedbackGeometry.create("long_press", listOf(20, 40, 100, 140), 300, 400)!!
        assertEquals(FeedbackPoint(60f, 90f), geometry.center)
        assertNull(geometry.start)
        assertNull(geometry.end)
    }
}

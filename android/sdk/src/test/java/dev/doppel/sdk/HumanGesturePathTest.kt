package dev.doppel.sdk

import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HumanGesturePathTest {
    private fun point(x: Float, y: Float) = PointF().apply { this.x = x; this.y = y }

    @Test fun preservesEndpointsAndOverallDirection() {
        val points = HumanGesturePath.samples(point(100f, 500f), point(900f, 500f), 1080, 1920, seed = 11L)
        assertEquals(100f, points.first().x, 0f)
        assertEquals(500f, points.first().y, 0f)
        assertEquals(900f, points.last().x, 0f)
        assertEquals(500f, points.last().y, 0f)
        assertTrue(points.zipWithNext().all { it.second.x >= it.first.x })
        assertTrue(points.drop(1).dropLast(1).any { abs(it.y - 500f) > 0.5f })
    }

    @Test fun verticalSwipeDoesNotChangeItsPrimaryDirection() {
        val points = HumanGesturePath.samples(point(500f, 900f), point(500f, 100f), 1080, 1920, seed = 17L)
        assertTrue(points.zipWithNext().all { it.second.y <= it.first.y })
        assertTrue(points.drop(1).dropLast(1).all { abs(it.x - 500f) <= 32.1f })
    }

    @Test fun differentSeedsProduceDifferentButBoundedPaths() {
        val start = point(100f, 500f); val end = point(900f, 500f)
        val first = HumanGesturePath.samples(start, end, 1080, 1920, seed = 1L)
        val second = HumanGesturePath.samples(start, end, 1080, 1920, seed = 2L)
        assertTrue(first.zip(second).drop(1).dropLast(1).any { (a, b) -> abs(a.y - b.y) > 0.01f || abs(a.x - b.x) > 0.01f })
        assertTrue(first.zipWithNext().all { it.second.x >= it.first.x })
        assertTrue(second.zipWithNext().all { it.second.x >= it.first.x })
    }

    @Test fun everyRecordedTrackEndsExactlyAtRequestedTarget() {
        val start = point(120f, 500f); val end = point(980f, 500f)
        for (seed in 0L until 100L) {
            val points = HumanGesturePath.samples(start, end, 1080, 1920, seed)
            assertEquals(end.x, points.last().x, 0f)
            assertEquals(end.y, points.last().y, 0f)
        }
    }

    @Test fun everyEdgeAndCornerStaysInsideDisplayWithoutChangingEndpoints() {
        for ((width, height) in listOf(1080 to 1920, 1920 to 1080, 1 to 800)) {
            val right = (width - 1).toFloat(); val bottom = (height - 1).toFloat()
            val edges = listOf(point(0f, 0f) to point(0f, bottom), point(right, bottom) to point(right, 0f),
                point(0f, 0f) to point(right, 0f), point(right, bottom) to point(0f, bottom),
                point(0f, 0f) to point(right, bottom), point(0f, bottom) to point(right, 0f))
            for ((start, end) in edges) for (seed in 0L until 100L) {
                val points = HumanGesturePath.samples(start, end, width, height, seed)
                assertTrue(points.all { it.x in 0f..right && it.y in 0f..bottom })
                assertEquals(start.x, points.first().x, 0f); assertEquals(start.y, points.first().y, 0f)
                assertEquals(end.x, points.last().x, 0f); assertEquals(end.y, points.last().y, 0f)
            }
        }
    }
}

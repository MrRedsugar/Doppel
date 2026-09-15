package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class EdgeDockGeometryTest {
    @Test fun connectedShouldersAreConcaveAndTangentToThePhysicalEdge() {
        val shape = EdgeDockGeometry.create(58f, 58f, 8f, true, 1f)!!
        assertEquals(EdgeDockPoint(58f, 0f), shape.start)
        val top = shape.segments.first() as EdgeDockSegment.Cubic
        assertEquals(shape.start.x, top.control1.x, 0.001f)
        assertTrue(top.control1.y > shape.start.y)
        assertEquals(EdgeDockPoint(50f, 8f), top.end)
        assertEquals(top.end.y, top.control2.y, 0.001f)
        assertTrue(top.control2.x > top.end.x)
        val bottom = shape.segments[6] as EdgeDockSegment.Cubic
        assertEquals(EdgeDockPoint(58f, 58f), bottom.end)
        assertEquals(bottom.end.x, bottom.control2.x, 0.001f)
        assertTrue(bottom.control2.y < bottom.end.y)
        assertEquals(shape.start, shape.segments.last().end)
    }

    @Test fun leftAndRightAreExactMirrorsAtEveryDockingFraction() {
        for (width in listOf(58f, 196f)) for (amount in listOf(0f, 0.2f, 0.5f, 1f)) {
            val right = EdgeDockGeometry.create(width, 58f, 8f, true, amount)!!
            val left = EdgeDockGeometry.create(width, 58f, 8f, false, amount)!!
            val rightPoints = points(right)
            val leftPoints = points(left)
            assertEquals(rightPoints.size, leftPoints.size)
            for ((a, b) in rightPoints.zip(leftPoints)) {
                assertEquals(width - a.x, b.x, 0.0001f)
                assertEquals(a.y, b.y, 0.0001f)
            }
        }
    }

    @Test fun detachedEntryBecomesTheOriginalFullHeightCapsule() {
        val shape = EdgeDockGeometry.create(196f, 58f, 8f, true, 0f)!!
        val first = shape.segments.first() as EdgeDockSegment.Cubic
        assertEquals(EdgeDockPoint(196f, 29f), shape.start)
        assertEquals(EdgeDockPoint(167f, 0f), first.end)
        assertTrue(first.control1.y < shape.start.y)
        assertEquals(shape.start, (shape.segments[6] as EdgeDockSegment.Cubic).end)
        assertEquals(0f, points(shape).minOf { it.x }, 0.001f)
        assertEquals(58f, points(shape).maxOf { it.y }, 0.001f)
    }

    @Test fun geometryScalesWithDensityWithoutGrowingItsTouchWindow() {
        val baseline = EdgeDockGeometry.create(58f, 58f, 8f, true, 1f)!!
        for (density in listOf(1f, 2.5f, 4f)) for (width in listOf(58f, 196f)) {
            for (amount in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val shape = EdgeDockGeometry.create(width * density, 58f * density, 8f * density, true, amount)!!
                for (point in points(shape)) {
                    assertTrue(point.x in 0f..width * density)
                    assertTrue(point.y in 0f..58f * density)
                }
            }
            if (width == 58f) {
                val scaled = EdgeDockGeometry.create(width * density, 58f * density, 8f * density, true, 1f)!!
                for ((a, b) in points(baseline).zip(points(scaled))) {
                    assertEquals(a.x * density, b.x, 0.0001f)
                    assertEquals(a.y * density, b.y, 0.0001f)
                }
            }
        }
    }

    @Test fun dockingUsesDistanceToTheChosenEdgeIncludingExpandedEntry() {
        assertEquals(1f, EdgeDockGeometry.attachment(0f, 58f, 576f, false, 24f), 0f)
        assertEquals(0.5f, EdgeDockGeometry.attachment(12f, 58f, 576f, false, 24f), 0f)
        assertEquals(0f, EdgeDockGeometry.attachment(24f, 58f, 576f, false, 24f), 0f)
        assertEquals(1f, EdgeDockGeometry.attachment(380f, 196f, 576f, true, 24f), 0f)
        assertEquals(0.5f, EdgeDockGeometry.attachment(368f, 196f, 576f, true, 24f), 0f)
        assertEquals(0f, EdgeDockGeometry.attachment(200f, 196f, 576f, true, 24f), 0f)
        assertEquals(1f, EdgeDockGeometry.attachment(-2f, 58f, 576f, false, 24f), 0f)
    }

    @Test fun intermediateShouldersStayContinuousAndInvalidBoundsDoNotDraw() {
        val justDetached = EdgeDockGeometry.create(58f, 58f, 8f, true, 0.001f)!!
        val detached = EdgeDockGeometry.create(58f, 58f, 8f, true, 0f)!!
        for ((a, b) in points(detached).zip(points(justDetached))) {
            assertTrue(kotlin.math.abs(a.x - b.x) < 0.06f)
            assertTrue(kotlin.math.abs(a.y - b.y) < 0.06f)
        }
        for (width in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(EdgeDockGeometry.create(width, 58f, 8f, true, 1f))
        }
        assertNull(EdgeDockGeometry.create(58f, 0f, 8f, true, 1f))
        assertNull(EdgeDockGeometry.create(58f, 58f, Float.NaN, true, 1f))
        assertNull(EdgeDockGeometry.create(58f, 58f, 8f, true, Float.NaN))
        assertEquals(0f, EdgeDockGeometry.attachment(Float.NaN, 58f, 576f, true, 24f), 0f)
        assertEquals(0f, EdgeDockGeometry.attachment(0f, 58f, 576f, true, 0f), 0f)
    }

    private fun points(contour: EdgeDockContour): List<EdgeDockPoint> = listOf(contour.start) +
        contour.segments.flatMap {
            when (it) {
                is EdgeDockSegment.Line -> listOf(it.end)
                is EdgeDockSegment.Cubic -> listOf(it.control1, it.control2, it.end)
            }
        }
}

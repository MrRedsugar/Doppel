package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class VisualAnchorTrackerTest {
    private val tracker = VisualAnchorTracker()
    private val bounds = VisualAnchorRect(44, 40, 76, 72)
    private fun scene(): IntArray = IntArray(160 * 120) { 0xff202428.toInt() }.also { pixels ->
        for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
            val v = ((x - bounds.left) * 83 + (y - bounds.top) * 47 + (x * y) * 7) and 255
            pixels[y * 160 + x] = 0xff000000.toInt() or (v shl 16) or ((v xor 137) shl 8) or (v xor 51)
        }
    }
    private fun image(pixels: IntArray = scene()) = VisualAnchorImage(160, 120, pixels)
    private fun prepare(pixels: IntArray = scene(), radius: Int = 24) = tracker.prepare(image(pixels),
        VisualAnchorSpec("button", bounds, 60, 56, radius))
    private fun anchor(pixels: IntArray = scene(), radius: Int = 24): VisualAnchor =
        (prepare(pixels, radius) as VisualAnchorPreparation.Prepared).anchor
    private fun moved(dx: Int, dy: Int): IntArray {
        val original = scene(); val result = IntArray(original.size) { 0xff202428.toInt() }
        for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
            result[(y + dy) * 160 + x + dx] = original[y * 160 + x]
        }
        return result
    }

    @Test fun relocationUsesLatestPixelsRatherThanOriginalCoordinates() {
        val result = tracker.locate(anchor(), image(moved(7, -5))) as VisualAnchorMatch.Matched
        assertEquals(67, result.x); assertEquals(51, result.y)
        assertEquals(VisualAnchorRect(51, 35, 83, 67), result.bounds)
    }
    @Test fun unrelatedAnimatedPixelsDoNotInvalidateLocalTarget() {
        val pixels = scene()
        for (y in 90 until 120) for (x in 0 until 160) pixels[y * 160 + x] = 0xfff0a012.toInt()
        assertTrue(tracker.locate(anchor(), image(pixels)) is VisualAnchorMatch.Matched)
    }
    @Test fun flatAndBoundaryClippedReferencesCannotBecomeAnchors() {
        assertEquals("low_texture", (prepare(IntArray(160 * 120) { 0xff303030.toInt() }) as VisualAnchorPreparation.Rejected).reason)
        val result = tracker.prepare(image(), VisualAnchorSpec("edge", VisualAnchorRect(-1, 40, 32, 72), 8, 56))
        assertEquals("invalid_region", (result as VisualAnchorPreparation.Rejected).reason)
    }
    @Test fun duplicateTargetsRejectRatherThanPickingTheClosestOne() {
        val original = scene(); val both = original.copyOf()
        for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
            both[y * 160 + x + 36] = original[y * 160 + x]
        }
        val spec = VisualAnchorSpec("button", bounds, 60, 56, searchRadiusPx = 48, contextPaddingPx = 0)
        val result = tracker.prepare(image(both), spec)
        assertEquals("ambiguous", (result as VisualAnchorPreparation.Rejected).reason)
        val source = (tracker.prepare(image(original), spec) as VisualAnchorPreparation.Prepared).anchor
        assertEquals("ambiguous", (tracker.locate(source, image(both)) as VisualAnchorMatch.Rejected).reason)
    }
    @Test fun occlusionAndReplacedGlyphRejectEvenWhenMostOfScreenIsStable() {
        val pixels = scene()
        for (y in 48 until 64) for (x in 52 until 68) pixels[y * 160 + x] = 0xffeeeeee.toInt()
        assertTrue(tracker.locate(anchor(), image(pixels)) is VisualAnchorMatch.Rejected)
        assertTrue(tracker.locate(anchor(), image(moved(30, 0))) is VisualAnchorMatch.Rejected)
    }
    @Test fun unchangedIconWithReplacedNeighbourhoodIsRejected() {
        val source = scene(); val changed = source.copyOf()
        for (y in 32 until 80) for (x in 36 until 84) {
            if (x !in bounds.left until bounds.right || y !in bounds.top until bounds.bottom) changed[y * 160 + x] = 0xffcccccc.toInt()
        }
        assertEquals("context_changed", (tracker.locate(anchor(), image(changed)) as VisualAnchorMatch.Rejected).reason)
    }
    @Test fun similarLuminanceWithDifferentColourDoesNotMatch() {
        val changed = scene().map { color -> (color and -0x1000000) or ((color and 255) shl 16) or (color and 0xff00) or ((color shr 16) and 255) }.toIntArray()
        assertTrue(tracker.locate(anchor(), image(changed)) is VisualAnchorMatch.Rejected)
    }
    @Test fun boundedBrightnessChangeMatchesAndReferenceCopiesCallerPixels() {
        val source = scene(); val reference = anchor(source)
        source.fill(0xffcccccc.toInt())
        val brighter = scene().map { color ->
            0xff000000.toInt() or ((((color shr 16) and 255) + 5).coerceAtMost(255) shl 16) or
                ((((color shr 8) and 255) + 5).coerceAtMost(255) shl 8) or (((color and 255) + 5).coerceAtMost(255))
        }.toIntArray()
        assertTrue(tracker.locate(reference, image(brighter)) is VisualAnchorMatch.Matched)
    }
    @Test fun dimensionsAndExcessiveWorkBoundsReject() {
        assertEquals("dimensions_changed", (tracker.locate(anchor(), VisualAnchorImage(80, 60, IntArray(4800))) as VisualAnchorMatch.Rejected).reason)
        val large = tracker.prepare(image(), VisualAnchorSpec("button", bounds, 60, 56, searchRadiusPx = 97))
        assertEquals("invalid_region", (large as VisualAnchorPreparation.Rejected).reason)
    }
    @Test fun nearbyBlankTargetMayKeepExplicitPlannerOffsetFromTexturedLandmark() {
        val spec = VisualAnchorSpec("landmark", bounds, 110, 96)
        val reference = (tracker.prepare(image(), spec) as VisualAnchorPreparation.Prepared).anchor
        val match = tracker.locate(reference, image(moved(7, -5))) as VisualAnchorMatch.Matched
        assertEquals(117, match.x); assertEquals(91, match.y)
        assertEquals(VisualAnchorRect(51, 35, 83, 67), match.bounds)
    }
    @Test fun anOffsetTargetMovingOutsideCurrentImageCannotProduceCoordinates() {
        val spec = VisualAnchorSpec("edge-target", bounds, 150, 56)
        val reference = (tracker.prepare(image(), spec) as VisualAnchorPreparation.Prepared).anchor
        assertEquals("target_out_of_bounds", (tracker.locate(reference, image(moved(15, 0))) as VisualAnchorMatch.Rejected).reason)
    }
    @Test fun explicitOffsetsBeyond128PixelsOrSourceImageAreRejected() {
        val source = scene()
        val large = VisualAnchorImage(300, 120, IntArray(36000) { index ->
            val x = index % 300; val y = index / 300
            if (x < 160) source[y * 160 + x] else 0xff202428.toInt()
        })
        assertEquals("invalid_region", (tracker.prepare(large, VisualAnchorSpec("far", bounds, 204, 56)) as VisualAnchorPreparation.Rejected).reason)
        assertEquals("invalid_region", (tracker.prepare(image(), VisualAnchorSpec("outside", bounds, 160, 56)) as VisualAnchorPreparation.Rejected).reason)
    }
}

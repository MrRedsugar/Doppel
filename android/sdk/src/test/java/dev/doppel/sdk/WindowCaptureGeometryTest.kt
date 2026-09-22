package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class WindowCaptureGeometryTest {
    @Test fun exactWindowPixelsKeepTheirScreenOffsetAndScale() {
        val bounds = listOf(27, 429, 1053, 1563)
        val placement = requireNotNull(WindowCaptureGeometry.placement(1026, 1134, bounds, 1080, 1920))
        assertEquals(listOf(0, 0, 1026, 1134), placement.sourceBounds)
        assertEquals(bounds, placement.displayBounds)
        assertFalse(placement.inferredSurfaceInsets)
        // Transparent pixels are legitimate when dimensions already establish the mapping.
        assertNotNull(WindowCaptureGeometry.placement(2, 2, listOf(3, 4, 5, 6), 10, 10, IntArray(4)))
    }

    @Test fun measuredPermissionWindowInsetsPreserveActualPixelCoordinates() {
        val width = 1218
        val height = 1326
        val pixels = IntArray(width * height)
        for (y in 144 until 1182) for (x in 144 until 1074) pixels[y * width + x] = 0xffeeeeee.toInt()
        val placement = requireNotNull(WindowCaptureGeometry.placement(width, height,
            listOf(27, 429, 1053, 1563), 1080, 1920, pixels))
        assertEquals(listOf(96, 96, 1122, 1230), placement.sourceBounds)
        assertEquals(listOf(27, 429, 1053, 1563), placement.displayBounds)
        assertTrue(placement.inferredSurfaceInsets)
        // Buffer pixel (144,144) stays at display (75,477), with no scaling.
        assertEquals(75, placement.displayBounds[0] + 144 - placement.sourceBounds[0])
        assertEquals(477, placement.displayBounds[1] + 144 - placement.sourceBounds[1])
    }

    @Test fun offscreenWindowsClipBothRectanglesByTheSameNumberOfPixels() {
        val placement = requireNotNull(WindowCaptureGeometry.placement(12, 14,
            listOf(-2, -3, 10, 11), 8, 9))
        assertEquals(listOf(2, 3, 10, 12), placement.sourceBounds)
        assertEquals(listOf(0, 0, 8, 9), placement.displayBounds)
        val pixels = IntArray(100)
        for (y in 3..6) for (x in 3..6) pixels[y * 10 + x] = 0xffffffff.toInt()
        val insetPlacement = requireNotNull(WindowCaptureGeometry.placement(10, 10,
            listOf(-1, -2, 5, 4), 4, 3, pixels))
        assertEquals(listOf(3, 4, 7, 7), insetPlacement.sourceBounds)
        assertEquals(listOf(0, 0, 4, 3), insetPlacement.displayBounds)
        for (bounds in listOf(listOf(-8, 0, -2, 6), listOf(10, 0, 16, 6),
            listOf(0, -6, 6, 0), listOf(0, 10, 6, 16))) {
            assertNull(WindowCaptureGeometry.placement(6, 6, bounds, 10, 10))
        }
    }

    @Test fun inferenceRequiresOpaqueContentAndRejectsOpaqueMargins() {
        val pixels = IntArray(100)
        for (y in 3..6) for (x in 3..6) pixels[y * 10 + x] = 0xffffffff.toInt()
        fun place(value: IntArray?) = WindowCaptureGeometry.placement(10, 10,
            listOf(5, 8, 11, 14), 20, 20, value)
        assertNotNull(place(pixels))
        assertNull(place(null))
        assertNull(place(IntArray(100)))
        for (alpha in listOf(1, 64, 254)) {
            assertNotNull("Semi-transparent shadow need not be symmetric", place(pixels.copyOf().also { it[0] = alpha shl 24 }))
        }
        assertNull(place(pixels.copyOf().also { it[0] = 0xff000000.toInt() }))
        assertNull("Shadow without any solid content cannot establish a content crop", place(IntArray(100) { 0x40000000 }))
        assertNotNull("Opaque content may be asymmetric while staying inside the crop", place(pixels.copyOf().also { it[32] = 0xffffffff.toInt() }))
    }

    @Test fun measuredAospDialogKeepsIts96PixelInsetsDespiteAsymmetricShadow() {
        val width = 1218
        val height = 1048
        val bounds = listOf(27, 568, 1053, 1424)
        val pixels = IntArray(width * height)
        // Recorded native dimensions and nonzero-alpha bounding box, including the lower drop shadow.
        for (y in 92 until 1033) for (x in 54 until 1171) pixels[y * width + x] = 0x40000000
        for (y in 96 until 952) for (x in 96 until 1122) pixels[y * width + x] = 0xffeeeeee.toInt()
        fun place(value: IntArray) = WindowCaptureGeometry.placement(width, height, bounds, 1080, 1920, value)
        val placement = requireNotNull(place(pixels))
        assertEquals(listOf(96, 96, 1122, 952), placement.sourceBounds)
        assertEquals(bounds, placement.displayBounds)
        assertTrue(placement.inferredSurfaceInsets)
        for ((x, y) in listOf(95 to 96, 1122 to 96, 96 to 95, 96 to 952)) {
            assertNull("Opaque content outside any crop edge must not be discarded: $x,$y",
                place(pixels.copyOf().also { it[y * width + x] = 0xffffffff.toInt() }))
        }
    }

    @Test fun rotatedScaledUnequalOrOddBufferMarginsAreUnsupported() {
        for ((width, height) in listOf(0 to 6, 6 to 0, -1 to 6, 5 to 5, 9 to 9, 10 to 12)) {
            assertNull(WindowCaptureGeometry.placement(width, height, listOf(0, 0, 6, 6),
                20, 20, if (width > 0 && height > 0) IntArray(width * height) else null))
        }
        assertNull(WindowCaptureGeometry.placement(1920, 1080, listOf(0, 0, 1080, 1920), 1080, 1920))
        assertNull(WindowCaptureGeometry.placement(10, 10, listOf(0, 0, 6, 6), 20, 20, IntArray(99)))
    }

    @Test fun displaySizedImeUsesScreenCoordinatesOnlyWhenAlphaConfirmsItsTouchableBounds() {
        val pixels = IntArray(8 * 12)
        for (y in 8 until 12) for (x in 0 until 8) pixels[y * 8 + x] = 0xffdddddd.toInt()
        val bounds = listOf(0, 8, 8, 12)
        fun place(value: IntArray = pixels, actualBounds: List<Int> = bounds, allow: Boolean = true) =
            WindowCaptureGeometry.placement(8, 12, actualBounds, 8, 12, value, allow)
        val placement = requireNotNull(place())
        assertEquals(bounds, placement.sourceBounds)
        assertEquals(bounds, placement.displayBounds)
        assertTrue(placement.inferredDisplayOrigin)
        assertFalse(placement.inferredSurfaceInsets)
        assertNull(place(allow = false))
        assertNull(place(IntArray(96)))
        assertNull(place(pixels.copyOf().also { it[7 * 8] = 0x01000000 }))
        assertNull(place(actualBounds = listOf(0, 7, 8, 12)))
        assertNull(place(actualBounds = listOf(1, 8, 8, 12)))
    }

    @Test fun invalidBoundsAndIntegerOverflowCannotProduceAValidPlacement() {
        for (bounds in listOf(emptyList(), listOf(0, 0, 6), listOf(1, 1, 1, 6),
            listOf(0, 5, 6, 4), listOf(Int.MIN_VALUE, 0, Int.MAX_VALUE, 6))) {
            assertNull(WindowCaptureGeometry.placement(6, 6, bounds, 10, 10))
        }
        assertNull(WindowCaptureGeometry.placement(6, 6, listOf(0, 0, 6, 6), 0, 10))
        assertNull(WindowCaptureGeometry.placement(6, 6, listOf(0, 0, 6, 6), 10, -1))
    }
}

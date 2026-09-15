package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class ScreenPixelContentTest {
    @Test fun allBlackRgbIsEmptyRegardlessOfAlpha() {
        val black = IntArray(256) { alpha -> alpha shl 24 }
        assertFalse(ScreenPixelContent.hasVisibleRgb(black))
        assertFalse(ScreenPixelContent.hasVisibleRgb(IntArray(1080 * 607) { 0xff000000.toInt() }))
    }
    @Test fun evenOneMinimalNonzeroChannelAmongBlackPixelsIsRetained() {
        for (pixel in listOf(0xff000001.toInt(), 0xff000100.toInt(), 0xff010000.toInt())) {
            for (index in listOf(0, 255, 1023)) {
                val pixels = IntArray(1024) { 0xff000000.toInt() }.apply { this[index] = pixel }
                assertTrue(ScreenPixelContent.hasVisibleRgb(pixels))
            }
        }
    }
    @Test fun veryDarkScenesAreNotDiscardedByAverageBrightnessOrArea() {
        assertTrue(ScreenPixelContent.hasVisibleRgb(IntArray(1080 * 607) { 0xff010101.toInt() }))
        val tinyDetail = IntArray(1080 * 607) { 0xff000000.toInt() }.apply { this[lastIndex] = 0xff010101.toInt() }
        assertTrue(ScreenPixelContent.hasVisibleRgb(tinyDetail))
    }
    @Test fun alphaDoesNotHideNonzeroRgbInTheExactContentProbe() {
        for (alpha in 0..255) assertTrue(ScreenPixelContent.hasVisibleRgb(intArrayOf(alpha shl 24 or 1)))
    }
    @Test fun noPixelsCannotEstablishVisibleContentAndInputIsNeverModified() {
        assertFalse(ScreenPixelContent.hasVisibleRgb(intArrayOf()))
        val pixels = intArrayOf(0xff000000.toInt(), 0xff000001.toInt(), 0x11000000)
        val before = pixels.copyOf()
        assertTrue(ScreenPixelContent.hasVisibleRgb(pixels)); assertArrayEquals(before, pixels)
    }
}

package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class ScreenPrivacyMaskTest {
    @Test fun scaledBoundsMaskPasswordAndAntialiasedEdgesWithoutMovingOrHidingAdjacentControls() {
        val pixels = IntArray(100) { it }
        ScreenPrivacyMask.apply(pixels, 10, 10, 100, 100, listOf(listOf(20, 30, 60, 50)))
        for (y in 0..9) for (x in 0..9) {
            val expected = if (x in 1..6 && y in 2..5) 0xff333333.toInt() else y * 10 + x
            assertEquals("Pixel $x,$y", expected, pixels[y * 10 + x])
        }
    }
    @Test fun overlappingAndPartiallyOffscreenPrivateFieldsStayWithinTheImage() {
        val pixels = IntArray(100) { 0xffffffff.toInt() }
        ScreenPrivacyMask.apply(pixels, 10, 10, 10, 10, listOf(listOf(-5, -5, 3, 3), listOf(2, 2, 30, 30)))
        assertEquals(0xff333333.toInt(), pixels[0])
        assertEquals(0xff333333.toInt(), pixels[99])
        assertEquals(0xffffffff.toInt(), pixels[9])
    }
}

package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class ThemePaletteTest {
    @Test fun systemAppearanceTracksSystemWhileExplicitChoiceDoesNot() {
        assertFalse(ThemeMode.SYSTEM.isDark(false)); assertTrue(ThemeMode.SYSTEM.isDark(true))
        assertFalse(ThemeMode.LIGHT.isDark(true)); assertTrue(ThemeMode.DARK.isDark(false))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse("unknown")); assertEquals(ThemeMode.SYSTEM, ThemeMode.parse(null))
    }

    @Test fun transitionsReachExactEndpointsAndPreserveAlpha() {
        assertEquals(ThemePalette.light, ThemePalette.light.blend(ThemePalette.dark, -1f))
        assertEquals(ThemePalette.dark, ThemePalette.light.blend(ThemePalette.dark, 2f))
        val midpoint = ThemePalette.light.blend(ThemePalette.dark, 0.5f)
        assertNotEquals(ThemePalette.light, midpoint); assertNotEquals(ThemePalette.dark, midpoint)
        midpoint.values().forEach { assertEquals(255, it ushr 24) }
        assertEquals(0x80808080.toInt(), ThemePalette.blendColor(0, 0xffffffff.toInt(), 0.5f))
    }

    @Test fun bothThemesKeepBodyTextAndPrimaryActionsReadable() {
        for (palette in listOf(ThemePalette.light, ThemePalette.dark)) {
            assertTrue(contrast(palette.ink, palette.background) >= 7.0)
            assertTrue(contrast(palette.muted, palette.background) >= 4.5)
            assertTrue(contrast(palette.onPrimary, palette.ink) >= 7.0)
            palette.values().forEachIndexed { index, color -> assertEquals(color, palette.colorAt(index)) }
        }
    }

    private fun contrast(first: Int, second: Int): Double {
        fun light(color: Int): Double {
            fun channel(shift: Int): Double {
                val value = (color ushr shift and 255) / 255.0
                return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
        val a = light(first); val b = light(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}

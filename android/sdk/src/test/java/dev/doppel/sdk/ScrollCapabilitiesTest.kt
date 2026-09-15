package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class ScrollCapabilitiesTest {
    @Test fun reproducedBottomBoundaryNeverOffersForwardOrHorizontalScroll() {
        val observed = setOf(1, 4, 8, 64, 8192, 16908342, 16908343)
        assertEquals(listOf("up"), ScrollCapabilities.directions(observed))
        assertEquals(8192, ScrollCapabilities.action("up", observed))
        assertNull(ScrollCapabilities.action("down", observed))
        assertNull(ScrollCapabilities.action("left", observed))
    }
    @Test fun explicitDirectionsTakePrecedenceWithoutInventingOtherAxes() {
        val observed = setOf(4096, 8192, 16908345)
        assertEquals(listOf("left"), ScrollCapabilities.directions(observed))
        assertEquals(16908345, ScrollCapabilities.action("left", observed))
        assertNull(ScrollCapabilities.action("down", observed))
    }
    @Test fun legacyVerticalDirectionsAndUnavailableNodesRemainDistinct() {
        assertEquals(listOf("up", "down"), ScrollCapabilities.directions(setOf(4096, 8192)))
        assertTrue(ScrollCapabilities.directions(emptySet()).isEmpty())
        assertNull(ScrollCapabilities.action("sideways", setOf(4096)))
        assertEquals(16908346, ScrollCapabilities.action("down", setOf(4096, 16908346)))
    }
}

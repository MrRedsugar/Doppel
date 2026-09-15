package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class VisualPixelAnchorTest {
    private data class Scene(val width: Int, val height: Int, val cx: Int, val cy: Int, val pixels: IntArray) {
        val radius get() = (min(width, height) * .0125).toInt().coerceIn(8, 16)
        fun image(values: IntArray = pixels) = VisualPixels(width, height, values)
        fun gesture(kind: String = "tap", endX: Int? = null) = VisualGesture(kind, "capture", cx.toDouble() / width, cy.toDouble() / height,
            endX?.toDouble()?.div(width), endX?.let { cy.toDouble() / height }, if (kind == "tap") 80 else 600, "按钮", "当前界面", "safe")
        fun paint(values: IntArray, left: Int, top: Int, right: Int, bottom: Int, color: Int) {
            for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(height)) for (x in left.coerceAtLeast(0) until right.coerceAtMost(width)) values[y * width + x] = color
        }
        fun glyph(values: IntArray, x: Int = cx, y: Int = cy, plus: Boolean = true, color: Int = 0xe8d87e) {
            val half = radius - 2
            for (dy in -half until half) for (dx in -half until half) {
                if (abs(dy) <= 1 || plus && abs(dx) <= 1) values[(y + dy) * width + x + dx] = color
            }
        }
        fun animated(): IntArray = pixels.copyOf().also { values ->
            for (y in 0 until height) for (x in 0 until width) {
                if (abs(x - cx) >= radius * 3 || abs(y - cy) >= radius * 3) values[y * width + x] = values[y * width + x] xor 0xffffff
            }
        }
    }
    private fun scene(seed: Int = 0): Scene {
        val width = 240 + seed * 16; val height = 180 + seed * 12
        val scene = Scene(width, height, 50 + seed * 13 % (width - 100), 50 + seed * 17 % (height - 100),
            IntArray(width * height) { index -> val value = 20 + (index * 17 + seed * 23) % 80; value shl 16 or (value shl 8) or value })
        scene.paint(scene.pixels, scene.cx - scene.radius * 3, scene.cy - scene.radius * 3,
            scene.cx + scene.radius * 3, scene.cy + scene.radius * 3, 0x151719)
        scene.glyph(scene.pixels)
        return scene
    }

    @Test fun animatedBackgroundWithStableTexturedTargetPassesAcrossSizesAndLocations() {
        for (seed in 0..11) {
            val scene = scene(seed)
            val result = scene.image().compare(scene.image(scene.animated()), scene.gesture())
            assertTrue("Seed $seed: ${result.json()}", result.matches); assertEquals("target_anchor", result.mode)
            assertEquals("stable", result.reason); assertTrue(result.json().getDouble("global_changed_fraction") > .30)
            assertEquals(1.0, result.json().getDouble("core_edge_agreement_min"), .0001)
        }
    }
    @Test fun pulsingOuterGlowMayChangeWhileItsCoreGlyphAndBoundedNeighbourhoodStayStable() {
        for (seed in 0..5) {
            val scene = scene(seed); val animated = scene.animated()
            for (dy in -scene.radius * 2 until scene.radius * 2) for (dx in -scene.radius * 2 until scene.radius * 2) {
                if (max(abs(dx), abs(dy)) in scene.radius + 2..scene.radius + 3) animated[(scene.cy + dy) * scene.width + scene.cx + dx] = 0xc8bd3c
            }
            val result = scene.image().compare(scene.image(animated), scene.gesture())
            assertTrue(result.json().toString(), result.matches); assertEquals("target_anchor", result.mode)
            assertTrue(result.json().getDouble("local_changed_fraction_max") > .15)
            assertEquals(0.0, result.json().getDouble("core_mean_max"), 0.0)
        }
    }
    @Test fun motionCannotAuthorizeAnUntexturedPointOrAControlNearAnUnobservedEdge() {
        val scene = scene(); scene.paint(scene.pixels, scene.cx - 8, scene.cy - 8, scene.cx + 8, scene.cy + 8, 0x151719)
        val result = scene.image().compare(scene.image(scene.animated()), scene.gesture())
        assertFalse(result.matches); assertEquals("anchor_missing", result.reason)
        val edge = Scene(240, 180, 3, 90, IntArray(240 * 180) { 0x151719 })
        for (y in 84 until 96) for (x in 0 until 9) edge.pixels[y * edge.width + x] = if ((x + y) % 2 == 0) 0xe8d87e else 0x151719
        val edgeResult = edge.image().compare(edge.image(edge.animated()), edge.gesture())
        assertFalse(edgeResult.matches); assertEquals("anchor_missing", edgeResult.reason)
    }
    @Test fun movedRecolouredObscuredOrChangedActionGlyphsNeverUseAnimationFallback() {
        for (seed in 0..7) for (change in listOf("moved", "colour", "obscured", "minus")) {
            val scene = scene(seed); val changed = scene.animated()
            scene.paint(changed, scene.cx - scene.radius, scene.cy - scene.radius, scene.cx + scene.radius, scene.cy + scene.radius, 0x151719)
            when (change) {
                "moved" -> scene.glyph(changed, x = scene.cx + 3)
                "colour" -> scene.glyph(changed, color = 0x397cf0)
                "obscured" -> scene.paint(changed, scene.cx - 8, scene.cy - 8, scene.cx + 8, scene.cy + 8, 0xc0c0c0)
                else -> scene.glyph(changed, plus = false)
            }
            val result = scene.image().compare(scene.image(changed), scene.gesture())
            assertFalse("$seed / $change: ${result.json()}", result.matches); assertEquals("core_changed", result.reason)
        }
    }
    @Test fun aDifferentNeighbourhoodCannotBorrowAnUnchangedCenterIcon() {
        val scene = scene(); val changed = scene.animated()
        scene.paint(changed, scene.cx - scene.radius * 3, scene.cy - scene.radius * 3,
            scene.cx + scene.radius * 3, scene.cy + scene.radius * 3, 0xc0c0c0)
        // Replace the neighbourhood, then preserve the entire 16×16 core, including its top/left border.
        for (y in scene.cy - scene.radius until scene.cy + scene.radius) for (x in scene.cx - scene.radius until scene.cx + scene.radius) {
            changed[y * scene.width + x] = scene.pixels[y * scene.width + x]
        }
        val result = scene.image().compare(scene.image(changed), scene.gesture())
        assertEquals(0.0, result.json().getDouble("core_mean_max"), 0.0)
        assertEquals(1.0, result.json().getDouble("core_edge_agreement_min"), 0.0)
        assertFalse(result.matches); assertEquals("context_changed", result.reason)
    }
    @Test fun tinyActionGlyphChangeAlsoFailsWhenTheWholeScreenWouldLookStable() {
        val scene = scene(); val changed = scene.pixels.copyOf()
        scene.paint(changed, scene.cx - 8, scene.cy - 8, scene.cx + 8, scene.cy + 8, 0x151719); scene.glyph(changed, plus = false)
        val result = scene.image().compare(scene.image(changed), scene.gesture())
        assertFalse(result.matches); assertEquals("core_changed", result.reason)
        assertTrue(result.json().getDouble("global_mean") < 24)
    }
    @Test fun aSwipeChecksInterveningPixelsAndRejectsAnObstacleBetweenTheOldFiveSamples() {
        val scene = Scene(240, 180, 40, 90, IntArray(240 * 180) { 0x151719 })
        scene.glyph(scene.pixels); scene.glyph(scene.pixels, x = 200)
        val gesture = scene.gesture("swipe", 200)
        assertTrue(scene.image().matches(scene.image(scene.pixels.copyOf()), gesture))
        val changed = scene.pixels.copyOf()
        scene.paint(changed, 58, 87, 62, 94, 0xf0c030)
        val result = scene.image().compare(scene.image(changed), gesture)
        assertFalse(result.matches); assertEquals("path_changed", result.reason)
        assertTrue(result.json().getInt("path_points") > 5)
    }
    @Test fun stableFlatScreensRetainStrictPathAndDimensionChangesAlwaysReject() {
        val scene = Scene(240, 180, 120, 90, IntArray(240 * 180) { 0x646464 })
        val result = scene.image().compare(scene.image(IntArray(240 * 180) { 0x6e6e6e }), scene.gesture())
        assertTrue(result.matches); assertEquals("strict", result.mode)
        assertFalse(result.json().getBoolean("endpoint_anchors"))
        val wrongSize = scene.image().compare(VisualPixels(120, 90, IntArray(120 * 90) { 0x646464 }), scene.gesture())
        assertFalse(wrongSize.matches); assertEquals("dimensions", wrongSize.reason)
        assertFalse(result.json().toString().contains("capture")); assertFalse(result.json().has("x")); assertFalse(result.json().has("pixels"))
    }
}

package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VisualGestureTest {
    private fun candidate() = JSONObject().put("kind", "tap").put("capture_id", "capture-a")
        .put("x", .4).put("y", .6).put("duration_ms", 80).put("label", "开始行动")
        .put("screen_context", "关卡准备界面").put("safety", "safe")
    private fun frame() = VisualFrame("capture-a", "screen-a", "dev.fixture", 1920, 1080, 960, 540, 1, 1000, 46000, "hash-a")
    private fun invalid(body: JSONObject) { assertThrows(IllegalArgumentException::class.java) { VisualGesture.parse(body) } }

    @Test fun normalizedCoordinatesMapToPhysicalDisplayAndRejectEdgesOutsideDisplay() {
        val gesture = VisualGesture.parse(candidate())
        assertEquals(768f, gesture.start(frame()).x, 0f)
        assertEquals(648f, gesture.start(frame()).y, 0f)
        for (value in listOf(-.01, 1.0, 1.1, "0.4", JSONObject.NULL)) invalid(candidate().put("x", value))
        invalid(candidate().put("duration_ms", 80.5))
        invalid(candidate().put("x", 0.4).put("unexpected", true))
    }
    @Test fun swipeRequiresSeparateFiniteEndpointAndBoundedDuration() {
        val swipe = candidate().put("kind", "swipe").put("end_x", .7).put("end_y", .6).put("duration_ms", 700)
        assertEquals(1344f, VisualGesture.parse(swipe).end(frame())!!.x, 0f)
        invalid(candidate().put("kind", "swipe"))
        invalid(JSONObject(swipe.toString()).put("end_x", .4))
        invalid(JSONObject(swipe.toString()).put("duration_ms", 5001))
        invalid(candidate().put("end_x", .8))
        invalid(candidate().put("kind", "long_press").put("duration_ms", 80))
    }
    @Test fun nearOneNormalizedEndpointAndItsEntirePathRemainInPhysicalDisplay() {
        val value = candidate().put("kind", "swipe").put("x", .999999).put("y", .2)
            .put("end_x", .999999).put("end_y", .8).put("duration_ms", 700)
        val gesture = VisualGesture.parse(value)
        assertEquals(1919f, gesture.start(frame()).x, 0f)
        assertEquals(1919f, gesture.end(frame())!!.x, 0f)
        assertTrue(gesture.geometry(frame()).path.all { it.x in 0f..1919f && it.y in 0f..1079f })
    }
    @Test fun frameMustMatchAllDimensionsOrientationPackageScreenAndLifetime() {
        val frame = frame()
        assertTrue(frame.matches("screen-a", "dev.fixture", 1920, 1080, 1, 1200))
        assertFalse(frame.matches("screen-b", "dev.fixture", 1920, 1080, 1, 1200))
        assertFalse(frame.matches("screen-a", "dev.other", 1920, 1080, 1, 1200))
        assertFalse(frame.matches("screen-a", "dev.fixture", 1080, 1920, 0, 1200))
        assertFalse(frame.matches("screen-a", "dev.fixture", 1920, 1080, 1, 46001))
        assertFalse(frame.matches("screen-a", "dev.fixture", 1920, 1080, 1, 999))
        assertEquals(frame, VisualFrame.parse(frame.json()))
    }
    @Test fun pixelCheckToleratesSmallBrightnessAnimationButRejectsNewTargetAndLargeOverlay() {
        val original = VisualPixels(100, 100, ByteArray(10000) { 100 })
        assertTrue(original.matches(VisualPixels(100, 100, ByteArray(10000) { 110 }), VisualGesture.parse(candidate())))
        val changed = ByteArray(10000) { 100 }
        for (y in 50..70) for (x in 30..50) changed[y * 100 + x] = 220.toByte()
        assertFalse(original.matches(VisualPixels(100, 100, changed), VisualGesture.parse(candidate())))
        assertFalse(original.matches(VisualPixels(100, 100, ByteArray(10000) { 200.toByte() }), VisualGesture.parse(candidate())))
        assertFalse(original.matches(VisualPixels(50, 50, ByteArray(2500) { 100 }), VisualGesture.parse(candidate())))
    }
    @Test fun missingSafetyOrPaymentVerificationAndSensitiveInputAreNeverAuthorized() {
        assertNull(VisualGesture.parse(candidate()).blockedReason(emptyList(), false))
        assertEquals("payment", VisualGesture.parse(candidate().put("label", "确认支付")).blockedReason(emptyList(), false))
        assertEquals("payment", VisualGesture.parse(candidate()).blockedReason(listOf("收银台"), false))
        assertEquals("verification", VisualGesture.parse(candidate()).blockedReason(listOf("安全验证"), false))
        assertEquals("sensitive", VisualGesture.parse(candidate()).blockedReason(emptyList(), true))
        assertEquals("uncertain", VisualGesture.parse(candidate().put("safety", "uncertain")).blockedReason(emptyList(), false))
        invalid(candidate().apply { remove("safety") })
    }
    @Test fun similarlyBrightButDifferentColorTargetsDoNotPassPixelCheck() {
        val red = VisualPixels(100, 100, IntArray(10000) { 0x640000 })
        val green = VisualPixels(100, 100, IntArray(10000) { 0x003300 })
        assertFalse(red.matches(green, VisualGesture.parse(candidate())))
    }
    @Test fun hostPermitCannotBeForgedReplayedOrTransferredToAnotherActionOrRun() {
        val gesture = VisualGesture.parse(candidate())
        assertThrows(IllegalArgumentException::class.java) { VisualGesturePermits.issue("r", gesture, "assist", false, 1000) }
        assertFalse(VisualGesturePermits.consume("invented", "r", gesture, 1001))
        val token = VisualGesturePermits.issue("r", gesture, "assist", true, 1000)
        assertFalse(VisualGesturePermits.consume(token, "other", gesture, 1001))
        val token2 = VisualGesturePermits.issue("r", gesture, "full", false, 1000)
        assertTrue(VisualGesturePermits.consume(token2, "r", gesture, 1001))
        assertFalse(VisualGesturePermits.consume(token2, "r", gesture, 1002))
        val token3 = VisualGesturePermits.issue("r", gesture, "full", false, 1000)
        assertFalse(VisualGesturePermits.consume(token3, "r", VisualGesture.parse(candidate().put("x", .5)), 1001))
        val token4 = VisualGesturePermits.issue("r", gesture, "full", false, 1000)
        VisualGesturePermits.revoke("r")
        assertFalse(VisualGesturePermits.consume(token4, "r", gesture, 1001))
    }
}

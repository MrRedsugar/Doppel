package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectVisualPixelsTest {
    private fun frame() = VisualFrame("capture-a", "screen-a", "dev.fixture", 3200, 1440, 1080, 486, 1, 1000, 46000, "hash-a")
    private fun candidate() = JSONObject().put("capture_id", "capture-a").put("x", 540).put("y", 243)
        .put("duration_ms", 80).put("label", "Visible target").put("screen_context", "Current canvas").put("safety", "safe")

    @Test fun convertsImagePixelsUsingOnlyDeviceOwnedDimensionsAcrossOrientations() {
        val landscape = DirectGroundingPixels.parseProposal("propose_tap", candidate(), frame())
        assertEquals(.5, landscape.x, 0.0); assertEquals(.5, landscape.y, 0.0)
        assertEquals(1600f, landscape.start(frame()).x, 0f); assertEquals(720f, landscape.start(frame()).y, 0f)
        val portrait = frame().copy(displayWidth = 1440, displayHeight = 3200, imageWidth = 486, imageHeight = 1080, rotation = 0)
        val gesture = DirectGroundingPixels.parseProposal("propose_tap", candidate().put("x", 243).put("y", 540), portrait)
        assertEquals(.5, gesture.x, 0.0); assertEquals(.5, gesture.y, 0.0)
        assertEquals(720f, gesture.start(portrait).x, 0f); assertEquals(1600f, gesture.start(portrait).y, 0f)
    }

    @Test fun zeroAndLastPixelsAreValidWithoutDividingByTheLastPixelIndex() {
        val first = DirectGroundingPixels.parseProposal("propose_tap", candidate().put("x", 0).put("y", 0), frame())
        assertEquals(0.0, first.x, 0.0); assertEquals(0.0, first.y, 0.0)
        val edgeFrame = frame().copy(imageWidth = 1000, imageHeight = 500)
        val last = DirectGroundingPixels.parseProposal("propose_tap", candidate().put("x", 999).put("y", 499), edgeFrame)
        assertEquals(.999, last.x, 0.0); assertEquals(.998, last.y, 0.0)
        assertEquals(last, VisualGesture.parse(last.json()))
    }

    @Test fun convertsBothSwipeEndpointsAndPreservesTheExistingGestureShape() {
        val body = candidate().put("x", 270).put("y", 243).put("end_x", 810).put("end_y", 324).put("duration_ms", 500)
        val original = body.toString()
        val gesture = DirectGroundingPixels.parseProposal("propose_swipe", body, frame())
        assertEquals(.25, gesture.x, 0.0); assertEquals(.5, gesture.y, 0.0)
        assertEquals(.75, gesture.endX!!, 0.0); assertEquals(2.0 / 3.0, gesture.endY!!, 0.0)
        assertEquals(2400f, gesture.end(frame())!!.x, 0f); assertEquals(960f, gesture.end(frame())!!.y, .001f)
        assertEquals(original, body.toString()); assertEquals(gesture, VisualGesture.parse(gesture.json()))
    }

    @Test fun rejectsNonIntegerNonFiniteMissingNegativeAndOutsidePixelsForEveryEndpoint() {
        for (key in listOf("x", "y", "end_x", "end_y")) {
            val maximum = if (key.endsWith("x")) 1080 else 486
            for (bad in listOf(.4, 1.5, "540", JSONObject.NULL, true, -1, maximum, Int.MAX_VALUE, Long.MAX_VALUE)) {
                val body = candidate().put("end_x", 810).put("end_y", 324).put("duration_ms", 500).put(key, bad)
                assertThrows("Reject $key=$bad", VisualValidationException::class.java) {
                    DirectGroundingPixels.parseProposal("propose_swipe", body, frame())
                }
            }
            val absent = candidate().put("end_x", 810).put("end_y", 324).put("duration_ms", 500).apply { remove(key) }
            assertThrows(VisualValidationException::class.java) { DirectGroundingPixels.parseProposal("propose_swipe", absent, frame()) }
            for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                // org.json blocks these on put; inject the accessor value to verify our own host boundary.
                val source = candidate().put("end_x", 810).put("end_y", 324).put("duration_ms", 500)
                val body = object : JSONObject() {
                    override fun opt(name: String): Any? = if (name == key) bad else super.opt(name)
                }
                // The string constructor calls virtual opt while detecting duplicate keys; populate finite fields directly.
                for (name in source.keys()) body.put(name, source.get(name))
                val rejected = assertThrows(VisualValidationException::class.java) { DirectGroundingPixels.parseProposal("propose_swipe", body, frame()) }
                assertEquals(VisualValidationReason.PIXEL_COORDINATE_RANGE, rejected.reason); assertEquals(key, rejected.field)
            }
        }
    }

    @Test fun modelCannotSupplyDimensionsAuthorityGestureKindOrUnexpectedEndpoints() {
        for (key in listOf("image_width", "image_height", "display_width", "display_height", "approved", "visual_permit", "kind", "unknown")) {
            assertThrows(VisualValidationException::class.java) {
                DirectGroundingPixels.parseProposal("propose_tap", candidate().put(key, 1), frame())
            }
        }
        for (tool in listOf("propose_tap", "propose_long_press")) for (key in listOf("end_x", "end_y")) {
            assertThrows(VisualValidationException::class.java) {
                DirectGroundingPixels.parseProposal(tool, candidate().put("duration_ms", if (tool == "propose_tap") 80 else 700)
                    .put(key, JSONObject.NULL), frame())
            }
        }
        assertThrows(VisualValidationException::class.java) { DirectGroundingPixels.parseProposal("propose_gesture", candidate(), frame()) }
    }
}

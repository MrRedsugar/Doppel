package dev.doppel.sdk

import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalVisualMotorTest {
    private fun pixels() = IntArray(160 * 120) { index ->
        val x = index % 160; val y = index / 160
        if (x in 44 until 76 && y in 40 until 72) {
            val v = (x * 83 + y * 47 + x * y * 7) and 255
            0xff000000.toInt() or (v shl 16) or ((v xor 137) shl 8) or (v xor 51)
        } else 0xff202428.toInt()
    }
    private fun capture(id: String = "source", at: Long = 1000, displayWidth: Int = 320): JSONObject {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/motor-icon.png")).use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return JSONObject().put("image_base64", Base64.getEncoder().encodeToString(bytes)).put("mime_type", "image/png")
            .put("visual_frame", VisualFrame(id, "screen-$id", "example.app", displayWidth, 240, 160, 120, 1, at, at + 45000, sha).json())
    }
    private fun proposal(): JSONObject = JSONObject().put("capture_id", "source").put("coordinate_space", "image_pixels")
        .put("anchors", JSONArray().put(JSONObject().put("id", "glyph").put("bounds", JSONArray(listOf(44, 40, 76, 72))).put("x", 60).put("y", 56)))
        .put("steps", JSONArray().put(JSONObject().put("kind", "tap").put("start_anchor_id", "glyph").put("duration_ms", 80)
            .put("label", "打开设置").put("screen_context", "当前应用工具栏").put("safety", "safe")))
    private val decoder: (ByteArray) -> VisualAnchorImage? = { VisualAnchorImage(160,120,pixels()) }

    private fun motor(proposal: JSONObject = proposal()) = (LocalVisualMotor.prepare(proposal, capture(), 1001, decoder) as LocalVisualMotorPreparation.Prepared).motor

    @Test fun outputUsesFreshFrameAndNormalizedProductionGestureWithoutPermit() {
        val ready = motor().evaluate(capture("fresh", 1002), 1003) as LocalVisualMotorDecision.Ready
        val action = ready.action
        assertEquals("visual_gesture", action.getString("kind")); assertEquals("screen-fresh", action.getString("screen_id"))
        assertFalse(action.has("visual_permit"))
        val gesture = VisualGesture.parse(action.getJSONObject("gesture"))
        assertEquals("fresh", gesture.captureId); assertEquals(60.0 / 160, gesture.x, .000001)
        assertEquals(56.0 / 120, gesture.y, .000001)
        assertEquals(120f, gesture.start(VisualFrame.parse(action.getJSONObject("visual_frame"))).x, .001f)
    }
    @Test fun commandBindingRequiresExactIssuedTokenAndReceipt() {
        val motor = motor(); val ready = motor.evaluate(capture(), 1001) as LocalVisualMotorDecision.Ready
        assertFalse(motor.bindCommand("wrong", "command-a")); assertTrue(motor.bindCommand(ready.token, "command-a"))
        assertFalse(motor.bindCommand(ready.token, "command-b")); assertFalse(motor.acknowledge("other", true, 1002))
        assertTrue(motor.evaluate(capture("new", 1003), 1003) is LocalVisualMotorDecision.Waiting)
        assertTrue(motor.acknowledge("command-a", true, 1004))
        assertFalse(motor.acknowledge("command-a", true, 1005))
        assertTrue(motor.evaluate(capture("final", 1006), 1006) is LocalVisualMotorDecision.Complete)
    }
    @Test fun riskClassificationIsPreservedForHostToRejectRatherThanBecomingAuthority() {
        val spec = proposal(); spec.getJSONArray("steps").getJSONObject(0).put("safety", "payment").put("label", "确认支付")
        val ready = motor(spec).evaluate(capture(), 1001) as LocalVisualMotorDecision.Ready
        assertEquals("payment", VisualGesture.parse(ready.action.getJSONObject("gesture")).blockedReason(emptyList(), false))
        assertFalse(ready.action.has("approved")); assertFalse(ready.action.has("visual_permit"))
    }
    @Test fun invalidDigestAndDecoderDimensionMismatchNeverCreatePlan() {
        val bad = capture(); bad.getJSONObject("visual_frame").put("sha256", "a".repeat(64))
        assertTrue(LocalVisualMotor.prepare(proposal(), bad, 1001, decoder) is LocalVisualMotorPreparation.Rejected)
        assertTrue(LocalVisualMotor.prepare(proposal(), capture(), 1001) { VisualAnchorImage(80, 60, IntArray(4800)) } is LocalVisualMotorPreparation.Rejected)
    }
    @Test fun forgedFieldsFractionalPixelsAndWrongCoordinateSpacesReject() {
        val forged = proposal().put("visual_permit", "invented")
        assertTrue(LocalVisualMotor.prepare(forged, capture(), 1001, decoder) is LocalVisualMotorPreparation.Rejected)
        val fractional = proposal(); fractional.getJSONArray("anchors").getJSONObject(0).put("x", 60.5)
        assertTrue(LocalVisualMotor.prepare(fractional, capture(), 1001, decoder) is LocalVisualMotorPreparation.Rejected)
        assertTrue(LocalVisualMotor.prepare(proposal().put("coordinate_space", "normalized"), capture(), 1001, decoder) is LocalVisualMotorPreparation.Rejected)
    }
    @Test fun cancelCaptureTamperingAndChangedDisplayEndSegment() {
        val cancelled = motor(); cancelled.cancel()
        assertEquals("cancelled", (cancelled.evaluate(capture(), 1001) as LocalVisualMotorDecision.Stopped).reason)
        val changed = capture(); changed.put("image_base64", changed.getString("image_base64").dropLast(8))
        assertEquals("capture_invalid", (motor().evaluate(changed, 1001) as LocalVisualMotorDecision.Stopped).reason)
        assertEquals("display_dimensions_changed", (motor().evaluate(capture(displayWidth = 640), 1001) as LocalVisualMotorDecision.Stopped).reason)
    }
    @Test fun sourceMaySurviveSlowPlanningButExecutionMustUseRecentCapture() {
        val prepared = LocalVisualMotor.prepare(proposal(), capture(), 8000, decoder) as LocalVisualMotorPreparation.Prepared
        assertTrue(prepared.motor.evaluate(capture("new", 8001), 8002) is LocalVisualMotorDecision.Ready)
        assertTrue(motor().evaluate(capture(), 3000) is LocalVisualMotorDecision.Stopped)
    }
    @Test fun schemaAdvertisesActualImagePixelRangeAndNoAuthorityFields() {
        val schema = LocalVisualMotor.tool(VisualFrame.parse(capture().getJSONObject("visual_frame")))
        assertEquals("execute_visual_plan", schema.getJSONObject("function").getString("name"))
        val anchor = schema.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
            .getJSONObject("anchors").getJSONObject("items").getJSONObject("properties")
        assertEquals(159, anchor.getJSONObject("x").getInt("maximum")); assertEquals(119, anchor.getJSONObject("y").getInt("maximum"))
        assertFalse(schema.toString().contains("visual_permit"))
    }
}

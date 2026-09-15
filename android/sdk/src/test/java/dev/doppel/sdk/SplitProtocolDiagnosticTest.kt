package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SplitProtocolDiagnosticTest {
    @Test fun rejectedWireObjectSurvivesOnlyAsStructureAndCorrectionCanContinue() {
        val engine = SplitTaskEngine(null, {}, { 1000L })
        val id = engine.create(JSONObject().put("goal", "打开设置").put("mode", "full").put("device_id", "direct-this-phone")).getString("id")
        val capture = engine.poll().getJSONObject("command")
        engine.result(JSONObject().put("run_id", id).put("command_id", capture.getString("id")).put("status", "ok")
            .put("observation", JSONObject()).put("data", JSONObject().put("image_base64", "frame")
                .put("visual_frame", JSONObject().put("capture_id", "current"))))
        fun response(value: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
            .put("message", JSONObject().put("content", value.toString()))))
        val decision = JSONObject().put("kind", "tap").put("target", "private-target-value")
            .put("expected", "private-expected-value").put("screen_context", "private-context-value")
        val bad = JSONObject().put("decision", decision).put("state", JSONObject().put("phase", "private-phase-value"))
        engine.accept(engine.takeWork()!!, response(bad))
        val failedRun = engine.get(id)
        val diagnostic = failedRun.getJSONObject("grounding_result").getJSONObject("protocol_diagnostic")
        assertTrue(diagnostic.getBoolean("output_parsed"))
        assertEquals("$.state", diagnostic.getJSONObject("validation").getString("path"))
        assertEquals("object", diagnostic.getJSONObject("validation").getString("schema_branch"))
        val shape = diagnostic.getJSONObject("output_shape").getJSONObject("fields")
        assertEquals("string", shape.getJSONObject("state").getJSONObject("fields").getJSONObject("phase").getString("type"))
        assertEquals("string", shape.getJSONObject("decision").getJSONObject("fields").getJSONObject("target").getString("type"))
        assertFalse(failedRun.toString().contains("private-"))
        assertEquals(1, failedRun.getInt("protocol_failures_primary"))
        assertTrue("Rejected decision must not queue a device action", engine.poll().isNull("command"))
        val recovery = engine.takeWork()!!
        assertFalse(recovery.grounding)
        assertTrue(recovery.payload.toString().contains("missing_fields"))
        assertFalse(recovery.payload.toString().contains("private-"))
        engine.accept(recovery, response(JSONObject().put("decision", decision).put("state", JSONObject.NULL)))
        assertEquals(0, engine.get(id).getInt("protocol_failures_primary"))
        assertEquals("screenshot", engine.poll().getJSONObject("command").getString("kind"))
    }
}

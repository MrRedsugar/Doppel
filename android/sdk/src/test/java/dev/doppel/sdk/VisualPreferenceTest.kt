package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VisualPreferenceTest {
    @Test fun visualPreferenceAcquiresPixelsEvenWhenANameExists() {
        val engine = DirectTaskEngine(null, {}, { 2000L }, visualControl = true, preferVisualObservation = true)
        engine.create(JSONObject().put("device_id", DirectRuntime.DEVICE_ID).put("goal", "打开列表").put("mode", "full"))
        val c = engine.poll().getJSONObject("command")
        engine.result(JSONObject().put("run_id", c.getString("run_id")).put("command_id", c.getString("id")).put("status", "ok")
            .put("observation", JSONObject().put("screen_id", "screen").put("package_name", "example.list").put("width", 1440).put("height", 3200)
                .put("nodes", JSONArray().put(JSONObject().put("id", "n1").put("text", "列表").put("enabled", true).put("clickable", true))))
            .put("data", JSONObject().put("device_profile", JSONObject().put("visual_gestures", true))))
        assertNull(engine.takeWork())
        assertTrue(engine.poll().getJSONObject("command").getBoolean("include_screenshot"))
    }
    @Test fun missingCaptureCapabilityKeepsSemanticWorkAvailable() {
        val engine = DirectTaskEngine(null, {}, { 2000L }, visualControl = true, preferVisualObservation = true)
        engine.create(JSONObject().put("device_id", DirectRuntime.DEVICE_ID).put("goal", "读取").put("mode", "full"))
        val c = engine.poll().getJSONObject("command")
        engine.result(JSONObject().put("run_id", c.getString("run_id")).put("command_id", c.getString("id")).put("status", "ok")
            .put("observation", JSONObject().put("screen_id", "screen").put("package_name", "example.list").put("nodes", JSONArray())))
        assertFalse(engine.takeWork()!!.visualAgent)
    }
}

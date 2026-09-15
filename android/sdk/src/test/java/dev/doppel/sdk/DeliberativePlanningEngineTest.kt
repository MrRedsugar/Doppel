package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeliberativePlanningEngineTest {
    private fun work(planned: Boolean, visual: Boolean): Pair<DirectTaskEngine, DirectTaskEngine.Work> {
        val engine = DirectTaskEngine(null, {}, { 2000L }, visualControl = true,
            preferVisualObservation = visual, plannedControl = planned)
        val id = engine.create(JSONObject().put("device_id", DirectRuntime.DEVICE_ID).put("mode", "full")
            .put("goal", "找到设置中的关于页面")).getString("id")
        val command = engine.poll().getJSONObject("command")
        val screen = JSONObject().put("screen_id", "current").put("package_name", "example.settings")
            .put("width", 1080).put("height", 2400).put("nodes", JSONArray().put(JSONObject()
                .put("id", "n0").put("text", "设置").put("role", "button").put("enabled", true)
                .put("clickable", true).put("bounds", JSONArray(listOf(20, 100, 300, 300)))))
        val data = JSONObject().put("device_profile", JSONObject().put("visual_gestures", true))
        if (visual) data.put("image_base64", "dGVzdA==").put("mime_type", "image/png")
            .put("visual_frame", VisualFrame("capture", "current", "example.settings", 1080, 2400,
                432, 960, 0, 1000, 46000, "a".repeat(64)).json())
        engine.result(JSONObject().put("run_id", id).put("command_id", command.getString("id"))
            .put("status", "ok").put("observation", screen).put("data", data))
        return engine to requireNotNull(engine.takeWork())
    }

    @Test fun semanticPlannerUsesDeliberationBudgetBeforeLocalExecution() {
        val (engine, work) = work(true, false)
        assertFalse(work.vision)
        assertEquals("enabled", work.payload.getJSONObject("thinking").getString("type"))
        assertEquals(6144, work.payload.getInt("max_completion_tokens"))
        assertEquals(1, engine.get(work.runId).getInt("calls"))
        assertTrue(engine.poll().isNull("command"))
        val diagnostic = engine.get(work.runId).getJSONObject("model_request_diagnostic")
        assertEquals("enabled", diagnostic.getString("thinking"))
        assertEquals(6144, diagnostic.getInt("max_completion_tokens"))
        assertFalse(diagnostic.has("reasoning_content"))
    }

    @Test fun directVisualPlannerAlsoReasonsWithoutScreenshotQuestionIntermediary() {
        val (_, work) = work(true, true)
        assertTrue(work.visualAgent)
        assertEquals("mimo-v2.5", work.payload.getString("model"))
        assertEquals("enabled", work.payload.getJSONObject("thinking").getString("type"))
        assertEquals(6144, work.payload.getInt("max_completion_tokens"))
        val messages = work.payload.getJSONArray("messages")
        assertTrue((0 until messages.length()).all { messages.getJSONObject(it).optString("role") in setOf("system", "user") })
        val names = work.payload.getJSONArray("tools").let { tools -> (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") } }
        assertTrue("execute_visual_plan" in names)
        assertFalse("inspect_screen" in names)
    }

    @Test fun legacyAndSingleTargetPayloadsKeepTheirCompatibilityBudget() {
        for (visual in listOf(false, true)) {
            val (_, work) = work(false, visual)
            assertEquals("disabled", work.payload.getJSONObject("thinking").getString("type"))
            assertEquals(1600, work.payload.getInt("max_completion_tokens"))
        }
        val payload = DirectPayload.chat(DirectPayload.VISION, JSONArray(), JSONArray())
        assertEquals("disabled", payload.getJSONObject("thinking").getString("type"))
    }
}

package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic observations exercise the actual engine; no device or provider is contacted. */
class PlannedVisualRoutingRegressionTest {
    private data class Session(val engine: DirectTaskEngine, val runId: String, val work: DirectTaskEngine.Work)

    private fun start(planned: Boolean): Session {
        val engine = DirectTaskEngine(null, {}, { 2000L }, visualControl = true, plannedControl = planned)
        val runId = engine.create(JSONObject().put("device_id", DirectRuntime.DEVICE_ID).put("mode", "full")
            .put("goal", "查看当前设置页面中的关于入口")).getString("id")
        val read = engine.poll().getJSONObject("command")
        assertEquals("observe", read.getString("kind"))
        val screen = JSONObject().put("screen_id", "current-screen").put("package_name", "example.settings")
            .put("width", 1080).put("height", 2400).put("tree_complete", true).put("truncated", false)
            .put("nodes", JSONArray().put(JSONObject().put("id", "n0_0").put("text", "关于")
                .put("role", "button").put("resource_id", "example.settings:id/about").put("enabled", true)
                .put("clickable", true).put("editable", false).put("password", false)
                .put("bounds", JSONArray(listOf(30, 100, 500, 200)))))
        engine.result(JSONObject().put("run_id", runId).put("command_id", read.getString("id"))
            .put("status", "ok").put("observation", screen)
            .put("data", JSONObject().put("device_profile", JSONObject().put("visual_gestures", true))))
        val work = requireNotNull(engine.takeWork())
        assertFalse("A reliable current semantic screen must stay in planning", work.vision)
        assertFalse(work.visualAgent)
        assertFalse(work.grounding)
        return Session(engine, runId, work)
    }

    private fun names(work: DirectTaskEngine.Work): Set<String> = work.payload.getJSONArray("tools").let { tools ->
        (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }.toSet()
    }

    private fun reply(name: String, args: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("finish_reason", "tool_calls").put("message", JSONObject().put("tool_calls", JSONArray().put(JSONObject()
            .put("function", JSONObject().put("name", name).put("arguments", args.toString())))))))

    @Test fun plannedSemanticWorkOffersDirectObservationWithoutLegacyVisualRoutes() {
        val offered = names(start(planned = true).work)
        assertTrue("observe_screen" in offered)
        assertTrue("execute_plan" in offered)
        assertTrue("action" in offered)
        assertFalse("visual_action" in offered)
        assertFalse("inspect_screen" in offered)
    }

    @Test fun fabricatedVisualActionPausesBeforeSchedulingCaptureOrGrounding() {
        assertLegacyRouteRejected("visual_action", JSONObject().put("intent", "点击当前关于入口"))
    }

    @Test fun fabricatedInspectScreenAlsoCannotStartAQuestionRoundTrip() {
        assertLegacyRouteRejected("inspect_screen", JSONObject().put("question", "关于入口在哪里"))
    }

    private fun assertLegacyRouteRejected(name: String, args: JSONObject) {
        val session = start(planned = true)
        session.engine.accept(session.work, reply(name, args))
        val run = session.engine.get(session.runId)
        assertEquals("Rejected legacy routing follows the engine's validation pause", "paused", run.getString("status"))
        assertNotNull(run.optJSONObject("validation_diagnostic"))
        assertTrue("A rejected route must not queue a screenshot or device action", session.engine.poll().isNull("command"))
        assertNull("A rejected route must not create a grounding/provider work item", session.engine.takeWork())
        assertEquals(1, run.getInt("calls"))
        val metrics = run.getJSONObject("perception_metrics")
        assertEquals(0, metrics.optInt("grounding_calls"))
        assertEquals(0, metrics.optInt("vision_calls"))
        assertEquals(0, metrics.optInt("visual_agent_calls"))
    }

    @Test fun ordinaryModeRetainsItsAdvertisedLegacyVisualAction() {
        val session = start(planned = false)
        val offered = names(session.work)
        assertTrue("visual_action" in offered)
        assertTrue("inspect_screen" in offered)
        assertFalse("observe_screen" in offered)
        session.engine.accept(session.work, reply("visual_action", JSONObject().put("intent", "点击当前关于入口")))
        val capture = session.engine.poll().getJSONObject("command")
        assertEquals("observe", capture.getString("kind"))
        assertTrue(capture.getBoolean("include_screenshot"))
        assertEquals("running", session.engine.get(session.runId).getString("status"))
    }
}

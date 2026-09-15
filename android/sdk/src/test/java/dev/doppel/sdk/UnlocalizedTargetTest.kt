package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** A clickable container is not a localized business control, even when its center is on-screen. */
class UnlocalizedTargetTest {
    private var stored = ""
    private fun screen() = JSONObject().put("screen_id", "canvas-page").put("package_name", "dev.fixture")
        .put("width", 1440).put("height", 3200).put("nodes", JSONArray()
            .put(JSONObject().put("id", "n1").put("text", "").put("description", "").put("resource_id", "")
                .put("role", "button").put("clickable", true).put("enabled", true).put("password", false)
                .put("bounds", JSONArray(listOf(0, 200, 1440, 2960))))
            .put(JSONObject().put("id", "n2").put("text", "打卡").put("role", "button").put("selected", true)
                .put("clickable", true).put("enabled", true).put("password", false)
                .put("bounds", JSONArray(listOf(0, 2960, 480, 3140))))
            .put(JSONObject().put("id", "n3").put("text", "查看详情").put("role", "button")
                .put("clickable", true).put("enabled", true).put("password", false)
                .put("bounds", JSONArray(listOf(100, 800, 500, 1100)))))
    private fun ready(visualControl: Boolean, gestures: Boolean): Pair<DirectTaskEngine, String> {
        val engine = DirectTaskEngine(null, { stored = it }, { 1000L }, visualControl = visualControl)
        val id = engine.create(JSONObject().put("device_id", DirectRuntime.DEVICE_ID).put("mode", "full")
            .put("goal", "打开业务按钮对应的页面")).getString("id")
        val observation = engine.poll().getJSONObject("command")
        assertEquals("observe", observation.getString("kind"))
        engine.result(JSONObject().put("run_id", id).put("command_id", observation.getString("id")).put("status", "ok")
            .put("observation", screen()).put("data", JSONObject().put("device_profile", JSONObject().put("visual_gestures", gestures))))
        return engine to id
    }
    private fun propose(engine: DirectTaskEngine, target: String) {
        val work = engine.takeWork()
        assertNotNull(work)
        assertFalse("This proposal starts from semantic evidence", work!!.vision)
        val args = JSONObject().put("kind", "tap").put("target", target)
        engine.accept(work, JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls")
            .put("message", JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("function", JSONObject()
                .put("name", "action").put("arguments", args.toString()))))))))
    }

    @Test fun unnamedNearlyFullScreenContainerTriggersFreshVisualLocalizationWithoutTap() {
        val (engine, id) = ready(visualControl = true, gestures = true)
        propose(engine, "n1")
        val command = engine.poll().getJSONObject("command")
        assertEquals("observe", command.getString("kind"))
        assertTrue(command.getBoolean("include_screenshot"))
        assertEquals("running", engine.get(id).getString("status"))
        val run = JSONArray(stored).getJSONObject(0)
        assertEquals("unlocalized_container", run.getJSONObject("recovery_feedback").getString("code"))
        assertFalse(run.getJSONObject("recovery_feedback").getBoolean("action_executed"))
        assertEquals(0, run.getInt("successful_mutations"))
    }

    @Test fun boundedNamedControlKeepsTheOrdinarySemanticAction() {
        val (engine, id) = ready(visualControl = true, gestures = true)
        propose(engine, "n3")
        val command = engine.poll().getJSONObject("command")
        assertEquals("tap", command.getString("kind"))
        assertEquals("n3", command.getString("target"))
        assertEquals("canvas-page", command.getString("screen_id"))
        assertEquals("running", engine.get(id).getString("status"))
    }

    @Test fun visualControlDisabledDoesNotIntroduceScreenshotRequirement() {
        val (engine, _) = ready(visualControl = false, gestures = true)
        propose(engine, "n1")
        val command = engine.poll().getJSONObject("command")
        assertEquals("tap", command.getString("kind")); assertEquals("n1", command.getString("target"))
    }

    @Test fun deviceWithoutVisualGesturesKeepsExistingTextOnlyExecution() {
        val (engine, _) = ready(visualControl = true, gestures = false)
        propose(engine, "n1")
        val command = engine.poll().getJSONObject("command")
        assertEquals("tap", command.getString("kind")); assertEquals("n1", command.getString("target"))
    }
}

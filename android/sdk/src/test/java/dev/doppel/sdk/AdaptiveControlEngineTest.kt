package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Engine-level contracts: recovery chooses a new observation; it never replays an uncertain mutation. */
class AdaptiveControlEngineTest {
    private var stored = ""
    private fun engine() = DirectTaskEngine(null, { stored = it }, { 1000L })
    private fun create(engine: DirectTaskEngine): String = engine.create(JSONObject().put("device_id", DirectRuntime.DEVICE_ID)
        .put("goal", "打开列表项的详情").put("mode", "full")).getString("id")
    private fun screen(id: String = "page-a", value: String = "0") = JSONObject().put("screen_id", id)
        .put("package_name", "dev.fixture").put("width", 1440).put("height", 3200)
        .put("nodes", JSONArray().put(JSONObject().put("id", "n1").put("text", "详情").put("role", "button")
            .put("clickable", true).put("enabled", true).put("password", false).put("bounds", JSONArray(listOf(100, 800, 400, 1100))))
            .put(JSONObject().put("id", "n2").put("text", "其他入口").put("role", "button")
                .put("clickable", true).put("enabled", true).put("password", false).put("bounds", JSONArray(listOf(800, 800, 1100, 1100))))
            .put(JSONObject().put("id", "n3").put("text", value).put("role", "android.widget.TextView")
                .put("enabled", true).put("bounds", JSONArray(listOf(100, 1500, 1300, 1900)))))
    private fun result(command: JSONObject, status: String, observed: JSONObject, data: JSONObject = JSONObject()) = JSONObject()
        .put("run_id", command.getString("run_id")).put("command_id", command.getString("id"))
        .put("status", status).put("message", if (status == "ok") "系统已接受操作，请检查后续屏幕" else "控件未执行操作")
        .put("observation", observed).put("data", data)
    private fun observe(engine: DirectTaskEngine, observed: JSONObject = screen()) {
        val command = engine.poll().getJSONObject("command")
        assertEquals("observe", command.getString("kind"))
        engine.result(result(command, "ok", observed))
    }
    private fun reply(target: String) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls")
        .put("message", JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("function", JSONObject()
            .put("name", "action").put("arguments", JSONObject().put("kind", "tap").put("target", target).toString())))))))
    private fun propose(engine: DirectTaskEngine, target: String = "n1") {
        val work = engine.takeWork()
        assertNotNull("A running, observed task should request a new decision", work)
        engine.accept(work!!, reply(target))
    }
    private fun acceptedTap(engine: DirectTaskEngine, after: JSONObject): JSONObject {
        propose(engine)
        val command = engine.poll().getJSONObject("command")
        assertEquals("tap", command.getString("kind"))
        engine.result(result(command, "ok", after, JSONObject().put("action_state", "accepted")))
        return command
    }
    private fun savedRun() = JSONArray(stored).getJSONObject(0)
    private fun explicitFailedClick() = JSONObject().put("action_state", "failed").put("action_diagnostic", JSONObject()
        .put("node_present", true).put("enabled", true).put("clickable", true).put("long_clickable", false)
        .put("editable", false).put("scrollable", false).put("password", false)
        .put("action_ids", JSONArray(listOf(16))).put("requested_action_advertised", true).put("requested_action_id", 16))

    @Test fun thirdUnchangedTargetReobservesInsteadOfDispatchingAndAlternativeRemainsAvailable() {
        val engine = engine(); val id = create(engine); observe(engine)
        acceptedTap(engine, screen("page-generation-b"))
        val second = acceptedTap(engine, screen("page-generation-c"))
        propose(engine)
        val recovery = engine.poll().getJSONObject("command")
        assertEquals("observe", recovery.getString("kind"))
        assertNotEquals(second.getString("id"), recovery.getString("id"))
        assertEquals("running", engine.get(id).getString("status"))
        assertEquals("repeated_unchanged_target", savedRun().getJSONObject("recovery_feedback").getString("code"))
        assertFalse(savedRun().getJSONObject("recovery_feedback").getBoolean("action_executed"))
        observe(engine, screen("page-generation-d"))
        propose(engine, "n2")
        val alternative = engine.poll().getJSONObject("command")
        assertEquals("tap", alternative.getString("kind")); assertEquals("n2", alternative.getString("target"))
    }

    @Test fun definiteNativeFailureReturnsEvidenceToPlannerAfterFreshObservation() {
        val engine = engine(); val id = create(engine); observe(engine); propose(engine)
        val failed = engine.poll().getJSONObject("command")
        engine.result(result(failed, "error", screen("after-failure"), explicitFailedClick()))
        assertEquals("running", engine.get(id).getString("status"))
        val recovery = engine.poll().getJSONObject("command")
        assertEquals("observe", recovery.getString("kind")); assertNotEquals(failed.getString("id"), recovery.getString("id"))
        assertEquals("action_not_executed", savedRun().getJSONObject("recovery_feedback").getString("code"))
        assertFalse(savedRun().getJSONObject("recovery_feedback").getBoolean("action_executed"))
        observe(engine, screen("fresh-after-failure"))
        val work = engine.takeWork()!!
        assertTrue("The planner must receive the failure instead of blindly repeating it", work.payload.toString().contains("action_not_executed"))
        engine.accept(work, reply("n2"))
        assertEquals("n2", engine.poll().getJSONObject("command").getString("target"))
    }

    @Test fun unknownOrAcceptedFailureNeverAutomaticallyResumes() {
        val variants = listOf(JSONObject(), JSONObject().put("action_state", "failed"),
            explicitFailedClick().put("action_state", "accepted"), explicitFailedClick().put("action_state", "unconfirmed"))
        for (data in variants) {
            val engine = engine(); val id = create(engine); observe(engine); propose(engine)
            val command = engine.poll().getJSONObject("command")
            engine.result(result(command, "error", screen("after-error"), data))
            assertEquals(data.toString(), "paused", engine.get(id).getString("status"))
            assertTrue(engine.poll().isNull("command")); assertNull(engine.takeWork())
        }
    }

    @Test fun manualTakeoverStillWinsOverAnExplicitNativeFailure() {
        val engine = engine(); val id = create(engine); observe(engine); propose(engine)
        val command = engine.poll().getJSONObject("command")
        engine.result(result(command, "error", screen("after-error"), explicitFailedClick().put("human_takeover", "verification")))
        assertEquals("paused", engine.get(id).getString("status"))
        assertTrue(engine.poll().isNull("command")); assertNull(engine.takeWork())
        assertTrue(savedRun().getJSONObject("pending_request").getBoolean("manual_only"))
    }

    @Test fun aGrowingVisibleValueDoesNotBlockRepeatedKeyActions() {
        val engine = engine(); create(engine); observe(engine)
        acceptedTap(engine, screen("page-b", "1"))
        acceptedTap(engine, screen("page-c", "11"))
        propose(engine)
        assertEquals("tap", engine.poll().getJSONObject("command").getString("kind"))
    }
}

package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SplitAppLaunchTest {
    private fun reply(body: String) = SplitTestReply.response(JSONObject(body))
    private fun commandResult(engine: SplitTaskEngine, data: JSONObject = JSONObject(), status: String = "ok", pkg: String? = null) {
        val command = engine.poll().getJSONObject("command")
        engine.result(JSONObject().put("command_id", command.getString("id")).put("run_id", command.getString("run_id"))
            .put("status", status).put("data", data).apply {
                if (pkg != null) put("observation", JSONObject().put("screen_id", "screen-$pkg").put("package_name", pkg))
            })
    }
    private fun screenshot(engine: SplitTaskEngine, pkg: String = "dev.fixture") {
        assertEquals("screenshot", engine.poll().getJSONObject("command").getString("kind"))
        commandResult(engine, JSONObject().put("image_base64", "cGl4ZWxz").put("visual_frame", JSONObject()
            .put("capture_id", "capture-$pkg").put("package_name", pkg).put("display_width", 1080).put("display_height", 1920).put("rotation", 0)), pkg = pkg)
    }
    private fun start(direct: Boolean, mode: String = "full"): Pair<SplitTaskEngine, String> {
        val engine = SplitTaskEngine(null, {}, { 1000L }, enhancementEnabled = { !direct })
        val id = engine.create(JSONObject().put("goal", "打开系统设置").put("device_id", "direct-this-phone").put("mode", mode)).getString("id")
        screenshot(engine)
        return engine to id
    }
    private fun query(engine: SplitTaskEngine) {
        engine.accept(engine.takeWork()!!, reply("""{"kind":"list_apps","query":"设置"}"""))
        assertEquals("list_apps", engine.poll().getJSONObject("command").getString("kind"))
        commandResult(engine, JSONObject().put("total", 1).put("apps", JSONArray().put(JSONObject()
            .put("package_name", "com.android.settings").put("label", "系统设置唯一测试名称"))))
    }
    private val launch = """{"kind":"execute","action":"launch","package_name":"com.android.settings","target":"打开系统设置","expected":"系统设置页面出现"}"""

    @Test fun bothModesQueryOnceAndLaunchWithoutGrounderThenVerifyNewScreenshot() {
        for (direct in listOf(false, true)) {
            val (engine, id) = start(direct)
            query(engine)
            val planner = engine.takeWork()!!
            assertFalse(planner.grounding)
            assertTrue(planner.payload.toString().contains("系统设置唯一测试名称"))
            engine.accept(planner, reply(launch))
            val command = engine.poll().getJSONObject("command")
            assertEquals("launch", command.getString("kind"))
            assertEquals("com.android.settings", command.getString("package_name"))
            assertEquals("capture-dev.fixture", command.getJSONObject("source").getString("capture_id"))
            assertFalse(command.has("points"))
            commandResult(engine, JSONObject().put("action_state", "accepted"))
            screenshot(engine, "com.android.settings")
            val next = engine.takeWork()!!
            assertFalse(next.grounding)
            assertFalse("The complete application result must not recur", next.payload.toString().contains("系统设置唯一测试名称"))
            assertTrue(engine.internalRun(id).getJSONObject("last_receipt").getBoolean("launch_verified"))
            assertTrue(engine.internalRun(id).getJSONArray("recent_steps").getJSONObject(0).getJSONObject("receipt").getBoolean("launch_verified"))
            assertEquals(0, engine.internalRun(id).optJSONObject("model_metrics")?.optJSONObject("grounding")?.optInt("calls") ?: 0)
        }
    }

    @Test fun launchNeedsDeviceReportedPackageAndLateResponseCannotLaunchAfterPause() {
        val (engine, id) = start(false)
        engine.accept(engine.takeWork()!!, reply(launch))
        assertTrue(engine.poll().isNull("command"))
        query(engine)
        val pending = engine.takeWork()!!
        engine.control(id, "pause", JSONObject())
        engine.accept(pending, reply(launch))
        assertTrue(engine.poll().isNull("command"))
        assertEquals("paused", engine.statusOrNull(id))
    }

    @Test fun acceptedLaunchDoesNotCountAsVerifiedWhenScreenshotShowsOtherApplication() {
        val (engine, id) = start(true)
        query(engine); engine.accept(engine.takeWork()!!, reply(launch))
        commandResult(engine, JSONObject().put("action_state", "accepted"))
        screenshot(engine, "dev.fixture")
        assertFalse(engine.internalRun(id).getJSONObject("last_receipt").getBoolean("launch_verified"))
        assertFalse(engine.takeWork()!!.grounding)
    }

    @Test fun approvalRefreshKeepsAlreadyListedPackagesAndInterruptedLaunchReceiptIsNotLost() {
        val (engine, id) = start(false, "ask")
        query(engine); engine.accept(engine.takeWork()!!, reply(launch))
        val approval = engine.get(id).getJSONObject("pending_request")
        engine.control(id, "answer", JSONObject().put("request_id", approval.getString("id")).put("approve", true))
        screenshot(engine)
        engine.accept(engine.takeWork()!!, reply(launch))
        val sent = engine.poll().getJSONObject("command")
        assertEquals("launch", sent.getString("kind"))
        engine.control(id, "pause", JSONObject())
        val received = engine.result(JSONObject().put("command_id", sent.getString("id")).put("run_id", id)
            .put("status", "ok").put("data", JSONObject().put("action_state", "accepted")))
        assertTrue(received.getBoolean("accepted"))
        assertEquals("paused", engine.statusOrNull(id))
        assertEquals("launch", engine.internalRun(id).getJSONObject("last_receipt").getString("action"))
        assertTrue(engine.poll().isNull("command"))
    }

    @Test fun launchSchemasRequirePackageAndRejectCoordinatesInBothModes() {
        for (direct in listOf(false, true)) {
            val schema = SplitOutputSchema.format("primary", direct)
            val response = reply(launch)
            val parsed = SplitAgentProtocol.content(response, schema, "primary")
            assertEquals("launch", parsed.getString("action"))
            assertEquals("com.android.settings", parsed.getString("package_name"))
            val wire = JSONObject(response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
            wire.getJSONObject("decision").put("points", JSONArray().put(JSONArray(listOf(500, 500))))
            assertThrows(SplitSchemaViolation::class.java) { SplitOutputSchema.validate(wire, schema) }
            wire.getJSONObject("decision").remove("points"); wire.getJSONObject("decision").remove("package_name")
            assertThrows(SplitSchemaViolation::class.java) { SplitOutputSchema.validate(wire, schema) }
        }
        val native = SplitAgentProtocol.grounding(JSONObject().put("status", "located").put("action", "launch")
            .put("package_name", "com.android.settings"), "launch")
        assertEquals("com.android.settings", native.getString("package_name"))
    }

    @Test fun appQueryCannotContinueModelWorkAfterDeviceRequestsTakeover() {
        val (engine, id) = start(false)
        engine.accept(engine.takeWork()!!, reply("""{"kind":"list_apps","query":"设置"}"""))
        commandResult(engine, JSONObject().put("human_takeover", "interruption"), "blocked")
        assertEquals("paused", engine.statusOrNull(id))
        assertNull(engine.takeWork())
        assertTrue(engine.poll().isNull("command"))
    }
}

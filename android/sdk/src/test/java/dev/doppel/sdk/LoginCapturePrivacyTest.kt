package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic host observations only. No account, login value, device or provider is used. */
class LoginCapturePrivacyTest {
    private val pkg = "example.login"
    private val omitted = "login_sensitive"

    private fun screen(id: String, privateCapture: Boolean = false, heading: String = "登录") = JSONObject()
        .put("screen_id", id).put("package_name", pkg).put("captured_at", 2000L)
        .put("width", 1080).put("height", 2400).put("nodes", JSONArray()
            .put(JSONObject().put("id", "n1").put("role", "input").put("description", "手机号").put("text", "")
                .put("editable", true).put("enabled", true).put("bounds", JSONArray(listOf(100, 200, 900, 350))))
            .put(JSONObject().put("id", "n2").put("role", "button").put("text", heading)
                .put("clickable", true).put("enabled", true).put("bounds", JSONArray(listOf(100, 600, 900, 800))))
            .put(JSONObject().put("id", "n3").put("role", "input").put("description", "登录验证码").put("text", "")
                .put("editable", true).put("enabled", true).put("bounds", JSONArray(listOf(100, 400, 900, 550)))))
        .apply { if (privateCapture) put("screenshot_privacy", JSONObject().put("source", "android_host")
            .put("reason", omitted).put("screen_id", id).put("package_name", pkg).put("captured_at", 2000L)) }

    private fun response(command: JSONObject, observation: JSONObject, image: String? = null, omission: Boolean = false): JSONObject {
        val data = JSONObject().put("device_profile", JSONObject().put("visual_gestures", true))
        if (image != null) data.put("image_base64", image).put("mime_type", "image/png").put("visual_frame",
            VisualFrame("capture-${observation.getString("screen_id")}", observation.getString("screen_id"), pkg,
                1080, 2400, 540, 1200, 0, 1000, 46000, "a".repeat(64)).json())
        if (omission) data.put("screenshot_omitted_reason", omitted)
        return JSONObject().put("run_id", command.getString("run_id")).put("command_id", command.getString("id"))
            .put("status", "ok").put("observation", observation).put("data", data)
    }

    private fun reply(name: String, arguments: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("finish_reason", "tool_calls").put("message", JSONObject().put("tool_calls", JSONArray().put(JSONObject()
            .put("function", JSONObject().put("name", name).put("arguments", arguments.toString())))))))

    private fun start(observation: JSONObject, image: String? = null, preferVisual: Boolean = true): DirectTaskEngine {
        val engine = DirectTaskEngine(null, {}, { 2000L }, visualControl = true, preferVisualObservation = preferVisual)
        engine.create(JSONObject().put("device_id", DirectRuntime.DEVICE_ID).put("goal", "使用已授权的本机登录辅助继续登录").put("mode", "full"))
        engine.result(response(engine.poll().getJSONObject("command"), observation, image))
        return engine
    }

    private fun names(work: DirectTaskEngine.Work): List<String> {
        val tools = work.payload.getJSONArray("tools")
        return (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }
    }

    private fun nativeAction(engine: DirectTaskEngine, work: DirectTaskEngine.Work, kind: String, target: String): JSONObject {
        engine.accept(work, reply("action", JSONObject().put("kind", kind).put("target", target)))
        return engine.poll().getJSONObject("command").also { assertEquals(kind, it.getString("kind")) }
    }

    @Test fun acceptedLoginInputContinuesWithRedactedSemanticsWithoutReplayingIt() {
        val engine = start(screen("before"), "before-image")
        val visual = engine.takeWork()!!
        assertTrue(visual.visualAgent)
        val phone = nativeAction(engine, visual, "login_phone", "n1")
        assertFalse(phone.has("text"))
        engine.result(response(phone, screen("after-phone", true)))
        val semantic = engine.takeWork()
        assertNotNull("Login privacy must preserve the available semantic path", semantic)
        assertFalse(semantic!!.vision)
        assertFalse(semantic.visualAgent)
        assertFalse(names(semantic).any { it in setOf("inspect_screen", "visual_action", "propose_tap") })
        assertFalse(semantic.payload.toString().contains("data:image"))
        assertTrue(engine.poll().isNull("command"))
        val code = nativeAction(engine, semantic, "login_code", "n3")
        assertNotEquals(phone.getString("id"), code.getString("id"))
        assertFalse(code.has("text"))
        assertEquals("after-phone", code.getString("screen_id"))
    }

    @Test fun explicitHostOmissionCompletesCombinedObservationWithoutAnotherCapture() {
        for (preferVisual in listOf(true, false)) {
            val engine = start(screen("before"), preferVisual = preferVisual)
            if (preferVisual) assertNull(engine.takeWork()) else {
                engine.accept(engine.takeWork()!!, reply("inspect_screen", JSONObject().put("question", "查看当前界面")))
            }
            val capture = engine.poll().getJSONObject("command")
            assertTrue(capture.getBoolean("include_screenshot"))
            engine.result(response(capture, screen("private", true), omission = true))
            val work = engine.takeWork()
            assertNotNull(work)
            assertFalse("A pending vision question must not re-enable images", work!!.vision)
            assertTrue(engine.poll().isNull("command"))
            assertEquals("running", engine.get(capture.getString("run_id")).getString("status"))
        }
    }

    @Test fun missingOrMismatchedOmissionNeverHidesMissingScreenshotFailure() {
        for (variant in listOf("missing_reason", "missing_state", "old_screen", "old_package", "old_time")) {
            val engine = start(screen("before"))
            assertNull(engine.takeWork())
            val capture = engine.poll().getJSONObject("command")
            val current = screen("private", variant != "missing_state")
            when (variant) {
                "old_screen" -> current.getJSONObject("screenshot_privacy").put("screen_id", "before")
                "old_package" -> current.getJSONObject("screenshot_privacy").put("package_name", "another.app")
                "old_time" -> current.getJSONObject("screenshot_privacy").put("captured_at", 1999L)
            }
            engine.result(response(capture, current, omission = variant != "missing_reason"))
            assertEquals(variant, "paused", engine.get(capture.getString("run_id")).getString("status"))
            assertNull(engine.takeWork())
        }
    }

    @Test fun oldPrivacyStateDoesNotDisableVisualPreferenceForANewObservation() {
        val current = screen("new", true)
        current.getJSONObject("screenshot_privacy").put("screen_id", "old")
        val engine = start(current)
        assertNull(engine.takeWork())
        assertTrue(engine.poll().getJSONObject("command").getBoolean("include_screenshot"))
    }

    @Test fun enteringPrivacyClearsHistoricalImagesAndFreshPublicObservationRestoresVisual() {
        val engine = start(screen("before"), "old-source-image")
        val tap = nativeAction(engine, engine.takeWork()!!, "tap", "n2")
        engine.result(response(tap, screen("login"), "login-source-image"))
        val prior = engine.takeWork()!!
        assertTrue(prior.payload.toString().contains("old-source-image"))
        val phone = nativeAction(engine, prior, "login_phone", "n1")
        engine.result(response(phone, screen("private", true)))
        val semantic = engine.takeWork()!!
        assertFalse(semantic.vision)
        assertFalse(semantic.payload.toString().contains("old-source-image"))
        engine.accept(semantic, reply("navigate", JSONObject().put("kind", "observe")))
        val observe = engine.poll().getJSONObject("command")
        engine.result(response(observe, screen("public"), "new-public-image"))
        val restored = engine.takeWork()!!
        assertTrue(restored.visualAgent)
        assertTrue(restored.payload.toString().contains("new-public-image"))
        assertFalse(restored.payload.toString().contains("old-source-image"))
        assertFalse(restored.payload.toString().contains("login-source-image"))
    }

    @Test fun modelCannotRequestAnUnadvertisedCaptureWhilePrivacyIsCurrent() {
        for (tool in listOf("inspect_screen", "visual_action")) {
            val engine = start(screen("private", true))
            val work = engine.takeWork()!!
            val args = if (tool == "inspect_screen") JSONObject().put("question", "查看屏幕")
                else JSONObject().put("intent", "点击继续")
            engine.accept(work, reply(tool, args))
            assertTrue(engine.poll().isNull("command"))
            assertEquals("paused", engine.get(work.runId).getString("status"))
        }
    }

    @Test fun privacyStateDoesNotGrantPaymentOrVerificationAuthorization() {
        for (label in listOf("确认付款", "请完成安全验证")) {
            val engine = start(screen("private", true, label))
            val work = engine.takeWork()!!
            engine.accept(work, reply("action", JSONObject().put("kind", "tap").put("target", "n2")))
            assertTrue(engine.poll().isNull("command"))
            assertEquals(label, "paused", engine.get(work.runId).getString("status"))
            assertTrue(engine.get(work.runId).getJSONObject("pending_request").getBoolean("manual_only"))
        }
    }

    @Test fun ordinaryBlockedCaptureStillRequiresManualTakeover() {
        val engine = start(screen("before"))
        assertNull(engine.takeWork())
        val capture = engine.poll().getJSONObject("command")
        val result = response(capture, screen("private", true), omission = true).put("status", "blocked")
        result.getJSONObject("data").put("human_takeover", "login")
        engine.result(result)
        assertEquals("paused", engine.get(capture.getString("run_id")).getString("status"))
        assertNull(engine.takeWork())
    }

    @Test fun privacyOmissionCannotReplaceRequiredPostGestureVerification() {
        val initial = screen("before").apply { getJSONArray("nodes").remove(2) }
        val engine = start(initial, "source-image")
        val work = engine.takeWork()!!
        engine.accept(work, reply("propose_tap", JSONObject().put("capture_id", "capture-before")
            .put("x", 250).put("y", 350).put("duration_ms", 80).put("label", "打开登录")
            .put("screen_context", "普通应用界面").put("safety", "safe")))
        val action = engine.poll().getJSONObject("command")
        assertEquals("visual_gesture", action.getString("kind"))
        val accepted = response(action, initial)
        accepted.getJSONObject("data").put("action_state", "accepted")
            .put("read_diagnostic", JSONObject().put("error_class", "ScreenNotReadyException")
                .put("source_file", "DoppelAccessibilityService.kt").put("source_line", 759).put("reason_code", "empty_frame"))
            .put("visual_diagnostic", JSONObject().put("reason_code", "visual_pixels_verified").put("stage", "verify")
                .put("frame_matches", true).put("pixels_match", true))
        engine.result(accepted)
        val read = engine.poll().getJSONObject("command")
        assertEquals("observe", read.getString("kind"))
        assertTrue(read.getBoolean("include_screenshot"))
        engine.result(response(read, screen("private", true), omission = true))
        assertEquals("paused", engine.get(work.runId).getString("status"))
        assertEquals(1, engine.get(work.runId).getInt("successful_mutations"))
        assertTrue(engine.poll().isNull("command"))
        assertNull(engine.takeWork())
    }
}

package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SplitNativeToolsTest {
    private fun start(direct: Boolean): Pair<SplitTaskEngine, String> {
        val engine = SplitTaskEngine(null, {}, { 1000L }, enhancementEnabled = { !direct })
        val id = engine.create(JSONObject().put("goal", "执行本机工具验证").put("device_id", "direct-this-phone").put("mode", "full")).getString("id")
        screenshot(engine)
        return engine to id
    }
    private fun result(engine: SplitTaskEngine, data: JSONObject = JSONObject(), status: String = "ok", observation: JSONObject? = null) {
        val command = engine.poll().getJSONObject("command")
        engine.result(JSONObject().put("command_id", command.getString("id")).put("run_id", command.getString("run_id"))
            .put("status", status).put("data", data).put("observation", observation ?: JSONObject.NULL))
    }
    private fun screenshot(engine: SplitTaskEngine) {
        assertEquals("screenshot", engine.poll().getJSONObject("command").getString("kind"))
        result(engine, JSONObject().put("image_base64", "cGl4ZWxz").put("visual_frame", JSONObject().put("capture_id", "fixture-frame")
            .put("package_name", "dev.fixture").put("display_width", 1080).put("display_height", 1920).put("rotation", 0)),
            observation = JSONObject().put("screen_id", "fixture-screen").put("package_name", "dev.fixture")
                .put("login_assist", JSONObject().put("enabled", true).put("phone_available", true).put("code_ready", false)))
    }
    private fun reply(body: JSONObject) = SplitTestReply.response(body)
    private fun action(kind: String) = JSONObject().put("kind", "execute").put("action", kind)
        .put("target", "本机工具明确目标").put("expected", "本机工具结果可核对")
    private fun read(kind: String) = JSONObject().put("kind", kind).apply {
        if (kind == "read_calendar") put("start_date", "").put("days", 3)
        if (kind == "read_notifications") put("package_name", "")
    }
    private fun context(work: SplitTaskEngine.Work): JSONObject {
        val messages = work.payload.getJSONArray("messages")
        return JSONObject(messages.getJSONObject(messages.length() - 1).getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test fun nativeActionsHaveIdenticalLocalRoutingInBothModesAndNeverAskBForCoordinates() {
        for (direct in listOf(false, true)) for (kind in SplitAgentProtocol.nativeActions - "launch") {
            val (engine, _) = start(direct)
            val request = action(kind).apply {
                if (kind in setOf("volume", "adjust_volume")) put("stream", "media")
                if (kind == "volume") put("percent", 30)
                if (kind == "adjust_volume") put("direction", "down")
                if (kind in setOf("copy", "cut")) put("selection", "all")
            }
            engine.accept(engine.takeWork()!!, reply(request))
            val native = engine.poll().getJSONObject("command")
            assertEquals(kind, native.getString("kind"))
            assertEquals("fixture-frame", native.getJSONObject("source").getString("capture_id"))
            assertFalse(native.has("points")); assertFalse(native.has("action"))
            result(engine, JSONObject().put("action_state", "accepted"))
            screenshot(engine)
            assertFalse(engine.takeWork()!!.grounding)
        }
    }

    @Test fun clipboardReadRefreshesScreenshotWhileOtherReadsKeepItAndAllResultsAppearOnlyOnce() {
        for (kind in SplitAgentProtocol.readActions) {
            val (engine, _) = start(false)
            engine.accept(engine.takeWork()!!, reply(read(kind)))
            assertEquals(kind, engine.poll().getJSONObject("command").getString("kind"))
            result(engine, JSONObject().put("fixture_note", "one-private-result"))
            if (kind == "read_clipboard") { assertNull(engine.takeWork()); screenshot(engine) }
            else assertTrue(engine.poll().isNull("command"))
            val received = engine.takeWork()!!
            assertFalse(received.grounding)
            assertEquals("one-private-result", context(received).getJSONObject("device_read_result").getJSONObject("data").getString("fixture_note"))
            engine.accept(received, reply(JSONObject().put("kind", "wait").put("duration_ms", 500)))
            result(engine); screenshot(engine)
            assertFalse(context(engine.takeWork()!!).has("device_read_result"))
        }
    }

    @Test fun readsPreserveUnavailableReasonAndTakeoverStopsWork() {
        val (engine, id) = start(true)
        engine.accept(engine.takeWork()!!, reply(read("read_calendar")))
        result(engine, JSONObject().put("reason_code", "permission_required"), "error")
        val response = context(engine.takeWork()!!).getJSONObject("device_read_result")
        assertEquals("error", response.getString("status"))
        assertEquals("permission_required", response.getJSONObject("data").getString("reason_code"))
        engine.interrupt("测试暂停")
        assertEquals("paused", engine.statusOrNull(id))
        val (second, sid) = start(false)
        second.accept(second.takeWork()!!, reply(read("read_notifications")))
        result(second, JSONObject().put("human_takeover", "interruption"), "blocked")
        assertEquals("paused", second.statusOrNull(sid)); assertNull(second.takeWork())
    }

    @Test fun invalidPrimaryOutputRetainsReadForCorrectionWithoutRequeryAndCancelledWorkCannotRestoreIt() {
        for (kind in SplitAgentProtocol.readActions + "list_apps") {
            val (engine, id) = start(false)
            val key = if (kind == "list_apps") "app_list_result" else "device_read_result"
            val request = if (kind == "list_apps") JSONObject().put("kind", kind).put("query", "fixture") else read(kind)
            fun deliver() {
                assertEquals(kind, engine.poll().getJSONObject("command").getString("kind"))
                result(engine, if (kind == "list_apps") JSONObject().put("total", 1).put("apps", JSONArray()
                    .put(JSONObject().put("package_name", "dev.fixture").put("label", "Fixture")))
                    else JSONObject().put("fixture_note", "original-read-data"))
                if (kind == "read_clipboard") screenshot(engine)
            }
            engine.accept(engine.takeWork()!!, reply(request)); deliver()
            val first = engine.takeWork()!!
            val original = context(first).getJSONObject(key).toString()
            val malformed = reply(JSONObject().put("kind", "invalid_decision"))
            engine.accept(first, malformed)
            assertEquals("running", engine.statusOrNull(id))
            assertTrue("Output correction must not queue another device read", engine.poll().isNull("command"))
            val corrected = engine.takeWork()!!
            assertFalse(corrected.grounding)
            assertEquals(original, context(corrected).getJSONObject(key).toString())
            engine.accept(corrected, reply(JSONObject().put("kind", "wait").put("duration_ms", 500)))
            result(engine); screenshot(engine)
            val consumed = engine.takeWork()!!
            assertFalse("A valid response consumes the read normally", context(consumed).has(key))

            engine.accept(consumed, reply(request)); deliver()
            val cancelled = engine.takeWork()!!
            engine.control(id, "pause", JSONObject()); engine.control(id, "resume", JSONObject()); screenshot(engine)
            engine.accept(cancelled, malformed)
            assertFalse("A late response from the cancelled generation cannot restore old data", context(engine.takeWork()!!).has(key))
        }
    }

    @Test fun loginPhoneAndFreshCodeAreLocalAndMissingCodeWaitDoesNotPauseOrRevealValue() {
        for (direct in listOf(false, true)) for (kind in listOf("login_phone", "login_code")) {
            val (engine, id) = start(direct)
            val work = engine.takeWork()!!
            assertTrue(context(work).getJSONObject("login_assist").getBoolean("enabled"))
            engine.accept(work, reply(action(kind).put("package_name", "dev.fixture").apply {
                if (kind == "login_code") put("code_candidate_id", JSONObject.NULL)
            }))
            val native = engine.poll().getJSONObject("command")
            assertEquals(kind, native.getString("kind")); assertFalse(native.has("text")); assertFalse(native.has("points"))
            result(engine, JSONObject().put("action_state", if (kind == "login_code") "waiting_for_code" else "accepted").put("retry_after_ms", 1500))
            assertEquals("running", engine.statusOrNull(id)); screenshot(engine)
            val next = engine.takeWork()!!
            assertFalse(next.grounding)
            if (kind == "login_code") assertEquals("waiting_for_code", context(next).getJSONObject("last_receipt").getString("action_state"))
        }
    }

    @Test fun nativeFieldSchemasRejectUnknownModesSecretValuesAndOversizedReadRange() {
        for (direct in listOf(false, true)) {
            val schema = SplitOutputSchema.format("primary", direct)
            for (bad in listOf(
                action("volume").put("stream", "voice_call").put("percent", 20),
                action("volume").put("stream", "media").put("percent", 101),
                action("copy").put("selection", "guess"),
                action("login_code").put("package_name", "dev.fixture").put("code_candidate_id", JSONObject.NULL).put("text", "123456"),
                read("read_calendar").put("days", 100),
                JSONObject().put("kind", "read_clipboard").put("run_id", "invented")
            )) assertThrows(SplitSchemaViolation::class.java) { SplitAgentProtocol.content(reply(bad), schema, "primary") }
        }
    }

    @Test fun codeCandidateSelectionIsLocalAndStrictlyNullableInBothVisualModes() {
        for (direct in listOf(false, true)) for (candidate in listOf(null, "candidate-2")) {
            val (engine, _) = start(direct)
            val request = action("login_code").put("package_name", "dev.fixture")
                .put("code_candidate_id", candidate ?: JSONObject.NULL)
            engine.accept(engine.takeWork()!!, reply(request))
            val native = engine.poll().getJSONObject("command")
            assertEquals("login_code", native.getString("kind"))
            assertEquals(candidate, native.opt("code_candidate_id"))
            assertFalse(native.has("text")); assertNull(engine.takeWork())
            val schema = SplitOutputSchema.format("primary", direct)
            for (invalid in listOf("", "x".repeat(129), 42)) {
                assertThrows(SplitSchemaViolation::class.java) {
                    SplitAgentProtocol.content(reply(JSONObject(request.toString()).put("code_candidate_id", invalid)), schema, "primary")
                }
            }
            request.remove("code_candidate_id")
            assertThrows(SplitSchemaViolation::class.java) { SplitAgentProtocol.content(reply(request), schema, "primary") }
        }
    }

    @Test fun smsRequestAndResendFollowOnlyPlannerIntentThroughDirectAndGroundedTap() {
        for (direct in listOf(false, true)) {
            val (engine, id) = start(direct)
            // A prefilled phone needs no login_phone command; an ordinary later tap must not inherit the flag.
            for (flag in listOf(true, true, JSONObject.NULL)) {
                val planner = engine.takeWork()!!
                val prompt = planner.payload.getJSONArray("messages").getJSONObject(0).getString("content")
                assertTrue(prompt.contains("手机号已预填")); assertTrue(prompt.contains("重发"))
                assertFalse(prompt.contains("signature_matches_hint"))
                val request = action("tap").put("target", "当前步骤的继续按钮").put("request_login_code", flag)
                if (direct) request.put("points", JSONArray("[[400,600]]"))
                engine.accept(planner, reply(request))
                assertEquals(flag, engine.get(id).getJSONObject("last_intent").get("request_login_code"))
                if (!direct) {
                    screenshot(engine)
                    val grounder = engine.takeWork()!!
                    assertTrue(grounder.grounding)
                    assertFalse("B does not receive or decide the SMS monitoring intent", grounder.payload.toString().contains("request_login_code"))
                    engine.accept(grounder, reply(JSONObject().put("status", "located").put("action", "tap")
                        .put("points", JSONArray("[[400,600]]")).put("assessment", JSONObject().put("alignment", "consistent"))))
                }
                val command = engine.poll().getJSONObject("command")
                assertEquals("split_action", command.getString("kind"))
                assertEquals(flag, command.getJSONObject("semantic_intent").get("request_login_code"))
                assertFalse(command.getJSONObject("action").has("request_login_code"))
                assertEquals("dev.fixture", command.getJSONObject("source").getString("package_name"))
                result(engine); screenshot(engine)
            }
            val metrics = engine.get(id).getJSONObject("model_metrics")
            assertEquals("No extra planner request is needed", 3, metrics.getJSONObject("primary").getInt("calls"))
            assertEquals(if (direct) 0 else 3, metrics.optJSONObject("grounding")?.optInt("calls") ?: 0)
        }
    }
}

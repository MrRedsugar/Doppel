package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Protocol recovery must never reinterpret free text as an executable command or completion. */
class DirectProtocolRecoveryTest {
    private companion object {
        const val UNTRUSTED_CONTENT = "private-provider-content-that-must-not-be-replayed"
    }

    private class Session(val visual: Boolean = true) {
        var stored = ""
        var step = 0
        var evidenceId = ""
        val engine = DirectTaskEngine(null, { stored = it }, { 2000L }, visualControl = visual)
        val id = engine.create(JSONObject().put("device_id", DirectRuntime.DEVICE_ID)
            .put("goal", "读取计算器当前结果").put("mode", "full")).getString("id")
        val screenId get() = "screen-$step"
        val captureId get() = "capture-$step"

        init { deliver() }

        fun state(): JSONObject = engine.get(id)
        fun work(): DirectTaskEngine.Work = requireNotNull(engine.takeWork())
        fun deliver(): JSONObject {
            val command = engine.poll().getJSONObject("command")
            step++
            evidenceId = command.getString("id")
            val nodes = JSONArray()
            if (!visual) nodes.put(JSONObject().put("id", "n1").put("text", "数字1")
                .put("enabled", true).put("clickable", true).put("bounds", JSONArray(listOf(20, 20, 120, 120))))
            val observation = JSONObject().put("screen_id", screenId).put("package_name", "dev.calculator")
                .put("width", 1080).put("height", 2400).put("nodes", nodes)
            val frame = VisualFrame(captureId, screenId, "dev.calculator", 1080, 2400, 540, 1200, 0, 1000, 45000, "pixels-$step")
            val data = JSONObject().put("device_profile", JSONObject().put("visual_gestures", true))
                .put("image_base64", "aW1hZ2U=").put("mime_type", "image/png").put("visual_frame", frame.json())
            assertTrue(engine.result(JSONObject().put("run_id", id).put("command_id", evidenceId)
                .put("status", "ok").put("observation", observation).put("data", data)).getBoolean("accepted"))
            return command
        }

        fun assertCorrectionQueued() {
            assertEquals("running", state().getString("status"))
            val feedback = state().getJSONObject("model_protocol_feedback")
            assertEquals("missing_tool_call", feedback.getString("code"))
            assertEquals(false, feedback.getBoolean("action_executed"))
            val command = engine.poll().getJSONObject("command")
            assertEquals("Only a new observation may be queued after missing tool calls", "observe", command.getString("kind"))
            if (visual) assertTrue(command.getBoolean("include_screenshot"))
            assertFalse(stored.contains(UNTRUSTED_CONTENT))
            assertFalse(engine.events(id).toString().contains(UNTRUSTED_CONTENT))
        }
    }

    private fun response(message: JSONObject, finishReason: String = "stop") = JSONObject().put("choices", JSONArray()
        .put(JSONObject().put("finish_reason", finishReason).put("message", message)))

    private fun missing(emptyArray: Boolean = false, finishReason: String = "stop") = response(
        JSONObject().put("content", UNTRUSTED_CONTENT).apply { if (emptyArray) put("tool_calls", JSONArray()) }, finishReason)

    private fun call(name: String, args: JSONObject) = JSONObject().put("function", JSONObject()
        .put("name", name).put("arguments", args.toString()))

    private fun tool(name: String, args: JSONObject) = response(JSONObject().put("tool_calls", JSONArray().put(call(name, args))), "tool_calls")

    private fun tap(session: Session, x: Int = 100, safety: String = "safe") = tool("propose_tap", JSONObject()
        .put("capture_id", session.captureId).put("x", x).put("y", 100).put("duration_ms", 80)
        .put("label", "数字1").put("screen_context", "计算器键盘").put("safety", safety))

    @Test fun absentStopToolCallsReobserveAndReturnTypedFeedbackToVisualActor() {
        val session = Session()
        val original = session.work()
        assertTrue(original.visualAgent)
        session.engine.accept(original, missing())
        session.assertCorrectionQueued()
        assertEquals(0, session.state().getInt("successful_mutations"))
        session.deliver()
        val corrected = session.work().payload.toString()
        assertTrue(corrected.contains("missing_tool_call"))
        assertTrue(corrected.contains("action_executed"))
        assertTrue(corrected.contains(session.captureId))
        assertFalse(corrected.contains(UNTRUSTED_CONTENT))
    }

    @Test fun emptyStopToolCallsUseTheSameBoundedCorrection() {
        val session = Session()
        session.engine.accept(session.work(), missing(emptyArray = true))
        session.assertCorrectionQueued()
        assertEquals(0, session.state().getInt("successful_mutations"))
    }

    @Test fun semanticPlannerAlsoReceivesTypedMissingCallFeedback() {
        val session = Session(visual = false)
        val first = session.work()
        assertFalse(first.vision)
        session.engine.accept(first, missing())
        session.assertCorrectionQueued()
        session.deliver()
        val corrected = session.work()
        assertFalse(corrected.vision)
        assertTrue(corrected.payload.toString().contains("missing_tool_call"))
        assertFalse(corrected.payload.toString().contains(UNTRUSTED_CONTENT))
    }

    @Test fun aThirdConsecutiveMissingCallPausesWithoutDispatchingAnAction() {
        val session = Session()
        repeat(2) { attempt ->
            session.engine.accept(session.work(), missing(emptyArray = attempt == 1))
            session.assertCorrectionQueued()
            session.deliver()
        }
        session.engine.accept(session.work(), missing())
        assertEquals("paused", session.state().getString("status"))
        assertTrue(session.engine.poll().isNull("command"))
        assertEquals(0, session.state().getInt("successful_mutations"))
        assertEquals(3, session.state().getInt("calls"))
        assertFalse(session.stored.contains(UNTRUSTED_CONTENT))
    }

    @Test fun acceptedValidActionResetsConsecutiveCorrectionBudgetAndClearsFeedback() {
        val session = Session()
        repeat(2) {
            session.engine.accept(session.work(), missing())
            session.assertCorrectionQueued()
            session.deliver()
        }
        session.engine.accept(session.work(), tap(session))
        assertEquals("visual_gesture", session.engine.poll().getJSONObject("command").getString("kind"))
        session.deliver()
        assertEquals(1, session.state().getInt("successful_mutations"))
        assertFalse(session.state().has("model_protocol_feedback"))
        repeat(2) {
            session.engine.accept(session.work(), missing())
            session.assertCorrectionQueued()
            session.deliver()
        }
        assertEquals("running", session.state().getString("status"))
        session.engine.accept(session.work(), missing())
        assertEquals("paused", session.state().getString("status"))
        assertEquals(1, session.state().getInt("successful_mutations"))
    }

    @Test fun correctionAllowsAProperFinishToolButNeverUsesFreeTextAsCompletion() {
        val session = Session()
        session.engine.accept(session.work(), missing())
        session.assertCorrectionQueued()
        session.deliver()
        session.engine.accept(session.work(), tool("finish", JSONObject().put("outcome", "completed")
            .put("summary", "已读取当前显示结果").put("screen_id", session.screenId).put("evidence_id", session.evidenceId)))
        assertEquals("completed", session.state().getString("status"))
        assertTrue(session.engine.poll().isNull("command"))
        assertEquals(0, session.state().getInt("successful_mutations"))
        assertFalse(session.stored.contains(UNTRUSTED_CONTENT))
    }

    @Test fun providerErrorsMissingResponsesAndNonStopResultsStayPaused() {
        val cases = listOf(
            null to null,
            JSONObject().put("choices", JSONArray()) to null,
            missing() to "transport_failure",
            missing(finishReason = "length") to null,
            missing(finishReason = "content_filter") to null,
            missing(finishReason = "tool_calls") to null,
        )
        for ((reply, error) in cases) {
            val session = Session()
            session.engine.accept(session.work(), reply, error)
            assertEquals("paused", session.state().getString("status"))
            assertTrue(session.engine.poll().isNull("command"))
            assertEquals(0, session.state().getInt("successful_mutations"))
            assertFalse(session.state().has("model_protocol_feedback"))
        }
    }

    @Test fun wrongCallContainerUnknownToolsAndUnsafeArgumentsDoNotBecomeMissingCallRecovery() {
        repeat(4) { scenario ->
            val session = Session()
            val reply = when (scenario) {
                0 -> response(JSONObject().put("tool_calls", JSONObject().put("unexpected", true)))
                1 -> tool("execute_shell", JSONObject().put("command", "unoffered-command"))
                2 -> tap(session, x = -1)
                else -> tap(session, safety = "payment")
            }
            session.engine.accept(session.work(), reply)
            assertEquals("scenario=$scenario", "paused", session.state().getString("status"))
            assertTrue(session.engine.poll().isNull("command"))
            assertEquals(0, session.state().getInt("successful_mutations"))
            assertFalse(session.state().has("model_protocol_feedback"))
        }
    }

    @Test fun ordinaryReadOnlyVisionTextStillNeedsNoToolCall() {
        val session = Session(visual = false)
        session.engine.accept(session.work(), tool("inspect_screen", JSONObject().put("question", "读取当前结果")))
        session.deliver()
        val reader = session.work()
        assertTrue(reader.vision)
        assertFalse(reader.visualAgent)
        assertFalse(reader.grounding)
        session.engine.accept(reader, response(JSONObject().put("content", "当前显示0")))
        assertEquals("running", session.state().getString("status"))
        assertTrue(session.engine.poll().isNull("command"))
        assertFalse(session.state().has("model_protocol_feedback"))
        assertFalse(session.work().vision)
    }
}

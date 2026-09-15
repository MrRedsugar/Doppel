package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Service gate outcomes exercise the real engine; these are synthetic state-machine contracts only. */
class VisualDispatchGateEngineTest {
    private data class Session(val engine: DirectTaskEngine, val run: String, val command: JSONObject)
    private fun screen() = JSONObject().put("screen_id", "screen-a").put("package_name", "example.canvas")
        .put("width", 1920).put("height", 1080).put("nodes", JSONArray())
    private fun reply(tool: String, arguments: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls")
        .put("message", JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("function", JSONObject().put("name", tool).put("arguments", arguments.toString())))))))
    private fun result(command: JSONObject, status: String, data: JSONObject) = JSONObject().put("command_id", command.getString("id"))
        .put("run_id", command.getString("run_id")).put("status", status).put("message", "核验截图超过派发时限，请重新观察")
        .put("observation", screen()).put("data", data)
    private fun start(): Session {
        val engine = DirectTaskEngine(null, {}, { 2000L })
        val run = engine.create(JSONObject().put("goal", "点击当前画布中的已授权入口").put("device_id", DirectRuntime.DEVICE_ID).put("mode", "full")).getString("id")
        engine.result(result(engine.poll().getJSONObject("command"), "ok", JSONObject().put("device_profile", JSONObject().put("visual_gestures", true))))
        engine.accept(checkNotNull(engine.takeWork()), reply("visual_action", JSONObject().put("intent", "点击当前入口")))
        val read = engine.poll().getJSONObject("command")
        assertTrue(read.getBoolean("include_screenshot"))
        val frame = VisualFrame("capture-a", "screen-a", "example.canvas", 1920, 1080, 960, 540, 0, 1000, 46000, "frame-hash")
        engine.result(result(read, "ok", JSONObject().put("image_base64", "aW1hZ2U=").put("mime_type", "image/png").put("visual_frame", frame.json())))
        engine.accept(checkNotNull(engine.takeWork()), reply("propose_tap", JSONObject().put("capture_id", "capture-a").put("x", 384).put("y", 324)
            .put("duration_ms", 80).put("label", "入口").put("screen_context", "当前测试画布").put("safety", "safe")))
        val action = engine.poll().getJSONObject("command")
        assertEquals("visual_gesture", action.getString("kind"))
        return Session(engine, run, action)
    }
    private fun blocked(actionState: String) = JSONObject().put("action_state", actionState).put("reason_code", "gesture_context_changed")
        .put("visual_diagnostic", JSONObject().put("reason_code", "gesture_context_changed").put("stage", "input")
            .put("frame_matches", true).put("pixels_match", true).put("verification_age_ms", 1043))

    @Test fun confirmedUnsubmittedGateRequestsFreshObservationWithoutReplayingTheGesture() {
        val session = start()
        val state = VisualDispatchGate.actionState(submitted = false, accepted = false)
        val receipt = result(session.command, VisualDispatchGate.blockedStatus(state), blocked(state))
        assertTrue(session.engine.result(receipt).getBoolean("accepted"))
        assertEquals("running", session.engine.get(session.run).getString("status"))
        assertEquals(0, session.engine.get(session.run).getInt("successful_mutations"))
        val next = session.engine.poll().getJSONObject("command")
        assertEquals("observe", next.getString("kind"))
        assertNotEquals(session.command.getString("id"), next.getString("id"))
        assertFalse(next.has("gesture")); assertFalse(next.has("visual_permit"))
        assertFalse("The obsolete result cannot be accepted twice", session.engine.result(receipt).getBoolean("accepted"))
        assertEquals(next.getString("id"), session.engine.poll().getJSONObject("command").getString("id"))
        session.engine.result(result(next, "ok", JSONObject()))
        assertTrue("Fresh evidence must return control to planning, never repeat the old gesture", session.engine.poll().isNull("command"))
        assertNotNull(session.engine.takeWork())
    }

    @Test fun submittedUnknownGatePausesWithoutSchedulingAnObservationOrMutation() {
        assertUncertainPauses(submitted = true, accepted = false)
    }

    @Test fun acceptedReceiptWithContradictoryGateNeverBecomesSafeToRepeat() {
        assertUncertainPauses(submitted = true, accepted = true)
        assertUncertainPauses(submitted = false, accepted = true)
    }

    private fun assertUncertainPauses(submitted: Boolean, accepted: Boolean) {
        val session = start()
        val state = VisualDispatchGate.actionState(submitted, accepted)
        assertEquals("error", VisualDispatchGate.blockedStatus(state))
        session.engine.result(result(session.command, VisualDispatchGate.blockedStatus(state), blocked(state)))
        assertEquals("paused", session.engine.get(session.run).getString("status"))
        assertTrue(session.engine.poll().isNull("command"))
        assertNull(session.engine.takeWork())
        assertEquals(0, session.engine.get(session.run).getInt("successful_mutations"))
    }
}

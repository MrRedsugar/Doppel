package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic engine contracts only: no device, model API or claim of business success. */
class AdaptiveVisualEngineTest {
    private data class Session(val engine: DirectTaskEngine, val runId: String)
    private val samePixels = "a".repeat(64)

    private fun screen(id: String) = JSONObject().put("screen_id", id).put("package_name", "dev.opaque.notes")
        .put("width", 1440).put("height", 3200).put("nodes", JSONArray()
            .put(JSONObject().put("id", "n1").put("role", "button").put("text", "进入编辑")
                .put("enabled", true).put("clickable", true).put("bounds", JSONArray(listOf(100, 800, 500, 1100))))
            .put(JSONObject().put("id", "n2").put("role", "button").put("text", "其他入口")
                .put("enabled", true).put("clickable", true).put("bounds", JSONArray(listOf(800, 800, 1200, 1100))))
            // An editable surface makes equal accessibility text insufficient to prove no change.
            .put(JSONObject().put("id", "n3").put("role", "android.widget.EditText").put("text", "")
                .put("enabled", true).put("editable", true).put("password", false)
                .put("bounds", JSONArray(listOf(100, 1400, 1300, 2600)))))

    private fun frame(screenId: String, captureId: String, sha: String = samePixels) = VisualFrame(
        captureId, screenId, "dev.opaque.notes", 1440, 3200, 648, 1440, 0, 1000, 46000, sha)

    private fun reply(name: String, args: JSONObject): JSONObject {
        val function = JSONObject().put("name", name).put("arguments", args.toString())
        val message = JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("function", function)))
        return JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls").put("message", message)))
    }

    private fun response(command: JSONObject, screenId: String, captureId: String? = null, status: String = "ok",
        extra: JSONObject = JSONObject(), sha: String = samePixels): JSONObject {
        val data = JSONObject(extra.toString()).put("device_profile", JSONObject().put("visual_gestures", true))
        if (captureId != null) data.put("image_base64", "c3ludGhldGljLWZyYW1l")
            .put("mime_type", "image/png").put("visual_frame", frame(screenId, captureId, sha).json())
        return JSONObject().put("run_id", command.getString("run_id")).put("command_id", command.getString("id"))
            .put("status", status).put("message", if (status == "ok") "系统已接受，请核对结果" else "控件未执行操作")
            .put("observation", screen(screenId)).put("data", data)
    }

    private fun startVisualSession(): Session {
        val engine = DirectTaskEngine(null, {}, { 2000L }, visualControl = true)
        val id = engine.create(JSONObject().put("device_id", DirectRuntime.DEVICE_ID)
            .put("goal", "进入笔记编辑页面").put("mode", "full")).getString("id")
        val initial = engine.poll().getJSONObject("command")
        engine.result(response(initial, "initial-semantic"))
        val planner = engine.takeWork()!!
        assertFalse(planner.visualAgent)
        // Enter visual control through a public planner tool, without constructing persisted internals.
        engine.accept(planner, reply("inspect_screen", JSONObject().put("question", "核对当前编辑入口")))
        val capture = engine.poll().getJSONObject("command")
        assertEquals("observe", capture.getString("kind"))
        assertTrue(capture.getBoolean("include_screenshot"))
        engine.result(response(capture, "source-screen", "source-capture"))
        return Session(engine, id)
    }

    private fun visualWork(session: Session): DirectTaskEngine.Work {
        val work = session.engine.takeWork()
        assertNotNull("A fresh observed frame must permit a visual decision", work)
        assertTrue("This regression must exercise the visual actor, not the semantic planner", work!!.visualAgent)
        return work
    }

    private fun proposeNativeTap(session: Session, target: String = "n1") {
        session.engine.accept(visualWork(session), reply("action", JSONObject().put("kind", "tap").put("target", target)))
    }

    private fun receipts(session: Session): JSONArray = session.engine.get(session.runId)
        .getJSONObject("action_progress").getJSONArray("receipts")

    private fun currentText(work: DirectTaskEngine.Work): String {
        val messages = work.payload.getJSONArray("messages")
        return messages.getJSONObject(messages.length() - 1).getJSONArray("content").getJSONObject(0).getString("text")
    }

    private fun acceptTapWithoutPixels(session: Session, generation: Int): JSONObject {
        proposeNativeTap(session)
        val action = session.engine.poll().getJSONObject("command")
        assertEquals("tap", action.getString("kind"))
        session.engine.result(response(action, "native-result-$generation", extra = JSONObject().put("action_state", "accepted")))
        val receipt = receipts(session).getJSONObject(receipts(session).length() - 1)
        assertEquals(action.getString("id"), receipt.getString("command_id"))
        assertEquals("Missing result pixels on the opaque page must remain unknown", "unknown", receipt.getString("effect"))
        return action
    }

    private fun pendingResultCapture(session: Session): JSONObject {
        assertNull("Capture the missing result image before asking the actor to act again", session.engine.takeWork())
        return session.engine.poll().getJSONObject("command").also {
            assertEquals("observe", it.getString("kind"))
            assertTrue(it.getBoolean("include_screenshot"))
        }
    }

    @Test fun nativeVisualActorTapsBindSupplementaryPixelsAndBlockThirdUnchangedTarget() {
        val session = startVisualSession()
        val acceptedIds = mutableListOf<String>()
        for (generation in 1..2) {
            val action = acceptTapWithoutPixels(session, generation)
            acceptedIds += action.getString("id")
            val capture = pendingResultCapture(session)
            assertNotEquals(action.getString("id"), capture.getString("id"))
            session.engine.result(response(capture, "captured-result-$generation", "result-capture-$generation"))
            val rows = receipts(session)
            assertEquals("A read completes the existing action receipt; it is not a new action", generation, rows.length())
            val receipt = rows.getJSONObject(generation - 1)
            assertEquals(acceptedIds[generation - 1], receipt.getString("command_id"))
            assertEquals("unchanged", receipt.getString("effect"))
            assertEquals("result-capture-$generation", receipt.getJSONObject("after").getString("capture_id"))
            assertFalse(receipt.getBoolean("proves_business_success"))
        }
        // Completing the second result must not overwrite the first command's frame association.
        assertEquals("result-capture-1", receipts(session).getJSONObject(0).getJSONObject("after").getString("capture_id"))
        proposeNativeTap(session)
        val next = session.engine.poll().getJSONObject("command")
        assertEquals("The third identical native tap must not reach the device", "observe", next.getString("kind"))
        assertTrue(next.getBoolean("include_screenshot"))
        assertEquals(2, receipts(session).length())
        assertEquals("running", session.engine.get(session.runId).getString("status"))
        session.engine.result(response(next, "after-repetition-read", "repetition-capture"))
        val recovery = visualWork(session)
        assertTrue(currentText(recovery).contains("repeated_unchanged_target"))
        assertTrue(currentText(recovery).contains("\"action_executed\":false"))
        session.engine.accept(recovery, reply("action", JSONObject().put("kind", "tap").put("target", "n2")))
        val alternative = session.engine.poll().getJSONObject("command")
        assertEquals("tap", alternative.getString("kind"))
        assertEquals("n2", alternative.getString("target"))
    }

    @Test fun cancelledSupplementaryReadAndResumeCannotBackfillOldReceipt() {
        val session = startVisualSession()
        val action = acceptTapWithoutPixels(session, 1)
        val oldCapture = pendingResultCapture(session)
        session.engine.control(session.runId, "pause", JSONObject())
        session.engine.control(session.runId, "resume", JSONObject())
        assertFalse(session.engine.result(response(oldCapture, "late-old-screen", "late-old-capture")).getBoolean("accepted"))
        val resumed = session.engine.poll().getJSONObject("command")
        assertNotEquals(oldCapture.getString("id"), resumed.getString("id"))
        // A new independent observation after resume has no authority to complete the old action.
        session.engine.result(response(resumed, "resumed-semantic-screen"))
        val freshCapture = pendingResultCapture(session)
        session.engine.result(response(freshCapture, "resumed-screen", "resumed-capture"))
        val receipt = receipts(session).getJSONObject(0)
        assertEquals(action.getString("id"), receipt.getString("command_id"))
        assertEquals("unknown", receipt.getString("effect"))
        assertFalse(receipt.getJSONObject("after").has("capture_id"))
        assertTrue(visualWork(session).visualAgent)
    }

    @Test fun differentResultPixelsRemainUnknownAndDoNotBlockAUsefulNextAttempt() {
        val session = startVisualSession()
        acceptTapWithoutPixels(session, 1)
        val capture = pendingResultCapture(session)
        session.engine.result(response(capture, "changed-pixel-screen", "changed-pixel-capture", sha = "b".repeat(64)))
        val receipt = receipts(session).getJSONObject(0)
        assertEquals("unknown", receipt.getString("effect"))
        assertEquals("changed-pixel-capture", receipt.getJSONObject("after").getString("capture_id"))
        assertFalse(receipt.getBoolean("proves_business_success"))
        proposeNativeTap(session)
        assertEquals("tap", session.engine.poll().getJSONObject("command").getString("kind"))
    }

    @Test fun explicitNativeFailureDiagnosticReachesVisualActorAfterFreshCapture() {
        val session = startVisualSession()
        proposeNativeTap(session)
        val failed = session.engine.poll().getJSONObject("command")
        val diagnostic = JSONObject().put("node_present", true).put("enabled", true).put("clickable", true)
            .put("editable", false).put("password", false).put("action_ids", JSONArray())
            .put("requested_action_advertised", false).put("requested_action_id", 16).put("unknown_private", "do-not-echo")
        session.engine.result(response(failed, "native-failure-screen", status = "error",
            extra = JSONObject().put("action_state", "failed").put("action_diagnostic", diagnostic)))
        assertEquals("running", session.engine.get(session.runId).getString("status"))
        val capture = pendingResultCapture(session)
        assertNotEquals(failed.getString("id"), capture.getString("id"))
        session.engine.result(response(capture, "failure-recovery-screen", "failure-recovery-capture"))
        val recovery = visualWork(session)
        val text = currentText(recovery)
        assertTrue(text.contains("action_not_executed"))
        assertTrue(text.contains("\"action_executed\":false"))
        assertTrue(text.contains("\"requested_action_advertised\":false"))
        assertTrue(text.contains("\"requested_action_id\":16"))
        assertFalse(text.contains("do-not-echo"))
        session.engine.accept(recovery, reply("action", JSONObject().put("kind", "tap").put("target", "n2")))
        val alternative = session.engine.poll().getJSONObject("command")
        assertEquals("tap", alternative.getString("kind"))
        assertEquals("n2", alternative.getString("target"))
        assertNotEquals(failed.getString("id"), alternative.getString("id"))
    }
}

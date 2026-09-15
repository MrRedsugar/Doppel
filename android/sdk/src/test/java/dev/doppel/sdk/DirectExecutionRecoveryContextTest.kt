package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Recovery must enrich the original action's history without inventing another accepted action. */
class DirectExecutionRecoveryContextTest {
    private var stored = ""
    private fun engine(previous: String? = null) = DirectTaskEngine(previous, { stored = it }, { 2000L })
    private fun screen(id: String = "page-before") = JSONObject().put("screen_id", id).put("package_name", "dev.fixture")
        .put("width", 1920).put("height", 1080).put("nodes", JSONArray())
    private fun pixels(page: String, capture: String) = JSONObject().put("image_base64", "aW1hZ2U=").put("mime_type", "image/png")
        .put("visual_frame", VisualFrame(capture, page, "dev.fixture", 1920, 1080, 960, 540, 1, 1000, 46000, "hash-$capture").json())
    private fun reply(tool: String, args: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls")
        .put("message", JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("function", JSONObject()
            .put("name", tool).put("arguments", args.toString())))))))
    private fun analysis(text: String) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
        .put("message", JSONObject().put("content", text))))
    private fun result(command: JSONObject, observation: JSONObject?, data: JSONObject = JSONObject(), status: String = "ok") = JSONObject()
        .put("run_id", command.getString("run_id")).put("command_id", command.getString("id")).put("status", status)
        .put("observation", observation ?: JSONObject.NULL).put("data", data)
    private fun knownLoading(reason: String) = JSONObject().put("action_state", "accepted")
        .put("read_diagnostic", JSONObject().put("error_class", "ScreenNotReadyException").put("source_file", "DoppelAccessibilityService.kt")
            .put("source_line", 759).put("reason_code", reason))
        .put("visual_diagnostic", JSONObject().put("reason_code", "visual_pixels_verified").put("stage", "verify")
            .put("frame_matches", true).put("pixels_match", true))
    private fun receipts(): JSONArray = JSONArray(stored).getJSONObject(0).getJSONObject("execution_context").getJSONArray("receipts")
    private fun beginLoading(engine: DirectTaskEngine, reason: String = "empty_frame"): JSONObject {
        engine.create(JSONObject().put("goal", "Open the selected canvas report and verify its title")
            .put("device_id", DirectRuntime.DEVICE_ID).put("mode", "full"))
        assertTrue(engine.result(result(engine.poll().getJSONObject("command"), screen(),
            JSONObject().put("device_profile", JSONObject().put("visual_gestures", true)))).getBoolean("accepted"))
        engine.accept(engine.takeWork()!!, reply("visual_action", JSONObject().put("intent", "Open the report preview")))
        assertTrue(engine.result(result(engine.poll().getJSONObject("command"), screen(), pixels("page-before", "capture-before"))).getBoolean("accepted"))
        engine.accept(engine.takeWork()!!, reply("propose_tap", JSONObject().put("capture_id", "capture-before").put("x", 384).put("y", 324)
            .put("duration_ms", 80).put("label", "Preview").put("screen_context", "Report canvas").put("safety", "safe")))
        val gesture = engine.poll().getJSONObject("command")
        assertTrue(engine.result(result(gesture, null, knownLoading(reason))).getBoolean("accepted"))
        assertEquals(1, receipts().length())
        assertFalse(receipts().getJSONObject(0).getJSONObject("result").has("capture_id"))
        return gesture
    }

    @Test fun supplementalEvidenceAnnotatesTheOriginalGestureAndKeepsItsOwnReadIdentity() {
        for (reason in listOf("root_unavailable", "empty_frame")) {
            val engine = engine()
            val gesture = beginLoading(engine, reason)
            val read = engine.poll().getJSONObject("command")
            assertEquals("observe", read.getString("kind"))
            assertNotEquals(gesture.getString("id"), read.getString("id"))
            assertTrue(engine.result(result(read, screen("page-preview"), pixels("page-preview", "capture-supplement"))).getBoolean("accepted"))
            val verification = engine.takeWork()!!
            assertTrue(verification.vision); assertFalse(verification.grounding)
            engine.accept(verification, analysis("The new preview title is visible."))
            assertEquals(1, receipts().length())
            val receipt = receipts().getJSONObject(0)
            assertEquals(gesture.getString("id"), receipt.getString("command_id"))
            assertEquals("capture-before", receipt.getJSONObject("source").getString("capture_id"))
            val after = receipt.getJSONObject("result")
            assertEquals("page-preview", after.getString("screen_id"))
            assertEquals("capture-supplement", after.getString("capture_id"))
            assertEquals(read.getString("id"), after.getString("evidence_id"))
            val interpretation = receipt.getJSONObject("after_analysis")
            assertTrue(interpretation.getBoolean("model_interpretation"))
            assertEquals(after.toString(), interpretation.getJSONObject("source").toString())
            assertFalse(receipt.getBoolean("proves_business_success"))
            assertEquals(1, engine.get(gesture.getString("run_id")).getInt("successful_mutations"))
            assertTrue(engine.poll().isNull("command"))
        }
    }

    @Test fun rejectedOrInvalidSupplementNeverSuppliesResultEvidenceToAcceptedHistory() {
        for (failure in listOf("error", "stale", "missing_image", "different_screen", "missing_observation", "takeover")) {
            val engine = engine()
            val gesture = beginLoading(engine)
            val before = receipts().toString()
            val read = engine.poll().getJSONObject("command")
            val data = pixels("page-preview", "unaccepted-capture")
            when (failure) {
                "missing_image" -> data.remove("image_base64")
                "different_screen" -> data.getJSONObject("visual_frame").put("screen_id", "unrelated-page")
                "takeover" -> data.put("human_takeover", "login")
            }
            val status = if (failure in setOf("error", "stale")) failure else "ok"
            assertTrue(engine.result(result(read, if (failure == "missing_observation") null else screen("page-preview"), data, status)).getBoolean("accepted"))
            assertEquals(failure, before, receipts().toString())
            assertEquals("paused", engine.get(gesture.getString("run_id")).getString("status"))
            assertNull(engine.takeWork()); assertTrue(engine.poll().isNull("command"))
        }
    }

    @Test fun knownLoadingTransitionsKeepReadingWithoutReplayingAcceptedGesture() {
        val engine = engine()
        val gesture = beginLoading(engine)
        val original = receipts().toString()
        repeat(3) {
            val read = engine.poll().getJSONObject("command")
            assertEquals("observe", read.getString("kind"))
            val data = JSONObject().put("visual_diagnostic", JSONObject()
                .put("reason_code", "capture_screen_changed").put("stage", "capture"))
            engine.result(result(read, screen("loading-$it"), data, "stale"))
            assertEquals("running", engine.get(gesture.getString("run_id")).getString("status"))
            assertEquals(original, receipts().toString())
            assertNull(engine.takeWork())
        }
        val fresh = engine.poll().getJSONObject("command")
        engine.result(result(fresh, screen("ready"), pixels("ready", "ready-capture")))
        assertNotNull(engine.takeWork())
        assertEquals(1, receipts().length())
        assertEquals(gesture.getString("id"), receipts().getJSONObject(0).getString("command_id"))
        assertEquals("ready-capture", receipts().getJSONObject(0).getJSONObject("result").getString("capture_id"))
    }

    @Test fun loadingObservationHasOneDeadlineEvenWhenEachReadChanges() {
        var clock = 2000L
        val engine = DirectTaskEngine(null, { stored = it }, { clock })
        val gesture = beginLoading(engine)
        val original = receipts().toString()
        clock += 15001
        val read = engine.poll().getJSONObject("command")
        val data = JSONObject().put("visual_diagnostic", JSONObject()
            .put("reason_code", "capture_screen_changed").put("stage", "capture"))
        engine.result(result(read, screen("still-loading"), data, "stale"))
        assertEquals("paused", engine.get(gesture.getString("run_id")).getString("status"))
        assertEquals(original, receipts().toString())
        assertTrue(engine.poll().isNull("command"))
    }

    @Test fun interruptedSupplementCannotBeAttachedAfterResumeCancellationOrProcessRestore() {
        for (interruption in listOf("pause", "cancel", "restore")) {
            val initial = engine()
            val gesture = beginLoading(initial)
            val id = gesture.getString("run_id")
            val oldRead = initial.poll().getJSONObject("command")
            val before = receipts().toString()
            val current = if (interruption == "restore") engine(stored) else initial.also { it.control(id, interruption, JSONObject()) }
            assertFalse(current.result(result(oldRead, screen("page-preview"), pixels("page-preview", "late-capture"))).getBoolean("accepted"))
            assertEquals(before, receipts().toString())
            assertTrue(current.poll().isNull("command"))
            if (interruption == "cancel") {
                assertEquals("cancelled", current.get(id).getString("status"))
            } else {
                current.control(id, "resume", JSONObject())
                val freshRead = current.poll().getJSONObject("command")
                assertNotEquals(oldRead.getString("id"), freshRead.getString("id"))
                assertTrue(current.result(result(freshRead, screen("page-current"))).getBoolean("accepted"))
                current.accept(current.takeWork()!!, reply("inspect_screen", JSONObject().put("question", "Read the current title")))
                assertTrue(current.result(result(current.poll().getJSONObject("command"), screen("page-current"),
                    pixels("page-current", "fresh-capture"))).getBoolean("accepted"))
                current.accept(current.takeWork()!!, analysis("A later independent screen is visible."))
                assertEquals("New observations cannot complete an interrupted action's historical evidence", before, receipts().toString())
                assertFalse(receipts().getJSONObject(0).has("after_analysis"))
            }
        }
    }
}

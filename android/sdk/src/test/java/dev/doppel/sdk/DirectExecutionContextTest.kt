package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectExecutionContextTest {
    private var stored = ""
    private fun engine(previous: String? = null) = DirectTaskEngine(previous, { stored = it }, { 2000L },
        skillCatalog = { JSONObject().put("items", JSONArray().put(JSONObject().put("name", "reports")
            .put("revision", "reference-v1").put("source", "bundled"))) })
    private fun screen(id: String = "page-a", editable: Boolean = false) = JSONObject()
        .put("screen_id", id).put("package_name", "dev.fixture").put("width", 1920).put("height", 1080)
        .put("nodes", JSONArray().put(JSONObject().put("id", "n1").put("text", if (editable) "private-input-value" else "Open details")
            .put("enabled", true).put("clickable", true).put("editable", editable).put("bounds", JSONArray(listOf(0, 0, 100, 100)))))
    private fun imageData(id: String = "page-a", capture: String = "capture-a") = JSONObject()
        .put("image_base64", "aW1hZ2U=").put("mime_type", "image/png")
        .put("visual_frame", VisualFrame(capture, id, "dev.fixture", 1920, 1080, 960, 540, 1, 1000, 46000, "pixels").json())
    private fun reply(tool: String, args: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls")
        .put("message", JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("function", JSONObject().put("name", tool).put("arguments", args.toString())))))))
    private fun analysis(text: String) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
        .put("message", JSONObject().put("content", text))))
    private fun deliver(engine: DirectTaskEngine, after: JSONObject = screen(), data: JSONObject = JSONObject(), status: String = "ok"): JSONObject {
        val sent = engine.poll().getJSONObject("command")
        assertTrue(engine.result(result(sent, after, data, status)).getBoolean("accepted"))
        return sent
    }
    private fun result(sent: JSONObject, after: JSONObject, data: JSONObject = JSONObject(), status: String = "ok") = JSONObject()
        .put("command_id", sent.getString("id")).put("run_id", sent.getString("run_id"))
        .put("status", status).put("observation", after).put("data", data)
    private fun ready(engine: DirectTaskEngine, editable: Boolean = false): String {
        val id = engine.create(JSONObject().put("goal", "Open the selected report and review its preview")
            .put("mode", "full").put("device_id", DirectRuntime.DEVICE_ID)).getString("id")
        deliver(engine, screen(editable = editable), JSONObject().put("device_profile", JSONObject().put("visual_gestures", true))
            .put("apps", JSONArray().put(JSONObject().put("package_name", "dev.fixture").put("label", "Fixture"))))
        return id
    }
    private fun context(): JSONObject {
        val value = JSONArray(stored).getJSONObject(0).optJSONObject("execution_context")
        assertNotNull("Accepted device actions need history independent of UI events", value)
        return value!!
    }
    private fun inspect(engine: DirectTaskEngine, page: String = "page-a", capture: String = "capture-a"): DirectTaskEngine.Work {
        engine.accept(engine.takeWork()!!, reply("inspect_screen", JSONObject().put("question", "What is visible now?")))
        deliver(engine, screen(page), imageData(page, capture))
        return engine.takeWork()!!
    }
    private fun launch(engine: DirectTaskEngine, page: String): JSONObject {
        engine.accept(engine.takeWork()!!, reply("launch", JSONObject().put("package_name", "dev.fixture")))
        return deliver(engine, screen(page))
    }
    private fun visualAction(engine: DirectTaskEngine): JSONObject {
        engine.accept(engine.takeWork()!!, reply("visual_action", JSONObject().put("intent", "Open the selected report preview")))
        deliver(engine, data = imageData())
        val candidate = JSONObject().put("capture_id", "capture-a").put("x", 384).put("y", 324).put("duration_ms", 80)
            .put("label", "Preview report").put("screen_context", "Report details").put("safety", "safe")
        engine.accept(engine.takeWork()!!, reply("propose_tap", candidate))
        return deliver(engine, screen("page-preview"), imageData("page-preview", "capture-after"))
    }

    @Test fun acceptedNavigationSurvivesFrequentObservationsWithoutReusingOldTargets() {
        val engine = engine(); ready(engine)
        val sent = launch(engine, "page-detail")
        repeat(20) {
            engine.accept(engine.takeWork()!!, reply("navigate", JSONObject().put("kind", "observe")))
            deliver(engine, screen("page-preview"))
        }
        val receipts = context().getJSONArray("receipts")
        assertEquals(1, receipts.length())
        val receipt = receipts.getJSONObject(0)
        assertEquals(sent.getString("id"), receipt.getString("command_id"))
        assertEquals("launch", receipt.getString("kind"))
        assertEquals("page-a", receipt.getJSONObject("source").getString("screen_id"))
        assertEquals("page-detail", receipt.getJSONObject("result").getString("screen_id"))
        val work = engine.takeWork()!!
        assertTrue(work.payload.toString().contains("打开应用"))
        assertFalse("Historical command IDs must not be dumped after a newer observation", work.payload.toString().contains(sent.getString("id")))
        assertTrue(work.payload.toString().contains("page-preview"))
        assertTrue(work.payload.getJSONArray("tools").toString().contains("visual_action"))
        assertFalse(receipt.has("target")); assertFalse(receipt.has("screen_id")); assertFalse(receipt.has("visual_permit"))
    }

    @Test fun ordinaryVisionAndPlannerReceiveGoalReferencesAndAcceptedNavigation() {
        for (tool in listOf("inspect_screen")) {
            val engine = engine(); ready(engine)
            engine.accept(engine.takeWork()!!, reply("load_skill", JSONObject().put("name", "reports")))
            engine.acceptLocal(engine.takeWork()!!, JSONObject().put("name", "reports").put("revision", "reference-v1")
                .put("instructions", "Preview must show the report title, not merely an open button.").put("source", "bundled"))
            val sent = launch(engine, "page-detail")
            val planner = engine.takeWork()!!
            engine.accept(planner, reply(tool, JSONObject().put(if (tool == "visual_action") "intent" else "question", "Identify the preview")))
            deliver(engine, screen("page-detail"), imageData("page-detail"))
            val vision = engine.takeWork()!!
            for (payload in listOf(planner.payload, vision.payload)) {
                val text = payload.toString()
                assertTrue("The goal must survive an inspection boundary", text.contains("Open the selected report and review its preview"))
                assertTrue("Loaded reference provenance must reach both models", text.contains("reference-v1"))
                assertTrue(text.contains("Preview must show the report title"))
                assertTrue("Accepted navigation should reach both models as readable history", text.contains("打开应用"))
                assertTrue(text.contains("reference_only"))
            }
            assertTrue(vision.vision)
        }
    }

    @Test fun groundingReceivesOnlyCurrentFrameAndIntentWithoutHistoricalInterpretations() {
        val engine = engine(); ready(engine)
        engine.accept(engine.takeWork()!!, reply("load_skill", JSONObject().put("name", "reports")))
        engine.acceptLocal(engine.takeWork()!!, JSONObject().put("name", "reports").put("revision", "reference-v1")
            .put("instructions", "reference-history-sentinel").put("source", "bundled"))
        engine.accept(inspect(engine), analysis("old-vision-sentinel guessed target x=0.8 y=0.1"))
        val sent = launch(engine, "page-detail")
        val planner = engine.takeWork()!!
        for (marker in listOf("reference-history-sentinel", "old-vision-sentinel", sent.getString("id"))) {
            assertTrue("Planning retains accepted context: $marker", planner.payload.toString().contains(marker))
        }
        engine.accept(planner, reply("visual_action", JSONObject().put("intent", "Identify the visible preview button")))
        deliver(engine, screen("page-detail"), imageData("page-detail", "capture-current"))
        val work = engine.takeWork()!!
        assertTrue(work.vision); assertTrue(work.grounding)
        val text = work.payload.toString()
        assertTrue(text.contains("Identify the visible preview button")); assertTrue(text.contains("capture-current"))
        assertTrue(text.contains("image_url"))
        for (marker in listOf("reference-history-sentinel", "old-vision-sentinel", "reference-v1", sent.getString("id"),
            "Open the selected report and review its preview", "reference_only", "execution_context")) {
            assertFalse("Atomic grounding must not inherit non-current context: $marker", text.contains(marker))
        }
    }

    @Test fun postActionInterpretationAttachesOnlyToItsCommandAndRemainsModelEvidence() {
        val engine = engine(); ready(engine)
        engine.accept(inspect(engine), analysis("Before action: selected report is Delta."))
        val sent = visualAction(engine)
        val verification = engine.takeWork()!!
        assertTrue(verification.vision)
        engine.accept(verification, analysis("After action: preview title Delta is visible."))
        var receipt = context().getJSONArray("receipts").getJSONObject(0)
        assertEquals(sent.getString("id"), receipt.getString("command_id"))
        assertEquals("capture-a", receipt.getJSONObject("source").getString("capture_id"))
        assertEquals("capture-after", receipt.getJSONObject("result").getString("capture_id"))
        assertTrue(receipt.getJSONObject("before_analysis").getBoolean("model_interpretation"))
        assertTrue(receipt.getJSONObject("after_analysis").getBoolean("model_interpretation"))
        assertTrue(receipt.getJSONObject("after_analysis").toString().contains("preview title Delta"))
        assertFalse(receipt.getBoolean("proves_business_success"))
        engine.accept(inspect(engine, "page-other", "capture-other"), analysis("Unrelated later screen."))
        receipt = context().getJSONArray("receipts").getJSONObject(0)
        assertFalse(receipt.getJSONObject("after_analysis").toString().contains("Unrelated later"))
        assertTrue(receipt.getJSONObject("after_analysis").toString().contains("preview title Delta"))
    }

    @Test fun identicalCanvasNodeIdentityCannotBindAnalysisFromDifferentPixels() {
        val engine = engine(); ready(engine)
        engine.accept(engine.takeWork()!!, reply("inspect_screen", JSONObject().put("question", "Read the report title")))
        val oldImage = imageData(capture = "capture-old")
        oldImage.getJSONObject("visual_frame").put("sha256", "different-pixels")
        deliver(engine, data = oldImage)
        engine.accept(engine.takeWork()!!, analysis("Old canvas contained a different report."))
        visualAction(engine)
        val receipt = context().getJSONArray("receipts").getJSONObject(0)
        assertFalse("A constant screen ID does not identify the visual source", receipt.has("before_analysis"))
        assertEquals("capture-a", receipt.getJSONObject("source").getString("capture_id"))
    }

    @Test fun visualHistoryBoundsInterpretationsAndNeverContainsCoordinatesOrPermit() {
        val engine = engine(); ready(engine)
        val sent = visualAction(engine)
        engine.accept(engine.takeWork()!!, analysis("Visible evidence. " + "detail ".repeat(1000)))
        val receipt = context().getJSONArray("receipts").getJSONObject(0)
        val text = receipt.getJSONObject("after_analysis").getString("text")
        assertTrue(text.startsWith("Visible evidence.")); assertTrue(text.length <= 1000)
        val record = receipt.toString()
        assertFalse(record.contains(sent.getString("visual_permit")))
        for (field in listOf("gesture", "x", "y", "end_x", "end_y", "visual_permit", "image_base64")) assertFalse(receipt.has(field))
        assertTrue(receipt.getJSONObject("target_description").getBoolean("untrusted"))
    }

    @Test fun inputReceiptsDoNotPersistValuesOrExecutionArguments() {
        val engine = engine(); ready(engine, editable = true)
        engine.accept(engine.takeWork()!!, reply("action", JSONObject().put("kind", "type").put("target", "n1").put("text", "typed-secret-183742")))
        deliver(engine, screen("page-edited", editable = true), JSONObject().put("text", "result-secret-591746").put("visual_permit", "private-permit"))
        val receipt = context().getJSONArray("receipts").getJSONObject(0)
        assertEquals("type", receipt.getString("kind"))
        val serialized = context().toString()
        for (secret in listOf("typed-secret", "result-secret", "private-input-value", "private-permit")) assertFalse(serialized.contains(secret))
        assertFalse(receipt.has("text")); assertFalse(receipt.has("target")); assertFalse(receipt.has("arguments"))
    }

    @Test fun failedStaleNoOpAndLateResultsDoNotBecomeAcceptedActionHistory() {
        for (status in listOf("error", "stale", "ok", "late")) {
            val engine = engine(); val id = ready(engine)
            engine.accept(engine.takeWork()!!, reply("launch", JSONObject().put("package_name", "dev.fixture")))
            val sent = engine.poll().getJSONObject("command")
            if (status == "late") engine.control(id, "pause", JSONObject())
            engine.result(result(sent, screen("page-other"), JSONObject().put("no_op", status == "ok"), if (status == "late") "ok" else status))
            val receipts = JSONArray(stored).getJSONObject(0).optJSONObject("execution_context")?.optJSONArray("receipts") ?: JSONArray()
            assertEquals(status, 0, receipts.length())
        }
    }

    @Test fun restoredHistoryExplainsPriorActionsButCannotRestoreCommandsOrApproval() {
        val engine = engine(); val id = ready(engine)
        val sent = launch(engine, "page-detail")
        val snapshot = stored
        val recovered = engine(snapshot)
        assertEquals("paused", recovered.get(id).getString("status"))
        assertTrue(recovered.poll().isNull("command")); assertNull(recovered.takeWork())
        recovered.control(id, "resume", JSONObject())
        assertEquals("observe", recovered.poll().getJSONObject("command").getString("kind"))
        deliver(recovered, screen("page-current"))
        val restoredPayload = recovered.takeWork()!!.payload.toString()
        assertTrue(restoredPayload.contains("打开应用"))
        assertFalse("Restored history should not expose an obsolete command ID", restoredPayload.contains(sent.getString("id")))
        assertTrue(recovered.poll().isNull("command"))
        assertFalse(context().getJSONArray("receipts").getJSONObject(0).getBoolean("proves_business_success"))
    }

    @Test fun oldGenerationVisionCannotAnnotateHistoryAfterPauseAndResume() {
        val engine = engine(); val id = ready(engine)
        visualAction(engine)
        val obsolete = engine.takeWork()!!
        engine.control(id, "pause", JSONObject())
        engine.control(id, "resume", JSONObject())
        deliver(engine, screen("page-user-changed"))
        engine.accept(obsolete, analysis("Obsolete preview marked complete."))
        val receipts = context().getJSONArray("receipts")
        assertEquals(1, receipts.length())
        assertFalse(receipts.toString().contains("Obsolete preview"))
        assertFalse(receipts.getJSONObject(0).has("after_analysis"))
        assertEquals("running", engine.get(id).getString("status"))
        assertNotNull(engine.takeWork())
    }

    @Test fun contextIsBoundedByActionsRatherThanRefreshCount() {
        val engine = engine(); ready(engine)
        val first = launch(engine, "page-0").getString("id")
        var last = ""
        repeat(16) { last = launch(engine, "page-${it + 1}").getString("id") }
        val receipts = context().getJSONArray("receipts")
        assertTrue(receipts.length() in 1..12)
        assertFalse(receipts.toString().contains(first)); assertTrue(receipts.toString().contains(last))
    }

    @Test fun oldRunsNeverInventReceiptsFromHumanReadableEvents() {
        val engine = engine(); val id = ready(engine)
        val old = JSONArray(stored)
        old.getJSONObject(0).remove("execution_context")
        old.getJSONObject(0).put("events", JSONArray().put(JSONObject().put("message", "正在执行视觉点击：Fake accepted result").put("created_at", 1000)))
        val recovered = engine(old.toString())
        recovered.control(id, "resume", JSONObject()); deliver(recovered)
        val receipts = JSONArray(stored).getJSONObject(0).optJSONObject("execution_context")?.optJSONArray("receipts") ?: JSONArray()
        assertEquals(0, receipts.length()); assertNotNull(recovered.takeWork())
    }
}

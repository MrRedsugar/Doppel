package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FeedbackOperatorEngineTest {
    private fun engine() = DirectTaskEngine(null, {}, { 2000L }, plannedControl = true, feedbackControl = true)
    private fun start(e: DirectTaskEngine): String {
        val id = e.create(JSONObject().put("goal", "打开设置").put("mode", "full").put("device_id", DirectRuntime.DEVICE_ID)).getString("id")
        receipt(e, "home"); return id
    }
    private fun receipt(e: DirectTaskEngine, page: String, editable: Boolean = false): JSONObject {
        val cmd = e.poll().getJSONObject("command")
        e.result(JSONObject().put("run_id", cmd.getString("run_id")).put("command_id", cmd.getString("id"))
            .put("status", "ok").put("observation", JSONObject().put("screen_id", page).put("package_name", "example.settings")
                .put("width", 1080).put("height", 2400).put("nodes", JSONArray().put(JSONObject().put("id", "n1")
                    .put("text", "设置").put("clickable", true).put("enabled", true).put("editable", editable).put("bounds", JSONArray(listOf(1,1,200,200))))))
            .put("data", JSONObject().put("apps", JSONArray().put(JSONObject().put("label", "设置").put("package_name", "example.settings")))))
        return cmd
    }
    private fun response(name: String, args: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("finish_reason", "tool_calls").put("message", JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
            .put("tool_calls", JSONArray().put(JSONObject().put("id", java.util.UUID.randomUUID().toString()).put("type", "function")
                .put("function", JSONObject().put("name", name).put("arguments", args.toString())))))))
    private fun plan(e: DirectTaskEngine) {
        e.accept(requireNotNull(e.takeWork()), response("plan_task", JSONObject().put("reason", "查看当前页面后进入设置")
            .put("stages", JSONArray().put(JSONObject().put("objective", "进入设置").put("exit_condition", "设置页可见")))))
    }
    @Test fun actorDoesNotNeedToCopyEvidenceAndFinishBindsTheRequestSource() {
        val e = engine(); val id = start(e); plan(e)
        val action = requireNotNull(e.takeWork())
        assertEquals("disabled", action.payload.getJSONObject("thinking").getString("type"))
        assertFalse(action.payload.getJSONArray("tools").toString().contains("task_progress"))
        e.accept(action, response("action", JSONObject().put("kind", "tap").put("target", "n1")))
        assertEquals("tap", receipt(e, "settings-page").getString("kind"))
        e.accept(requireNotNull(e.takeWork()), response("finish", JSONObject().put("outcome", "completed")
            .put("summary", "已打开设置").put("basis", "current_screen").put("read_scope", "visible")
            .put("stage_status", "reached").put("observed_result", "设置页面已显示")))
        assertEquals("completed", e.get(id).getString("status"))
    }
    @Test fun pausedInFlightResponseCannotDispatch() {
        val e = engine(); val id = start(e); plan(e); val work = requireNotNull(e.takeWork())
        e.control(id, "pause", JSONObject())
        e.accept(work, response("action", JSONObject().put("kind", "tap").put("target", "n1")))
        assertTrue(e.poll().isNull("command")); assertEquals("paused", e.get(id).getString("status"))
    }
    @Test fun duplicateOldResponseCannotReplaceAQueuedActionOrNewRequest() {
        val e = engine(); start(e); plan(e)
        val old = requireNotNull(e.takeWork())
        val action = response("action", JSONObject().put("kind", "tap").put("target", "n1"))
        e.accept(old, action)
        e.accept(old, action)
        assertEquals("tap", receipt(e, "settings-page").getString("kind"))
        val current = requireNotNull(e.takeWork())
        e.accept(old, action)
        assertTrue(e.isCurrent(current)); assertTrue(e.poll().isNull("command"))
    }
    @Test fun initialPlanCanReadKnowledgeWithoutAnActionOrStage() {
        val e = engine(); start(e)
        e.accept(requireNotNull(e.takeWork()), response("list_skills", JSONObject().put("query", "设置")))
        val work = requireNotNull(e.takeWork())
        assertEquals("list_skills", work.localTool)
        assertTrue(e.poll().isNull("command"))
    }
    @Test fun nonStringToolIdNeverDispatchesAnAction() {
        for (badId in listOf<Any>(17, true, JSONObject.NULL)) {
            val e = engine(); start(e); plan(e)
            val response = response("action", JSONObject().put("kind", "tap").put("target", "n1"))
            response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getJSONArray("tool_calls")
                .getJSONObject(0).put("id", badId)
            e.accept(requireNotNull(e.takeWork()), response)
            assertEquals("observe", e.poll().getJSONObject("command").getString("kind"))
        }
    }
    @Test fun finalStageCannotAlsoDispatchAnExtraTap() {
        val e = engine(); start(e); plan(e)
        e.accept(requireNotNull(e.takeWork()), response("action", JSONObject().put("kind", "tap").put("target", "n1")
            .put("stage_status", "reached").put("observed_result", "设置已经打开")))
        assertEquals("observe", e.poll().getJSONObject("command").getString("kind"))
    }
    @Test fun sameNameOldLocalReplyCannotReplaceCurrentRequest() {
        val e = engine(); start(e)
        e.accept(requireNotNull(e.takeWork()), response("list_skills", JSONObject().put("query", "first")))
        val old = requireNotNull(e.takeWork())
        e.acceptLocal(old, JSONObject().put("items", JSONArray()))
        e.accept(requireNotNull(e.takeWork()), response("list_skills", JSONObject().put("query", "second")))
        val current = requireNotNull(e.takeWork())
        e.acceptLocal(old, JSONObject().put("items", JSONArray()))
        assertTrue(e.isCurrent(current))
        e.acceptLocal(current, JSONObject().put("items", JSONArray()))
        assertNotNull(e.takeWork())
    }
    @Test fun skillRemovedWhileThinkingRevokesThatProposal() {
        var catalogue = JSONObject().put("items", JSONArray().put(JSONObject().put("name", "settings-guide").put("revision", "one").put("source", "bundled")))
        val e = DirectTaskEngine(null, {}, { 2000L }, skillCatalog = { catalogue }, plannedControl = true, feedbackControl = true,
            skillReference = { _,_,_ -> JSONObject().put("items", JSONArray().put(JSONObject().put("name", "settings-guide")
                .put("revision", "one").put("source", "bundled").put("instructions", "设置参考"))) })
        start(e); plan(e)
        val work = requireNotNull(e.takeWork())
        catalogue = JSONObject().put("items", JSONArray())
        e.accept(work, response("action", JSONObject().put("kind", "tap").put("target", "n1")))
        assertEquals("observe", e.poll().getJSONObject("command").getString("kind"))
    }
    @Test fun newestKnowledgePageAndItsCursorAreAlwaysIncluded() {
        val rows = JSONArray()
        repeat(3) { i -> rows.put(JSONObject().put("content", JSONObject().put("name", "guide").put("revision", "v1")
            .put("path", "references/long.md").put("offset", i*4500).put("next_offset", (i+1)*4500)
            .put("content", if (i == 2) "LATEST-PAGE-MARKER" + "z".repeat(4400) else "x".repeat(4500)))) }
        val supplied = FeedbackOperatorContext.loadedKnowledge(JSONObject().put("knowledge", rows))
        val latest = supplied.getJSONObject(0).getJSONObject("content")
        assertTrue(latest.getString("content").startsWith("LATEST-PAGE-MARKER"))
        assertEquals(9000, latest.getInt("offset")); assertEquals(13500, latest.getInt("next_offset"))
        val clippedOlder = supplied.getJSONObject(1).getJSONObject("content")
        assertEquals(clippedOlder.getInt("offset") + clippedOlder.getString("content").length, clippedOlder.getInt("next_offset"))
    }
    @Test fun historyDoesNotArchiveTheFullEnvironmentAndCatalog() {
        val e = engine(); start(e); plan(e)
        repeat(5) {
            e.accept(requireNotNull(e.takeWork()), response("navigate", JSONObject().put("kind", "observe")))
            receipt(e, "page-$it")
        }
        val messages = requireNotNull(e.takeWork()).payload.getJSONArray("messages")
        val historicalUsers = (0 until messages.length()-1).map { messages.getJSONObject(it) }.filter { it.optString("role") == "user" }
        assertTrue(historicalUsers.isNotEmpty())
        assertTrue(historicalUsers.all { it.optString("content").length <= 2200 })
        assertTrue(historicalUsers.none { it.toString().contains("设备应用与能力") })
    }
    @Test fun unavailableNodeReturnsFreshObservationInsteadOfPausing() {
        val e = engine(); val id = start(e); plan(e)
        e.accept(requireNotNull(e.takeWork()), response("action", JSONObject().put("kind", "tap").put("target", "n0_27")))
        assertEquals("running", e.get(id).getString("status"))
        assertEquals("observe", receipt(e, "home").getString("kind"))
        val next = requireNotNull(e.takeWork())
        assertTrue(next.payload.getJSONArray("messages").toString().contains("model_arguments_rejected"))
        e.accept(next, response("action", JSONObject().put("kind", "tap").put("target", "n1")))
        assertEquals("tap", receipt(e, "settings-page").getString("kind"))
    }
    @Test fun invalidArgumentsCannotAdvanceTheStageTheyAccompany() {
        val e = engine(); val id = start(e); plan(e)
        e.accept(requireNotNull(e.takeWork()), response("action", JSONObject().put("kind", "tap").put("target", "n0_27")
            .put("stage_status", "reached").put("observed_result", "设置已经打开")))
        assertEquals(0, e.get(id).getJSONObject("feedback_operator").getInt("active_index"))
        assertEquals("observe", receipt(e, "home").getString("kind"))
    }
    @Test fun missingInputTextCannotCommitReachedStageEvenWithAValidEditableTarget() {
        val e = engine()
        val id = e.create(JSONObject().put("goal", "打开设置并输入备注").put("mode", "full").put("device_id", DirectRuntime.DEVICE_ID)).getString("id")
        receipt(e, "settings-editor", editable = true)
        e.accept(requireNotNull(e.takeWork()), response("plan_task", JSONObject().put("reason", "确认编辑页面后填写备注")
            .put("stages", JSONArray().put(JSONObject().put("objective", "打开设置编辑页").put("exit_condition", "备注输入框可见"))
                .put(JSONObject().put("objective", "填写备注").put("exit_condition", "指定文字已显示")))))
        val before = e.get(id).getJSONObject("feedback_operator").toString()
        val work = requireNotNull(e.takeWork())
        val tools = work.payload.getJSONArray("tools")
        val action = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }.single { it.getString("name") == "action" }
        assertTrue("Typing must be offered so node capability cannot mask the missing-text bug",
            action.getJSONObject("parameters").getJSONObject("properties").getJSONObject("kind").getJSONArray("enum").toString().contains("\"type\""))
        e.accept(work, response("action", JSONObject().put("kind", "type").put("target", "n1")
            .put("stage_status", "reached").put("observed_result", "备注输入框已经显示")))
        val run = e.get(id)
        assertEquals("running", run.getString("status"))
        assertEquals(before, run.getJSONObject("feedback_operator").toString())
        assertEquals(0, run.getJSONObject("feedback_operator").getInt("active_index"))
        assertEquals("invalid_dependent_fields", run.getJSONObject("recovery_feedback").getString("reason"))
        assertEquals(1, run.getInt("operator_argument_rejections"))
        assertEquals("observe", receipt(e, "settings-editor", editable = true).getString("kind"))
    }
    @Test fun repeatedInvalidProposalsHaveABoundAndNeverSendInput() {
        val e = engine(); val id = start(e); plan(e)
        repeat(3) { attempt ->
            e.accept(requireNotNull(e.takeWork()), response("action", JSONObject().put("kind", "tap").put("target", "n0_27")))
            if (attempt < 2) assertEquals("observe", receipt(e, "home").getString("kind"))
        }
        assertEquals("paused", e.get(id).getString("status"))
        assertTrue(e.poll().isNull("command"))
    }
    @Test fun visualActorUsesUniqueSemanticTargetsAndGroundsOpaqueCanvasWithTheLocator() {
        for (semanticTarget in listOf(false, true)) {
        val e = DirectTaskEngine(null, {}, { 2000L }, visualControl = true, preferVisualObservation = true,
            plannedControl = true, feedbackControl = true, guiAvailable = { true })
        e.create(JSONObject().put("goal", "点击当前画面的返回按钮").put("mode", "full").put("device_id", DirectRuntime.DEVICE_ID))
        fun screenReceipt(): JSONObject {
            val command = e.poll().getJSONObject("command")
            val frame = VisualFrame("capture-a", "scene", "example.canvas", 1920, 1080, 960, 540, 1, 1000, 46000, "hash-a")
            e.result(JSONObject().put("run_id", command.getString("run_id")).put("command_id", command.getString("id"))
                .put("status", "ok").put("observation", JSONObject().put("screen_id", "scene").put("package_name", "example.canvas")
                    .put("width", 1920).put("height", 1080).put("nodes", JSONArray().apply {
                        if (semanticTarget) put(JSONObject().put("id", "n1").put("text", "返回").put("role", "button")
                            .put("clickable", true).put("enabled", true).put("bounds", JSONArray(listOf(0,0,150,150))))
                    }))
                .put("data", JSONObject().put("device_profile", JSONObject().put("visual_gestures", true))
                    .put("image_base64", "aW1hZ2U=").put("mime_type", "image/png").put("visual_frame", frame.json())))
            return command
        }
        screenReceipt(); plan(e)
        if (!e.poll().isNull("command")) screenReceipt()
        val work = requireNotNull(e.takeWork())
        assertEquals("enabled", work.payload.getJSONObject("thinking").getString("type"))
        assertTrue(work.payload.getJSONArray("tools").toString().contains("locate_ui"))
        e.accept(work, response("propose_tap", JSONObject().put("x", 37).put("y", 38).put("duration_ms", 80)
            .put("label", "返回").put("screen_context", "当前章节界面").put("safety", "safe")))
        if (semanticTarget) {
            assertEquals("tap", e.poll().getJSONObject("command").getString("kind"))
            assertEquals("n1", e.poll().getJSONObject("command").getString("target"))
            assertNull(e.takeWork())
        } else {
            assertTrue(e.poll().isNull("command"))
            assertEquals("locate_ui", requireNotNull(e.takeWork()).localTool)
        }
        }
    }
    @Test fun successfulGuiHandoffBreaksTheConsecutiveArgumentFailureSequence() {
        val e = DirectTaskEngine(null, {}, { 2000L }, visualControl = true, preferVisualObservation = true,
            plannedControl = true, feedbackControl = true, guiAvailable = { true })
        val id = e.create(JSONObject().put("goal", "点击当前画面的返回按钮").put("mode", "full")
            .put("device_id", DirectRuntime.DEVICE_ID)).getString("id")
        val frame = VisualFrame("capture-a", "scene", "example.canvas", 1920, 1080, 960, 540, 1, 1000, 46000, "a".repeat(64))
        fun readOrActionReceipt() {
            val command = e.poll().getJSONObject("command")
            e.result(JSONObject().put("run_id", id).put("command_id", command.getString("id")).put("status", "ok")
                .put("observation", JSONObject().put("screen_id", "scene").put("package_name", "example.canvas")
                    .put("width", 1920).put("height", 1080).put("nodes", JSONArray()))
                .put("data", JSONObject().put("device_profile", JSONObject().put("visual_gestures", true))
                    .put("image_base64", "aW1hZ2U=").put("mime_type", "image/png").put("visual_frame", frame.json())))
        }
        fun proposal() = JSONObject().put("x", 37).put("y", 38).put("duration_ms", 80)
            .put("label", "返回").put("screen_context", "当前章节界面").put("safety", "safe")
        readOrActionReceipt(); plan(e)
        if (!e.poll().isNull("command")) readOrActionReceipt()
        repeat(2) {
            e.accept(requireNotNull(e.takeWork()), response("propose_tap", proposal().apply { remove("y") }))
            assertEquals("observe", e.poll().getJSONObject("command").getString("kind"))
            readOrActionReceipt()
        }
        assertEquals(2, e.get(id).getInt("operator_argument_rejections"))
        e.accept(requireNotNull(e.takeWork()), response("propose_tap", proposal()))
        val locator = requireNotNull(e.takeWork())
        assertEquals("locate_ui", locator.localTool)
        e.acceptLocal(locator, JSONObject().put("capture_id", frame.captureId).put("image_sha256", frame.sha256)
            .put("width", frame.imageWidth).put("height", frame.imageHeight).put("status", "point").put("x", 37).put("y", 38))
        assertEquals("visual_gesture", e.poll().getJSONObject("command").getString("kind"))
        readOrActionReceipt()
        e.accept(requireNotNull(e.takeWork()), response("propose_tap", proposal().apply { remove("y") }))
        assertEquals("running", e.get(id).getString("status"))
        assertEquals(1, e.get(id).getInt("operator_argument_rejections"))
        assertEquals("observe", e.poll().getJSONObject("command").getString("kind"))
    }
}

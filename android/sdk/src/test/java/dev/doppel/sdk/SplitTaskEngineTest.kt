package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SplitTaskEngineTest {
    @Test fun dragHoldSurvivesBothExecutionModesAndCurrentSequenceStroke() {
        for (enhanced in listOf(false, true)) for (sequence in listOf(false, true)) {
            val e = SplitTaskEngine(null, {}, { 1000L }, enhancementEnabled = { enhanced })
            start(e)
            val contract = JSONObject("""{"gesture_semantics":"object_drag","target_relative_direction":"right","intended_finger_direction":"right","start_hold_ms":850}""")
            val stroke = JSONObject("""{"points":[[100,500],[700,500]],"duration_ms":700}""")
            val action = if (sequence) "swipe_sequence" else "swipe"
            val decision = JSONObject().put("kind", "execute").put("action", action).put("target", "拖动卡片到右侧槽位").put("expected", "卡片落入槽位")
            if (sequence) decision.put("gesture_contracts", JSONArray().put(contract).put(JSONObject(contract.toString()).put("start_hold_ms", 950)))
            else contract.keys().forEach { decision.put(it, contract.get(it)) }
            if (!enhanced) {
                if (sequence) decision.put("strokes", JSONArray().put(stroke).put(JSONObject(stroke.toString())))
                else stroke.keys().forEach { decision.put(it, stroke.get(it)) }
            }
            e.accept(e.takeWork()!!, SplitTestReply.response(decision))
            if (enhanced) {
                screen(e)
                val work = e.takeWork()!!
                val instruction = JSONObject(work.payload.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text"))
                assertEquals(850, (if (sequence) instruction.getJSONArray("gesture_contracts").getJSONObject(0) else instruction).getInt("start_hold_ms"))
                val direction = JSONObject("""{"target_relative_direction":"right","required_finger_direction":"right","direction_corrected":false}""")
                val assessment = JSONObject().put("alignment", "consistent")
                val located = JSONObject().put("status", "located").put("action", action).put("assessment", assessment)
                if (sequence) {
                    located.put("strokes", JSONArray().put(stroke).put(JSONObject(stroke.toString())))
                    assessment.put("gesture_contracts", JSONArray().put(direction).put(JSONObject(direction.toString())))
                } else {
                    stroke.keys().forEach { located.put(it, stroke.get(it)) }
                    direction.keys().forEach { assessment.put(it, direction.get(it)) }
                }
                e.accept(work, SplitTestReply.response(located))
            }
            val command = e.poll().getJSONObject("command")
            assertEquals("split_action", command.getString("kind"))
            val planned = GestureSequencePlan.from(command.getJSONObject("action"), 1440, 3200).strokes.single()
            assertEquals(850L, planned.startHoldMs)
            assertEquals(700L, planned.durationMs)
            assertEquals(1550L, planned.totalMs)
            if (sequence) assertEquals(0, command.getInt("stroke_index"))
        }
    }

    @Test fun routedTaskKeepsOriginalRequestAndContextAcrossPlanningAndRecovery() {
        val original = "按刚才的计划查看美团定位页面。Do not place orders or change system permissions."
        val interpreted = "打开美团，点击首页左上角位置入口并报告地址页面。"
        val body = ConversationIntent.taskRequest(original, "direct-this-phone", "assist", "定位检查", interpreted)
        val e = engine()
        val id = e.create(body).getString("id")
        fun verify(current: SplitTaskEngine) {
            val run = current.get(id)
            assertEquals(original, run.getString("goal"))
            assertEquals(original, run.getJSONObject("task_state").getString("goal"))
            assertEquals(original, run.getJSONArray("conversation_messages").getJSONObject(0).getString("text"))
            assertEquals(interpreted, run.getString("task_context"))
            screen(current)
            val messages = current.takeWork()!!.payload.getJSONArray("messages")
            val planner = JSONObject(messages.getJSONObject(messages.length() - 1).getJSONArray("content").getJSONObject(0).getString("text"))
            assertEquals(original, planner.getString("task"))
            assertEquals(interpreted, planner.getString("task_context"))
        }
        verify(e)
        val restored = SplitTaskEngine(saved, { saved = it }, { 1000L })
        restored.control(id, "resume", JSONObject())
        verify(restored)
        assertEquals(original, restored.conversation(id).getJSONArray("items").getJSONObject(0).getString("goal"))
    }

    @Test fun invalidTaskContextCannotCreateARun() {
        val e = engine()
        for (invalid in listOf(JSONObject(), 123, JSONObject.NULL, "x".repeat(8001))) {
            assertTrue(runCatching { e.create(JSONObject().put("goal", "查看设置").put("device_id", "direct-this-phone")
                .put("task_context", invalid)) }.isFailure)
            assertFalse(e.hasUnfinished())
        }
    }

    @Test fun observedPackageSurvivesTaskCompletionForLaterChatMemory() {
        val e = engine()
        val id = start(e)
        e.accept(e.takeWork()!!, reply("""{"kind":"finish","message":"已查看","success":true}"""))
        assertEquals("secret.package", SplitTaskEngine(saved, {}).internalRun(id).getString("last_observed_package"))
    }
    @Test fun suppliedTitleIsValidatedBeforeCreationAndPersisted() {
        val e = engine()
        val body = JSONObject().put("goal", "打开设置").put("device_id", "direct-this-phone")
        for (invalid in listOf(JSONObject(), 123, "x".repeat(121))) {
            try { e.create(body.put("title", invalid)); fail("Invalid title accepted") }
            catch (_: IllegalArgumentException) { }
            assertFalse(e.hasUnfinished())
        }
        for (value in listOf("  设置\n 检查  ", JSONObject.NULL, "   ")) {
            val run = e.create(body.put("title", value))
            val expected = if (value == "  设置\n 检查  ") "设置 检查" else ConversationTitle.fromGoal("打开设置")
            assertEquals(expected, SplitTaskEngine(saved, {}).get(run.getString("id")).getString("title"))
            e.control(run.getString("id"), "cancel", JSONObject())
        }
    }
    @Test fun decliningApprovalPausesWithoutNewWorkAndConsumesOnlyThatRequest() {
        val e = engine()
        val id = e.create(JSONObject().put("goal", "打开设置").put("device_id", "direct-this-phone").put("mode", "ask")).getString("id")
        screen(e); propose(e)
        val request = e.get(id).getJSONObject("pending_request").getString("id")
        val answer = JSONObject().put("request_id", request).put("approve", false)
        assertEquals("paused", e.control(id, "answer", answer).getString("status"))
        assertFalse(e.get(id).has("pending_request"))
        assertTrue(e.poll().isNull("command")); assertNull(e.takeWork())
        assertEquals("paused", SplitTaskEngine(saved, {}).statusOrNull(id))
        try { e.control(id, "answer", answer.put("approve", true)); fail("Consumed approval must not be reused") }
        catch (_: IllegalStateException) { }
        e.control(id, "resume", JSONObject())
        assertEquals("screenshot", e.poll().getJSONObject("command").getString("kind"))
        screen(e); propose(e)
        assertEquals("awaiting_approval", e.get(id).getString("status"))
        assertNotEquals(request, e.get(id).getJSONObject("pending_request").getString("id"))
    }
    @Test fun companionReadDoesNotDeliverCommandsOrLeakInternalState() {
        var clock = 1000L
        var writes = 0
        val e = SplitTaskEngine(null, { writes++ }, { clock })
        assertNull(e.companionState())
        val id = e.create(JSONObject().put("goal", "查看设置").put("device_id", "direct-this-phone"))
            .getString("id")
        val before = writes
        val state = e.companionState()!!
        assertEquals("observing", state.getString("phase"))
        assertEquals(setOf("id", "title", "goal", "status", "mode", "source", "message", "phase",
            "current_step", "progress", "pending_request"), state.keys().asSequence().toSet())
        state.getJSONObject("progress").getJSONArray("plan").put("caller mutation")
        clock += 60001
        assertEquals("running", e.companionState()!!.getString("status"))
        assertEquals(0, e.companionState()!!.getJSONObject("progress").getJSONArray("plan").length())
        assertEquals(before, writes)
        // Reading the snapshot did not start the command receipt timeout or take model work.
        assertEquals("screenshot", e.poll().getJSONObject("command").getString("kind"))
        screen(e)
        assertNotNull(e.takeWork())
        assertEquals("thinking", e.companionState()!!.getString("phase"))
        e.control(id, "cancel", JSONObject())
        assertNull(e.companionState())
    }
    private var saved = ""
    private fun engine() = SplitTaskEngine(null, { saved = it }, { 1000L })
    private fun start(e: SplitTaskEngine): String {
        val id = e.create(JSONObject().put("goal", "打开设置查看手机型号").put("device_id", "direct-this-phone").put("mode", "full")).getString("id")
        screen(e); return id
    }
    private fun screen(e: SplitTaskEngine) {
        val c = e.poll().getJSONObject("command")
        e.result(JSONObject().put("command_id", c.getString("id")).put("run_id", c.getString("run_id")).put("status", "ok")
            .put("observation", JSONObject().put("package_name", "secret.package").put("screen_id", "secret-id"))
            .put("data", JSONObject().put("image_base64", "cGl4ZWxz").put("mime_type", "image/png")
                .put("visual_frame", JSONObject().put("display_width", 1440).put("display_height", 3200).put("rotation", 0))))
    }
    private fun reply(value: String): JSONObject {
        val body=JSONObject(value)
        if(body.optString("status")=="located") body.put("assessment",JSONObject().put("alignment","consistent")
            .put("observed","当前页面可见所请求控件").put("reason","目标与预期页面变化一致").apply {
                if(body.optString("action")=="swipe_sequence") put("gesture_contracts",JSONArray()
                    .put(JSONObject().put("target_relative_direction","down").put("required_finger_direction","up"))
                    .put(JSONObject().put("target_relative_direction","down").put("required_finger_direction","up")))
            })
        return SplitTestReply.response(body)
    }

    @Test fun publicListOmitsPrivateHistoryAndReturnsIndependentNestedData() {
        val e = engine()
        val id = e.create(JSONObject().put("goal", "查看设置").put("device_id", "direct-this-phone").put("mode", "full")).getString("id")
        val original = e.get(id).toString()
        val listed = e.list().getJSONArray("items").getJSONObject(0)
        for (field in listOf("events", "knowledge", "recent_steps", "approved_intent", "interrupted_commands")) assertFalse(listed.has(field))
        listed.getJSONObject("task_state").put("phase", "caller mutation")
        listed.getJSONArray("conversation_messages").getJSONObject(0).put("text", "caller mutation")
        assertEquals("Polling clients cannot alter the planner state or conversation", original, e.get(id).toString())
        assertFalse(e.list().toString().contains("caller mutation"))
    }
    @Test fun statusLookupIsReadOnlyAcrossRunningCancelledAndDeletedRuns() {
        var clock = 1000L; var writes = 0
        val e = SplitTaskEngine(null, { writes++ }, { clock })
        assertNull(e.statusOrNull("missing-run")); assertEquals(0, writes)
        val id = e.create(JSONObject().put("goal", "查看设置")
            .put("device_id", "direct-this-phone").put("mode", "full")).getString("id")
        val createdWrites = writes
        assertEquals("running", e.statusOrNull(id)); assertNull(e.statusOrNull("missing-run"))
        assertEquals(createdWrites, writes)
        // A status lookup must not deliver the command or start its receipt timeout.
        clock += 60001
        val command = e.poll().getJSONObject("command")
        assertEquals("screenshot", command.getString("kind"))
        assertEquals("running", e.statusOrNull(id))
        assertEquals(command.getString("id"), e.poll().getJSONObject("command").getString("id"))
        assertEquals(createdWrites, writes)
        screen(e)
        val observedWrites = writes
        assertEquals("running", e.statusOrNull(id)); assertEquals(observedWrites, writes)
        assertNotNull(e.takeWork())
        e.control(id, "cancel", JSONObject()); val cancelledWrites = writes
        assertEquals("cancelled", e.statusOrNull(id)); assertEquals(cancelledWrites, writes)
        e.delete(id); val deletedWrites = writes
        assertNull(e.statusOrNull(id)); assertEquals(deletedWrites, writes)
    }

    @Test fun automaticRunKeepsTaskRecordOutsideConversation() {
        val e = SplitTaskEngine(null, { saved = it }, { 1000L })
        val run = e.create(JSONObject().put("goal", "控件出现时执行任务")
            .put("device_id", "direct-this-phone").put("mode", "assist")
            .put("conversation_enabled", false).put("source", "trigger"))
        assertFalse(run.getBoolean("conversation_enabled"))
        assertTrue(run.isNull("conversation_id"))
        assertEquals(0, run.optJSONArray("conversation_messages")?.length() ?: 0)
        assertEquals(0, e.conversation(run.getString("id")).getJSONArray("items").length())
    }
    @Test fun webReferencesReachNextPlannerButLateCancelledResultsAreIgnored() {
        val e = engine(); val id = start(e)
        e.accept(e.takeWork()!!, reply("""{"kind":"search_web","query":"Android 文档"}"""))
        val search = e.takeWork()!!
        assertEquals("search_web", search.localTool)
        val reference = JSONObject().put("ok", true).put("untrusted", true).put("content_role", "reference_only")
            .put("provider", "bing").put("results", JSONArray().put(JSONObject()
                .put("title", "Android 文档").put("url", "https://developer.android.com/guide")
                .put("source", "developer.android.com").put("snippet", "公开技术资料")))
        e.acceptLocal(search, reference)
        val next = e.takeWork()!!
        assertTrue(next.payload.toString().contains("https://developer.android.com/guide"))
        assertTrue(SplitTaskEngine.readPersistedRuns(saved).getJSONObject(0).getJSONArray("knowledge").getJSONObject(0).getBoolean("reference_only"))
        e.accept(next, reply("""{"kind":"read_web","url":"https://developer.android.com/guide"}"""))
        val read = e.takeWork()!!
        assertEquals("read_web", read.localTool)
        e.control(id, "pause", JSONObject())
        val before = saved
        e.acceptLocal(read, JSONObject().put("text", "迟到结果"))
        assertEquals(before, saved)
        assertEquals(1, SplitTaskEngine.readPersistedRuns(saved).getJSONObject(0).getJSONArray("knowledge").length())
        assertNull(e.takeWork())
    }

    private fun propose(e: SplitTaskEngine) { e.accept(e.takeWork()!!, reply("""{"kind":"execute","action":"tap","target":"右上角齿轮设置按钮","expected":"设置页"}""")); if(e.poll().optJSONObject("command")?.optString("kind")=="screenshot") screen(e) }
    private fun sequence(e: SplitTaskEngine): JSONObject {
        e.accept(e.takeWork()!!,reply("""{"kind":"execute","action":"swipe_sequence","target":"连续向上滑动两次，露出下方内容","expected":"列表露出后续内容","gesture_contracts":[{"gesture_semantics":"reveal_content","target_relative_direction":"down","intended_finger_direction":"up"},{"gesture_semantics":"reveal_content","target_relative_direction":"down","intended_finger_direction":"up"}]}"""));screen(e)
        e.accept(e.takeWork()!!,reply("""{"status":"located","action":"swipe_sequence","strokes":[{"points":[[500,600],[500,500]],"duration_ms":300},{"points":[[500,600],[500,500]],"duration_ms":300}]}"""))
        return e.poll().getJSONObject("command")
    }
    private fun partial(c: JSONObject)=JSONObject().put("run_id",c.getString("run_id")).put("command_id",c.getString("id"))
        .put("status","cancelled").put("data",JSONObject().put("completed_strokes",0).put("unconfirmed_strokes",1).put("action_state","partial"))
    private fun missingScreen(e: SplitTaskEngine) {
        val c=e.poll().getJSONObject("command");assertEquals("screenshot",c.getString("kind"))
        e.result(JSONObject().put("run_id",c.getString("run_id")).put("command_id",c.getString("id"))
            .put("status","error").put("message","root_unavailable").put("data",JSONObject().put("reason_code","root_unavailable")))
    }
    @Test fun missingScreenshotRetriesAcquisitionWithoutAskingAForBlindWait() {
        var clock=1000L
        val e=SplitTaskEngine(null,{saved=it},{clock});val id=start(e)
        e.accept(e.takeWork()!!,reply("""{"kind":"wait","duration_ms":500,"reason":"等待页面"}"""))
        screen(e);missingScreen(e)
        assertEquals("running",e.get(id).getString("status"));assertTrue(e.poll().isNull("command"))
        assertFalse(e.readyForWork());assertNull(e.takeWork())
        assertEquals(1,e.get(id).getInt("calls"))
        clock+=250;screen(e)
        assertEquals("primary",e.takeWork()!!.payload.getString("_doppel_role"))
    }
    @Test fun missingGroundingScreenshotCannotUseHistoryAndFreshRetryRestoresB() {
        var clock=1000L
        val e=SplitTaskEngine(null,{saved=it},{clock});val id=start(e)
        e.accept(e.takeWork()!!,reply("""{"kind":"execute","action":"tap","target":"设置入口","expected":"进入设置"}"""))
        missingScreen(e)
        assertTrue(e.poll().isNull("command"));assertNull(e.takeWork())
        clock+=250;screen(e)
        assertTrue(e.takeWork()!!.grounding)
        e.control(id,"pause",JSONObject());assertFalse(e.readyForWork())
    }
    @Test fun screenshotHumanTakeoverStillPausesWithoutModelWork() {
        var clock=1000L
        val e=SplitTaskEngine(null,{saved=it},{clock});val id=start(e)
        e.accept(e.takeWork()!!,reply("""{"kind":"wait","duration_ms":500}"""));screen(e);missingScreen(e)
        clock+=250
        val c=e.poll().getJSONObject("command")
        e.result(JSONObject().put("run_id",id).put("command_id",c.getString("id")).put("status","blocked")
            .put("data",JSONObject().put("human_takeover","login")))
        assertEquals("paused",e.get(id).getString("status"));assertFalse(e.readyForWork())
    }
    @Test fun pausedLatePartialReceiptPersistsWithoutResumingOrReplay() {
        val e=engine();val id=start(e);val c=sequence(e);e.control(id,"pause",JSONObject())
        assertTrue(e.result(partial(c)).getBoolean("accepted"))
        assertEquals("paused",e.get(id).getString("status"));assertTrue(e.poll().isNull("command"))
        val receipt=e.get(id).getJSONObject("last_receipt")
        assertEquals(0,receipt.getInt("completed_strokes"));assertEquals(1,receipt.getInt("unconfirmed_strokes"))
        assertEquals(1,receipt.getJSONObject("executed_action").getJSONArray("strokes").length())
        assertFalse(e.result(partial(c)).getBoolean("accepted"))
        val restored=SplitTaskEngine(saved,{},{1000L});restored.control(id,"resume",JSONObject());screen(restored)
        assertTrue(restored.takeWork()!!.payload.toString().contains("unconfirmed_strokes"))
    }
    @Test fun cancelledLateReceiptCannotChangeNewTaskOrCommand() {
        val e=engine();val old=start(e);val c=sequence(e);e.control(old,"cancel",JSONObject())
        val newer=start(e);propose(e);val b=e.takeWork()!!
        assertTrue(e.result(partial(c)).getBoolean("accepted"))
        assertEquals("cancelled",e.get(old).getString("status"));assertEquals("running",e.get(newer).getString("status"))
        assertFalse(e.get(newer).has("last_receipt"));assertTrue(e.isCurrent(b))
    }
    @Test fun restoredInterruptedTicketAcceptsReceiptWithoutReplacingNewerReceiptOrWork() {
        val first=engine();val id=start(first);val old=sequence(first);first.control(id,"pause",JSONObject())
        val e=SplitTaskEngine(saved,{saved=it},{1000L});e.control(id,"resume",JSONObject());screen(e)
        val newer=sequence(e)
        e.result(JSONObject().put("run_id",id).put("command_id",newer.getString("id")).put("status","error")
            .put("data",JSONObject().put("completed_strokes",0).put("unconfirmed_strokes",1)))
        val capture=e.poll().getJSONObject("command")
        assertTrue(e.result(partial(old)).getBoolean("accepted"))
        assertEquals(newer.getString("id"),e.get(id).getJSONObject("last_receipt").getString("command_id"))
        assertEquals(capture.getString("id"),e.poll().getJSONObject("command").getString("id"))
        assertEquals("running",e.get(id).getString("status"));screen(e)
        val a=e.takeWork()!!
        assertTrue(a.payload.toString().contains("unconfirmed_strokes"))
        assertFalse(e.result(partial(old)).getBoolean("accepted"));assertTrue(e.isCurrent(a))
    }
    @Test fun completedFinishClearsRemainingButFailedFinishKeepsIt() {
        for(status in listOf("completed","failed")) {
            val e=engine();val id=start(e)
            e.accept(e.takeWork()!!,reply("""{"kind":"wait","duration_ms":500,"state":{"facts":["已看到结果"],"completed_steps":["打开页面"],"remaining_steps":["读取结果"],"phase":"读取"}}"""))
            screen(e);screen(e)
            e.accept(e.takeWork()!!,reply("""{"kind":"finish","status":"$status","message":"本轮结束"}"""))
            val state=e.get(id).getJSONObject("task_state")
            assertEquals(if(status=="completed") 0 else 1,state.getJSONArray("remaining_steps").length())
            assertEquals(if(status=="completed") "completed" else "读取",state.getString("phase"))
            assertEquals("已看到结果",state.getJSONArray("facts").getString(0))
        }
    }
    @Test fun plannerThenStatelessGrounderThenDevice() {
        val e=engine();start(e);propose(e);val b=e.takeWork()!!
        assertEquals("grounding",b.payload.getString("_doppel_role"))
        val messages=b.payload.getJSONArray("messages"); assertEquals(2,messages.length())
        assertFalse(messages.toString().contains("secret.package"));assertFalse(messages.toString().contains("secret-id"))
        assertFalse(messages.toString().contains("打开设置查看手机型号"))
        e.accept(b,reply("""{"status":"located","action":"tap","points":[[500,600]]}"""))
        assertEquals("split_action",e.poll().getJSONObject("command").getString("kind"))
    }
    @Test fun waitBypassesBAndReturnsFreshScreenshotToA() {
        val e=engine();start(e);e.accept(e.takeWork()!!,reply("""{"kind":"wait","duration_ms":1500,"reason":"还在加载"}"""))
        val c=e.poll().getJSONObject("command");assertEquals("wait",c.getString("kind"));assertEquals(1500,c.getInt("duration_ms"));assertNull(e.takeWork())
        e.result(JSONObject().put("run_id",c.getString("run_id")).put("command_id",c.getString("id")).put("status","ok"))
        assertEquals("screenshot",e.poll().getJSONObject("command").getString("kind"));screen(e)
        assertEquals("primary",e.takeWork()!!.payload.getString("_doppel_role"))
    }
    @Test fun grounderRefusalGoesBackToPlannerWithoutPausing() {
        val e=engine();val id=start(e);propose(e)
        e.accept(e.takeWork()!!,reply("""{"status":"ambiguous","reason":"两个相同图标，需要说明靠左或靠右"}"""))
        assertEquals("running",e.get(id).getString("status"));assertTrue(e.poll().isNull("command"))
        assertTrue(e.takeWork()!!.payload.toString().contains("两个相同图标"))
    }
    @Test fun pauseDiscardsLateGroundingResponse() {
        val e=engine();val id=start(e);propose(e);val b=e.takeWork()!!;e.control(id,"pause",JSONObject())
        e.accept(b,reply("""{"status":"located","action":"tap","points":[[500,600]]}"""))
        assertTrue(e.poll().isNull("command"));assertEquals("paused",e.get(id).getString("status"))
    }
    @Test fun taskStateSurvivesProcessRestartAndResumeObservesAgain() {
        val e=engine();val id=start(e)
        e.accept(e.takeWork()!!,reply("""{"kind":"wait","duration_ms":500,"state":{"facts":["手机型号待查"],"completed_steps":["已进入桌面"]}}"""))
        val restored=SplitTaskEngine(saved,{},{1000L});assertEquals("paused",restored.get(id).getString("status"))
        assertTrue(restored.get(id).getJSONObject("task_state").toString().contains("已进入桌面"))
        restored.control(id,"resume",JSONObject());assertEquals("screenshot",restored.poll().getJSONObject("command").getString("kind"))
    }
    @Test fun refusesCoordinateForDifferentActionAndAllowsSamePointRetry() {
        val e=engine();val id=start(e);propose(e)
        e.accept(e.takeWork()!!,reply("""{"status":"located","action":"long_press","points":[[500,600]]}"""))
        assertTrue(e.poll().isNull("command"));assertEquals("running",e.get(id).getString("status"))
        propose(e);e.accept(e.takeWork()!!,reply("""{"status":"located","action":"tap","points":[[500,600]]}"""))
        screen(e);screen(e);propose(e);e.accept(e.takeWork()!!,reply("""{"status":"located","action":"tap","points":[[500,600]]}"""))
        assertEquals("split_action",e.poll().getJSONObject("command").getString("kind"))
    }
}

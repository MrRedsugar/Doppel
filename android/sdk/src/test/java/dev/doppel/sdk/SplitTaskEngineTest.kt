package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SplitTaskEngineTest {
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

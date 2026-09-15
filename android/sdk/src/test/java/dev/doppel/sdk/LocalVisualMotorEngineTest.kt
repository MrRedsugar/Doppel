package dev.doppel.sdk

import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalVisualMotorEngineTest {
    private var variedCaptureEncoding = false
    private fun pixels() = IntArray(160 * 120) { index ->
        val x = index % 160; val y = index / 160
        if (x in 44 until 76 && y in 40 until 72) {
            val v = (x * 83 + y * 47 + x * y * 7) and 255
            0xff000000.toInt() or (v shl 16) or ((v xor 137) shl 8) or (v xor 51)
        } else 0xff202428.toInt()
    }
    private fun capture(id: String = "source", at: Long = 1000, displayWidth: Int = 320): JSONObject {
        val png = requireNotNull(javaClass.getResourceAsStream("/motor-icon.png")).use { it.readBytes() }
        // Distinct capture encodings keep the injected anchor pixels stable, as with an animated canvas.
        val bytes = if (variedCaptureEncoding) png + id.toByteArray() else png
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return JSONObject().put("image_base64", Base64.getEncoder().encodeToString(bytes)).put("mime_type", "image/png")
            .put("visual_frame", VisualFrame(id, "screen-$id", "example.app", displayWidth, 240, 160, 120, 1, at, at + 45000, sha).json())
    }
    private fun proposal(): JSONObject = JSONObject().put("capture_id", "source").put("coordinate_space", "image_pixels")
        .put("anchors", JSONArray().put(JSONObject().put("id", "glyph").put("bounds", JSONArray(listOf(44, 40, 76, 72))).put("x", 60).put("y", 56)))
        .put("steps", JSONArray().put(JSONObject().put("kind", "tap").put("start_anchor_id", "glyph").put("duration_ms", 80)
            .put("label", "打开设置").put("screen_context", "当前应用工具栏").put("safety", "safe")))
    private val decoder: (ByteArray) -> VisualAnchorImage? = { VisualAnchorImage(160,120,pixels()) }

    private var clock=1001L
    private fun engine(persisted: String? = null, save: (String) -> Unit = {})=DirectTaskEngine(persisted,save, {2000L},visualControl=true,preferVisualObservation=true,plannedControl=true,
        elapsedNow={clock},prepareMotor={spec,data,at->LocalVisualMotor.prepare(spec,data,at,decoder)})
    private fun result(e:DirectTaskEngine,c:JSONObject,id:String,at:Long, screenId: String = "screen-$id") {
        clock=at+1;val data=capture(id,at).put("device_profile",JSONObject().put("visual_gestures",true))
            .put("action_completed_at_elapsed_ms",at-1).put("action_state","accepted")
        e.result(JSONObject().put("run_id",c.getString("run_id")).put("command_id",c.getString("id")).put("status","ok").put("data",data)
            .put("observation",JSONObject().put("screen_id",screenId).put("package_name","example.app")
                .put("width",320).put("height",240).put("nodes",JSONArray())))
    }
    private fun begin(e:DirectTaskEngine, mode: String = "full", stepCount: Int = 2):DirectTaskEngine.Work {
        e.create(JSONObject().put("device_id",DirectRuntime.DEVICE_ID).put("mode",mode).put("goal","执行两次独立点击后核对"))
        result(e,e.poll().getJSONObject("command"),"source",1000)
        val w=e.takeWork()!!;val p=proposal()
        repeat(stepCount - 1) { p.getJSONArray("steps").put(JSONObject(p.getJSONArray("steps").getJSONObject(0).toString())) }
        val reply=JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","tool_calls").put("message",JSONObject()
            .put("tool_calls",JSONArray().put(JSONObject().put("function",JSONObject().put("name","execute_visual_plan").put("arguments",p.toString())))))))
        e.accept(w,reply);return w
    }
    @Test fun twoFreshVisualActionsShareOnePlannerCallAndRequireFinalVerification() {
        val e=engine();val w=begin(e)
        val read=e.poll().getJSONObject("command");assertEquals("observe",read.getString("kind"));result(e,read,"fresh",1003)
        assertNull(e.takeWork());val c1=e.poll().getJSONObject("command");assertEquals("visual_gesture",c1.getString("kind"))
        result(e,c1,"after1",1007);assertNull(e.takeWork());val c2=e.poll().getJSONObject("command");assertEquals("visual_gesture",c2.getString("kind"))
        assertEquals(1,e.get(w.runId).getInt("calls"));result(e,c2,"after2",1011)
        val verify=e.takeWork()!!;assertTrue(verify.visualAgent);assertEquals(2,e.get(w.runId).getInt("calls"))
        assertEquals(2,e.get(w.runId).getJSONObject("perception_metrics").getInt("local_visual_actions"))
        assertEquals("verify",e.get(w.runId).getJSONObject("local_visual_plan").getString("state"))
        assertEquals(2,e.get(w.runId).getJSONObject("local_visual_plan").getInt("steps"))
        assertEquals(2,e.get(w.runId).getJSONObject("local_visual_plan").getInt("accepted_steps"))
    }
    @Test fun pauseRevokesPreparedSegmentBeforeAnyGesture() {
        val e=engine();val w=begin(e);val read=e.poll().getJSONObject("command")
        e.control(w.runId,"pause",JSONObject());result(e,read,"fresh",1003)
        assertNull(e.takeWork());assertTrue(e.poll().isNull("command"))
        assertStoppedPlan(e,w,"paused",0)
    }
    @Test fun privateObservationCancelsSegmentWithoutRepeatedScreenshots() {
        val e=engine();val w=begin(e);val read=e.poll().getJSONObject("command")
        e.result(JSONObject().put("run_id",read.getString("run_id")).put("command_id",read.getString("id")).put("status","ok")
            .put("data",JSONObject().put("screenshot_omitted_reason",ScreenCapturePrivacy.LOGIN_SENSITIVE))
            .put("observation",ScreenCapturePrivacy.attach(JSONObject().put("screen_id","private").put("package_name","example.app").put("width",320).put("height",240)
                .put("nodes",JSONArray()).put("captured_at",2000L),true)))
        val work=e.takeWork()
        assertTrue(e.poll().isNull("command"))
        assertTrue(work==null || !work.visualAgent)
        assertStoppedPlan(e,w,"stopped",0)
    }

    private fun assertStoppedPlan(e: DirectTaskEngine, w: DirectTaskEngine.Work, state: String, accepted: Int) {
        val plan=e.get(w.runId).getJSONObject("local_visual_plan")
        assertEquals(state,plan.getString("state"));assertEquals(2,plan.getInt("steps"))
        assertEquals(accepted,plan.optInt("accepted_steps"));assertTrue(plan.getString("reason").isNotBlank())
    }
    private fun nextGesture(e: DirectTaskEngine): JSONObject {
        result(e,e.poll().getJSONObject("command"),"fresh",1003)
        assertNull(e.takeWork());return e.poll().getJSONObject("command").also { assertEquals("visual_gesture",it.getString("kind")) }
    }
    private fun tool(name: String, args: JSONObject) = JSONObject().put("choices",JSONArray().put(JSONObject()
        .put("finish_reason","tool_calls").put("message",JSONObject().put("tool_calls",JSONArray().put(JSONObject()
            .put("function",JSONObject().put("name",name).put("arguments",args.toString())))))))

    @Test fun pauseAndResumePreserveAcceptedStepsWithoutRevivingOldMotor() {
        val e=engine();val w=begin(e);result(e,nextGesture(e),"after1",1007)
        e.control(w.runId,"pause",JSONObject());assertStoppedPlan(e,w,"paused",1)
        e.control(w.runId,"resume",JSONObject());assertStoppedPlan(e,w,"paused",1)
        result(e,e.poll().getJSONObject("command"),"resumed",1011)
        assertNotNull(e.takeWork());assertTrue(e.poll().isNull("command"))
    }
    @Test fun cancellationPreservesAcceptedSteps() {
        val e=engine();val w=begin(e);result(e,nextGesture(e),"after1",1007)
        e.control(w.runId,"cancel",JSONObject());assertStoppedPlan(e,w,"cancelled",1)
        assertNull(e.takeWork());assertTrue(e.poll().isNull("command"))
    }
    @Test fun staleGestureEndsSegmentAndQueuesOnlyFreshObservation() {
        val e=engine();val w=begin(e);val gesture=nextGesture(e)
        e.result(JSONObject().put("run_id",w.runId).put("command_id",gesture.getString("id")).put("status","stale")
            .put("data",JSONObject().put("action_state","not_dispatched").put("reason_code","gesture_context_changed")))
        assertStoppedPlan(e,w,"replan",0)
        assertEquals("observe",e.poll().getJSONObject("command").getString("kind"))
    }
    @Test fun uncertainGestureEndsSegmentAndPausesWithoutReplay() {
        val e=engine();val w=begin(e);val gesture=nextGesture(e)
        e.result(JSONObject().put("run_id",w.runId).put("command_id",gesture.getString("id")).put("status","error")
            .put("data",JSONObject().put("action_state","unconfirmed")))
        assertStoppedPlan(e,w,"paused",0);assertEquals("paused",e.get(w.runId).getString("status"))
        assertTrue(e.poll().isNull("command"))
    }
    @Test fun approvalStopsRemainingVisualSegmentBeforeAnyGesture() {
        val e=engine();val w=begin(e,"ask");result(e,e.poll().getJSONObject("command"),"fresh",1003)
        assertNull(e.takeWork());assertEquals("awaiting_approval",e.get(w.runId).getString("status"))
        assertStoppedPlan(e,w,"paused",0);assertTrue(e.poll().isNull("command"))
    }
    @Test fun switchingToSemanticPlanRecordsWhyVisualSegmentStopped() {
        val e=engine();val w=begin(e);result(e,e.poll().getJSONObject("command"),"fresh",1003)
        // Exercise the tool switch with an invalid replacement contract: the old segment must still end.
        e.accept(w,tool("execute_plan",JSONObject()))
        assertStoppedPlan(e,w,"stopped",0)
        assertEquals("observe",e.poll().getJSONObject("command").getString("kind"))
    }
    @Test fun dispatchMismatchEndsSegmentWithoutQueuingGesture() {
        val e=engine();val w=begin(e)
        result(e,e.poll().getJSONObject("command"),"fresh",1003,"different-screen")
        e.takeWork();assertStoppedPlan(e,w,"replan",0)
        assertTrue(e.poll().isNull("command"))
        assertEquals("local_visual_rejected",e.get(w.runId).getJSONObject("recovery_feedback").getString("code"))
    }
    @Test fun successfulReplacementKeepsPreviousProgressAndStartsFreshCount() {
        val e=engine();val w=begin(e);result(e,nextGesture(e),"after1",1007)
        e.accept(w,tool("execute_visual_plan",proposal().put("capture_id","after1")))
        val run=e.get(w.runId);val previous=run.getJSONArray("local_visual_plan_history").getJSONObject(0)
        assertEquals("stopped",previous.getString("state"));assertTrue(previous.getString("reason").isNotBlank())
        assertEquals(2,previous.getInt("steps"));assertEquals(1,previous.getInt("accepted_steps"))
        val current=run.getJSONObject("local_visual_plan")
        assertEquals("prepared",current.getString("state"));assertEquals(1,current.getInt("steps"));assertEquals(0,current.getInt("accepted_steps"))
    }
    @Test fun rejectedReplacementCannotLeavePreviousSegmentRunning() {
        val e=engine();val w=begin(e);result(e,nextGesture(e),"after1",1007)
        e.accept(w,tool("execute_visual_plan",proposal().put("capture_id","wrong-capture")))
        assertStoppedPlan(e,w,"stopped",1)
        result(e,e.poll().getJSONObject("command"),"reobserve",1011)
        assertNotNull(e.takeWork());assertTrue(e.poll().isNull("command"))
    }
    @Test fun queuePersistenceFailureCannotDeliverAnUnboundVisualGesture() {
        var failNextSave=false; var failed=false
        val e=engine { if(failNextSave) { failNextSave=false;failed=true;error("synthetic disk failure") } }
        val w=begin(e);result(e,e.poll().getJSONObject("command"),"fresh",1003)
        failNextSave=true;e.takeWork();assertTrue(failed)
        assertStoppedPlan(e,w,"replan",0)
        assertTrue("Queue failure must remove the still-undelivered gesture",e.poll().isNull("command"))
    }
    @Test fun processRestoreEndsPersistedVisualSegmentAndPreservesProgress() {
        var saved="";val e=engine { saved=it };val w=begin(e)
        result(e,nextGesture(e),"after1",1007)
        val restored=engine(saved)
        assertStoppedPlan(restored,w,"paused",1)
        assertNull(restored.takeWork());assertTrue(restored.poll().isNull("command"))
    }
    @Test fun twoUnknownVisualEffectsStopThirdLocalStepAndReturnToPlanning() {
        variedCaptureEncoding=true
        val e=engine();val w=begin(e,stepCount=3)
        result(e,nextGesture(e),"after1",1007)
        assertNull(e.takeWork());val second=e.poll().getJSONObject("command")
        assertEquals("visual_gesture",second.getString("kind"));result(e,second,"after2",1011)
        assertNull(e.takeWork())
        val read=e.poll().getJSONObject("command");assertEquals("observe",read.getString("kind"))
        val run=e.get(w.runId);assertEquals("running",run.getString("status"))
        val plan=run.getJSONObject("local_visual_plan")
        assertEquals("replan",plan.getString("state"));assertEquals(3,plan.getInt("steps"));assertEquals(2,plan.getInt("accepted_steps"))
        assertEquals(run.getJSONObject("recovery_feedback").getString("message"),plan.getString("reason"))
        assertEquals("repeated_unverified_target",run.getJSONObject("recovery_feedback").getString("code"))
        assertFalse(run.getJSONObject("recovery_feedback").getBoolean("action_executed"))
        val receipts=run.getJSONObject("action_progress").getJSONArray("receipts")
        assertEquals(2,receipts.length());repeat(2) { assertEquals("unknown",receipts.getJSONObject(it).getString("effect")) }
        result(e,read,"replanned",1015)
        val next=e.takeWork()!!;assertTrue(e.poll().isNull("command"))
        e.accept(next,tool("propose_tap",JSONObject().put("capture_id","replanned").put("x",100).put("y",90)
            .put("duration_ms",80).put("label","another visible target").put("screen_context","current fixture").put("safety","safe")))
        val different=e.poll().getJSONObject("command")
        assertEquals("visual_gesture",different.getString("kind"))
        assertEquals(100.0/160,different.getJSONObject("gesture").getDouble("x"),1e-9)
    }
}

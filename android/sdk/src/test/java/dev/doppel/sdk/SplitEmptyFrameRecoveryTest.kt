package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SplitEmptyFrameRecoveryTest {
    private var clock=1000L
    private var saved=""
    private fun engine()=SplitTaskEngine(null,{saved=it},{clock},enhancementEnabled={false})
    private fun create(e:SplitTaskEngine)=e.create(JSONObject().put("goal","查看当前页面").put("mode","full")
        .put("device_id","direct-this-phone")).getString("id")
    private fun failure(c:JSONObject,code:String="capture_empty_frame")=JSONObject()
        .put("run_id",c.getString("run_id")).put("command_id",c.getString("id")).put("status","error")
        .put("message","屏幕暂时全黑").put("data",JSONObject().put("reason_code",code)
            .put("read_diagnostic",JSONObject().put("error_class","ScreenNotReadyException")
                .put("reason_code","empty_frame").put("source_file","DoppelAccessibilityService.kt").put("source_line",1000)))
    private fun success(c:JSONObject)=JSONObject().put("run_id",c.getString("run_id")).put("command_id",c.getString("id"))
        .put("status","ok").put("data",JSONObject().put("image_base64","visible-current-frame")
            .put("visual_frame",JSONObject().put("capture_id","visible-current-frame")))
    private fun next(e:SplitTaskEngine,id:String):JSONObject {
        val retry=e.get(id).getJSONObject("capture_retry")
        assertFalse(retry.getBoolean("model_requested"));assertFalse(e.readyForWork());assertNull(e.takeWork())
        val delay=retry.getLong("delay_ms")
        assertTrue(delay in 1L..2000L)
        assertTrue(e.poll().isNull("command"))
        clock+=delay-1;assertTrue(e.poll().isNull("command"));clock++
        return e.poll().getJSONObject("command").also {assertEquals("screenshot",it.getString("kind"))}
    }

    @Test fun severalBlackFramesWaitLocallyAndFirstVisibleFrameImmediatelyReturnsToA() {
        val e=engine();val id=create(e)
        var c=e.poll().getJSONObject("command")
        for(delay in listOf(250L,500L,1000L,1500L,2000L,2000L)) {
            e.result(failure(c))
            assertEquals("running",e.get(id).getString("status"));assertEquals(0,e.get(id).getInt("calls"))
            assertEquals(delay,e.get(id).getJSONObject("capture_retry").getLong("delay_ms"))
            assertEquals("empty_frame",e.get(id).getJSONObject("capture_retry").getJSONObject("read_diagnostic").getString("reason_code"))
            c=next(e,id)
        }
        e.result(success(c));assertEquals(0,e.get(id).getInt("capture_failures"));assertFalse(e.get(id).has("capture_retry"))
        assertTrue(e.readyForWork());val work=e.takeWork()!!;assertFalse(work.grounding);assertEquals(1,e.get(id).getInt("calls"))
        e.accept(work,SplitTestReply.response(JSONObject("""{"kind":"execute","action":"tap","target":"可见按钮","expected":"打开目标页","points":[[400,500]]}""")))
        val action=e.poll().getJSONObject("command")
        e.result(JSONObject().put("run_id",id).put("command_id",action.getString("id")).put("status","ok"))
        e.result(failure(e.poll().getJSONObject("command")))
        assertEquals(1,e.get(id).getJSONObject("capture_retry").getInt("retry"))
        assertEquals(0L,e.get(id).getJSONObject("capture_retry").getLong("elapsed_ms"))
    }

    @Test fun thirtySecondBudgetStopsBlackFramesAndResumeGetsANewBudget() {
        val e=engine();val id=create(e)
        e.result(failure(e.poll().getJSONObject("command")))
        clock+=29900
        e.result(failure(e.poll().getJSONObject("command")))
        assertEquals(100L,e.get(id).getJSONObject("capture_retry").getLong("delay_ms"))
        val last=next(e,id);e.result(failure(last))
        assertEquals("paused",e.get(id).getString("status"));assertEquals(0,e.get(id).getInt("calls"))
        assertTrue(e.get(id).getJSONObject("capture_retry").getBoolean("exhausted"))
        assertEquals(30000L,e.get(id).getJSONObject("capture_retry").getLong("elapsed_ms"))
        assertNull(e.takeWork());assertTrue(e.poll().isNull("command"))
        e.control(id,"resume",JSONObject());assertFalse(e.get(id).has("capture_retry"))
        e.result(failure(e.poll().getJSONObject("command")))
        assertEquals(1,e.get(id).getJSONObject("capture_retry").getInt("retry"))
        assertEquals(0L,e.get(id).getJSONObject("capture_retry").getLong("elapsed_ms"))
        assertFalse(e.result(success(last)).getBoolean("accepted"))
        e.result(success(next(e,id)));assertNotNull(e.takeWork())
    }

    @Test fun repeatedClockRollbacksStillHitAttemptLimitWithoutCallingModels() {
        val e=engine();val id=create(e)
        var c=e.poll().getJSONObject("command")
        repeat(31) {
            clock=1000L
            e.result(failure(c));assertEquals("running",e.get(id).getString("status"))
            c=next(e,id)
        }
        clock=1000L;e.result(failure(c))
        assertEquals("paused",e.get(id).getString("status"))
        assertEquals(32,e.get(id).getJSONObject("capture_retry").getInt("retry"))
        assertEquals(0,e.get(id).getInt("calls"));assertNull(e.takeWork())
    }

    @Test fun otherFailureCodesNeverAcquireTheLongBlackFrameBudget() {
        val e=engine();val id=create(e)
        var c=e.poll().getJSONObject("command")
        repeat(3) { e.result(failure(c,"capture_feedback_pending"));c=next(e,id) }
        e.result(failure(c,"capture_feedback_pending"))
        assertEquals("paused",e.get(id).getString("status"))
        assertFalse(e.get(id).getJSONObject("capture_retry").has("window_ms"))
        assertEquals(0,e.get(id).getInt("calls"))
    }

    @Test fun blackFramesDoNotSpendOrResetTheSeparateTechnicalFailureBudget() {
        val e=engine();val id=create(e)
        var c=e.poll().getJSONObject("command")
        repeat(6) { e.result(failure(c));c=next(e,id) }
        for(retry in 1..3) {
            e.result(failure(c,"capture_feedback_pending"))
            assertEquals(retry,e.get(id).getJSONObject("capture_retry").getInt("retry"));c=next(e,id)
            e.result(failure(c));c=next(e,id)
        }
        e.result(failure(c,"capture_feedback_pending"))
        assertEquals("paused",e.get(id).getString("status"));assertEquals(0,e.get(id).getInt("calls"))
    }

    @Test fun userPauseCancellationAndRestartDiscardTheEmptyFrameBudget() {
        for(action in listOf("pause","cancel","restart")) {
            val e=engine();val id=create(e)
            e.result(failure(e.poll().getJSONObject("command")));val pending=next(e,id)
            e.result(failure(pending))
            val current=if(action=="restart") SplitTaskEngine(saved,{}, {clock},enhancementEnabled={false})
                else e.also {it.control(id,action,JSONObject())}
            assertFalse(current.result(success(pending)).getBoolean("accepted"))
            assertNull(current.takeWork());assertTrue(current.poll().isNull("command"))
            if(action!="cancel") {
                clock+=60000;current.control(id,"resume",JSONObject())
                current.result(failure(current.poll().getJSONObject("command")))
                assertEquals("running",current.get(id).getString("status"))
                assertEquals(1,current.get(id).getJSONObject("capture_retry").getInt("retry"))
                assertEquals(0L,current.get(id).getJSONObject("capture_retry").getLong("elapsed_ms"))
            }
        }
    }
}

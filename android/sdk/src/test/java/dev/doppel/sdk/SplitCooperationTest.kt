package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SplitCooperationTest {
    private val e=SplitTaskEngine(null,{},{1000L})
    private fun reply(body:JSONObject)=SplitTestReply.response(body)
    private fun screen(name:String) {
        val c=e.poll().getJSONObject("command")
        e.result(JSONObject().put("run_id",c.getString("run_id")).put("command_id",c.getString("id")).put("status","ok")
            .put("observation",JSONObject().put("package_name","private.package").put("activity","private.activity"))
            .put("data",JSONObject().put("image_base64",name).put("visual_frame",JSONObject().put("capture_id",name))))
    }
    private fun start():String=e.create(JSONObject().put("goal","查看今日考勤结果").put("mode","full").put("device_id","direct-this-phone"))
        .getString("id").also {screen("initial")}
    private fun propose(expected:String="出现本次打卡成功与时间") {
        e.accept(e.takeWork()!!,reply(JSONObject().put("kind","execute").put("action","tap")
            .put("target","考勤页下方写着打卡的图标").put("expected",expected).put("screen_context","当前考勤页，底部是功能导航")))
    }
    private fun located(alignment:String="consistent",withAssessment:Boolean=true)=JSONObject().put("status","located").put("action","tap")
        .put("points",JSONArray().put(JSONArray(listOf(500,900)))).apply {
            if(withAssessment) put("assessment",JSONObject().put("alignment",alignment)
                .put("observed","底部打卡属于导航，实际打卡是页面蓝色按钮")
                .put("reason",if(alignment=="consistent") "目标与预期可观察变化相符" else "导航只能打开考勤页，不能提交本次打卡"))
        }
    private fun lastContext(work:SplitTaskEngine.Work):JSONObject {
        val messages=work.payload.getJSONArray("messages")
        return JSONObject(messages.getJSONObject(messages.length()-1).getJSONArray("content").getJSONObject(0).getString("text"))
    }
    @Test fun expectedEffectAndSceneReachStatelessBWithoutPrivateMetadata() {
        start();propose();screen("fresh")
        val b=e.takeWork()!!;val content=lastContext(b)
        assertEquals("出现本次打卡成功与时间",content.getString("expected"))
        assertEquals("当前考勤页，底部是功能导航",content.getString("screen_context"))
        assertEquals(2,b.payload.getJSONArray("messages").length())
        for(secret in listOf("private.package","private.activity","capture_id","查看今日考勤结果")) assertFalse(b.payload.toString().contains(secret))
    }
    @Test fun explicitIntentRefusalPassesObservedRelationshipBackToA() {
        val id=start();propose();screen("fresh")
        e.accept(e.takeWork()!!,reply(JSONObject().put("status","intent_mismatch")
            .put("reason","底部是导航，实际打卡是页面蓝色按钮")))
        assertTrue("No gesture may be queued",e.poll().isNull("command"))
        val a=e.takeWork()!!;assertFalse(a.grounding)
        val assessedIntent=lastContext(a).getJSONObject("last_intent")
        assertEquals("出现本次打卡成功与时间",assessedIntent.getString("expected"))
        assertEquals("考勤页下方写着打卡的图标",assessedIntent.getString("target"))
        val feedback=lastContext(a).getJSONObject("grounding_result")
        assertEquals("intent_mismatch",feedback.getString("status"))
        assertTrue(feedback.getString("reason").contains("实际打卡"))
        assertEquals("running",e.get(id).getString("status"))
    }
    @Test fun uncertainEffectMustReturnToAWithoutInjectingCoordinates() {
        start();propose();screen("fresh");e.accept(e.takeWork()!!,reply(located("uncertain")))
        assertTrue(e.poll().isNull("command"));assertFalse(e.takeWork()!!.grounding)
    }
    @Test fun locatedWithoutAssessmentCannotBypassCooperation() {
        start();propose();screen("fresh");e.accept(e.takeWork()!!,reply(located(withAssessment=false)))
        assertTrue(e.poll().isNull("command"));assertFalse(e.takeWork()!!.grounding)
    }
    @Test fun missingExpectedEffectReturnsToAWithoutAskingBToGuess() {
        start();e.accept(e.takeWork()!!,reply(JSONObject().put("kind","execute").put("action","tap").put("target","打卡")))
        assertTrue(e.poll().isNull("command"));assertFalse(e.takeWork()!!.grounding)
    }
    @Test fun acceptedAssessmentTravelsWithReceiptWithoutExtraModelCall() {
        val id=start();propose("显示考勤导航页");screen("fresh")
        e.accept(e.takeWork()!!,reply(located()))
        val command=e.poll().getJSONObject("command");assertEquals("split_action",command.getString("kind"))
        assertEquals("fresh",command.getJSONObject("source").getString("capture_id"))
        e.result(JSONObject().put("run_id",id).put("command_id",command.getString("id")).put("status","ok")
            .put("data",JSONObject().put("action_state","accepted")))
        screen("after");val a=e.takeWork()!!;assertFalse(a.grounding)
        val assessment=lastContext(a).getJSONObject("last_receipt").getJSONObject("grounding_assessment")
        assertEquals("consistent",assessment.getString("alignment"))
        assertEquals("before_action",assessment.getString("phase"))
        assertEquals(3,e.get(id).getInt("calls"))
    }
    @Test fun lateAssessmentCannotResumeCancelledTask() {
        val id=start();propose();screen("fresh");val b=e.takeWork()!!
        e.control(id,"cancel",JSONObject());e.accept(b,reply(located("inconsistent")))
        assertTrue(e.poll().isNull("command"));assertNull(e.takeWork());assertEquals("cancelled",e.get(id).getString("status"))
    }

    @Test fun swipeCoordinatesContradictingPlannerAndGrounderNeverReachDevice() {
        start()
        e.accept(e.takeWork()!!,reply(JSONObject()
            .put("kind","execute").put("action","swipe")
            .put("target","章节列表中露出左侧更早章节").put("expected","看到第一章")
            .put("gesture_semantics","reveal_content")
            .put("target_relative_direction","left")
            .put("intended_finger_direction","right")))
        screen("fresh")
        val value=JSONObject("""{"status":"located","action":"swipe","points":[[800,500],[200,500]],"assessment":{"alignment":"consistent","observed":"第一章位于当前章节左侧","reason":"需要右滑露出左侧内容","target_relative_direction":"left","required_finger_direction":"right"}}""")
        e.accept(e.takeWork()!!,reply(value))
        assertTrue("Contradicting swipe must not be dispatched",e.poll().isNull("command"))
        val recovery=e.takeWork()!!
        assertFalse(recovery.grounding)
        val result=lastContext(recovery).getJSONObject("grounding_result")
        assertEquals("intent_mismatch",result.getString("status"))
        assertEquals("gesture_coordinate_mismatch",result.getString("reason_code"))
    }

    @Test fun malformedGrounderResponseDoesNotConsumePrimaryRetryBudget() {
        val id=start()
        e.accept(e.takeWork()!!,reply(JSONObject()
            .put("kind","execute").put("action","swipe")
            .put("target","露出左侧关卡").put("expected","看到1-7")
            .put("gesture_semantics","reveal_content")
            .put("target_relative_direction","left")
            .put("intended_finger_direction","right")))
        screen("fresh")
        val malformed=JSONObject("""{"status":"located","action":"swipe","points":[[200,500],[800,500]],"assessment":{"alignment":"consistent","observed":"目标在左侧","reason":"需要右滑"}}""")
        e.accept(e.takeWork()!!,reply(malformed))
        assertEquals(1,e.get(id).getInt("protocol_failures_grounding"))
        repeat(2) {
            e.accept(e.takeWork()!!,reply(JSONObject().put("kind","invalid")))
            assertEquals("running",e.get(id).getString("status"))
        }
        assertEquals(2,e.get(id).getInt("protocol_failures_primary"))
        assertEquals(1,e.get(id).getInt("protocol_failures_grounding"))
    }
}

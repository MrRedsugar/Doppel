package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WaitObservationTest {
    private var clock=1000L
    private val e=SplitTaskEngine(null,{}, {clock})
    private fun screen(name:String) {
        val c=e.poll().getJSONObject("command")
        assertEquals("screenshot",c.getString("kind"))
        e.result(JSONObject().put("run_id",c.getString("run_id")).put("command_id",c.getString("id"))
            .put("status","ok").put("data",JSONObject().put("image_base64",name)
                .put("visual_frame",JSONObject().put("capture_id",name))))
    }
    private fun begin():String = e.create(JSONObject().put("goal","打开任务页面并查看结果")
        .put("device_id","direct-this-phone").put("mode","full")).getString("id").also {screen("pending")}
    private fun reply(decision:JSONObject):JSONObject = JSONObject().put("choices",JSONArray().put(JSONObject()
        .put("finish_reason","stop").put("message",JSONObject().put("content",JSONObject()
            .put("decision",decision).put("state",JSONObject.NULL).toString()))))
    private fun waitAndCapture(name:String) {
        val work=e.takeWork()!!
        e.accept(work,reply(JSONObject().put("kind","wait").put("duration_ms",500).put("reason","处理未结束")
            .put("evidence","正在处理指示仍然可见").put("wait_condition","处理结束，入口或结果可以操作")))
        val c=e.poll().getJSONObject("command");assertEquals("wait",c.getString("kind"))
        clock+=700
        e.result(JSONObject().put("run_id",c.getString("run_id")).put("command_id",c.getString("id")).put("status","ok"))
        screen(name)
    }
    private fun context(work:SplitTaskEngine.Work):JSONObject {
        val messages=work.payload.getJSONArray("messages")
        return JSONObject(messages.getJSONObject(messages.length()-1).getJSONArray("content").getJSONObject(0).getString("text"))
    }
    @Test fun nextDecisionReceivesActualWaitTimeConditionAndBeforeAfterImages() {
        begin();waitAndCapture("ready")
        val work=e.takeWork()!!;val state=context(work).getJSONObject("wait_observation")
        assertEquals(1,state.getInt("consecutive_waits"));assertEquals(700,state.getLong("elapsed_ms"))
        assertEquals("处理结束，入口或结果可以操作",state.getString("until"))
        val text=work.payload.getJSONArray("messages").toString()
        assertTrue(text.contains("等待前"));assertTrue(text.contains("base64,pending"));assertTrue(text.contains("base64,ready"))
    }
    @Test fun repeatedWaitsRequestReassessmentWithoutForcingAPauseOrUnknownCoordinates() {
        val id=begin();repeat(3) {waitAndCapture("pending-$it")}
        val work=e.takeWork()!!;val state=context(work).getJSONObject("wait_observation")
        assertEquals(3,state.getInt("consecutive_waits"));assertTrue(state.getBoolean("reassess"))
        assertEquals("running",e.get(id).getString("status"));assertTrue(e.poll().isNull("command"))
        assertTrue(work.payload.toString().contains("可操作"))
    }
    @Test fun normalActionClearsWaitStreakAndFreshCaptureStillPrecedesGrounding() {
        begin();waitAndCapture("ready")
        e.accept(e.takeWork()!!,reply(JSONObject().put("kind","tap")
            .put("target","页面底部可操作的入口").put("expected","进入下一页面").put("screen_context","")
            .put("request_login_code",JSONObject.NULL)))
        screen("ready-for-b");val b=e.takeWork()!!;assertTrue(b.grounding)
        e.accept(b,JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","stop")
            .put("message",JSONObject().put("content","""{"result":{"status":"not_found","reason":"入口已消失"}}""")))))
        assertFalse(context(e.takeWork()!!).has("wait_observation"))
    }
}

package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlannedControlEngineTest {
    private fun screen(id:String,label:String)=JSONObject().put("screen_id",id).put("package_name","example.app").put("width",1080).put("height",2400)
        .put("nodes",JSONArray().put(JSONObject().put("id",id).put("text",label).put("enabled",true).put("clickable",true).put("bounds",JSONArray(listOf(0,0,200,100)))))
    private fun result(e:DirectTaskEngine,c:JSONObject,s:JSONObject,status:String="ok")=e.result(JSONObject().put("run_id",c.getString("run_id")).put("command_id",c.getString("id"))
        .put("status",status).put("observation",s).put("data",JSONObject()))
    private fun start(mode:String="full"):DirectTaskEngine { val e=DirectTaskEngine(null,{}, {2000L},plannedControl=true);e.create(JSONObject().put("device_id",DirectRuntime.DEVICE_ID).put("mode",mode).put("goal","去设置看关于"));result(e,e.poll().getJSONObject("command"),screen("n1","设置"));return e }
    private fun reply(args:JSONObject)=JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","tool_calls").put("message",JSONObject().put("tool_calls",JSONArray().put(JSONObject().put("function",JSONObject().put("name","execute_plan").put("arguments",args.toString())))))))
    private fun plan(vararg labels:String)=JSONObject().put("objective","查看设置中的关于页面").put("steps",JSONArray(labels.map { JSONObject().put("kind","tap").put("package_name","example.app").put("selector",JSONObject().put("text",it)) }))
    @Test fun twoStepsUseOneModelCallAndFreshTargets() {
        val e=start();val w=e.takeWork()!!;e.accept(w,reply(plan("设置","关于")))
        assertNull(e.takeWork());val first=e.poll().getJSONObject("command");assertEquals("n1",first.getString("target"))
        result(e,first,screen("n2","关于"));assertNull(e.takeWork());val second=e.poll().getJSONObject("command");assertEquals("n2",second.getString("target"))
        assertEquals(1,e.get(w.runId).getInt("calls"));result(e,second,screen("n3","版本信息"))
        val final=e.takeWork()!!;assertEquals(2,e.get(w.runId).getInt("calls"));assertTrue(final.payload.toString().contains("local_plan_verify"))
    }
    @Test fun cancellationRevokesRemainingSteps() {
        val e=start();val w=e.takeWork()!!;e.accept(w,reply(plan("设置","关于")));e.takeWork();val c=e.poll().getJSONObject("command")
        e.control(w.runId,"cancel",JSONObject());result(e,c,screen("n2","关于"));assertTrue(e.poll().isNull("command"));assertNull(e.takeWork())
    }
    @Test fun everyStepStillUsesApprovalAndPaymentPolicy() {
        val e=start("ask");val w=e.takeWork()!!;e.accept(w,reply(plan("设置","关于")));e.takeWork();assertTrue(e.poll().isNull("command"));assertEquals("awaiting_approval",e.get(w.runId).getString("status"))
        val p=start();val pw=p.takeWork()!!;p.accept(pw,reply(plan("设置","确认付款")));p.takeWork();result(p,p.poll().getJSONObject("command"),screen("n2","确认付款"));p.takeWork()
        assertTrue(p.poll().isNull("command"));assertEquals("paused",p.get(pw.runId).getString("status"))
    }
    @Test fun runtimeDoesNotOfferOldScreenshotQuestionTool() {
        val w=start().takeWork()!!;val t=w.payload.getJSONArray("tools");val names=(0 until t.length()).map{t.getJSONObject(it).getJSONObject("function").getString("name")}
        assertTrue("execute_plan" in names);assertFalse("inspect_screen" in names)
    }
    @Test fun plannedRuntimeRefusesLegacyQuestionEvenIfModelInventsIt() {
        val e=start();val w=e.takeWork()!!
        val response=reply(JSONObject().put("question","是否点击到了"))
        response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getJSONArray("tool_calls")
            .getJSONObject(0).getJSONObject("function").put("name","inspect_screen")
        e.accept(w,response)
        assertEquals("paused",e.get(w.runId).getString("status"))
        assertFalse(e.events(w.runId).toString().contains("视觉分析："))
        assertTrue(e.poll().isNull("command"))
    }
}

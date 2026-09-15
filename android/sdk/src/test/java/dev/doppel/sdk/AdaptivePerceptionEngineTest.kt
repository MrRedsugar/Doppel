package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AdaptivePerceptionEngineTest {
    private fun native(id:String)=JSONObject().put("screen_id",id).put("package_name","example.editor")
        .put("width",1080).put("height",2400).put("tree_complete",true).put("nodes",JSONArray()
            .put(JSONObject().put("id","n1").put("text","4572").put("role","input").put("resource_id","example.editor:id/result")
                .put("enabled",true).put("editable",true).put("bounds",JSONArray(listOf(0,0,1000,300)))))
    private fun result(e:DirectTaskEngine,s:JSONObject,image:Boolean=false):JSONObject {
        val c=e.poll().getJSONObject("command")
        val d=JSONObject().put("device_profile",JSONObject().put("visual_gestures",true))
        if(image)d.put("image_base64","aW1hZ2U=").put("mime_type","image/png")
            .put("visual_frame",VisualFrame("capture-${s.getString("screen_id")}",s.getString("screen_id"),s.getString("package_name"),1080,2400,540,1200,0,1000,46000,"a".repeat(64)).json())
        e.result(JSONObject().put("run_id",c.getString("run_id")).put("command_id",c.getString("id"))
            .put("status","ok").put("observation",s).put("data",d));return c
    }
    private fun engine():DirectTaskEngine=DirectTaskEngine(null,{}, {2000L},visualControl=true,plannedControl=true).also {
        it.create(JSONObject().put("device_id",DirectRuntime.DEVICE_ID).put("goal","查看结果").put("mode","full"))
    }
    private fun response(name:String,args:JSONObject)=JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","tool_calls")
        .put("message",JSONObject().put("tool_calls",JSONArray().put(JSONObject().put("function",JSONObject().put("name",name).put("arguments",args.toString())))))))
    @Test fun usableEditorKeepsPrimaryPlannerAndActualValue() {
        val e=engine();result(e,native("a"));val w=e.takeWork()!!
        assertFalse(w.visualAgent);assertEquals(DirectPayload.PLANNER,w.payload.getString("model"));assertTrue(w.payload.toString().contains("4572"))
    }
    @Test fun leavingCanvasRestoresSemanticPlanning() {
        val e=engine();result(e,native("a").put("nodes",JSONArray()),true);val visual=e.takeWork()!!;assertTrue(visual.visualAgent)
        e.accept(visual,response("navigate",JSONObject().put("kind","back")))
        result(e,native("b"));val next=e.takeWork()!!
        assertFalse(next.visualAgent);assertEquals(DirectPayload.PLANNER,next.payload.getString("model"))
    }
    @Test fun explicitScreenshotFeedsOneVisualDecisionThenFreshSceneChoosesAgain() {
        val e=engine();result(e,native("a"));e.accept(e.takeWork()!!,response("observe_screen",JSONObject()))
        assertTrue(e.poll().getJSONObject("command").getBoolean("include_screenshot"));result(e,native("b"),true)
        val v=e.takeWork()!!;assertTrue(v.visualAgent)
        e.accept(v,response("navigate",JSONObject().put("kind","back")));result(e,native("c"))
        assertFalse(e.takeWork()!!.visualAgent)
    }
}

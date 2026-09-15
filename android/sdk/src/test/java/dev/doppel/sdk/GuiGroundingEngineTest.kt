package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GuiGroundingEngineTest {
    private val frame=VisualFrame("capture-1","s1","example.app",1440,3200,720,1600,0,100,20000,"a".repeat(64))
    private fun start(mode:String="full", gui:Boolean=true):Pair<DirectTaskEngine,DirectTaskEngine.Work> {
        val e=DirectTaskEngine(null,{}, {2000L},visualControl=true,preferVisualObservation=true,plannedControl=true,guiAvailable={gui})
        e.create(JSONObject().put("device_id",DirectRuntime.DEVICE_ID).put("mode",mode).put("goal","打开设置"))
        val c=e.poll().getJSONObject("command")
        e.result(JSONObject().put("run_id",c.getString("run_id")).put("command_id",c.getString("id")).put("status","ok")
            .put("observation",JSONObject().put("screen_id","s1").put("package_name","example.app").put("width",1440).put("height",3200).put("nodes",JSONArray()))
            .put("data",JSONObject().put("device_profile",JSONObject().put("visual_gestures",true)).put("visual_frame",frame.json()).put("image_base64","aGVsbG8=").put("mime_type","image/png")))
        return e to e.takeWork()!!
    }
    private fun locatorWork(e:DirectTaskEngine,w:DirectTaskEngine.Work):DirectTaskEngine.Work {
        val args=JSONObject().put("target","设置").put("kind","tap").put("screen_context","当前应用的设置入口").put("safety","safe")
        e.accept(w,JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","tool_calls").put("message",JSONObject().put("tool_calls",JSONArray()
            .put(JSONObject().put("function",JSONObject().put("name","locate_ui").put("arguments",args.toString()))))))))
        return e.takeWork()!!
    }
    private fun response()=JSONObject().put("capture_id",frame.captureId).put("image_sha256",frame.sha256).put("width",720).put("height",1600)
        .put("status","point").put("x",180).put("y",800).put("latency_ms",125)
    @Test fun locatorPointGoesThroughHostAndNeverPersistsScreenshotInKnowledge() {
        val(e,w)=start();val local=locatorWork(e,w);assertEquals("locate_ui",local.localTool)
        e.acceptLocal(local,response());assertEquals("visual_gesture",e.poll().getJSONObject("command").getString("kind"))
        assertEquals(1,e.get(w.runId).getInt("calls"));assertEquals(1,e.get(w.runId).getJSONObject("perception_metrics").getInt("gui_locator_calls"))
    }
    @Test fun locatorDoesNotBypassAskMode() {
        val(e,w)=start("ask");e.acceptLocal(locatorWork(e,w),response());assertTrue(e.poll().isNull("command"))
        assertEquals("awaiting_approval",e.get(w.runId).getString("status"))
    }
    @Test fun lateLocatorResultCannotExecuteAfterCancellation() {
        val(e,w)=start();val local=locatorWork(e,w);e.control(w.runId,"cancel",JSONObject());e.acceptLocal(local,response())
        assertTrue(e.poll().isNull("command"));assertEquals("cancelled",e.get(w.runId).getString("status"))
    }
    @Test fun invalidPointTriggersOnlyFreshObservation() {
        val(e,w)=start();e.acceptLocal(locatorWork(e,w),response().put("capture_id","wrong"))
        assertEquals("observe",e.poll().getJSONObject("command").getString("kind"))
        assertEquals("gui_locator_unavailable",e.get(w.runId).getJSONObject("recovery_feedback").getString("code"))
    }
    private fun proposeTap(e:DirectTaskEngine,w:DirectTaskEngine.Work) {
        val args=JSONObject().put("capture_id",frame.captureId).put("x",360).put("y",800).put("duration_ms",80)
            .put("label","设置").put("screen_context","当前应用的设置入口").put("safety","safe")
        e.accept(w,JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","tool_calls").put("message",JSONObject()
            .put("tool_calls",JSONArray().put(JSONObject().put("function",JSONObject().put("name","propose_tap").put("arguments",args.toString()))))))))
    }
    @Test fun configuredLocatorRefinesAProposedPointWithoutAnotherPlannerRoundTrip() {
        val(e,w)=start();proposeTap(e,w);assertTrue(e.poll().isNull("command"))
        val local=e.takeWork()!!;assertEquals("locate_ui",local.localTool)
        e.acceptLocal(local,response())
        val gesture=e.poll().getJSONObject("command").getJSONObject("gesture")
        assertEquals(.25,gesture.getDouble("x"),0.00001)
        assertEquals(1,e.get(w.runId).getInt("calls"))
    }
    @Test fun absentGuiTargetNeverFallsBackToTheProposedGuess() {
        val(e,w)=start();proposeTap(e,w);val local=e.takeWork()!!
        e.acceptLocal(local,response().put("status","not_found").apply {remove("x");remove("y")})
        assertEquals("observe",e.poll().getJSONObject("command").getString("kind"))
        assertEquals("gui_target_not_found",e.get(w.runId).getJSONObject("recovery_feedback").getString("code"))
    }
    @Test fun disabledLocatorKeepsTheExistingHostValidatedVisualPath() {
        val(e,w)=start(gui=false);proposeTap(e,w)
        assertEquals("visual_gesture",e.poll().getJSONObject("command").getString("kind"))
    }
}

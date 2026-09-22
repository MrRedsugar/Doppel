package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OptionalGroundingTest {
    private fun engine(enabled:Boolean=false,save:(String)->Unit={})=SplitTaskEngine(null,save,{1000L},enhancementEnabled={enabled})
    private fun reply(value:JSONObject)=SplitTestReply.response(value)
    private fun decision()=JSONObject("""{"kind":"execute","action":"tap","target":"计算器数字7","expected":"输入7","points":[[150,520]]}""")
    private fun start(e:SplitTaskEngine,mode:String="full"):String {
        val id=e.create(JSONObject().put("goal","计算23乘17").put("device_id","direct-this-phone").put("mode",mode)).getString("id")
        capture(e,"a-frame");return id
    }
    private fun capture(e:SplitTaskEngine,name:String) {
        val c=e.poll().getJSONObject("command");assertEquals("screenshot",c.getString("kind"))
        e.result(JSONObject().put("run_id",c.getString("run_id")).put("command_id",c.getString("id")).put("status","ok")
            .put("observation",JSONObject().put("screen_id",name)).put("data",JSONObject().put("image_base64",name)
                .put("visual_frame",JSONObject().put("capture_id",name))))
    }
    @Test fun disabledEnhancementDispatchesAActionAgainstItsOwnImageWithoutB() {
        val e=engine();val id=start(e);val a=e.takeWork()!!
        assertFalse(a.grounding);assertEquals("primary",a.payload.getString("_doppel_role"))
        val prompt=a.payload.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(prompt.contains("0..1000"));assertFalse(prompt.contains("不要自己给坐标"))
        e.accept(a,reply(decision()))
        val c=e.poll().getJSONObject("command")
        assertEquals("split_action",c.getString("kind"));assertEquals("a-frame",c.getJSONObject("source").getString("capture_id"))
        assertEquals(150.0,c.getJSONObject("action").getJSONArray("points").getJSONArray(0).getDouble(0),0.0)
        assertFalse(c.has("grounding_assessment"));assertEquals("direct",e.get(id).getString("execution_mode"));assertEquals(1,e.get(id).getInt("calls"))
        e.result(JSONObject().put("run_id",id).put("command_id",c.getString("id")).put("status","ok").put("data",JSONObject().put("action_state","accepted")))
        capture(e,"after");assertFalse(e.takeWork()!!.grounding)
        assertFalse(e.get(id).getJSONObject("model_metrics").has("grounding"))
    }
    @Test fun enabledEnhancementStillTakesFreshImageAndCallsB() {
        val e=engine(true);val id=start(e)
        e.accept(e.takeWork()!!,reply(decision().apply {remove("points")}));capture(e,"b-fresh")
        val b=e.takeWork()!!;assertTrue(b.grounding)
        assertTrue(b.payload.toString().contains("b-fresh"));assertEquals("ab",e.get(id).getString("execution_mode"))
    }
    @Test fun directCoordinatesAreRequiredAndMustStayInsideNormalizedBounds() {
        for(value in listOf(decision().apply {remove("points")},decision().put("points",JSONArray("[[1001,0]]")),decision().put("points",JSONArray("[[-1,50]]")))) {
            val e=engine();start(e);e.accept(e.takeWork()!!,reply(value))
            assertTrue(e.poll().isNull("command"));assertFalse(e.takeWork()!!.grounding)
        }
    }
    @Test fun directModeSupportsExistingDoubleTapSwipeAndInputGrammar() {
        for(fields in listOf("""{"action":"double_tap","points":[[100,200],[300,400]]}""",
            """{"action":"swipe_sequence","gesture_contracts":[{"gesture_semantics":"reveal_content","target_relative_direction":"right","intended_finger_direction":"left"},{"gesture_semantics":"reveal_content","target_relative_direction":"right","intended_finger_direction":"left"}],"strokes":[{"points":[[600,500],[500,500]],"duration_ms":350},{"points":[[600,500],[500,500]],"duration_ms":350}]}""",
            """{"action":"type","text":"你好"}""","""{"action":"home"}""")) {
            val e=engine();start(e);val value=JSONObject(fields).put("kind","execute").put("target","当前页面目标").put("expected","目标变化")
            e.accept(e.takeWork()!!,reply(value));val c=e.poll().getJSONObject("command")
            if (value.getString("action") == "home") {
                assertEquals("home", c.getString("kind")); assertFalse(c.has("action"))
                assertEquals("a-frame", c.getJSONObject("source").getString("capture_id"))
            } else {
                assertEquals("split_action",c.getString("kind"));assertEquals(value.getString("action"),c.getJSONObject("action").getString("action"))
            }
        }
    }
    @Test fun waitRemainsAnADecisionAndDoesNotRequestGrounding() {
        val e=engine();start(e);e.accept(e.takeWork()!!,reply(JSONObject("""{"kind":"wait","duration_ms":500,"reason":"加载中"}""")))
        assertEquals("wait",e.poll().getJSONObject("command").getString("kind"))
    }
    @Test fun cancelledDirectResponseCannotQueueAnAction() {
        val e=engine();val id=start(e);val work=e.takeWork()!!;e.control(id,"cancel",JSONObject());e.accept(work,reply(decision()))
        assertTrue(e.poll().isNull("command"));assertNull(e.takeWork())
    }
    @Test fun approvalReobservesAndNeverReplaysTheOldCoordinates() {
        val e=engine();val id=start(e,"ask");e.accept(e.takeWork()!!,reply(decision()))
        val pending=e.get(id).getJSONObject("pending_request");assertTrue(e.poll().isNull("command"))
        assertFalse(pending.getJSONObject("intent").has("points"))
        e.control(id,"answer",JSONObject().put("request_id",pending.getString("id")).put("approve",true));capture(e,"after-approval")
        e.accept(e.takeWork()!!,reply(decision().put("points",JSONArray("[[750,520]]"))))
        val c=e.poll().getJSONObject("command");assertEquals("after-approval",c.getJSONObject("source").getString("capture_id"))
        assertEquals(750.0,c.getJSONObject("action").getJSONArray("points").getJSONArray(0).getDouble(0),0.0)
    }
    @Test fun missingCurrentCaptureStillBlocksDirectExecutionAndCompletion() {
        for(value in listOf(decision(),JSONObject("""{"kind":"finish","status":"completed","message":"完成"}"""))) {
            var clock=1000L
            val e=SplitTaskEngine(null,{}, {clock},enhancementEnabled={false})
            val id=e.create(JSONObject().put("goal","计算").put("mode","full").put("device_id","direct-this-phone")).getString("id")
            val c=e.poll().getJSONObject("command")
            e.result(JSONObject().put("run_id",c.getString("run_id")).put("command_id",c.getString("id")).put("status","error").put("message","窗口变化"))
            assertFalse(e.readyForWork());assertNull(e.takeWork())
            assertEquals(0,e.get(id).getInt("calls"));assertEquals("running",e.get(id).getString("status"))
            assertTrue(e.poll().isNull("command"))
            clock+=249;assertTrue(e.poll().isNull("command"));assertNull(e.takeWork())
            clock++
            capture(e,"recovered-current-frame")
            val work=e.takeWork()!!;assertFalse(work.grounding)
            e.accept(work,reply(value))
            if(value.getString("kind")=="finish") {
                assertEquals("completed",e.get(id).getString("status"));assertTrue(e.poll().isNull("command"))
            } else {
                val action=e.poll().getJSONObject("command")
                assertEquals("split_action",action.getString("kind"))
                assertEquals("recovered-current-frame",action.getJSONObject("source").getString("capture_id"))
            }
        }
    }
    @Test fun modeIsFrozenForAnExistingRunAndPersistedAcrossRestart() {
        var enabled=false;var saved=""
        val e=SplitTaskEngine(null,{saved=it},{1000L},enhancementEnabled={enabled});val id=start(e)
        enabled=true;e.accept(e.takeWork()!!,reply(decision()));assertEquals("split_action",e.poll().getJSONObject("command").getString("kind"))
        val restored=SplitTaskEngine(saved,{}, {1000L},enhancementEnabled={true})
        restored.control(id,"resume",JSONObject());capture(restored,"restart")
        restored.accept(restored.takeWork()!!,reply(decision()));assertEquals("split_action",restored.poll().getJSONObject("command").getString("kind"))
    }
    @Test fun legacyTasksKeepABWhenTheSettingHasSinceChanged() {
        var saved="";val original=engine(true,{saved=it});val id=start(original)
        val rows=SplitTaskEngine.readPersistedRuns(saved);rows.getJSONObject(0).remove("execution_mode")
        val restored=SplitTaskEngine(rows.toString(),{}, {1000L},enhancementEnabled={false})
        restored.control(id,"resume",JSONObject());capture(restored,"restart")
        restored.accept(restored.takeWork()!!,reply(decision().apply {remove("points")}));capture(restored,"fresh")
        assertTrue(restored.takeWork()!!.grounding)
    }

    @Test fun directModeRejectsCoordinatesOppositeToDeclaredFingerDirection() {
        val e=engine();start(e)
        val value=JSONObject("""{"kind":"execute","action":"swipe","target":"露出左侧第一章","expected":"看到第一章","gesture_semantics":"reveal_content","target_relative_direction":"left","intended_finger_direction":"right","points":[[600,500],[500,500]]}""")
        e.accept(e.takeWork()!!,reply(value))
        assertTrue(e.poll().isNull("command"))
        assertFalse(e.takeWork()!!.grounding)
    }
}

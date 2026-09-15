package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SplitCaptureRetryTest {
    private var clock=1000L
    private var saved=""
    private fun engine()=SplitTaskEngine(null,{saved=it},{clock})
    private fun create(e:SplitTaskEngine)=e.create(JSONObject().put("goal","查看当前应用").put("mode","full").put("device_id","direct-this-phone")).getString("id")
    private fun failure(c:JSONObject, status:String="error", takeover:Boolean=false)=JSONObject()
        .put("run_id",c.getString("run_id")).put("command_id",c.getString("id")).put("status",status).put("message","助手界面仍在清理")
        .put("data",JSONObject().put("reason_code","capture_companion_pending").apply {if(takeover) put("human_takeover","screen_capture_required")})
    private fun success(c:JSONObject)=JSONObject().put("run_id",c.getString("run_id")).put("command_id",c.getString("id")).put("status","ok")
        .put("observation",JSONObject()).put("data",JSONObject().put("image_base64","fresh")
            .put("visual_frame",JSONObject().put("capture_id","fresh")))

    @Test fun failuresUseThreeBoundedLocalRetriesThenPauseAndResumeFromNewCapture() {
        val e=engine();val id=create(e)
        var command=e.poll().getJSONObject("command")
        val original=JSONObject(command.toString())
        for(delay in listOf(250L,500L,1000L)) {
            assertTrue(e.result(failure(command)).getBoolean("accepted"))
            assertEquals(0,e.get(id).getInt("calls"));assertFalse(e.readyForWork());assertNull(e.takeWork())
            assertTrue(e.poll().isNull("command"))
            clock+=delay-1;assertTrue(e.poll().isNull("command"));clock++
            command=e.poll().getJSONObject("command")
            assertEquals("screenshot",command.getString("kind"))
            assertFalse(e.result(success(original)).getBoolean("accepted"))
        }
        e.result(failure(command))
        assertEquals("paused",e.get(id).getString("status"));assertEquals(4,e.get(id).getInt("capture_failures"))
        assertTrue(e.get(id).getString("message").contains("点击继续"))
        assertTrue(e.poll().isNull("command"));assertNull(e.takeWork());assertEquals(0,e.get(id).getInt("calls"))
        e.control(id,"resume",JSONObject())
        val retry=e.poll().getJSONObject("command")
        assertEquals("screenshot",retry.getString("kind"));assertEquals(0,e.get(id).getInt("capture_failures"))
        assertFalse(e.result(success(command)).getBoolean("accepted"))
        e.result(success(retry));assertEquals("primary",e.takeWork()!!.payload.getString("_doppel_role"))
    }

    @Test fun pauseCancelAndRestartDiscardDelayedCaptureWithoutReplayingAction() {
        for(action in listOf("pause","cancel")) {
            val e=engine();val id=create(e);val command=e.poll().getJSONObject("command")
            e.result(failure(command));e.control(id,action,JSONObject());clock+=5000
            assertFalse(e.result(success(command)).getBoolean("accepted"))
            assertTrue(e.poll().isNull("command"));assertNull(e.takeWork())
            assertEquals(if(action=="pause") "paused" else "cancelled",e.get(id).getString("status"))
        }
        val e=engine();val id=create(e);e.result(failure(e.poll().getJSONObject("command")))
        val restored=SplitTaskEngine(saved,{saved=it},{clock})
        assertEquals("paused",restored.get(id).getString("status"));assertTrue(restored.poll().isNull("command"))
        restored.control(id,"resume",JSONObject())
        assertEquals("screenshot",restored.poll().getJSONObject("command").getString("kind"))
    }

    @Test fun permissionOrCancelledCaptureNeverUsesLocalRetryOrModel() {
        for(pair in listOf("blocked" to false,"cancelled" to false,"error" to true)) {
            val e=engine();val id=create(e);val command=e.poll().getJSONObject("command")
            e.result(failure(command,pair.first,pair.second))
            assertEquals("paused",e.get(id).getString("status"));assertEquals(0,e.get(id).getInt("calls"))
            assertTrue(e.poll().isNull("command"));assertNull(e.takeWork());assertFalse(e.get(id).has("capture_retry"))
        }
    }

    @Test fun captureFailureAfterAcceptedActionRetriesOnlyTheScreenshot() {
        val e=engine();val id=create(e);e.result(success(e.poll().getJSONObject("command")))
        e.accept(e.takeWork()!!,SplitTestReply.response(JSONObject().put("kind","execute").put("action","tap")
            .put("target","当前页面的入口").put("expected","打开下一页")))
        e.result(success(e.poll().getJSONObject("command")))
        e.accept(e.takeWork()!!,SplitTestReply.response(JSONObject("""{"status":"located","action":"tap","points":[[400,500]],"assessment":{"alignment":"consistent"}}""")))
        val action=e.poll().getJSONObject("command");assertEquals("split_action",action.getString("kind"))
        e.result(JSONObject().put("run_id",id).put("command_id",action.getString("id")).put("status","ok")
            .put("data",JSONObject().put("completed_strokes",1)))
        e.result(failure(e.poll().getJSONObject("command")));assertNull(e.takeWork())
        clock+=250
        assertEquals("screenshot",e.poll().getJSONObject("command").getString("kind"))
        assertEquals(2,e.get(id).getInt("calls"))
        assertEquals(action.getString("id"),e.get(id).getJSONObject("last_receipt").getString("command_id"))
    }
}

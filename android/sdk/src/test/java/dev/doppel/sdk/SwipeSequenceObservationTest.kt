package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SwipeSequenceObservationTest {
    private fun response(value:JSONObject)=SplitTestReply.response(value)
    private fun screen(e:SplitTaskEngine,name:String,pkg:String="game"):JSONObject {
        val c=e.poll().getJSONObject("command");assertEquals("screenshot",c.getString("kind"))
        e.result(JSONObject().put("run_id",c.getString("run_id")).put("command_id",c.getString("id")).put("status","ok")
            .put("observation",JSONObject().put("package_name",pkg)).put("data",JSONObject().put("image_base64",name)
                .put("visual_frame",JSONObject().put("capture_id",name).put("package_name",pkg)
                    .put("display_width",3200).put("display_height",1440).put("rotation",1))))
        return c
    }
    private fun begin(clock:()->Long={1000L},interval:Int=0):Pair<SplitTaskEngine,String> {
        val e=SplitTaskEngine(null,{},clock,enhancementEnabled={false})
        val id=e.create(JSONObject().put("goal","滑动查找目标").put("mode","full").put("device_id","direct-this-phone")).getString("id")
        screen(e,"before")
        e.accept(e.takeWork()!!,response(JSONObject("""{"kind":"execute","action":"swipe_sequence","target":"列表区域","expected":"露出左侧条目","swipe_extent":"small","gesture_contracts":[{"gesture_semantics":"reveal_content","target_relative_direction":"left","intended_finger_direction":"right"},{"gesture_semantics":"reveal_content","target_relative_direction":"left","intended_finger_direction":"right"}],"strokes":[{"points":[[300,500],[400,500]],"duration_ms":600},{"points":[[300,500],[450,500]],"duration_ms":700}]}""").put("interval_ms",interval)))
        return e to id
    }
    private fun performed(e:SplitTaskEngine,status:String="ok"):JSONObject {
        val c=e.poll().getJSONObject("command");assertEquals("split_action",c.getString("kind"))
        assertEquals(1,c.getJSONObject("action").getJSONArray("strokes").length())
        e.result(JSONObject().put("run_id",c.getString("run_id")).put("command_id",c.getString("id")).put("status",status)
            .put("data",JSONObject().put("completed_strokes",if(status=="ok") 1 else 0).put("action_state",if(status=="ok") "accepted" else "not_dispatched")
                .put("sequence_navigation",JSONObject().put("generation",12).put("window_id",5))))
        return c
    }
    @Test fun capturesEachStrokeBeforeNextAndShowsBothResultsToAInOrder() {
        val (e,id)=begin()
        val first=performed(e);assertEquals(0,first.getInt("stroke_index"));assertNull(e.takeWork())
        val shot1=screen(e,"after-first")
        assertEquals("swipe_step",shot1.getString("capture_purpose"))
        assertEquals(0,shot1.getInt("stroke_index"));assertNull(e.takeWork())
        val second=performed(e);assertEquals(1,second.getInt("stroke_index"))
        assertEquals(12,second.getJSONObject("sequence_navigation").getInt("generation"))
        assertEquals("after-first",second.getJSONObject("source").getString("capture_id"))
        assertEquals(450.0,second.getJSONObject("action").getJSONArray("strokes").getJSONObject(0).getJSONArray("points").getJSONArray(1).getDouble(0),0.0)
        screen(e,"after-second")
        val a=e.takeWork()!!;assertFalse(a.grounding)
        val messages=a.payload.getJSONArray("messages").toString()
        assertTrue(messages.indexOf("after-first") < messages.indexOf("after-second"))
        assertTrue(messages.contains("第 1 段滑动后的结果"));assertTrue(messages.contains("第 2 段滑动后的结果"))
        assertTrue(messages.contains("当前最新截图"))
        assertEquals(2,e.get(id).getInt("calls")) // one planning decision, then one review; no model calls between strokes
    }
    @Test fun cancellationWhileCapturingNeverDispatchesRemainingStroke() {
        val (e,id)=begin();performed(e)
        val c=e.poll().getJSONObject("command");e.control(id,"cancel",JSONObject())
        val result=e.result(JSONObject().put("run_id",id).put("command_id",c.getString("id")).put("status","ok")
            .put("data",JSONObject().put("image_base64","late").put("visual_frame",JSONObject())))
        assertFalse(result.getBoolean("accepted"));assertTrue(e.poll().isNull("command"));assertNull(e.takeWork())
    }
    @Test fun screenshotTimeDoesNotShortenRequestedMinimumInterval() {
        var time=1000L
        val (e,_)=begin({time},1000)
        performed(e);screen(e,"first")
        assertTrue(e.poll().isNull("command"));assertNull(e.takeWork())
        time=2000L
        val next=e.poll().getJSONObject("command")
        assertEquals(1,next.getInt("stroke_index"))
        assertEquals("before",next.getString("grounding_capture_id"))
        assertEquals("first",next.getJSONObject("source").getString("capture_id"))
    }
    @Test fun failedStrokeReturnsToAAfterFreshCaptureWithoutReplayingRemainder() {
        val (e,_)=begin();performed(e,"error");screen(e,"after-failure")
        assertTrue(e.poll().isNull("command"));assertNotNull(e.takeWork())
    }
    @Test fun changedApplicationAfterFirstStrokeStopsRemainingSequence() {
        val (e,_)=begin();performed(e);screen(e,"new-app","incoming.app")
        assertTrue(e.poll().isNull("command"));assertNotNull(e.takeWork())
    }

    @Test fun blackFrameAfterFirstStrokeWaitsForAWithoutContinuingTheUnconfirmedSequence() {
        var time=1000L
        val (e,id)=begin({time});performed(e)
        val c=e.poll().getJSONObject("command")
        e.result(JSONObject().put("run_id",id).put("command_id",c.getString("id")).put("status","error")
            .put("data",JSONObject().put("reason_code","capture_empty_frame")))
        assertNull(e.takeWork());assertEquals(1,e.get(id).getInt("calls"))
        time+=250;screen(e,"visible-after-black")
        assertTrue(e.poll().isNull("command"))
        assertFalse(e.takeWork()!!.grounding)
        assertEquals(2,e.get(id).getInt("calls"))
    }

    @Test fun eachReceiptKeepsOnlyItsOwnCorrectionExtentAndAssessment() {
        val e=SplitTaskEngine(null,{},{1000L},enhancementEnabled={true})
        val id=e.create(JSONObject().put("goal","小幅右滑查找条目").put("mode","full")
            .put("device_id","direct-this-phone")).getString("id")
        screen(e,"before")
        e.accept(e.takeWork()!!,response(JSONObject("""{"kind":"execute","action":"swipe_sequence","target":"列表背景小幅滑动两次","expected":"露出左侧条目","swipe_extent":"small","gesture_contracts":[{"gesture_semantics":"reveal_content","target_relative_direction":"left","intended_finger_direction":"left"},{"gesture_semantics":"reveal_content","target_relative_direction":"left","intended_finger_direction":"right"}]}""")))
        screen(e,"grounding")
        e.accept(e.takeWork()!!,response(JSONObject("""{"status":"located","action":"swipe_sequence","strokes":[{"points":[[300,500],[400,500]],"duration_ms":600},{"points":[[300,500],[450,500]],"duration_ms":700}],"interval_ms":0,"assessment":{"alignment":"consistent","gesture_contracts":[{"target_relative_direction":"left","required_finger_direction":"right","direction_corrected":true},{"target_relative_direction":"left","required_finger_direction":"right","direction_corrected":false}]}}""")))
        performed(e)
        val first=e.get(id).getJSONObject("last_receipt")
        val firstContract=first.getJSONObject("executed_action").getJSONObject("direction_contract")
        assertTrue(firstContract.getBoolean("direction_corrected"))
        assertEquals(1,firstContract.getJSONArray("direction_corrections").length())
        assertEquals(0,firstContract.getJSONArray("direction_corrections").getJSONObject(0).getInt("stroke_index"))
        assertEquals(1,firstContract.getJSONObject("extent_validation").getJSONArray("strokes").length())
        assertEquals(0,firstContract.getJSONObject("extent_validation").getJSONArray("strokes").getJSONObject(0).getInt("stroke_index"))
        val firstAssessment=first.getJSONObject("grounding_assessment").getJSONArray("gesture_contracts")
        assertEquals(1,firstAssessment.length());assertTrue(firstAssessment.getJSONObject(0).getBoolean("direction_corrected"))
        screen(e,"after-first")
        performed(e)
        val second=e.get(id).getJSONObject("last_receipt")
        assertEquals(1,second.getInt("stroke_index"))
        val secondContract=second.getJSONObject("executed_action").getJSONObject("direction_contract")
        assertFalse(secondContract.getBoolean("direction_corrected"))
        assertEquals(0,secondContract.getJSONArray("direction_corrections").length())
        val secondExtent=secondContract.getJSONObject("extent_validation").getJSONArray("strokes")
        assertEquals(1,secondExtent.length());assertEquals(1,secondExtent.getJSONObject(0).getInt("stroke_index"))
        assertEquals(700,secondExtent.getJSONObject(0).getInt("duration_ms"))
        val secondAssessment=second.getJSONObject("grounding_assessment").getJSONArray("gesture_contracts")
        assertEquals(1,secondAssessment.length());assertFalse(secondAssessment.getJSONObject(0).getBoolean("direction_corrected"))
        assertEquals(1,secondAssessment.getJSONObject(0).getInt("stroke_index"))
        screen(e,"after-second")
        val context=e.takeWork()!!.payload.getJSONArray("messages")
        val receipt=JSONObject(context.getJSONObject(context.length()-1).getJSONArray("content").getJSONObject(0)
            .getString("text")).getJSONObject("last_receipt")
        assertFalse(receipt.getJSONObject("executed_action").getJSONObject("direction_contract").getBoolean("direction_corrected"))
    }

    @Test fun directSequenceExtentEvidenceKeepsGlobalIndexForCurrentStroke() {
        val (e,id)=begin();performed(e);screen(e,"after-first");performed(e)
        val receipt=e.get(id).getJSONObject("last_receipt")
        val extent=receipt.getJSONObject("executed_action").getJSONObject("direction_contract")
            .getJSONObject("coordinate_validation").getJSONObject("extent_validation").getJSONArray("strokes")
        assertEquals(1,extent.length());assertEquals(1,extent.getJSONObject(0).getInt("stroke_index"))
        assertEquals(700,extent.getJSONObject(0).getInt("duration_ms"))
    }
}

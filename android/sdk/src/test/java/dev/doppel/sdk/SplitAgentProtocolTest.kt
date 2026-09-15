package dev.doppel.sdk
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class SplitAgentProtocolTest {
    @Test fun explicitSwipeExtentReachesGrounderForSingleAndContinuousSwipes() {
        for (action in listOf("swipe", "swipe_sequence")) {
            for (extent in listOf("small", "large")) {
                val intent = swipeIntent(action).put("swipe_extent", extent)
                if(extent=="large") intent.put("scroll_goal","boundary").put("boundary_reason","直接去起点")
                val request = SplitAgentProtocol.grounder("pixels", intent)
                val instruction = JSONObject(request.getJSONArray("messages").getJSONObject(1)
                    .getJSONArray("content").getJSONObject(0).getString("text"))
                assertEquals(extent, instruction.getString("swipe_extent"))
                assertEquals(action, instruction.getString("action"))
            }
        }
    }

    @Test fun omittedSwipeExtentBecomesExplicitSmallInstruction() {
        val destination = JSONObject().put("target", "章节列表")
        SplitAgentProtocol.copySwipeExtent(JSONObject(), destination)
        assertEquals("small", destination.getString("swipe_extent"))
        assertTrue(destination.getString("target").contains("小幅"))
        for (action in listOf("swipe", "swipe_sequence")) {
            val request = SplitAgentProtocol.grounder("pixels", swipeIntent(action))
            val instruction = JSONObject(request.getJSONArray("messages").getJSONObject(1)
                .getJSONArray("content").getJSONObject(0).getString("text"))
            assertEquals("small",instruction.getString("swipe_extent"))
            assertTrue(instruction.getString("target").contains("小幅"))
        }
    }

    @Test fun copiedSwipeExtentPreservesPlannerAndExecutorCoordinates() {
        val from = swipeIntent("swipe").put("swipe_extent", "small")
        val before = from.toString()
        val action = SplitAgentProtocol.grounding(JSONObject("""{"status":"located","action":"swipe","points":[[410,450],[530,450]],"duration_ms":280}"""), "swipe")
        val strokesBefore = action.getJSONArray("strokes").toString()
        SplitAgentProtocol.copySwipeExtent(from, action)
        assertEquals("small", action.getString("swipe_extent"))
        assertEquals(strokesBefore, action.getJSONArray("strokes").toString())
        assertEquals(before, from.toString())
    }

    @Test fun invalidSwipeExtentIsRejectedBeforeRequestConstruction() {
        for (value in listOf<Any>("medium", "tiny", "Small", "", 20, true, JSONObject.NULL)) {
            val from = swipeIntent("swipe").put("swipe_extent", value)
            val destination = JSONObject().put("target", "章节列表")
            try {
                SplitAgentProtocol.copySwipeExtent(from, destination)
                fail("invalid swipe_extent accepted: $value")
            } catch (_: IllegalArgumentException) { }
            assertFalse(destination.has("swipe_extent"))
            try {
                SplitAgentProtocol.grounder("pixels", from)
                fail("invalid swipe_extent sent to B: $value")
            } catch (_: IllegalArgumentException) { }
        }
    }

    private fun swipeIntent(action: String): JSONObject {
        val intent = JSONObject("""{"action":"swipe","target":"章节列表空白处","expected":"显示左侧更早章节","gesture_semantics":"reveal_content","target_relative_direction":"left","intended_finger_direction":"right"}""")
        if (action == "swipe_sequence") {
            intent.put("action", action).put("gesture_contracts", org.json.JSONArray().put(
                JSONObject("""{"gesture_semantics":"reveal_content","target_relative_direction":"left","intended_finger_direction":"right"}""")))
        }
        return intent
    }

    @Test fun explicitSwipeRefusalPreservesReasonWithoutLocatedOnlyDirectionFields() {
        val value=JSONObject("""{"status":"intent_mismatch","reason":"expected 与目标方位相反","assessment":{"alignment":"inconsistent","observed":"较早节点在左侧","reason":"expected 却要求露出右侧内容"}}""")
        val result=SplitAgentProtocol.reviewedGrounding(value,"swipe",JSONObject())
        assertEquals("intent_mismatch",result.getString("status"))
        assertEquals("expected 与目标方位相反",result.getString("reason"))
        assertFalse(result.has("strokes"))
        assertTrue(result.getJSONObject("assessment").getString("observed").contains("左侧"))
    }

    @Test fun grounderReceivesPlannerDirectionContractWithoutDeviceMetadata() {
        val intent=JSONObject("""{"action":"swipe","target":"露出左侧第一章","expected":"看到更早章节","gesture_semantics":"reveal_content","target_relative_direction":"left","intended_finger_direction":"right"}""")
        val payload=SplitAgentProtocol.grounder("pixels",intent)
        val content=payload.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        val instruction=JSONObject(content.getJSONObject(0).getString("text"))
        assertEquals("reveal_content",instruction.getString("gesture_semantics"))
        assertEquals("left",instruction.getString("target_relative_direction"))
        assertEquals("right",instruction.getString("intended_finger_direction"))
        assertFalse(instruction.has("required_output"))
        assertFalse(instruction.has("output_note"))
        assertEquals("json_schema",payload.getJSONObject("response_format").getString("type"))
        assertFalse(instruction.has("package_name"))
    }

    @Test fun grounderGetsIndependentVisualDirectionRulesWithoutSharedPlannerPrompt() {
        val input=swipeIntent("swipe").put("target","列表区域，从左向右寻找后面的条目")
        val original=input.toString()
        val request=SplitAgentProtocol.grounder("current-pixels",input)
        val system=request.getJSONArray("messages").getJSONObject(0).getString("content")
        assertFalse(system.contains(SwipeDirectionPrompt.SEMANTICS))
        assertFalse(system.contains(SwipeDirectionPrompt.COORDINATES))
        assertTrue(system.indexOf("当前可见项目的排列顺序") < system.indexOf("最后才与 A 的候选方向比较"))
        assertTrue(system.contains("两字段可能一起错"))
        assertTrue(system.contains("target 文本里的手指描述都只是候选"))
        assertTrue(system.contains("保持所找目标不变"))
        assertEquals(original,input.toString())
        val content=request.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertTrue(content.getJSONObject(1).getJSONObject("image_url").getString("url").endsWith("current-pixels"))
        val supplied=JSONObject(content.getJSONObject(0).getString("text"))
        assertEquals("left",supplied.getString("target_relative_direction"))
        assertEquals("right",supplied.getString("intended_finger_direction"))
    }

    @Test fun doubleTapSupportsOneOrTwoLocations() {
        val same=SplitAgentProtocol.grounding(JSONObject("""{"status":"located","action":"double_tap","points":[[500,600]],"interval_ms":120}"""),"double_tap")
        assertEquals(1,same.getJSONArray("points").length())
        val different=SplitAgentProtocol.grounding(JSONObject("""{"status":"located","action":"double_tap","points":[[500,600],[700,300]],"interval_ms":120}"""),"double_tap")
        assertEquals(2,different.getJSONArray("points").length())
    }
    @Test fun continuousSwipesKeepEveryWaypoint() {
        val a=SplitAgentProtocol.grounding(JSONObject("""{"status":"located","action":"swipe_sequence","strokes":[{"points":[[500,800],[400,500],[500,200]],"duration_ms":400},{"points":[[500,800],[500,200]],"duration_ms":300}]}"""),"swipe_sequence")
        assertEquals(3,a.getJSONArray("strokes").getJSONObject(0).getJSONArray("points").length())
    }
    @Test fun invalidCoordinatesNeverReachExecutor() {
        for (value in listOf(-1,1001)) {
            try { SplitAgentProtocol.grounding(JSONObject("""{"status":"located","action":"tap","points":[[$value,600]]}"""),"tap");fail("out of bounds accepted") } catch (_:IllegalArgumentException) {}
        }
    }

    @Test fun successfulGrounderDoesNotNeedExplanatoryText() {
        val result=SplitAgentProtocol.reviewedGrounding(JSONObject("""{"status":"located","action":"tap","points":[[400,500]],"assessment":{"alignment":"consistent"}}"""),"tap",JSONObject())
        assertEquals("located",result.getString("status"))
        assertFalse(result.getJSONObject("assessment").has("observed"))
        assertFalse(result.getJSONObject("assessment").has("reason"))
    }

    @Test fun compactGrounderCorrectionExecutesAndReportsEffectiveContract() {
        val planner=swipeIntent("swipe").put("intended_finger_direction","left")
        val value=JSONObject("""{"status":"located","action":"swipe","points":[[400,500],[500,500]],"duration_ms":800,"assessment":{"alignment":"consistent","target_relative_direction":"left","required_finger_direction":"right","direction_corrected":true}}""")
        val result=SplitAgentProtocol.reviewedGrounding(value,"swipe",planner,3200,1440)
        assertEquals("located",result.getString("status"))
        val contract=result.getJSONObject("direction_contract")
        assertTrue(contract.getBoolean("direction_corrected"))
        assertEquals("right",contract.getJSONArray("effective").getJSONObject(0).getString("intended_finger_direction"))
        assertEquals("left",contract.getJSONArray("planner").getJSONObject(0).getString("intended_finger_direction"))
        assertEquals(500.0,result.getJSONArray("strokes").getJSONObject(0).getJSONArray("points").getJSONArray(1).getDouble(0),0.0)
    }

    @Test fun defaultSmallDistanceCannotBeBypassedByCorrectDirection() {
        val value=JSONObject("""{"status":"located","action":"swipe","points":[[200,500],[800,500]],"duration_ms":800,"assessment":{"alignment":"consistent","target_relative_direction":"left","required_finger_direction":"right","direction_corrected":false}}""")
        val result=SplitAgentProtocol.reviewedGrounding(value,"swipe",swipeIntent("swipe"))
        assertEquals("intent_mismatch",result.getString("status"))
        assertEquals("swipe_extent_exceeded",result.getString("reason_code"))
        assertFalse(result.has("strokes"))
    }

    @Test fun physicalDragIsNotLimitedByBrowsingDefault() {
        val planner=swipeIntent("swipe").put("gesture_semantics","physical_gesture")
        val value=JSONObject("""{"status":"located","action":"swipe","points":[[200,500],[800,500]],"duration_ms":800,"assessment":{"alignment":"consistent","target_relative_direction":"unknown","required_finger_direction":"right","direction_corrected":false}}""")
        val result=SplitAgentProtocol.reviewedGrounding(value,"swipe",planner)
        assertEquals("located",result.getString("status"))
        assertFalse(result.has("swipe_extent"))
    }

    @Test fun objectDragThenFacingRetainsCoordinatesAndRecordsOnlyTheEndpointRefinement() {
        val planner = JSONObject("""{"action":"swipe_sequence","target":"从右下方卡片拖到中央偏左的槽位，再从落点向右选择朝向","expected":"卡片放入槽位并朝向右侧",
            "gesture_contracts":[{"gesture_semantics":"object_drag","target_relative_direction":"unknown","intended_finger_direction":"up"},
                {"gesture_semantics":"physical_gesture","target_relative_direction":"unknown","intended_finger_direction":"right"}]}""")
        val raw = """{"result":{"status":"located","action":"swipe_sequence","strokes":[
            {"points":[[850,850],[400,350]],"duration_ms":900},{"points":[[400,350],[600,350]],"duration_ms":400}],"interval_ms":100,
            "assessment":{"alignment":"consistent","gesture_contracts":[
                {"target_relative_direction":"up_left","required_finger_direction":"up_left","direction_corrected":true},
                {"target_relative_direction":"right","required_finger_direction":"right","direction_corrected":false}]}}}"""
        val parsed = SplitAgentProtocol.content(response(raw), SplitOutputSchema.format("grounding", expectedAction = "swipe_sequence"), "grounding")
        val original = parsed.toString()
        val result = SplitAgentProtocol.reviewedGrounding(parsed, "swipe_sequence", planner, 1920, 1080)
        assertEquals("located", result.getString("status"))
        assertEquals(parsed.getJSONArray("strokes").toString(), result.getJSONArray("strokes").toString())
        assertFalse(result.has("swipe_extent"))
        val contract = result.getJSONObject("direction_contract")
        assertTrue(contract.getBoolean("direction_corrected"))
        assertEquals(1, contract.getJSONArray("direction_corrections").length())
        assertEquals("object_drag", contract.getJSONArray("effective").getJSONObject(0).getString("gesture_semantics"))
        assertEquals("up_left", contract.getJSONArray("effective").getJSONObject(0).getString("intended_finger_direction"))
        assertEquals("right", contract.getJSONArray("effective").getJSONObject(1).getString("intended_finger_direction"))
        assertEquals(original, parsed.toString())
        // Declaring a corrected direction cannot authorize contradictory actual coordinates.
        parsed.getJSONArray("strokes").getJSONObject(0).put("points", org.json.JSONArray("[[850,850],[850,350]]"))
        val mismatch = SplitAgentProtocol.reviewedGrounding(parsed, "swipe_sequence", planner, 1920, 1080)
        assertEquals("gesture_coordinate_mismatch", mismatch.getString("reason_code"))
        assertFalse(mismatch.has("strokes"))
        val request = SplitAgentProtocol.grounder("pixels", planner)
        val content = request.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        val instruction = JSONObject(content.getJSONObject(0).getString("text"))
        assertEquals(planner.getString("target"), instruction.getString("target"))
        assertEquals(planner.getJSONArray("gesture_contracts").toString(), instruction.getJSONArray("gesture_contracts").toString())
    }

    @Test fun requestUsesStrictActionSchemaAndDoesNotMutateInputMessages() {
        val messages=org.json.JSONArray().put(JSONObject().put("role","system").put("content","原始规则"))
        val request=SplitAgentProtocol.request("grounding",messages,expectedAction="tap")
        assertEquals("原始规则",messages.getJSONObject(0).getString("content"))
        assertEquals("json_schema",request.getJSONObject("response_format").getString("type"))
        assertTrue(request.getJSONObject("response_format").getJSONObject("json_schema").getBoolean("strict"))
        assertTrue(request.getJSONArray("messages").getJSONObject(0).getString("content").contains("result"))
    }

    @Test fun primaryRequestHintUsesSingleV3DiscriminatorInBothModes() {
        for(direct in listOf(false,true)) {
            val request=SplitAgentProtocol.request("primary",org.json.JSONArray(),direct=direct)
            val hint=request.getJSONArray("messages").getJSONObject(0).getString("content")
            assertTrue(hint.contains("decision.kind 直接选择 tap/swipe"))
            assertTrue(hint.contains("不输出 action 字段，不使用 execute"))
            assertFalse(hint.contains("kind、action"))
            assertTrue(request.getJSONObject("response_format").getJSONObject("json_schema").getString("name").endsWith("_v3"))
        }
    }

    @Test fun contentValidatesExactRequestSchemaBeforeUnwrapping() {
        val format=SplitOutputSchema.format("grounding",expectedAction="tap")
        val raw="""{"result":{"status":"located","action":"tap","points":[[400,500]],"duration_ms":60,"assessment":{"alignment":"consistent"}}}"""
        val content=SplitAgentProtocol.content(response(raw),format,"grounding")
        assertEquals("tap",content.getString("action"))
        assertFalse(content.has("result"))
        for (invalid in listOf(raw.replace("\"duration_ms\":60,",""),raw.replace("\"action\":\"tap\"","\"action\":\"home\""))) {
            try { SplitAgentProtocol.content(response(invalid),format,"grounding");fail("invalid schema response accepted") }
            catch (_:IllegalArgumentException) { }
        }
    }

    private fun response(content:String)=JSONObject().put("choices",org.json.JSONArray().put(JSONObject()
        .put("finish_reason","stop").put("message",JSONObject().put("content",content))))
}

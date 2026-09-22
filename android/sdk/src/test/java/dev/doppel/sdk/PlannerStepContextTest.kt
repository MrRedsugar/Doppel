package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlannerStepContextTest {
    @Test fun latestStepIsSentOnceButAnUnexecutedNewIntentKeepsPriorEvidence() {
        val intent = JSONObject().put("action", "tap").put("target", "全部订单")
        val receipt = JSONObject().put("command_id", "executed-1").put("status", "ok")
        val rows = JSONArray().put(JSONObject().put("intent", intent).put("receipt", receipt))
        val archived = rows.toString()
        val latest = JSONObject(receipt.toString()).put("launch_verified", true)
        assertEquals(0, PlannerStepContext.steps(rows, intent, latest).length())
        val unexecuted = JSONObject().put("action", "tap").put("target", "订单详情")
        assertEquals(1, PlannerStepContext.steps(rows, unexecuted, latest).length())
        assertEquals(1, PlannerStepContext.steps(rows, intent, JSONObject().put("command_id", "read-2")).length())
        assertEquals(archived, rows.toString())
    }
    @Test fun historyRetainsRecoveryEvidenceAndDoesNotRewriteArchive() {
        val receipt = JSONObject("""{
            "action":"swipe_sequence","status":"error","message":"第二段未完成",
            "command_id":"transport-id","sequence_id":"sequence-id","sequence":8,
            "source_capture_id":"frame-id","grounding_capture_id":"other-frame",
            "stroke_index":1,"stroke_count":3,"completed_strokes":0,"unconfirmed_strokes":1,
            "reason_code":"gesture_cancelled","elapsed_ms":280,
            "touch_handoff":{"timings_ms":{"main_entered":3}},"guard_handoff":{"timings_ms":{"main_entered":1}},
            "grounding_assessment":{"phase":"before_action","consistent":true},
            "executed_action":{"action":"swipe_sequence","target":"列表","interval_ms":90,
              "strokes":[{"points":[[500,600],[500,450]],"duration_ms":350}],
              "direction_contract":{"coordinate_validation":{"consistent":true}},
              "extent_validation":{"maximum":250}}
        }""")
        val intent = JSONObject("""{"action":"swipe_sequence","target":"小幅浏览下方条目","expected":"下一项出现","intended_finger_direction":"up"}""")
        val original = JSONArray().put(JSONObject().put("intent", intent).put("receipt", receipt))
        val archived = original.toString()
        val projected = PlannerStepContext.steps(original)
        val actual = projected.getJSONObject(0).getJSONObject("receipt")
        assertEquals(archived, original.toString())
        assertEquals(intent.toString(), projected.getJSONObject(0).getJSONObject("intent").toString())
        for (key in listOf("status", "message", "stroke_index", "stroke_count", "completed_strokes", "unconfirmed_strokes", "reason_code", "elapsed_ms")) {
            assertEquals(receipt.get(key), actual.get(key))
        }
        assertEquals(receipt.getJSONObject("grounding_assessment").toString(), actual.getJSONObject("grounding_assessment").toString())
        assertEquals(receipt.getJSONObject("executed_action").getJSONArray("strokes").toString(), actual.getJSONObject("executed_action").getJSONArray("strokes").toString())
        assertFalse(actual.has("command_id"))
        assertFalse(actual.has("touch_handoff")); assertFalse(actual.has("guard_handoff"))
        assertFalse(actual.getJSONObject("executed_action").has("direction_contract"))
        assertTrue(projected.toString().length < archived.length)
    }

    @Test fun projectsOnlyLatestSixCompleteStepsWithoutChangingArchiveOrDurableState() {
        val values = JSONArray()
        repeat(24) { index ->
            val stroke = JSONObject().put("points", JSONArray("[[$index,500],[${index + 100},500]]")).put("duration_ms", 800)
            val contract = JSONObject("""{"direction_corrected":true,
                "effective":[{"target_relative_direction":"left","intended_finger_direction":"right"}],
                "direction_corrections":[{"stroke_index":0,"required":"right"}]}""")
            val action = JSONObject().put("action", "swipe").put("strokes", JSONArray().put(stroke)).put("direction_contract", contract)
            val receipt = JSONObject().put("status", "ok").put("message", "结果$index")
                .put("future_semantic_fact", "保留").put("executed_action", action)
            values.put(JSONObject().put("intent", JSONObject().put("target", "目标$index")).put("receipt", receipt))
        }
        val run = JSONObject().put("recent_steps", values)
            .put("task_state", JSONObject().put("facts", JSONArray().put("已确认页面结构"))
                .put("failed_routes", JSONArray().put("早于最近六步的失败入口")))
            .put("events", JSONArray().put(JSONObject().put("message", "原始事件")))
        val archived = run.toString()
        val output = PlannerStepContext.steps(run.getJSONArray("recent_steps"))
        assertEquals(6, output.length())
        repeat(6) { offset ->
            val index = offset + 18
            val projected = output.getJSONObject(offset)
            assertEquals("目标$index", projected.getJSONObject("intent").getString("target"))
            val receipt = projected.getJSONObject("receipt")
            assertEquals("结果$index", receipt.getString("message"))
            assertEquals("保留", receipt.getString("future_semantic_fact"))
            assertEquals(values.getJSONObject(index).getJSONObject("receipt").getJSONObject("executed_action").toString(),
                receipt.getJSONObject("executed_action").toString())
        }
        assertEquals(archived, run.toString())
        assertEquals(24, run.getJSONArray("recent_steps").length())
        assertEquals("早于最近六步的失败入口", run.getJSONObject("task_state").getJSONArray("failed_routes").getString(0))
        // Returned steps must not share mutable intent, points or correction objects with the archive.
        output.getJSONObject(0).getJSONObject("intent").put("target", "changed")
        output.getJSONObject(0).getJSONObject("receipt").getJSONObject("executed_action")
            .getJSONArray("strokes").getJSONObject(0).getJSONArray("points").getJSONArray(0).put(0, 999)
        output.getJSONObject(0).getJSONObject("receipt").getJSONObject("executed_action")
            .getJSONObject("direction_contract").put("direction_corrected", false)
        assertEquals(archived, run.toString())
    }

    @Test fun emptyAndShortHistoriesKeepAllAvailableStepsInOriginalOrder() {
        for (count in 0..6) {
            val values = JSONArray()
            repeat(count) { values.put(JSONObject().put("intent", JSONObject().put("target", "目标$it"))) }
            val output = PlannerStepContext.steps(values)
            assertEquals(count, output.length())
            repeat(count) { assertEquals("目标$it", output.getJSONObject(it).getJSONObject("intent").getString("target")) }
        }
    }

    @Test fun retainsGroundersDirectionCorrectionForFuturePlanning() {
        val original = JSONObject("""{"executed_action":{"action":"swipe","direction_contract":{
            "planner":[{"target_relative_direction":"left"}],
            "effective":[{"target_relative_direction":"right","intended_finger_direction":"left"}],
            "direction_corrected":true,"direction_corrections":[{"stroke_index":0,"required":"left"}],
            "coordinate_validation":{"abs_dx":200},"extent_validation":{"maximum":250}
        }}}""")
        val projected = PlannerStepContext.receipt(original).getJSONObject("executed_action").getJSONObject("direction_contract")
        assertTrue(projected.getBoolean("direction_corrected"))
        assertEquals("right", projected.getJSONArray("effective").getJSONObject(0).getString("target_relative_direction"))
        assertEquals("left", projected.getJSONArray("direction_corrections").getJSONObject(0).getString("required"))
        assertFalse(projected.has("coordinate_validation"))
        assertTrue(original.getJSONObject("executed_action").getJSONObject("direction_contract").has("coordinate_validation"))
    }
}

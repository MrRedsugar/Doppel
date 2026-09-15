package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SwipeMotionEvidenceTest {
    private fun stroke(points: String, duration: Int = 350) = JSONObject()
        .put("points", JSONArray(points)).put("duration_ms", duration)

    private fun step(action: String, strokes: JSONArray, status: String = "ok", directions: JSONArray? = null): JSONObject {
        val executed = JSONObject().put("action", action).put("strokes", strokes)
            .put("target", "私人任务内容不可复制")
        directions?.let {
            executed.put("direction_contract", JSONObject().put("coordinate_validation",
                JSONObject().put("actual_finger_directions", it)))
        }
        return JSONObject().put("intent", JSONObject().put("target", "未执行的想法不可使用"))
            .put("receipt", JSONObject().put("action", action).put("status", status).put("executed_action", executed))
    }

    private fun run(vararg steps: JSONObject) = JSONObject().put("recent_steps", JSONArray(steps.toList()))

    @Test fun onlySuccessfulExecutedSwipesContributeMotionEvidence() {
        val horizontal = JSONArray().put(stroke("[[800,510],[550,480],[200,500]]", 700))
        val successful = step("swipe", horizontal, directions = JSONArray().put("left"))
        val waiting = step("swipe", horizontal).apply { getJSONObject("receipt").put("action", "wait") }
        val failed = step("swipe", horizontal, status = "failed")
        val stale = step("swipe", horizontal, status = "stale")
        val mismatch = step("swipe", horizontal).apply {
            getJSONObject("receipt").getJSONObject("executed_action").put("action", "tap")
        }
        val source = run(waiting, successful, failed, stale, mismatch)
        val before = source.toString()
        val evidence = SwipeMotionEvidence.recent(source)

        assertEquals(1, evidence.length())
        val row = evidence.getJSONObject(0)
        assertEquals("swipe", row.getString("action"))
        assertEquals(0, row.getInt("stroke_index"))
        assertEquals(800.0, row.getJSONArray("start").getDouble(0), 0.0)
        assertEquals(200.0, row.getJSONArray("end").getDouble(0), 0.0)
        assertEquals(600.0, row.getDouble("abs_dx"), 0.0)
        assertEquals(10.0, row.getDouble("abs_dy"), 0.0)
        assertEquals(700, row.getInt("duration_ms"))
        assertEquals("left", row.getString("actual_finger_direction"))
        assertFalse(evidence.toString().contains("私人"))
        assertFalse(evidence.toString().contains("未执行"))
        assertFalse(row.has("target"))
        assertEquals(before, source.toString())
    }

    @Test fun lastFourTrajectoriesPreserveSequenceOrderAndPerStrokeDirection() {
        val earlier = step("swipe", JSONArray().put(stroke("[[200,500],[300,500]]")))
        val sequence = step("swipe_sequence", JSONArray()
            .put(stroke("[[500,800],[500,200]]", 200))
            .put(stroke("[[200,500],[800,500]]", 400))
            .put(stroke("[[800,500],[200,500]]", 600)),
            directions = JSONArray().put("up").put("right").put("left"))
        val latest = step("swipe", JSONArray().put(stroke("[[500,200],[500,700]]", 800)),
            directions = JSONArray().put("down"))

        val evidence = SwipeMotionEvidence.recent(run(earlier, sequence, latest))
        assertEquals(4, evidence.length())
        for ((index, direction) in listOf("up", "right", "left", "down").withIndex()) {
            val row = evidence.getJSONObject(index)
            assertEquals(direction, row.getString("actual_finger_direction"))
            assertEquals((index + 1) * 200, row.getInt("duration_ms"))
            assertEquals(if (index < 3) "swipe_sequence" else "swipe", row.getString("action"))
            assertEquals(if (index < 3) index else 0, row.getInt("stroke_index"))
        }
    }

    @Test fun sequenceLongerThanLimitRetainsItsLastFourActualStrokes() {
        val strokes = JSONArray()
        repeat(6) { strokes.put(stroke("[[100,500],[${200 + it * 100},500]]")) }
        val evidence = SwipeMotionEvidence.recent(run(step("swipe_sequence", strokes)))
        assertEquals(4, evidence.length())
        assertEquals(2, evidence.getJSONObject(0).getInt("stroke_index"))
        assertEquals(5, evidence.getJSONObject(3).getInt("stroke_index"))
    }

    @Test fun splitSequenceRetainsGlobalStrokeIndexAndExistingSequenceIdentity() {
        val sequenceId = "ca5a2f50-5f32-4ce1-af3d-c7719318bb5b"
        val first = step("swipe_sequence", JSONArray().put(stroke("[[100,500],[300,500]]")))
        val second = step("swipe_sequence", JSONArray().put(stroke("[[100,500],[500,500]]")))
        first.getJSONObject("receipt").put("sequence_id", sequenceId).put("stroke_index", 0)
        second.getJSONObject("receipt").put("sequence_id", sequenceId).put("stroke_index", 1)
        val olderBatchShape = step("swipe_sequence", JSONArray()
            .put(stroke("[[100,500],[600,500]]"))
            .put(stroke("[[100,500],[700,500]]")))
        olderBatchShape.getJSONObject("receipt").put("sequence_id", sequenceId).put("stroke_index", 2)

        val evidence = SwipeMotionEvidence.recent(run(first, second, olderBatchShape))
        assertEquals(4, evidence.length())
        repeat(4) { index ->
            assertEquals(index, evidence.getJSONObject(index).getInt("stroke_index"))
            assertEquals(sequenceId, evidence.getJSONObject(index).getString("sequence_id"))
        }
    }

    @Test fun sequenceIdentityIsOnlyCopiedWhenAlreadyAFullUuid() {
        for (identity in listOf("私人序列说明", "1-1-1-1-1", "", "ca5a2f50-5f32-4ce1-af3d-c7719318bb5b-extra")) {
            val value = step("swipe_sequence", JSONArray().put(stroke("[[100,500],[300,500]]")))
            value.getJSONObject("receipt").put("sequence_id", identity)
            assertFalse(SwipeMotionEvidence.recent(run(value)).getJSONObject(0).has("sequence_id"))
        }
    }

    @Test fun declaredOrMissingDirectionIsNotReportedAsVerified() {
        val value = step("swipe", JSONArray().put(stroke("[[200,500],[800,500]]")))
        value.getJSONObject("receipt").put("grounding_assessment",
            JSONObject().put("required_finger_direction", "right"))
        assertFalse(SwipeMotionEvidence.recent(run(value)).getJSONObject(0).has("actual_finger_direction"))

        value.getJSONObject("receipt").getJSONObject("executed_action").put("direction_contract",
            JSONObject().put("coordinate_validation", JSONObject().put("actual_finger_direction", "right")))
        assertEquals("right", SwipeMotionEvidence.recent(run(value)).getJSONObject(0).getString("actual_finger_direction"))
    }

    @Test fun explicitCompletedStrokeCountNeverIncludesUnconfirmedSequenceTail() {
        val value = step("swipe_sequence", JSONArray()
            .put(stroke("[[100,500],[300,500]]"))
            .put(stroke("[[100,500],[500,500]]"))
            .put(stroke("[[100,500],[700,500]]")))
        value.getJSONObject("receipt").put("completed_strokes", 2)
        val evidence = SwipeMotionEvidence.recent(run(value))
        assertEquals(2, evidence.length())
        assertEquals(400.0, evidence.getJSONObject(1).getDouble("abs_dx"), 0.0)
    }

    @Test fun absentOrMalformedHistoryDoesNotInventMotion() {
        assertEquals(0, SwipeMotionEvidence.recent(JSONObject()).length())
        val malformed = step("swipe_sequence", JSONArray()
            .put(stroke("[[100,500]]"))
            .put(stroke("[[100,500],[1001,500]]"))
            .put(stroke("[[100,500],[400,500]]").put("duration_ms", "350")))
        assertEquals(0, SwipeMotionEvidence.recent(run(JSONObject(), malformed)).length())
    }
}

package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.min

/** Compact injection evidence. It does not infer where unseen content is or whether a task succeeded. */
internal object SwipeMotionEvidence {
    private val directions = setOf("up", "up_right", "right", "down_right", "down", "down_left", "left", "up_left")
    private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun recent(run: JSONObject): JSONArray {
        val steps = run.optJSONArray("recent_steps") ?: return JSONArray()
        val motions = mutableListOf<JSONObject>()
        for (stepIndex in 0 until steps.length()) {
            val receipt = steps.optJSONObject(stepIndex)?.optJSONObject("receipt") ?: continue
            if (receipt.optString("status") != "ok") continue
            val action = receipt.optString("action")
            if (action != "swipe" && action != "swipe_sequence") continue
            val executed = receipt.optJSONObject("executed_action") ?: continue
            if (executed.optString("action") != action) continue
            val strokes = executed.optJSONArray("strokes") ?: continue
            if (strokes.length() !in 1..8 || (action == "swipe" && strokes.length() != 1)) continue
            val globalBase = if (receipt.has("stroke_index")) {
                integer(receipt.opt("stroke_index"), 0, 7) ?: continue
            } else 0
            val sequenceId = (receipt.opt("sequence_id") as? String)?.takeIf(uuidPattern::matches)
            val confirmedCount = if (receipt.has("completed_strokes")) {
                val count = integer(receipt.opt("completed_strokes"), 0, 8) ?: continue
                min(count, strokes.length())
            } else strokes.length()
            val validation = executed.optJSONObject("direction_contract")?.optJSONObject("coordinate_validation")
            for (strokeIndex in 0 until confirmedCount) {
                val globalIndex = globalBase + strokeIndex
                if (globalIndex > 7) continue
                val stroke = strokes.optJSONObject(strokeIndex) ?: continue
                val points = stroke.optJSONArray("points") ?: continue
                if (points.length() < 2) continue
                val start = point(points.optJSONArray(0)) ?: continue
                val end = point(points.optJSONArray(points.length() - 1)) ?: continue
                val duration = integer(stroke.opt("duration_ms"), 100, 3000) ?: continue
                val motion = JSONObject().put("action", action).put("stroke_index", globalIndex)
                    .put("start", JSONArray().put(start[0]).put(start[1]))
                    .put("end", JSONArray().put(end[0]).put(end[1]))
                    .put("abs_dx", abs(end[0] - start[0])).put("abs_dy", abs(end[1] - start[1]))
                    .put("duration_ms", duration)
                sequenceId?.let { motion.put("sequence_id", it) }
                val direction = (validation?.optJSONArray("actual_finger_directions")?.opt(strokeIndex) as? String)
                    ?: if (strokes.length() == 1) validation?.opt("actual_finger_direction") as? String else null
                if (direction != null && direction in directions) motion.put("actual_finger_direction", direction)
                motions.add(motion)
                if (motions.size > 4) motions.removeAt(0)
            }
        }
        return JSONArray(motions)
    }

    private fun point(value: JSONArray?): DoubleArray? {
        if (value == null || value.length() != 2) return null
        val coordinates = DoubleArray(2)
        for (axis in 0..1) {
            val coordinate = (value.opt(axis) as? Number)?.toDouble() ?: return null
            if (!coordinate.isFinite() || coordinate !in 0.0..1000.0) return null
            coordinates[axis] = coordinate
        }
        return coordinates
    }

    private fun integer(value: Any?, minimum: Int, maximum: Int): Int? {
        val number = (value as? Number)?.toDouble() ?: return null
        if (!number.isFinite() || number != number.toInt().toDouble() || number < minimum || number > maximum) return null
        return number.toInt()
    }
}

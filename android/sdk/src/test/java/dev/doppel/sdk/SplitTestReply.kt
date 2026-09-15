package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

/** Migrates legacy engine fixtures to the new wire envelope; never creates missing target/expected/coordinates/directions. */
internal object SplitTestReply {
    fun response(input: JSONObject): JSONObject {
        val value = JSONObject(input.toString())
        val grounding = !value.has("kind") && value.has("status")
        if (grounding && value.optString("status") == "located") {
            value.optJSONObject("assessment")?.let { assessment ->
                assessment.remove("observed"); assessment.remove("reason"); assessment.remove("correction_reason")
                if (assessment.has("required_finger_direction") && !assessment.has("direction_corrected")) assessment.put("direction_corrected", false)
                assessment.optJSONArray("gesture_contracts")?.let { contracts -> repeat(contracts.length()) {
                    contracts.getJSONObject(it).let { contract -> if (contract.has("required_finger_direction") && !contract.has("direction_corrected")) contract.put("direction_corrected", false) }
                } }
            }
        }
        if (value.optString("kind") == "execute") {
            if (!value.has("screen_context")) value.put("screen_context", "")
            if (value.optString("action") in setOf("swipe", "swipe_sequence")) {
                if (!value.has("swipe_extent")) value.put("swipe_extent", "small")
                if (!value.has("scroll_goal")) value.put("scroll_goal", "inspect")
                if (!value.has("boundary_reason")) value.put("boundary_reason", "")
            }
        }
        if (value.optString("kind") == "wait") {
            if (!value.has("reason")) value.put("reason", "等待设备状态更新")
            if (!value.has("wait_condition")) value.put("wait_condition", "新截图显示操作入口可用")
            if (!value.has("evidence")) value.put("evidence", "本测试设备回执尚未显示操作入口")
        }
        if (grounding || value.has("points") || value.has("strokes")) {
            val action = value.optString("action")
            if (action in setOf("tap", "double_tap", "long_press", "swipe") && !value.has("duration_ms")) value.put("duration_ms", if (action == "long_press") 650 else if (action == "swipe") 350 else 60)
            if (action in setOf("double_tap", "swipe_sequence") && !value.has("interval_ms")) value.put("interval_ms", 100)
        }
        val envelope = if (grounding) JSONObject().put("result", value) else {
            // Test fixtures describe the engine's semantic command; wire v2 spells the action only in kind.
            if (value.optString("kind") == "execute" && value.opt("action") is String) {
                value.put("kind", value.getString("action")); value.remove("action")
            }
            val state = value.optJSONObject("state")
            value.remove("state")
            state?.let {
                if (!it.has("phase")) it.put("phase", "测试阶段")
                for (field in listOf("facts", "completed_steps", "remaining_steps", "failed_routes")) if (!it.has(field)) it.put(field, JSONArray())
            }
            JSONObject().put("decision", value).put("state", state ?: JSONObject.NULL)
        }
        return JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
            .put("message", JSONObject().put("content", envelope.toString()))))
    }
}

package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

/** Only recent steps enter the model context; durable state and full receipts stay in the run. */
internal object PlannerStepContext {
    private const val RECENT_STEP_LIMIT = 6

    fun steps(values: JSONArray): JSONArray = JSONArray().apply {
        for (index in (values.length() - RECENT_STEP_LIMIT).coerceAtLeast(0) until values.length()) {
            val original = values.optJSONObject(index) ?: continue
            val step = JSONObject(original.toString())
            original.optJSONObject("receipt")?.let { step.put("receipt", receipt(it)) }
            put(step)
        }
    }

    fun receipt(value: JSONObject): JSONObject = JSONObject(value.toString()).apply {
        for (key in listOf("command_id", "sequence", "sequence_id", "source_capture_id", "grounding_capture_id",
            "touch_handoff", "guard_handoff")) remove(key)
        optJSONObject("executed_action")?.let { action ->
            // A already receives its semantic intent and the actual points / timing.
            // Keep B's effective/corrected direction, but omit duplicated A input
            // and the arithmetic used to validate an already recorded trajectory.
            action.optJSONObject("direction_contract")?.let { contract ->
                contract.remove("planner")
                contract.remove("coordinate_validation")
                contract.remove("extent_validation")
                if (contract.length() == 0) action.remove("direction_contract")
            }
            action.remove("extent_validation")
        }
    }
}

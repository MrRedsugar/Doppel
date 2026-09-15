package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/** Pure host-side validation for the semantic meaning and trajectory of swipe actions. */
internal object DirectionalGestureContract {
    data class Intent(
        val semantics: String,
        val targetDirection: String,
        val fingerDirection: String
    ) {
        fun toJson() = JSONObject()
            .put("gesture_semantics", semantics)
            .put("target_relative_direction", targetDirection)
            .put("intended_finger_direction", fingerDirection)
    }

    data class Validation(
        val consistent: Boolean,
        val reasonCode: String,
        val details: JSONObject
    )

    /** Accepted B directions are the contract the coordinates must satisfy. */
    data class Resolution(
        val validation: Validation,
        val effectiveIntents: List<Intent>,
        val corrected: Boolean
    )

    private val directions = setOf(
        "up", "up_right", "right", "down_right",
        "down", "down_left", "left", "up_left"
    )

    fun opposite(direction: String): String = when (direction) {
        "up" -> "down"
        "up_right" -> "down_left"
        "right" -> "left"
        "down_right" -> "up_left"
        "down" -> "up"
        "down_left" -> "up_right"
        "left" -> "right"
        "up_left" -> "down_right"
        else -> throw IllegalArgumentException("未知方向")
    }

    fun parsePlanner(action: String, value: JSONObject): List<Intent> {
        require(action == "swipe" || action == "swipe_sequence") { "方向合同仅适用于滑动" }
        val values = if (action == "swipe") {
            listOf(value)
        } else {
            val array = value.optJSONArray("gesture_contracts")
                ?: throw IllegalArgumentException("连续滑动缺少 gesture_contracts")
            require(array.length() in 1..8) { "连续滑动方向合同数量无效" }
            List(array.length()) { array.getJSONObject(it) }
        }
        return values.map(::parseIntent)
    }

    fun validateAssessment(action: String, intents: List<Intent>, assessment: JSONObject): Validation =
        resolveAssessment(action, intents, assessment).validation

    /** A-only execution cannot delegate a contradictory intent to B for correction. */
    fun validatePlannerSemantics(intents: List<Intent>): Validation {
        for ((index, intent) in intents.withIndex()) {
            if (intent.semantics == "reveal_content" && opposite(intent.targetDirection) != intent.fingerDirection ||
                intent.semantics == "object_drag" && intent.targetDirection != "unknown" && intent.targetDirection != intent.fingerDirection) {
                return mismatch("planner_direction_contradiction", JSONObject()
                    .put("stroke_index", index).put("planner", intent.toJson()))
            }
        }
        return success(JSONObject().put("checked_strokes", intents.size))
    }

    /** B may refine browsing or object endpoints; explicit physical directions remain locked. */
    fun resolveAssessment(action: String, intents: List<Intent>, assessment: JSONObject): Resolution {
        fun rejected(code: String, details: JSONObject) = Resolution(mismatch(code, details), emptyList(), false)
        val values = if (action == "swipe") {
            listOf(assessment)
        } else {
            val array = assessment.optJSONArray("gesture_contracts")
                ?: return rejected("grounder_contract_count_mismatch", JSONObject().put("expected_count", intents.size).put("actual_count", 0))
            if (array.length() != intents.size) {
                return rejected("grounder_contract_count_mismatch", JSONObject().put("expected_count", intents.size).put("actual_count", array.length()))
            }
            List(array.length()) { array.getJSONObject(it) }
        }
        if (values.size != intents.size) {
            return rejected("grounder_contract_count_mismatch", JSONObject().put("expected_count", intents.size).put("actual_count", values.size))
        }
        val effective = mutableListOf<Intent>()
        val corrections = JSONArray()
        for (index in intents.indices) {
            val expected = intents[index]
            val actual = values[index]
            val target = requiredDirection(actual, "target_relative_direction", allowUnknown = expected.semantics == "physical_gesture")
            val finger = requiredDirection(actual, "required_finger_direction")
            val internallyConsistent = when (expected.semantics) {
                "reveal_content" -> opposite(target) == finger
                "object_drag" -> target == finger
                else -> true
            }
            val targetConsistent = expected.semantics == "physical_gesture" || target == expected.targetDirection
            val changed = !targetConsistent || finger != expected.fingerDirection
            val details = JSONObject().put("stroke_index", index)
                .put("planner", expected.toJson())
                .put("grounder", JSONObject().put("target_relative_direction", target).put("required_finger_direction", finger))
            if (!internallyConsistent) return rejected("grounder_internal_direction_contradiction", details)
            if (actual.has("direction_corrected")) {
                require(actual.opt("direction_corrected") is Boolean) { "direction_corrected 必须为布尔值" }
            }
            if (changed) {
                // Explicit user finger/facing directions cannot become browsing inversions.
                // Object drags instead bind source/destination identities in A's target text;
                // B may refine their geometry with an explicit, recorded correction.
                if (expected.semantics == "physical_gesture") return rejected("grounder_direction_disagreement", details)
                if (!actual.optBoolean("direction_corrected", false)) {
                    return rejected("grounder_direction_disagreement", details)
                }
                corrections.put(JSONObject(details.toString()).put("direction_corrected", true))
            }
            effective += Intent(expected.semantics, if (expected.semantics == "physical_gesture") expected.targetDirection else target, finger)
        }
        return Resolution(success(JSONObject().put("checked_strokes", intents.size)
            .put("direction_corrections", corrections)), effective, corrections.length() > 0)
    }

    fun validateCoordinates(
        action: String,
        intents: List<Intent>,
        groundedAction: JSONObject,
        width: Int = 1000,
        height: Int = 1000
    ): Validation {
        require(width > 0 && height > 0) { "滑动方向校验需要有效画面尺寸" }
        val strokes = when {
            groundedAction.has("strokes") -> groundedAction.getJSONArray("strokes")
            action == "swipe" && groundedAction.has("points") -> JSONArray().put(groundedAction)
            else -> JSONArray()
        }
        if (strokes.length() != intents.size) {
            return mismatch(
                "gesture_contract_count_mismatch",
                JSONObject().put("expected_count", intents.size).put("actual_count", strokes.length())
            )
        }
        val actualDirections = JSONArray()
        for (index in intents.indices) {
            val points = strokes.getJSONObject(index).getJSONArray("points")
            require(points.length() >= 2) { "滑动轨迹至少需要两个点" }
            val first = points.getJSONArray(0)
            val last = points.getJSONArray(points.length() - 1)
            val actual = direction(
                first.getDouble(0), first.getDouble(1),
                last.getDouble(0), last.getDouble(1), width, height
            )
            actualDirections.put(actual)
            if (actual != intents[index].fingerDirection) {
                val details = JSONObject().put("stroke_index", index)
                    .put("intended_finger_direction", intents[index].fingerDirection)
                    .put("actual_finger_direction", actual)
                return mismatch("gesture_coordinate_mismatch", details)
            }
        }
        val details = JSONObject().put("actual_finger_directions", actualDirections)
        if (actualDirections.length() == 1) details.put("actual_finger_direction", actualDirections.getString(0))
        return success(details)
    }

    private fun parseIntent(value: JSONObject): Intent {
        val semantics = value.optString("gesture_semantics")
        require(semantics in setOf("reveal_content", "physical_gesture", "object_drag")) { "gesture_semantics 无效" }
        val target = requiredDirection(value, "target_relative_direction", allowUnknown = semantics != "reveal_content")
        val finger = requiredDirection(value, "intended_finger_direction")
        // Preserve A's original intent even when contradictory: B must see and explicitly correct it.
        return Intent(semantics, target, finger)
    }

    private fun requiredDirection(value: JSONObject, key: String, allowUnknown: Boolean = false): String {
        val direction = value.optString(key)
        require(direction in directions || (allowUnknown && direction == "unknown")) { "$key 无效" }
        return direction
    }

    private fun direction(x1: Double, y1: Double, x2: Double, y2: Double, width: Int, height: Int): String {
        require(abs(x2 - x1) + abs(y2 - y1) >= 1.0) { "滑动轨迹位移过小" }
        // The normalized axes have different scales on a non-square screen.
        // Recover the displayed trajectory before classifying its physical direction.
        val dx = (x2 - x1) * width / 1000.0
        val dy = (y2 - y1) * height / 1000.0
        val angle = atan2(dy, dx) * 180.0 / PI
        return when {
            angle >= -22.5 && angle < 22.5 -> "right"
            angle >= 22.5 && angle < 67.5 -> "down_right"
            angle >= 67.5 && angle < 112.5 -> "down"
            angle >= 112.5 && angle < 157.5 -> "down_left"
            angle >= 157.5 || angle < -157.5 -> "left"
            angle >= -157.5 && angle < -112.5 -> "up_left"
            angle >= -112.5 && angle < -67.5 -> "up"
            else -> "up_right"
        }
    }

    private fun success(details: JSONObject) = Validation(true, "", details)
    private fun mismatch(reasonCode: String, details: JSONObject) = Validation(false, reasonCode, details)
}

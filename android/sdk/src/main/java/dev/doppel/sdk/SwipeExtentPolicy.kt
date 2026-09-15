package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Makes browsing distance explicit without ever rescaling model-generated gesture coordinates. */
internal object SwipeExtentPolicy {
    private val extents = setOf("small", "large")
    private val endpointGestures = setOf("physical_gesture", "object_drag")
    private const val marker = "滑动幅度："

    fun apply(action: String, from: JSONObject, to: JSONObject) {
        if (action !in setOf("swipe", "swipe_sequence")) return
        val browsing = if (action == "swipe") from.optString("gesture_semantics") !in endpointGestures else {
            val contracts = from.optJSONArray("gesture_contracts")
            contracts == null || (0 until contracts.length()).any {
                contracts.getJSONObject(it).optString("gesture_semantics") !in endpointGestures
            }
        }
        if (!browsing) return
        val extent = if (from.has("swipe_extent")) from.opt("swipe_extent") as? String else "small"
        require(extent in extents) { "swipe_extent 必须为 small 或 large" }
        val goal = from.optString("scroll_goal", "inspect")
        require(goal in setOf("inspect", "boundary")) { "scroll_goal 必须为 inspect 或 boundary" }
        val boundaryReason = (from.opt("boundary_reason") as? String)?.trim().orEmpty()
        if (extent == "large" && browsing) require(goal == "boundary" && boundaryReason.isNotEmpty()) {
            "大幅浏览仅用于明确直达边界，需 scroll_goal=boundary 和 boundary_reason"
        }
        to.put("swipe_extent", extent).put("scroll_goal", goal)
        if (boundaryReason.isNotEmpty()) to.put("boundary_reason", boundaryReason)
        val target = to.optString("target", from.optString("target"))
        if (browsing && !target.contains(marker)) {
            val description = when (extent) {
                "small" -> "小幅，沿移动轴约 5%–15%，接近目标可更短，低速拖动后松手，避免甩动惯性"
                else -> "大幅，沿移动轴约 30%–60%，本次明确直达边界"
            }
            to.put("target", "$target；$marker$description。")
        }
    }

    fun validateCoordinates(
        intent: JSONObject, action: JSONObject, width: Int = 1000, height: Int = 1000
    ): DirectionalGestureContract.Validation {
        require(width > 0 && height > 0) { "幅度验证需要有效画面尺寸" }
        val extent = intent.optString("swipe_extent")
        if (intent.optString("gesture_semantics") in endpointGestures && !intent.has("gesture_contracts")) {
            return DirectionalGestureContract.Validation(true, "", JSONObject())
        }
        intent.optJSONArray("gesture_contracts")?.let { contracts ->
            if ((0 until contracts.length()).all { contracts.getJSONObject(it).optString("gesture_semantics") in endpointGestures }) {
                return DirectionalGestureContract.Validation(true, "", JSONObject())
            }
        }
        if (extent.isEmpty()) return DirectionalGestureContract.Validation(true, "", JSONObject())
        require(extent in extents) { "swipe_extent 无效" }
        require(extent != "large" || intent.optString("scroll_goal") == "boundary" &&
            (intent.opt("boundary_reason") as? String)?.isNotBlank() == true) {
            "大幅浏览仅用于明确直达边界，需 scroll_goal=boundary 和 boundary_reason"
        }
        val maxFraction = if (extent == "small") .15 else .60
        val strokes = action.optJSONArray("strokes") ?: JSONArray().apply { if (action.has("points")) put(action) }
        val evidence = JSONArray()
        for (index in 0 until strokes.length()) {
            if (intent.optJSONArray("gesture_contracts")?.optJSONObject(index)
                    ?.optString("gesture_semantics") in endpointGestures) continue
            val stroke = strokes.getJSONObject(index)
            val points = stroke.getJSONArray("points")
            var xTravel = 0.0
            var yTravel = 0.0
            var pixels = 0.0
            for (pointIndex in 1 until points.length()) {
                val previous = points.getJSONArray(pointIndex - 1)
                val current = points.getJSONArray(pointIndex)
                val dx = current.getDouble(0) - previous.getDouble(0)
                val dy = current.getDouble(1) - previous.getDouble(1)
                xTravel += abs(dx) / 1000.0
                yTravel += abs(dy) / 1000.0
                pixels += hypot(dx * width / 1000.0, dy * height / 1000.0)
            }
            val duration = stroke.optInt("duration_ms", 350)
            val row = JSONObject().put("stroke_index", index).put("axis_fraction", max(xTravel, yTravel))
                .put("path_length_px", pixels).put("duration_ms", duration)
                .put("speed_px_per_second", pixels * 1000.0 / max(duration, 1))
            evidence.put(row)
            if (max(xTravel, yTravel) > maxFraction + 1e-6) return DirectionalGestureContract.Validation(
                false, "swipe_extent_exceeded", JSONObject().put("stroke_index", index)
                    .put("swipe_extent", extent).put("max_axis_fraction", maxFraction).put("strokes", evidence)
            )
        }
        return DirectionalGestureContract.Validation(true, "", JSONObject().put("swipe_extent", extent).put("strokes", evidence))
    }
}

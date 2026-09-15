package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Two in-memory source frames of dispatched actions. Never a target cache or completion proof. */
internal class VisualActionHistory {
    private val history = ArrayDeque<JSONObject>()
    fun clear() = history.clear()
    fun entries() = JSONArray(history.map { JSONObject(it.toString()) })
    fun record(frame: VisualFrame?, image: String?, action: JSONObject, observation: JSONObject? = null) {
        val kind = action.optString("kind")
        if (frame == null || image.isNullOrBlank() || image.length > 5 * 1024 * 1024 ||
            kind !in setOf("tap", "long_press", "scroll", "visual_gesture", "back", "home", "launch")) return
        val description = JSONObject().put("kind", kind).put("command_id", action.optString("id").take(128))
            .put("action_state", "accepted").put("coordinate_space", "image_pixels")
        if (kind == "visual_gesture") action.optJSONObject("gesture")?.let {
            description.put("kind", it.optString("kind")).put("label", it.optString("label").take(220))
            description.put("x", (it.optDouble("x") * frame.imageWidth).roundToInt().coerceIn(0, frame.imageWidth - 1))
                .put("y", (it.optDouble("y") * frame.imageHeight).roundToInt().coerceIn(0, frame.imageHeight - 1))
            if (it.has("end_x") && it.has("end_y")) description
                .put("end_x", (it.optDouble("end_x") * frame.imageWidth).roundToInt().coerceIn(0, frame.imageWidth - 1))
                .put("end_y", (it.optDouble("end_y") * frame.imageHeight).roundToInt().coerceIn(0, frame.imageHeight - 1))
        } else {
            for (key in listOf("target", "direction", "package_name"))
                if (action.has(key)) description.put(key, action.optString(key).take(255))
            val nodes = observation?.optJSONArray("nodes") ?: JSONArray()
            val node = (0 until nodes.length()).mapNotNull { nodes.optJSONObject(it) }
                .firstOrNull { it.optString("id") == action.optString("target") && !it.optBoolean("password") && !it.optBoolean("editable") }
            val bounds = node?.optJSONArray("bounds")
            if (bounds?.length() == 4 && observation?.optString("screen_id") == frame.screenId) {
                description.put("x", ((bounds.getDouble(0) + bounds.getDouble(2)) / 2 * frame.imageWidth / frame.displayWidth).roundToInt().coerceIn(0, frame.imageWidth - 1))
                    .put("y", ((bounds.getDouble(1) + bounds.getDouble(3)) / 2 * frame.imageHeight / frame.displayHeight).roundToInt().coerceIn(0, frame.imageHeight - 1))
            }
        }
        while (history.size >= 2) history.removeFirst()
        history.addLast(JSONObject().put("capture_id", frame.captureId).put("width", frame.imageWidth)
            .put("height", frame.imageHeight).put("image", image).put("action", description))
    }
}

package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

/** Observation routing only. A usable editor need not have a persistable content fingerprint. */
internal object PerceptionRouting {
    private val renderedSurface = Regex("webview|surfaceview|textureview|canvas", RegexOption.IGNORE_CASE)
    private val nodeId = Regex("n[0-9]+(?:_[0-9]+)*")

    fun requiresPixels(observation: JSONObject?): Boolean {
        if (observation == null || observation.optBoolean("assistant_surface") || observation.optBoolean("truncated") ||
            observation.has("tree_complete") && !observation.optBoolean("tree_complete")) return true
        val width = observation.optInt("width")
        val height = observation.optInt("height")
        if (width !in 1..16384 || height !in 1..16384 || text(observation, "package_name").isBlank()) return true
        val nodes = observation.optJSONArray("nodes") ?: return true
        if (nodes.length() !in 1..299) return true
        val candidates = (0 until nodes.length()).mapNotNull { nodes.optJSONObject(it) }
        val references = candidates.groupingBy { text(it, "id") }.eachCount()
        var usableTarget = false
        for (node in candidates) {
            if (node.has("visible") && !node.optBoolean("visible")) continue
            val bounds = bounds(node.optJSONArray("bounds"))
            // An offscreen cached WebView is not evidence that the foreground native dialog is opaque.
            val visibleArea = bounds?.let {
                val left = it[0].coerceIn(0, width); val right = it[2].coerceIn(0, width)
                val top = it[1].coerceIn(0, height); val bottom = it[3].coerceIn(0, height)
                (right - left).toLong() * (bottom - top)
            }
            if (visibleArea == 0L) continue
            val role = text(node, "role")
            if (renderedSurface.containsMatchIn(role)) return true
            val editable = node.optBoolean("editable")
            val named = listOf("text", "description", "state_description").any { text(node, it).isNotBlank() }
            if (!editable && !named && visibleArea != null && visibleArea > width.toLong() * height / 4 &&
                (node.optBoolean("clickable") || role == "button")) return true
            if (!node.optBoolean("enabled") || node.optBoolean("password") || visibleArea == null) continue
            val id = text(node, "id")
            if (id.length > 120 || !nodeId.matches(id) || references[id] != 1) continue
            val directAction = editable || node.optBoolean("clickable") || node.optBoolean("long_clickable")
            val directions = node.optJSONArray("scroll_directions")
            val scrollAction = node.optBoolean("scrollable") && directions != null &&
                (0 until directions.length()).any { directions.opt(it) in setOf("up", "down", "left", "right") }
            val identifiable = named || text(node, "resource_id").isNotBlank() || editable && node.optBoolean("focused")
            if ((directAction || scrollAction) && identifiable) usableTarget = true
        }
        return !usableTarget
    }

    private fun text(value: JSONObject, key: String) = (value.opt(key) as? String).orEmpty().trim()

    private fun bounds(value: JSONArray?): List<Int>? {
        if (value == null || value.length() != 4) return null
        val coordinates = (0..3).map { index ->
            val number = (value.opt(index) as? Number)?.toDouble() ?: return null
            if (!number.isFinite() || number != kotlin.math.floor(number) || number < Int.MIN_VALUE || number > Int.MAX_VALUE) return null
            number.toInt()
        }
        return coordinates.takeIf { it[0] < it[2] && it[1] < it[3] }
    }
}

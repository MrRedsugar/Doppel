package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor

/** Candidate resolution only. The host still owns visual risk checks, authorization and current-node revalidation. */
internal object SemanticPointTarget {
    private val nodeId = Regex("n[0-9]+(?:_[0-9]+)*")
    private val booleanFields = listOf("enabled", "visible", "clickable", "long_clickable", "editable", "scrollable", "password", "checkable")

    fun resolve(observation: JSONObject, frame: VisualFrame, gesture: VisualGesture): String? {
        if (gesture.kind !in setOf("tap", "long_press") || gesture.captureId != frame.captureId || gesture.safety != "safe" ||
            gesture.endX != null || gesture.endY != null || !coordinate(gesture.x) || !coordinate(gesture.y) ||
            gesture.label.isBlank() || gesture.label.length > 240 ||
            gesture.durationMs !in if (gesture.kind == "tap") 40L..200L else 500L..2000L) return null
        if (observation.opt("screen_id") != frame.screenId || observation.opt("package_name") != frame.packageName ||
            integer(observation.opt("width")) != frame.displayWidth || integer(observation.opt("height")) != frame.displayHeight ||
            observation.has("capture_id") && observation.opt("capture_id") != frame.captureId ||
            observation.has("rotation") && integer(observation.opt("rotation")) != frame.rotation ||
            observation.has("tree_complete") && observation.opt("tree_complete") != true ||
            observation.has("assistant_surface") && observation.opt("assistant_surface") != false) return null

        val source = observation.optJSONArray("nodes") ?: return null
        // A bounded/incomplete list cannot establish uniqueness beyond its coverage.
        if (source.length() !in 1..300) return null
        val nodes = ArrayList<JSONObject>(source.length())
        val ids = hashSetOf<String>()
        for (index in 0 until source.length()) {
            val node = source.optJSONObject(index) ?: return null
            val id = node.opt("id") as? String ?: return null
            if (id.length > 120 || !nodeId.matches(id) || !ids.add(id)) return null
            if (booleanFields.any { node.has(it) && node.opt(it) !is Boolean } ||
                listOf("text", "description").any { node.has(it) && !node.isNull(it) && node.opt(it) !is String }) return null
            nodes.add(node)
        }

        val x = gesture.x * frame.displayWidth
        val y = gesture.y * frame.displayHeight
        var candidate: JSONObject? = null
        var candidateBounds: Bounds? = null
        for (node in nodes) {
            if (!visible(node) || !node.optBoolean("enabled") || !actionable(node)) continue
            // Unknown geometry on an actionable node cannot prove that it does not overlap the point.
            val bounds = bounds(node, frame) ?: return null
            if (!bounds.contains(x, y)) continue
            // Count before checking label, requested capability, area or sensitive/stateful attributes.
            if (candidate != null) return null
            candidate = node
            candidateBounds = bounds
        }
        val target = candidate ?: return null
        val area = candidateBounds ?: return null
        if (target.optBoolean("password") || target.optBoolean("checkable") ||
            !target.optBoolean(if (gesture.kind == "tap") "clickable" else "long_clickable") ||
            area.area > frame.displayWidth.toLong() * frame.displayHeight / 4) return null
        val label = directLabel(target).ifBlank { inheritedLabel(target, area, nodes, frame) }
        return (target.opt("id") as String).takeIf { label.isNotBlank() && gesture.label.contains(label) }
    }

    private fun coordinate(value: Double) = value.isFinite() && value >= 0 && value < 1
    private fun visible(node: JSONObject) = !node.has("visible") || node.optBoolean("visible")
    private fun actionable(node: JSONObject) = node.optBoolean("clickable") || node.optBoolean("long_clickable")

    /** Preserve every character within each full label; model-summary whitespace normalization/truncation is not evidence. */
    private fun directLabel(node: JSONObject) = listOf("text", "description")
        .map { (node.opt(it) as? String).orEmpty().trim() }.filter(String::isNotBlank).distinct().joinToString(" ")

    private fun inheritedLabel(target: JSONObject, area: Bounds, nodes: List<JSONObject>, frame: VisualFrame): String {
        if (target.optBoolean("editable") || target.optBoolean("scrollable")) return ""
        val prefix = (target.opt("id") as String) + "_"
        val labels = linkedSetOf<String>()
        for (child in nodes) {
            if (!(child.opt("id") as String).startsWith(prefix) || !visible(child)) continue
            val childBounds = bounds(child, frame) ?: return ""
            if (!area.contains(childBounds) || actionable(child) || listOf("editable", "scrollable", "password", "checkable").any { child.optBoolean(it) }) return ""
            directLabel(child).takeIf(String::isNotBlank)?.let(labels::add)
            if (labels.size > 1) return ""
        }
        return labels.singleOrNull().orEmpty()
    }

    private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val area get() = (right.toLong() - left) * (bottom.toLong() - top)
        fun contains(x: Double, y: Double) = x >= left && x < right && y >= top && y < bottom
        fun contains(other: Bounds) = left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
    }

    private fun bounds(node: JSONObject, frame: VisualFrame): Bounds? {
        val values = node.opt("bounds") as? JSONArray ?: return null
        if (values.length() != 4) return null
        val left = integer(values.opt(0)) ?: return null
        val top = integer(values.opt(1)) ?: return null
        val right = integer(values.opt(2)) ?: return null
        val bottom = integer(values.opt(3)) ?: return null
        if (left < 0 || top < 0 || right > frame.displayWidth || bottom > frame.displayHeight || left >= right || top >= bottom) return null
        return Bounds(left, top, right, bottom)
    }

    private fun integer(value: Any?): Int? {
        val number = (value as? Number)?.toDouble() ?: return null
        return number.takeIf { it.isFinite() && it == floor(it) && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()
    }
}

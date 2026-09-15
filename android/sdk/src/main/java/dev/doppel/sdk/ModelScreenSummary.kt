package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor

/** Model-facing presentation only. Always execute against the original current observation. */
object ModelScreenSummary {
    data class Summary(val text: String, val targetIds: List<String>, val totalNodes: Int,
        val meaningfulNodes: Int, val shownNodes: Int, val truncatedNodes: Int)
    private data class Row(val node: JSONObject, val label: String, val actions: String, val password: Boolean)

    fun render(screen: JSONObject, evidenceId: String? = null, maxNodes: Int = 220, maxChars: Int = 22000,
        imageWidth: Int? = null, imageHeight: Int? = null): Summary {
        require(maxNodes in 1..300 && maxChars in 1024..64000)
        if (screen.optBoolean("assistant_surface")) return Summary(
            "当前是助手自身界面（${text(screen, "package_name")}），聊天内容不属于目标应用的实时证据；请启动或返回目标应用。\n" +
                "screen_id=${text(screen, "screen_id")} evidence_id=$evidenceId", emptyList(), 0, 0, 0, 0)
        val source = screen.optJSONArray("nodes") ?: JSONArray()
        val deviceWidth = screen.optInt("width")
        val deviceHeight = screen.optInt("height")
        val imageCoordinates = imageWidth != null && imageHeight != null && imageWidth > 0 && imageHeight > 0 && deviceWidth > 0 && deviceHeight > 0
        val coordinateSpace = if (imageCoordinates) "image_pixels" else "device_pixels"
        val targetWidth = if (imageCoordinates) imageWidth!! else deviceWidth
        val targetHeight = if (imageCoordinates) imageHeight!! else deviceHeight
        val nodesById = (0 until minOf(source.length(), 300)).mapNotNull { source.optJSONObject(it) }
            .groupBy { text(it, "id") }.filterValues { it.size == 1 }.mapValues { it.value.single() }
        val rows = (0 until minOf(source.length(), 300)).mapNotNull { index ->
            val node = source.optJSONObject(index) ?: return@mapNotNull null
            if (node.has("visible") && !node.optBoolean("visible")) return@mapNotNull null
            val password = node.optBoolean("password")
            val enabled = node.optBoolean("enabled")
            val directions = directions(node.opt("scroll_directions"))
            val actions = if (password) "" else listOf("clickable" to "点", "long_clickable" to "长按", "editable" to "输入", "scrollable" to "滚动")
                .filter { enabled && node.optBoolean(it.first) && (it.first != "scrollable" || !directions.isNullOrEmpty()) }
                .joinToString(",") { it.second }
            var label = listOf("text", "description").map { text(node, it) }.filter(String::isNotBlank).distinct().joinToString(" ").take(220)
            if (!password && label.isBlank() && !node.optBoolean("editable") && !node.optBoolean("scrollable") &&
                (node.optBoolean("clickable") || node.optBoolean("long_clickable"))) label = inheritedLabel(node, screen)
            val stateful = node.optBoolean("checkable") || node.optBoolean("selected") || node.optBoolean("scrollable") || text(node, "state_description").isNotBlank()
            val hasCapability = listOf("clickable", "long_clickable", "editable").any { node.optBoolean(it) }
            if (!password && label.isBlank() && !stateful && !hasCapability) null else Row(node, label, actions, password)
        }
        val output = StringBuilder("应用 ${text(screen, "package_name").take(255)}，${deviceWidth}x${deviceHeight}\n当前 screen_id=${text(screen, "screen_id").take(128)} evidence_id=${evidenceId?.take(128)}\n")
        output.append("coordinate_space=$coordinateSpace size=${targetWidth}x${targetHeight} bounds=[left,top,right,bottom]\n")
        if (!imageCoordinates && (imageWidth != null || imageHeight != null)) output.append("image_geometry=unavailable；bounds仍为设备像素。\n")
        val targets = linkedSetOf<String>()
        var shown = 0
        for (row in rows) {
            if (shown >= maxNodes) break
            val node = row.node
            val id = text(node, "id").takeIf { it.length <= 120 && it.matches(Regex("n[0-9]+(?:_[0-9]+)*")) }
            val line = if (row.password) "受保护的密码输入框\n" else buildString {
                val interactive = row.actions.isNotEmpty()
                if (id != null && (interactive || node.optBoolean("scrollable"))) append("$id: ")
                val fallback = text(node, "resource_id").substringAfterLast('/').take(100)
                append(row.label.ifBlank { if (fallback.isBlank()) "无文字控件" else "无文字控件($fallback)" })
                if (interactive) append(" [${row.actions}]")
                if (!node.optBoolean("enabled")) append(" [] enabled=false")
                val role = text(node, "role").take(100)
                if (role.isNotBlank()) append(" role=$role")
                if (node.optBoolean("editable") && node.optBoolean("focused") && text(node,"ime_action") in setOf("done","next"))
                    append(" ime_action=${text(node,"ime_action")} editor_id=${text(node,"ime_editor_id")}")
                if (interactive && fallback.isNotBlank()) append(" resource_id=$fallback")
                (node.opt("focused") as? Boolean)?.let { append(" focused=$it") }
                if (node.optBoolean("checkable")) append(" checked=${(node.opt("checked") as? Boolean)?.toString() ?: "unknown"}")
                if (node.optBoolean("selected")) append(" selected=true")
                val state = text(node, "state_description").take(220)
                if (state.isNotBlank() && state != row.label) append(" state=$state")
                if (node.optBoolean("scrollable")) append(" scroll_directions=${directions(node.opt("scroll_directions"))?.joinToString(prefix = "[", postfix = "]") ?: "unknown"}")
                if (listOf("clickable", "long_clickable", "editable", "scrollable").any { node.optBoolean(it) }) {
                    val rect = clippedBounds(node, deviceWidth, deviceHeight)
                    val shownRect = rect?.let { mapBounds(it, deviceWidth, deviceHeight, targetWidth, targetHeight) }
                    append(" bounds=${shownRect?.joinToString(prefix = "[", postfix = "]", separator = ",") ?: "unknown"}")
                    if (rect != null) append(" region=${region(rect, deviceHeight)}")
                    val context = parentContext(node, row.label, nodesById, deviceWidth, deviceHeight)
                    if (context.isNotBlank()) append(" parent=$context")
                }
                append('\n')
            }
            // Reserve room for the truncation notice; never advertise a target whose row was omitted.
            if (output.length + line.length > maxChars - 100) break
            output.append(line); shown++
            if (row.actions.isNotEmpty() && id != null) targets.add(id)
        }
        val unscanned = (source.length() - 300).coerceAtLeast(0)
        val truncated = rows.size - shown + unscanned
        if (truncated > 0) output.append("另有 $truncated 项未展示；当前摘要不代表完整页面。\n")
        return Summary(output.toString(), targets.toList(), source.length(), rows.size, shown, truncated)
    }

    private fun text(node: JSONObject, key: String) = (node.opt(key) as? String).orEmpty().trim().replace(Regex("\\s+"), " ")
    private fun bounds(node: JSONObject): List<Int>? {
        val array = node.optJSONArray("bounds") ?: return null
        if (array.length() != 4) return null
        return (0..3).map {
            val value = (array.opt(it) as? Number)?.toDouble() ?: return null
            if (!value.isFinite() || value != floor(value) || value < Int.MIN_VALUE || value > Int.MAX_VALUE) return null
            value.toInt()
        }
            .takeIf { it[0] < it[2] && it[1] < it[3] }
    }
    private fun clippedBounds(node: JSONObject, width: Int, height: Int): List<Int>? {
        if (width <= 0 || height <= 0) return null
        val rect = bounds(node) ?: return null
        return listOf(rect[0].coerceIn(0, width), rect[1].coerceIn(0, height),
            rect[2].coerceIn(0, width), rect[3].coerceIn(0, height))
            .takeIf { it[0] < it[2] && it[1] < it[3] }
    }
    private fun mapBounds(rect: List<Int>, width: Int, height: Int, outputWidth: Int, outputHeight: Int): List<Int> {
        // Rectangles are half-open: keep a small target covered after downscaling.
        return listOf(floor(rect[0].toDouble() * outputWidth / width).toInt(),
            floor(rect[1].toDouble() * outputHeight / height).toInt(),
            ceil(rect[2].toDouble() * outputWidth / width).toInt(),
            ceil(rect[3].toDouble() * outputHeight / height).toInt())
    }
    private fun region(rect: List<Int>, height: Int): String {
        val centerY = (rect[1].toLong() + rect[3]) / 2.0
        return if (centerY < height / 3.0) "top" else if (centerY < height * 2.0 / 3.0) "middle" else "bottom"
    }
    private fun parentContext(node: JSONObject, label: String, nodesById: Map<String, JSONObject>, width: Int, height: Int): String {
        if (node.optBoolean("password") || node.optBoolean("editable") || width <= 0 || height <= 0) return ""
        val id = text(node, "id")
        if (!id.matches(Regex("n[0-9]+(?:_[0-9]+)+"))) return ""
        val parent = nodesById[id.substringBeforeLast('_')] ?: return ""
        if (parent.optBoolean("password") || parent.optBoolean("editable") || parent.has("visible") && !parent.optBoolean("visible")) return ""
        val parentRect = clippedBounds(parent, width, height) ?: return ""
        val childRect = clippedBounds(node, width, height) ?: return ""
        if (parentRect[0] > childRect[0] || parentRect[1] > childRect[1] || parentRect[2] < childRect[2] || parentRect[3] < childRect[3]) return ""
        if ((parentRect[2].toLong() - parentRect[0]) * (parentRect[3].toLong() - parentRect[1]) > width.toLong() * height / 4) return ""
        val parentLabel = text(parent, "text").ifBlank { text(parent, "description") }.take(72)
        val resource = text(parent, "resource_id").substringAfterLast('/').take(64)
        return listOf(parentLabel.takeIf { it.isNotBlank() && it != label }, resource.takeIf { it.isNotBlank() && it != parentLabel })
            .filterNotNull().joinToString("/")
    }
    /** Shared display/action alias only. Never rewrites raw node text or typed postcondition evidence. */
    internal fun inheritedLabel(node: JSONObject, screen: JSONObject): String {
        if (node.optBoolean("password") || node.optBoolean("editable") || node.optBoolean("scrollable") ||
            node.has("visible") && !node.optBoolean("visible") ||
            !(node.optBoolean("clickable") || node.optBoolean("long_clickable")) ||
            text(node,"text").isNotBlank() || text(node,"description").isNotBlank()) return ""
        val id=text(node,"id")
        if (!id.matches(Regex("n[0-9]+(?:_[0-9]+)*"))) return ""
        val area = bounds(node) ?: return ""
        if ((area[2].toLong() - area[0]) * (area[3].toLong() - area[1]) > screen.optInt("width").toLong() * screen.optInt("height") / 4) return ""
        val nodes = screen.optJSONArray("nodes") ?: return ""
        val labels = linkedSetOf<String>()
        for (index in 0 until minOf(nodes.length(), 300)) {
            val child = nodes.optJSONObject(index) ?: continue
            if (child === node || child.has("visible") && !child.optBoolean("visible")) continue
            // Geometry alone can also describe an unrelated sibling or overlay at the same position.
            if (!text(child,"id").startsWith(id+"_")) continue
            val rect = bounds(child) ?: continue
            if (rect[0] < area[0] || rect[1] < area[1] || rect[2] > area[2] || rect[3] > area[3]) continue
            if (listOf("clickable", "long_clickable", "editable", "scrollable", "password").any { child.optBoolean(it) }) return ""
            val label = text(child, "text").ifBlank { text(child, "description") }.take(220)
            if (label.isNotBlank()) labels.add(label)
            if (labels.size > 1) return ""
        }
        return labels.singleOrNull().orEmpty()
    }
    private fun directions(value: Any?): List<String>? {
        val array = value as? JSONArray ?: return null
        if (array.length() > 4) return null
        val values = (0 until array.length()).map { array.opt(it) as? String ?: return null }
        if (values.any { it !in setOf("up", "down", "left", "right") }) return null
        return values.distinct()
    }
}

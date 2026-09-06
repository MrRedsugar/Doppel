package dev.doppel.sdk

data class TargetNodeSnapshot(
    val id: String,
    val bounds: List<Int>,
    val text: String = "",
    val description: String = "",
    val hint: String = "",
    val className: String = "",
    val resourceId: String = "",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val enabled: Boolean = true,
    val password: Boolean = false,
    val scrollable: Boolean = false,
    val childCount: Int = 0
)

data class TargetScreenSnapshot(
    val screenId: String,
    val packageName: String,
    val windowId: Int,
    val navigationGeneration: Int,
    val width: Int,
    val height: Int,
    val capturedAt: Long,
    val nodes: List<TargetNodeSnapshot>,
    val complete: Boolean = true
)

class TargetHistory {
    private val screens = linkedMapOf<String, TargetScreenSnapshot>()
    private val commitLabel = Regex("发送|提交|删除|移除|保存|确认|确定|完成|下单|购买|撤销|取消|\\b(send|submit|delete|remove|save|confirm|purchase|pay|checkout|transfer)\\b", RegexOption.IGNORE_CASE)
    @Synchronized fun remember(screen: TargetScreenSnapshot) {
        screens.entries.removeAll { screen.capturedAt - it.value.capturedAt > 30000 }
        screens.remove(screen.screenId)
        screens[screen.screenId] = screen
        while (screens.size > 8) screens.remove(screens.keys.first())
    }
    @Synchronized fun clear() { screens.clear() }
    @Synchronized fun revalidates(expected: String, current: TargetScreenSnapshot, target: String, kind: String): Boolean {
        val previous = screens[expected] ?: return false
        if (kind !in setOf("tap", "type", "scroll") || !previous.complete || !current.complete) return false
        if (current.capturedAt - previous.capturedAt !in 0..30000) return false
        if (previous.packageName != current.packageName || previous.windowId != current.windowId ||
            previous.navigationGeneration != current.navigationGeneration || previous.width != current.width || previous.height != current.height) return false
        val old = previous.nodes.associateBy { it.id }
        val fresh = current.nodes.associateBy { it.id }
        val node = old[target] ?: return false
        if (node != fresh[target] || node.password || !node.enabled) return false
        var parent = target.substringBeforeLast('_', "")
        repeat(3) {
            if (parent.isNotEmpty()) {
                if (old[parent] == null || old[parent] != fresh[parent]) return false
                parent = parent.substringBeforeLast('_', "")
            }
        }
        if (kind == "scroll") return node.scrollable && node.resourceId.isNotBlank()
        if (kind == "tap" && !node.clickable || kind == "type" && !node.editable) return false
        val before = previous.nodes.filter { it.id == target || it.id.startsWith("${target}_") }
        val after = current.nodes.filter { it.id == target || it.id.startsWith("${target}_") }
        if (before.size > 30 || before != after || before.any { it.password }) return false
        val label = before.joinToString(" ") { "${it.text} ${it.description} ${it.hint}" }.trim()
        return label.isNotBlank() && !Policy.sensitive(label) && !commitLabel.containsMatchIn(label)
    }
}

package dev.doppel.sdk

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

internal object NotificationRecoveryPolicy {
    fun canDismiss(pkg: String, identity: String, labels: List<String>, top: Int, bottom: Int, height: Int, dismissible: Boolean): Boolean {
        if (pkg != "com.android.systemui" || !dismissible || height <= 0 || top < 0 || bottom <= top || bottom > height / 2) return false
        if (!Regex("heads.?up|notification", RegexOption.IGNORE_CASE).containsMatchIn(identity)) return false
        val text = labels.joinToString(" ")
        if (Regex("来电|接听|挂断|闹钟|稍后提醒|验证码|安全验证|支付|付款|incoming call|answer|snooze|verification|payment", RegexOption.IGNORE_CASE).containsMatchIn(text)) return false
        return true
    }
}

/** Uses advertised System UI dismissal only; never guesses an application's close button. */
internal class NotificationRecovery {
    private var runId = ""
    private val attempts = mutableMapOf<String, Int>()
    fun attempt(service: DoppelAccessibilityService, task: String, current: () -> Boolean): Boolean {
        if (task != runId) { runId = task; attempts.clear() }
        val height = service.resources.displayMetrics.heightPixels
        for (window in service.windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() != "com.android.systemui") continue
            val queue = java.util.ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
            var count = 0
            while (queue.isNotEmpty() && count++ < 160) {
                val node = queue.removeFirst(); val rect = Rect(); node.getBoundsInScreen(rect)
                repeat(node.childCount) { node.getChild(it)?.let(queue::add) }
                val identity = "${window.title?.toString().orEmpty()} ${node.viewIdResourceName.orEmpty()}"
                val dismissible = node.isVisibleToUser && node.isEnabled && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS }
                if (!NotificationRecoveryPolicy.canDismiss("com.android.systemui", identity, emptyList(), rect.top, rect.bottom, height, dismissible)) continue
                val labels = mutableListOf<String>(); val children = java.util.ArrayDeque<AccessibilityNodeInfo>(); children.add(node)
                var scanned = 0
                while (children.isNotEmpty() && scanned++ < 80) {
                    val child = children.removeFirst(); labels += child.text?.toString().orEmpty(); labels += child.contentDescription?.toString().orEmpty()
                    repeat(child.childCount) { child.getChild(it)?.let(children::add) }
                }
                if (NotificationRecoveryPolicy.canDismiss("com.android.systemui", identity, labels, rect.top, rect.bottom, height, dismissible)) {
                    val key = "${window.id}:${node.viewIdResourceName}:$rect"
                    if ((attempts[key] ?: 0) < 2 && attempts.values.sum() < 4 && current()) {
                        attempts[key] = (attempts[key] ?: 0) + 1
                        if (node.performAction(AccessibilityNodeInfo.ACTION_DISMISS)) return true
                    }
                }
            }
        }
        return false
    }
}

package dev.doppel.sdk

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import java.util.ArrayDeque

/**
 * Fast event-driven trigger engine. It inspects accessibility nodes only; no LLM,
 * screenshot or network request is made. A running/pending task always wins.
 */
class AutoTriggerEngine(private val service: AccessibilityService) {
    private val store = AutoTriggerStore(service)
    private var lastScanAt = 0L
    private val appearances = mutableMapOf<String, AutoTriggerOccurrence>()
    private val main = Handler(Looper.getMainLooper())
    private val deferredScan = Runnable {
        try { scan() } catch (_: Exception) {
            android.util.Log.w("DoppelTrigger", "Control scan failed; waiting for the next window event")
        }
    }

    fun onEvent(event: AccessibilityEvent?) {
        // Framework events can be recycled after this callback; retain only the type.
        val type = event?.eventType ?: return
        if (type !in setOf(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED)) return
        if (Looper.myLooper() == main.looper) deferredScan.run() else main.post(deferredScan)
    }

    private fun scan() {
        // Some apps emit dozens of content-change events per frame (video/animations).
        // A short global gate keeps the resident service cheap; rule cooldown remains
        // the authoritative action-level duplicate protection.
        val now = SystemClock.elapsedRealtime()
        val previous = lastScanAt
        if (now - previous < 80L) {
            // Keep a trailing scan: simply dropping a disappearance event can
            // otherwise leave a skipped occurrence consumed forever.
            main.removeCallbacks(deferredScan)
            main.postDelayed(deferredScan, 80L - (now - previous))
            return
        }
        lastScanAt = now
        main.removeCallbacks(deferredScan)
        // All entry points above run on main, including trailing scans and settlement.
        val rules = store.list().filter { it.enabled }
        appearances.keys.retainAll(rules.map { it.id }.toSet())
        if (rules.isEmpty()) return
        // rootInActiveWindow can temporarily resolve to our own accessibility
        // overlay (or be stale while a cross-app window is settling). Select
        // the focused application/system window on Android 14 first.
        val root = (service as? DoppelAccessibilityService)?.activeRoot()
            ?: service.rootInActiveWindow ?: return
        val pkg = root.packageName?.toString().orEmpty()
        // A notice, PIN dialog or system overlay is not evidence that the
        // underlying target disappeared. In particular Skip must not re-arm it.
        if (pkg.isBlank() || pkg == service.packageName || root.window?.type == AccessibilityWindowInfo.TYPE_SYSTEM) return
        rules.filter { it.packageName != pkg }.forEach { appearances[it.id]?.absent() }
        val relevantRules = rules.filter { it.packageName == pkg }
        if (relevantRules.isEmpty()) return
        val nodes = mutableMapOf<String, AccessibilityNodeInfo>()
        val visitedNodes = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_CONTROL_TREE_NODES) {
            // Keep the common 400-node path. If a configured ID/target is still
            // missing, continue the SAME breadth-first scan within the picker's
            // budget; do not silently discard a control that users could select.
            if (visited == 400 && relevantRules.all { rule ->
                    rule.matchResourceId in nodes && (rule.action in setOf("task", "back") ||
                        (rule.targetResourceId.isBlank() && rule.targetText.isBlank()) ||
                        visitedNodes.any { matches(it, rule.targetResourceId, rule.targetText) })
                }) break
            val node = queue.removeFirst()
            visited++
            visitedNodes.add(node)
            if (node.isVisibleToUser) node.viewIdResourceName?.takeIf { it.isNotBlank() }?.let { nodes.putIfAbsent(it, node) }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        val complete = queue.isEmpty()
        // Observe absence even while another task owns the device; otherwise
        // a genuinely new appearance after that task would remain suppressed.
        relevantRules.forEach { rule ->
            if (complete && rule.matchResourceId !in nodes) appearances[rule.id]?.absent()
        }
        relevantRules.forEach { rule ->
            // Trigger identity is resource-id only. Text is display metadata and
            // must not make an otherwise valid ID rule miss after localization.
            val trigger = nodes[rule.matchResourceId] ?: return@forEach
            val occurrence = appearances.getOrPut(rule.id) { AutoTriggerOccurrence() }
            if (!occurrence.canTrigger(now, rule.cooldownMs)) return@forEach
            // A task match may post a blocked-task message while busy. The
            // launcher still cannot execute until the user explicitly replaces
            // the current task. Direct accessibility actions must never run then.
            if (hasActiveTask() && rule.action != "task") return@forEach
            val attempt = occurrence.begin(now, rule.cooldownMs) ?: return@forEach
            fun settled(accepted: Boolean) {
                val finish = Runnable {
                    if (appearances[rule.id] === occurrence && occurrence.resolve(attempt, accepted,
                            SystemClock.elapsedRealtime(), rule.burstLimit, rule.burstWindowMs)) {
                        Toast.makeText(service, "${rule.controlLabel(service)}短时间内已触发 ${rule.burstLimit} 次，此规则暂停触发 60 秒", Toast.LENGTH_LONG).show()
                    }
                }
                if (Looper.myLooper() == Looper.getMainLooper()) finish.run() else main.post(finish)
            }
            val acted = try {
                val target = if (rule.targetResourceId.isBlank() && rule.targetText.isBlank()) trigger
                else visitedNodes.firstOrNull { matches(it, rule.targetResourceId, rule.targetText) }
                when (rule.action) {
                "task" -> AutoTriggerTaskLauncher.launch(service, rule.taskGoal,
                    onAdmission = ::settled,
                    sourceLabel = rule.controlLabel(service), sourceRuleId = rule.id, sourceRuleVersion = rule.json().toString())
                "back" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                "dismiss" -> target?.performAction(AccessibilityNodeInfo.ACTION_DISMISS) == true ||
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                else -> click(target)
                }
            } catch (_: Exception) {
                settled(false)
                false
            }
            if (rule.action != "task") settled(acted)
            // One event should execute at most one rule. If this rule could
            // not act (for example the target disappeared or a task is
            // already being submitted), continue checking the remaining
            // rules instead of suppressing them for this event.
            if (acted) return
        }
    }

    private fun hasActiveTask(): Boolean {
        // A run may be between submission and active_run persistence. Treat that
        // window as busy too, otherwise an accessibility event can trigger a
        // second rule while the user task is still being created.
        if (TaskSubmissionGate.creating.get() || AutomaticUnlockSession.active || AccessibilityControlPicker.active) return true
        val active = service.getSharedPreferences("doppel", 0).getString("active_run", "").orEmpty()
        if (active.isNotBlank()) return true
        val worker = DeviceWorkerService.instance
        return worker != null && !worker.isPaused
    }

    private fun click(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        repeat(5) {
            if (current == null) return false
            if (current!!.isVisibleToUser && current!!.isEnabled && current!!.isClickable &&
                current!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current!!.parent
        }
        return false
    }

    private fun matches(node: AccessibilityNodeInfo, resource: String, text: String): Boolean {
        val idOk = resource.isBlank() || node.viewIdResourceName == resource
        val query = text.trim()
        val textOk = query.isBlank() || listOf(node.text?.toString(), node.contentDescription?.toString())
            .filterNotNull().any { it == query || it.contains(query, ignoreCase = true) }
        return node.isVisibleToUser && idOk && textOk
    }

}

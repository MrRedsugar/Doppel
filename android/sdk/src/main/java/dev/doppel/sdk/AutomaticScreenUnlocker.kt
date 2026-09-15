package dev.doppel.sdk

import android.app.KeyguardManager
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/** Uses only the current System UI tree. No screenshots, model calls or credential logging. */
internal object AutomaticScreenUnlocker {
    internal fun digitLabel(text: String, description: String, resource: String): Char? {
        val label = text.trim().ifEmpty { description.trim() }
        if (label.length == 1 && label[0] in '0'..'9') return label[0]
        // Keypads may announce "2 ABC". Require a separated digit, never a number embedded in text.
        if (label.length > 1 && label[0] in '0'..'9' && label[1].isWhitespace()) return label[0]
        return Regex("(?:key|digit)([0-9])$").find(resource.substringAfterLast('/'))?.groupValues?.get(1)?.single()
    }

    private fun nodes(service: DoppelAccessibilityService): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        // Do not follow our wake Activity, IME, underlying app, or an overlay impersonating a keypad.
        service.windows.filter { it.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            .mapNotNull { it.root }.filter { it.packageName?.toString() == "com.android.systemui" }.forEach(pending::add)
        while (pending.isNotEmpty() && result.size < 500) {
            val node = pending.removeFirst()
            if (node.isVisibleToUser) result.add(node)
            repeat(node.childCount) { node.getChild(it)?.let(pending::add) }
        }
        return result
    }
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var candidate: AccessibilityNodeInfo? = node
        repeat(3) {
            val current = candidate ?: return null
            if (current.packageName?.toString() != "com.android.systemui" || !current.isVisibleToUser || !current.isEnabled) return null
            if (current.isClickable) return current
            candidate = current.parent
        }
        return null
    }
    private fun keypad(nodes: List<AccessibilityNodeInfo>): Map<Char, AccessibilityNodeInfo> {
        val keys = linkedMapOf<Char, AccessibilityNodeInfo>()
        val duplicates = HashSet<Char>()
        for (node in nodes) {
            if (node.isPassword || node.isEditable) continue
            val digit = digitLabel(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(), node.viewIdResourceName.orEmpty()) ?: continue
            val target = clickable(node) ?: continue
            val prior = keys.put(digit, target)
            if (prior != null && prior != target) duplicates.add(digit)
        }
        return if (keys.size == 10 && duplicates.isEmpty()) keys else emptyMap()
    }
    private fun confirm(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? = nodes.firstNotNullOfOrNull { node ->
        val id = node.viewIdResourceName.orEmpty().substringAfterLast('/')
        val label = node.text?.toString().orEmpty().ifBlank { node.contentDescription?.toString().orEmpty() }.trim()
        if (id in setOf("key_enter", "enter_key", "keyguard_done_button") || label in setOf("确认", "确定", "完成", "解锁", "Enter", "OK", "Unlock")) clickable(node) else null
    }

    fun unlock(service: DoppelAccessibilityService, credential: AutomaticUnlockCredentials.Credential,
               onCredentialInput: () -> Unit = {}, current: () -> Boolean): Boolean {
        val lock = service.getSystemService(KeyguardManager::class.java)
        check(lock.isDeviceSecure) { "System credential is required" }
        fun unlocked() = !lock.isDeviceLocked && !lock.isKeyguardLocked
        // Waking/requestDismissKeyguard is handled by the native Activity. Wait for its real bouncer.
        val until = SystemClock.elapsedRealtime() + 12000
        var page = emptyList<AccessibilityNodeInfo>()
        while (current() && SystemClock.elapsedRealtime() < until) {
            if (unlocked()) return true
            page = nodes(service)
            if (credential.kind == AutomaticUnlockCredentials.Kind.PIN && keypad(page).isNotEmpty() ||
                credential.kind == AutomaticUnlockCredentials.Kind.PASSWORD && page.any { it.isPassword && it.isEditable }) break
            Thread.sleep(120)
        }
        if (!current() || unlocked()) return current() && unlocked()
        // Never append to somebody's partially entered password or attempt a second password.
        val entry = page.firstOrNull { it.isPassword || it.viewIdResourceName.orEmpty().substringAfterLast('/') in setOf("pinEntry", "passwordEntry") } ?: return false
        if (!entry.text.isNullOrEmpty()) return false
        val needsConfirmation = confirm(page) != null
        when (credential.kind) {
            AutomaticUnlockCredentials.Kind.PIN -> for (digit in credential.value) {
                if (!current() || unlocked()) return false
                val target = keypad(nodes(service))[digit] ?: return false
                if (!target.refresh() || !target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return false
                onCredentialInput()
                Thread.sleep(100)
            }
            AutomaticUnlockCredentials.Kind.PASSWORD -> {
                val target = nodes(service).singleOrNull { it.isPassword && it.isEditable } ?: return false
                if (!current() || !target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, java.nio.CharBuffer.wrap(credential.value))
                    })) return false
                onCredentialInput()
            }
        }
        // Some keyguards submit PIN automatically; others expose an explicit confirmation key.
        Thread.sleep(250)
        if (!current()) return false
        if (!unlocked()) {
            val latest = nodes(service)
            val button = if (needsConfirmation) confirm(latest) else null
            if (button != null) button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            else if (credential.kind == AutomaticUnlockCredentials.Kind.PASSWORD && android.os.Build.VERSION.SDK_INT >= 30)
                latest.singleOrNull { it.isPassword && it.isEditable }?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        val submittedUntil = SystemClock.elapsedRealtime() + 4000
        while (current() && !unlocked() && SystemClock.elapsedRealtime() < submittedUntil) Thread.sleep(100)
        return current() && unlocked()
    }
}

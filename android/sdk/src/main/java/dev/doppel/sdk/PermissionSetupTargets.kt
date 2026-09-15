package dev.doppel.sdk

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.view.accessibility.AccessibilityNodeInfo

/** Narrow native targets for the permission request that this Activity just opened. */
internal object PermissionSetupTargets {
    fun notificationAccess(context: Context): Boolean {
        val own = ComponentName(context, LoginNotificationService::class.java)
        return android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == own }
    }

    fun isSystemPackage(context: Context, name: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(name, 0).flags and
            (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }.getOrDefault(false)

    /** All returned nodes are owned by the caller and recycled together after the action. */
    fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            result += node
            if (depth >= 25 || result.size >= 250) return
            for (i in 0 until node.childCount) {
                if (result.size >= 250) break
                node.getChild(i)?.let { walk(it, depth + 1) }
            }
        }
        walk(root, 0)
        return result
    }

    fun text(node: AccessibilityNodeInfo) = "${node.text?.toString().orEmpty()} ${node.contentDescription?.toString().orEmpty()}"
    fun restricted(nodes: List<AccessibilityNodeInfo>) = nodes.any { node ->
        listOf("受限设置", "Restricted setting", "由管理员", "disabled by admin").any { text(node).contains(it, true) }
    }

    fun ownsPage(nodes: List<AccessibilityNodeInfo>, names: Set<String>) = nodes.any { node ->
        names.any { it.isNotBlank() && text(node).contains(it) }
    }

    fun pageContains(nodes: List<AccessibilityNodeInfo>, phrases: List<String>) = nodes.any { node ->
        node.isVisibleToUser && phrases.any { text(node).contains(it, ignoreCase = true) }
    }

    fun runtimeAllow(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        // Never select a deny button or a transient one-time permission.
        for (id in listOf("permission_allow_foreground_only_button", "permission_allow_button")) {
            val matches = nodes.filter { it.isVisibleToUser && it.isEnabled && it.isClickable && it.viewIdResourceName?.substringAfterLast('/') == id }
            if (matches.size == 1) return matches.single()
        }
        return null
    }

    fun specialSwitch(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        val switches = nodes.filter { it.isVisibleToUser && it.isEnabled && it.isCheckable &&
            (it.className?.toString()?.contains("Switch") == true || it.viewIdResourceName?.substringAfterLast('/') in setOf("switch_widget", "switch_bar", "switch_main")) }
        return switches.singleOrNull()?.takeUnless { it.isChecked }
    }

    fun allowConfirmation(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? = nodes.filter { node ->
        node.isVisibleToUser && node.isEnabled && node.isClickable &&
            node.viewIdResourceName in setOf("android:id/button1", "com.android.settings:id/allow_button") &&
            node.text?.toString()?.trim()?.let { it in setOf("允许", "开启", "确定", "Allow", "Turn on", "OK") } == true
    }.singleOrNull()

    /** Used only if Android ignored a package-specific Settings deep link and shows an app list. */
    fun clickAppRow(nodes: List<AccessibilityNodeInfo>, names: Set<String>, isCurrent: () -> Boolean): Boolean {
        val labels = nodes.filter { it.isVisibleToUser && it.isEnabled && it.text?.toString() in names }
        if (labels.size != 1) return false
        return clickControl(labels.single(), isCurrent)
    }

    fun clickControl(node: AccessibilityNodeInfo, isCurrent: () -> Boolean): Boolean {
        @Suppress("DEPRECATION")
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        try {
            repeat(4) {
                val candidate = current ?: return false
                if (candidate.windowId != node.windowId || !candidate.isVisibleToUser || !candidate.isEnabled) return false
                if (candidate.isClickable) return isCurrent() && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                val parent = candidate.parent
                @Suppress("DEPRECATION")
                candidate.recycle()
                current = parent
            }
            return false
        } finally {
            @Suppress("DEPRECATION")
            current?.recycle()
        }
    }
}

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.Manifest
import android.app.Activity
import android.app.UiAutomation
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DirectMode
import dev.doppel.sdk.DoppelAccessibilityService
import dev.doppel.sdk.PermissionSetupActivity
import dev.doppel.sdk.PermissionSetupTargets
import dev.doppel.sdk.TaskSubmissionGate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Host prepares a missing runtime grant before instrumentation (revoking it inside kills the app).
 * Accessibility must already be manually enabled. Zero-assist and explicitly assisted runs are separate.
 */
class PermissionSetupDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private fun grants(): Map<String, Boolean> = linkedMapOf(
        "accessibility" to (DoppelAccessibilityService.instance != null),
        "overlay" to Settings.canDrawOverlays(context),
        "microphone" to granted(Manifest.permission.RECORD_AUDIO),
        "notifications" to ((Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)) &&
            context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()),
        "calendar" to granted(Manifest.permission.READ_CALENDAR),
        "notification_access" to PermissionSetupTargets.notificationAccess(context))
    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun active(activity: Activity): Boolean = PermissionSetupActivity::class.java.getDeclaredField("active")
        .apply { isAccessible = true }.getBoolean(activity)
    /** This Activity immediately opens Android Settings, so waiting for app-wide idle can time out
     * after the correct launch. A class-specific monitor observes creation without blocking it.
     */
    private fun launchSetup(): Activity {
        val monitor = inst.addMonitor(PermissionSetupActivity::class.java.name, null, false)
        var delivered = false
        try {
            context.startActivity(Intent(context, PermissionSetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
            val activity = inst.waitForMonitorWithTimeout(monitor, 8_000)
                ?: throw AssertionError("The permission settings Activity must be created")
            delivered = true
            return activity
        } finally {
            if (!delivered) monitor.lastActivity?.let(::finishOwned)
            inst.removeMonitor(monitor)
        }
    }
    private fun finishOwned(activity: Activity) {
        inst.runOnMainSync {
            if (!activity.isDestroyed) {
                if (activity.isTaskRoot) activity.finishAndRemoveTask() else activity.finish()
            }
        }
        val until = SystemClock.elapsedRealtime() + 5_000
        while (!activity.isDestroyed && SystemClock.elapsedRealtime() < until) Thread.sleep(50)
        assertTrue("The fixture must destroy its own permission Activity", activity.isDestroyed)
    }
    private fun history() = context.noBackupFilesDir.walkTopDown().filter { it.isFile &&
        (it.name.contains("run") || it.name.contains("chat") || it.name.contains("conversation") || it.name.contains("usage")) }
        .associate { it.relativeTo(context.noBackupFilesDir).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }

    @Test fun nativeSetupGrantsMissingPermissionsWithoutTaskOrConversation() = verifySetup(false)

    @Test fun protectedPermissionPagesExplainManualConfirmationAndContinueAfterUserAllows() = verifySetup(true)

    private fun verifySetup(allowUserAssistance: Boolean) {
        inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        assertNull("Host must retain/finish active work before this fixture", DeviceWorkerService.instance)
        assertNotNull("Accessibility is the one initial manual prerequisite", DoppelAccessibilityService.instance)
        assertFalse(TaskSubmissionGate.creating.get())
        val before = grants()
        assertTrue("Host must revoke at least one runtime permission before starting instrumentation",
            listOf("microphone", "notifications", "calendar").any { before[it] == false })
        val priorHistory = history()
        val priorPointer = context.getSharedPreferences("doppel", 0).getString("active_run", null)
        val folder = File(context.getExternalFilesDir(null), "permission-setup/${System.currentTimeMillis()}").apply { check(mkdirs()) }
        val pending = JSONArray()
        val assistance = JSONArray()
        val assistedTargets = mutableSetOf<String>()
        val manualReads = mutableMapOf<Int, Pair<Long, Int>>()
        val report = JSONObject().put("before", JSONObject(before)).put("model_requests", 0).put("submitted_tasks", 0)
            .put("pending_diagnostics", pending).put("mode", if (allowUserAssistance) "user_assisted_system_confirmation" else "zero_assistance")
            .put("manual_assistance", assistance).put("manual_assistance_count", 0)
        inst.sendStatus(0, android.os.Bundle().apply { putString("permission_setup_report", folder.absolutePath) })
        var activity: Activity? = null
        try {
            val started = SystemClock.elapsedRealtime()
            activity = launchSetup()
            val owner = activity!!
            var running = true
            var progress = ""
            var progressAt = SystemClock.elapsedRealtime()
            var recordedProgress = ""
            while (running && SystemClock.elapsedRealtime() - started < 380_000) {
                var state = JSONObject()
                inst.runOnMainSync { running = active(owner); state = setupState(owner) }
                val key = state.toString()
                val now = SystemClock.elapsedRealtime()
                if (key != progress) { progress = key; progressAt = now }
                if (running && now - progressAt >= 5_000 && recordedProgress != key && pending.length() < 12) {
                    recordedProgress = key
                    pending.put(pendingDiagnostic(state).put("elapsed_ms", now - started).put("unchanged_ms", now - progressAt))
                    File(folder, "report.json").writeText(report.toString(2))
                }
                if (running && allowUserAssistance && state.optBoolean("manualRequired")) {
                    val index = state.getInt("index")
                    val firstManual = manualReads.getOrPut(index) { now to state.getInt("assistReads") }
                    assertEquals("After explaining a protected page, the app must stop trying to read its nodes", firstManual.second, state.getInt("assistReads"))
                    var prompt = ""
                    inst.runOnMainSync {
                        prompt = (PermissionSetupActivity::class.java.getDeclaredField("summary").apply { isAccessible = true }.get(owner) as android.widget.TextView).text.toString()
                    }
                    assertTrue("Restricted native controls must be explained before any user assistance", prompt.contains("手动确认"))
                    val click = if (now - firstManual.first >= 1_200) assistAsUser(state, assistedTargets) else null
                    if (click != null) {
                        assistance.put(click.put("elapsed_ms", now - started))
                        report.put("manual_assistance_count", assistance.length())
                        File(folder, "report.json").writeText(report.toString(2))
                        Thread.sleep(500)
                    }
                }
                if (running) Thread.sleep(200)
            }
            assertFalse("The finite native setup must finish", running)
            val after = grants()
            report.put("after", JSONObject(after)).put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            File(folder, "report.json").writeText(report.toString(2))
            after.forEach { (permission, allowed) -> assertTrue("Native setup did not grant $permission; inspect its partial result", allowed) }
            if (allowUserAssistance) assertTrue("This case must really exercise a protected page requiring user confirmation", assistance.length() > 0)
            assertFalse("One-shot task creation gate is released", TaskSubmissionGate.creating.get())
            assertFalse("One-shot settings owner is released", DirectMode.settingsVisible)
            assertNull("No execution worker is created", DeviceWorkerService.instance)
            assertEquals(priorPointer, context.getSharedPreferences("doppel", 0).getString("active_run", null))
            assertEquals("Setup must retain exact task, chat and usage data", priorHistory, history())
            report.put("status", "passed")
        } finally {
            activity?.let(::finishOwned)
            assertFalse("Completion/failure must release the setup lease", TaskSubmissionGate.creating.get())
            File(folder, "report.json").writeText(report.toString(2))
            inst.sendStatus(0, android.os.Bundle().apply { putString("permission_setup_report", folder.absolutePath) })
        }
    }

    /** Only permission-runner state, never its gateway pointer, settings names or user preferences. */
    private fun setupState(activity: Activity): JSONObject = JSONObject().apply {
        for (name in listOf("index", "opened", "resumed", "dispatched", "settingsPackage", "returned", "active", "manualRequired", "assistReads")) {
            val value = PermissionSetupActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
            put(name, if (value is Set<*>) JSONArray(value.map { it.toString() }.sorted()) else value ?: JSONObject.NULL)
        }
    }

    /** No screenshot/private-app traversal. This captures just the bounded visible permission UI. */
    private fun pendingDiagnostic(state: JSONObject): JSONObject = JSONObject().put("runner", state).apply {
        val windows = JSONArray()
        DoppelAccessibilityService.instance?.windows?.take(12)?.forEach { window ->
            val item = JSONObject().put("id", window.id).put("type", window.type).put("focused", window.isFocused).put("active", window.isActive)
            window.root?.let { root ->
                try { item.put("package", root.packageName?.toString().orEmpty()) }
                finally { @Suppress("DEPRECATION") root.recycle() }
            }
            windows.put(item)
        }
        put("service_windows", windows)
        val root = DoppelAccessibilityService.instance?.activeRoot()
        if (root == null) { put("root_available", false); return@apply }
        val pkg = root.packageName?.toString().orEmpty()
        put("root_available", true).put("foreground_package", pkg).put("window_id", root.windowId)
        put("root_refresh", root.refresh())
        val allowed = pkg in setOf("com.android.settings", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller") && PermissionSetupTargets.isSystemPackage(context, pkg)
        if (!allowed) {
            put("tree_omitted", "Foreground is outside the fixture system permission pages")
            @Suppress("DEPRECATION") root.recycle()
            return@apply
        }
        val rows = JSONArray()
        var visited = 0
        fun scrub(value: CharSequence?): String = value?.toString().orEmpty()
            .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[email]")
            .replace(Regex("(?<![A-Za-z])[0-9]{4,}(?![A-Za-z])"), "[digits]").take(160)
        fun visit(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
            try {
                visited++
                if (node.packageName?.toString() != pkg) return
                if (node.isVisibleToUser) rows.put(JSONObject().put("depth", depth)
                    .put("class", node.className?.toString().orEmpty()).put("id", node.viewIdResourceName.orEmpty())
                    .put("text", if (node.isEditable || node.isPassword) "[input omitted]" else scrub(node.text))
                    .put("clickable", node.isClickable).put("enabled", node.isEnabled)
                    .put("checkable", node.isCheckable).put("checked", node.isChecked))
                if (depth >= 14) return
                for (i in 0 until node.childCount) { if (visited >= 120) break; node.getChild(i)?.let { visit(it, depth + 1) } }
            } finally { @Suppress("DEPRECATION") node.recycle() }
        }
        visit(root, 0)
        put("visible_system_nodes", rows).put("visited_nodes", visited)
    }

    /** Instrumentation stands in for the user's explicit click only AFTER production asks for it.
     * This is never called by the app and never turns a protected step into a zero-assist pass.
     */
    private fun assistAsUser(state: JSONObject, attempted: MutableSet<String>): JSONObject? {
        val automation = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val root = automation.rootInActiveWindow ?: return null
        val nodes = PermissionSetupTargets.nodes(root)
        try {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg !in setOf("com.android.settings", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
                    "com.android.packageinstaller", "com.google.android.packageinstaller")) return null
            val names = setOf(context.packageManager.getApplicationLabel(context.applicationInfo).toString(),
                context.packageManager.getServiceInfo(android.content.ComponentName(context, dev.doppel.sdk.LoginNotificationService::class.java), 0)
                    .loadLabel(context.packageManager).toString())
            if (!PermissionSetupTargets.ownsPage(nodes, names)) return null
            val index = state.getInt("index")
            val candidate = when (index) {
                2, 3, 4 -> PermissionSetupTargets.runtimeAllow(nodes)
                1, 5 -> PermissionSetupTargets.allowConfirmation(nodes) ?: PermissionSetupTargets.specialSwitch(nodes)
                else -> null
            } ?: return null
            val key = "$index:${candidate.viewIdResourceName}:${candidate.text}"
            if (key in attempted) return null
            attempted += key
            val accepted = PermissionSetupTargets.clickControl(candidate) { root.refresh() }
            assertTrue("The fixture's explicit user confirmation must be accepted by the real system control", accepted)
            return JSONObject().put("step_index", index).put("permission_page", pkg)
                .put("control_id", candidate.viewIdResourceName.orEmpty()).put("accepted", accepted)
                .put("actor", "instrumentation_simulated_user")
        } finally { nodes.forEach { @Suppress("DEPRECATION") it.recycle() } }
    }

    @Test fun userCancelAndExistingCreationKeepTaskStateIntact() {
        inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        assertNull(DeviceWorkerService.instance)
        assertFalse(TaskSubmissionGate.creating.get())
        val before = history()
        val pointer = context.getSharedPreferences("doppel", 0).getString("active_run", null)
        var activity: Activity? = null
        var refused: Activity? = null
        var ownGate = false
        try {
            activity = launchSetup()
            val owner = activity!!
            inst.runOnMainSync { owner.onBackPressed() }
            finishOwned(owner)
            activity = null
            assertFalse(TaskSubmissionGate.creating.get())
            assertFalse(DirectMode.settingsVisible)
            ownGate = TaskSubmissionGate.creating.compareAndSet(false, true)
            assertTrue(ownGate)
            refused = launchSetup()
            val rejected = refused!!
            inst.runOnMainSync {
                assertFalse("An existing creation must not be replaced", active(rejected))
                assertTrue("A refused Activity must not release another owner's lease", TaskSubmissionGate.creating.get())
            }
        } finally {
            try { activity?.let(::finishOwned); refused?.let(::finishOwned) }
            finally { if (ownGate) TaskSubmissionGate.creating.set(false) }
        }
        assertEquals(pointer, context.getSharedPreferences("doppel", 0).getString("active_run", null))
        assertEquals(before, history())
    }
}

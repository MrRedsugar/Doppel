@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")

package dev.doppel.developer

import android.Manifest
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real dialog effects, fixed production split_action commands. No model requests or persisted tasks. */
class PopupHandlingDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val ui by lazy { inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }
    private val fixture = "dev.doppel.testapp"
    private val permission = Manifest.permission.POST_NOTIFICATIONS
    private lateinit var service: DoppelAccessibilityService
    private lateinit var folder: File
    private lateinit var report: JSONObject
    private var session = ""

    private fun await(message: String, timeout: Long = 7000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        do { if (condition()) return; SystemClock.sleep(80) } while (SystemClock.elapsedRealtime() < until)
        assertTrue(message, condition())
    }
    private fun collect(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (root == null) emptyList() else
        listOf(root) + (0 until root.childCount).flatMap { collect(root.getChild(it)) }
    private fun allNodes(): List<AccessibilityNodeInfo> {
        val windows = ui.windows.sortedByDescending { it.layer }
        return try { windows.flatMap { collect(it.root) }.ifEmpty { collect(ui.rootInActiveWindow) } }
        finally { windows.forEach { it.recycle() } }
    }
    private fun <T> readNodes(block: (List<AccessibilityNodeInfo>) -> T): T {
        val nodes = allNodes()
        return try { block(nodes) } finally { nodes.forEach { it.recycle() } }
    }
    private fun stateOrNull(): JSONObject? = readNodes { nodes ->
        nodes.firstOrNull { it.packageName?.toString() == fixture && it.contentDescription?.startsWith("popup-result:") == true }
            ?.contentDescription?.toString()?.substringAfter("popup-result:")?.let(::JSONObject)
    }
    private fun state() = requireNotNull(stateOrNull()).also { assertEquals(session, it.getString("session")) }
    private fun has(text: String) = readNodes { nodes -> nodes.any { it.isVisibleToUser && it.text?.toString() == text } }
    private fun clickFixture(text: String) {
        await("Fixture control must exist: $text") { has(text) }
        assertTrue(readNodes { nodes -> nodes.first { it.isVisibleToUser && it.isClickable && it.text?.toString() == text }
            .performAction(AccessibilityNodeInfo.ACTION_CLICK) })
    }
    private fun target(text: String): Pair<Int, Rect> = readNodes { nodes ->
        val node = nodes.first { it.isVisibleToUser && it.isClickable && it.text?.toString() == text }
        node.windowId to Rect().also(node::getBoundsInScreen)
    }
    private fun request(kind: String) = JSONObject().put("id", UUID.randomUUID().toString())
        .put("run_id", "popup-fixture-$session").put("kind", kind).put("mode", "full").put("split_agent", true)
    private fun capture(name: String): JSONObject {
        var shot = JSONObject()
        repeat(3) {
            if (shot.optString("status") != "ok") {
                SystemClock.sleep(250)
                shot = service.execute(request("screenshot"))
            }
        }
        assertEquals(shot.optString("message"), "ok", shot.optString("status"))
        val bytes = Base64.decode(shot.getJSONObject("data").getString("image_base64"), Base64.DEFAULT)
        File(folder, "$name.png").writeBytes(bytes)
        return shot
    }
    private fun tap(shot: JSONObject, bounds: Rect, label: String): JSONObject {
        val frame = shot.getJSONObject("data").getJSONObject("visual_frame")
        return request("split_action").put("source", frame).put("action", JSONObject().put("status", "located")
            .put("action", "tap").put("target", label).put("duration_ms", 100)
            .put("points", JSONArray().put(JSONArray(listOf(bounds.exactCenterX() * 1000.0 / frame.getInt("display_width"),
                bounds.exactCenterY() * 1000.0 / frame.getInt("display_height"))))))
    }
    private fun checkDialogCapture(shot: JSONObject, windowId: Int) {
        val data = shot.getJSONObject("data")
        assertEquals("accessibility_window", data.getString("capture_backend"))
        assertEquals("The production pixels must come from the foreground dialog window", windowId, data.getInt("capture_window_id"))
        val labels = shot.getJSONObject("observation").getJSONArray("nodes").toString()
        assertTrue(labels.contains("关闭弹窗"))
        assertFalse("Dialog observation must not report the underlying button as its target", labels.contains("底层操作"))
        val bytes = Base64.decode(data.getString("image_base64"), Base64.DEFAULT)
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val green = pixels.count { (it and 0x00ffffff) == 0x001f6d53 }
            assertTrue("The real foreground dialog's green panel must be in delivered pixels", green > 50)
            report.put("foreground_dialog_marker_pixels", green)
        } finally { bitmap.recycle() }
    }
    private fun closeWithProductionGesture(name: String) {
        val located = target("关闭弹窗")
        val shot = capture("$name-dialog")
        checkDialogCapture(shot, located.first)
        val receipt = service.execute(tap(shot, located.second, "关闭前景弹窗"))
        report.put("${name}_close_receipt", receipt)
        assertEquals(receipt.optString("message"), "ok", receipt.optString("status"))
        assertEquals("accepted", receipt.getJSONObject("data").getString("action_state"))
        await("The real dialog must dismiss") { !has("关闭弹窗") }
        assertEquals(0, state().getInt("background_clicks"))
        assertEquals(1, state().getInt("closed"))
        capture("$name-after-close")
    }
    private fun preservedState(): Map<String, Any?> {
        val history = File(context.noBackupFilesDir, "direct-runs-v1.json")
        return mapOf("active_run" to context.getSharedPreferences("doppel", 0).getString("active_run", null),
            "rules" to context.getSharedPreferences("doppel_auto_triggers", 0).getString("rules", null),
            "tasks_sha256" to history.takeIf { it.isFile }?.readBytes()?.let { MessageDigest.getInstance("SHA-256").digest(it).toList() })
    }
    private fun scenario(name: String, work: () -> Unit) {
        ui
        assertTrue(Build.VERSION.SDK_INT >= 34)
        assertNull("Do not replace a user Worker", DeviceWorkerService.instance)
        assertFalse("Do not operate during any existing task", DirectRuntime.get(context).hasUnfinishedRun())
        assertFalse(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        service = requireNotNull(DoppelAccessibilityService.instance)
        assertFalse(service.guardVisible)
        val before = preservedState()
        session = UUID.randomUUID().toString()
        folder = File(context.getExternalFilesDir(null), "full-feature/popups/$name-${System.currentTimeMillis()}").apply { check(mkdirs()) }
        report = JSONObject().put("test", name).put("passed", false).put("model_calls", 0).put("persisted_tasks_created", 0)
            .put("scope", "real Android windows and production executor; fixed commands, not a model acceptance test")
        try { work(); report.put("passed", true) }
        catch (error: Throwable) { report.put("error", error.toString().take(1200)); throw error }
        finally {
            service.stopActionFeedback()
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val unchanged = before == preservedState()
            report.put("tasks_and_rules_unchanged", unchanged)
            if (!unchanged) report.put("passed", false)
            File(folder, "report.json").writeText(report.toString(2))
            assertTrue("Popup tests must preserve all tasks and rules", unchanged)
        }
    }
    private fun open() {
        context.startActivity(Intent().setClassName(fixture, "$fixture.PopupFixtureActivity")
            .putExtra("session", session).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("A fresh popup fixture must be visible") { stateOrNull()?.optString("session") == session }
        SystemClock.sleep(350)
    }

    @Test fun ordinaryDialogCaptureAndClickCloseOnlyTheForegroundWindow() = scenario("ordinary") {
        open()
        val base = capture("base")
        clickFixture("打开普通弹窗")
        await("A real AlertDialog must become foreground") { has("关闭弹窗") }
        assertNotEquals(base.getJSONObject("data").getInt("capture_window_id"), target("关闭弹窗").first)
        closeWithProductionGesture("ordinary")
        report.put("foreground_dialog_closed_without_underlying_click", true)
    }

    @Test fun popupAppearingAfterScreenshotMakesTheOldActionStaleWithoutAnyClick() = scenario("late") {
        open()
        val base = capture("before-popup")
        val oldCommand = tap(base, target("底层操作").second, "截图时可见的底层操作")
        clickFixture("延迟显示弹窗")
        await("The delayed AlertDialog must appear after the source screenshot") { has("关闭弹窗") }
        val receipt = service.execute(oldCommand)
        report.put("stale_receipt", receipt)
        assertEquals("stale", receipt.optString("status"))
        assertEquals("not_dispatched", receipt.getJSONObject("data").getString("action_state"))
        assertEquals("source_navigation_changed", receipt.getJSONObject("data").getString("reason_code"))
        assertEquals(0, state().getInt("background_clicks")); assertEquals(0, state().getInt("closed"))
        assertTrue(has("关闭弹窗"))
        closeWithProductionGesture("late")
        report.put("old_action_not_replayed", true)
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    private fun permissionFlags(): Set<String> {
        val line = shell("dumpsys package $fixture").lineSequence().firstOrNull {
            it.trimStart().startsWith("$permission: granted=")
        } ?: error("The fixture's runtime notification permission state must be readable")
        return line.substringAfter("flags=[", "").substringBefore(']').split('|').map(String::trim).filter(String::isNotBlank).toSet()
    }
    private fun clearUserPermissionFlags() { shell("pm clear-permission-flags --user current $fixture $permission user-set user-fixed") }
    private fun manualPermissionChoice() {
        val clicked = readNodes { nodes -> nodes.firstOrNull {
            it.isVisibleToUser && it.isEnabled && it.isClickable &&
                it.viewIdResourceName?.endsWith(":id/permission_deny_button") == true
        }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
        if (clicked) { report.put("manual_choice_driver", "instrumentation clicks the actual native Deny button"); return }
        // A permission controller can hide its accessibility tree even from UiAutomation.
        // Only this known disposable notification prompt receives manual keyboard input;
        // no such fallback is exposed to the app/model or production executor.
        report.put("manual_choice_driver", "instrumentation TAB/ENTER on disposable notification prompt")
        for (key in listOf(KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ENTER)) {
            assertTrue(ui.injectInputEvent(KeyEvent(KeyEvent.ACTION_DOWN, key), true))
            assertTrue(ui.injectInputEvent(KeyEvent(KeyEvent.ACTION_UP, key), true))
        }
    }

    @Test fun unreadableNotificationPermissionOffersManualChoiceAndFreshCaptureRecovers() = scenario("permission") {
        val originallyGranted = context.packageManager.checkPermission(permission, fixture) == PackageManager.PERMISSION_GRANTED
        val originalFlags = permissionFlags()
        assertTrue("Do not modify administrator-fixed notification permissions", originalFlags.none { it in setOf("POLICY_FIXED", "SYSTEM_FIXED") })
        val userFlags = setOf("USER_SET", "USER_FIXED")
        var changed = false
        try {
            changed = true
            if (originallyGranted) ui.revokeRuntimePermission(fixture, permission)
            clearUserPermissionFlags()
            open()
            clickFixture("请求通知权限")
            SystemClock.sleep(700)
            val native = ui.takeScreenshot()
            try { if (native != null) File(folder, "permission-native.png").outputStream().use { native.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { native?.recycle() }
            val receipt = service.execute(request("screenshot"))
            val data = receipt.optJSONObject("data") ?: JSONObject()
            val diagnostic = data.optJSONObject("read_diagnostic")
            report.put("permission_receipt", JSONObject(receipt.toString()).apply { optJSONObject("data")?.remove("image_base64") })
            if (receipt.optString("status") == "error") {
                assertEquals("The explicit unreadable-root branch must be the reason for this case", "root_unavailable", diagnostic?.optString("reason_code"))
                assertTrue(receipt.getString("message").contains("若出现系统权限弹窗"))
                assertTrue(receipt.getString("message").contains("手动选择后继续"))
                assertFalse(receipt.getString("message").contains("正在加载"))
                report.put("unreadable_root_manual_hint_verified", true)
            } else {
                assertEquals("A readable permission window is a supported device variant", "ok", receipt.optString("status"))
                assertTrue(receipt.getJSONObject("observation").getString("package_name").contains("permissioncontroller"))
                report.put("unreadable_root_manual_hint_verified", false).put("permission_tree_readable_on_this_device", true)
            }
            manualPermissionChoice()
            await("A manual system permission choice must return to the requesting activity") {
                stateOrNull()?.optBoolean("permission_completed") == true && !has("关闭弹窗")
            }
            assertEquals(0, state().getInt("background_clicks"))
            val recovered = capture("after-manual-choice")
            assertEquals(fixture, recovered.getJSONObject("observation").getString("package_name"))
            report.put("manual_choice_recovered_fresh_capture", true).put("permission_automatically_clicked_by_app", false)
        } finally {
            if (changed) {
                context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                if (originallyGranted) ui.grantRuntimePermission(fixture, permission) else ui.revokeRuntimePermission(fixture, permission)
                clearUserPermissionFlags()
                val restore = originalFlags.intersect(userFlags).map { it.lowercase().replace('_', '-') }
                if (restore.isNotEmpty()) shell("pm set-permission-flags --user current $fixture $permission ${restore.joinToString(" ")}")
                val restored = (context.packageManager.checkPermission(permission, fixture) == PackageManager.PERMISSION_GRANTED) == originallyGranted &&
                    permissionFlags().intersect(userFlags) == originalFlags.intersect(userFlags)
                report.put("original_notification_permission_and_user_flags_restored", restored)
                assertTrue("Restore the fixture's original notification permission and user-set/user-fixed flags", restored)
            }
        }
    }
}

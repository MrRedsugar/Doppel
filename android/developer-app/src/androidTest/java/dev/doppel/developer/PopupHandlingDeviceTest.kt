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
import android.view.accessibility.AccessibilityWindowInfo
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
    private fun foregroundWindowId(): Int? = service.windows.firstOrNull {
        it.isFocused && it.type in setOf(AccessibilityWindowInfo.TYPE_APPLICATION, AccessibilityWindowInfo.TYPE_SYSTEM)
    }?.id
    private fun windowEvidence(windows: List<AccessibilityWindowInfo>): JSONArray = try {
        JSONArray(windows.map { window ->
            val area = Rect().also(window::getBoundsInScreen)
            JSONObject().put("id", window.id).put("type", window.type).put("layer", window.layer)
                .put("focused", window.isFocused).put("active", window.isActive)
                .put("bounds", JSONArray(listOf(area.left, area.top, area.right, area.bottom)))
        })
    } finally { windows.forEach { it.recycle() } }
    private fun checkFullDisplayCapture(shot: JSONObject) {
        val data = shot.getJSONObject("data")
        assertEquals("accessibility_skip_screenshot", data.getString("capture_backend"))
        assertFalse("Our visible layers must not be cleared or hidden for capture", data.getBoolean("overlay_cleanup_performed"))
        assertFalse(data.has("overlay_reconstruction"))
        assertFalse(data.has("native_window_capture"))
        val frame = data.getJSONObject("visual_frame")
        val native = requireNotNull(ui.takeScreenshot())
        try {
            assertEquals(native.width, frame.getInt("display_width"))
            assertEquals(native.height, frame.getInt("display_height"))
        } finally { native.recycle() }
        val bytes = Base64.decode(data.getString("image_base64"), Base64.DEFAULT)
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            assertEquals(frame.getInt("image_width"), bitmap.width)
            assertEquals(frame.getInt("image_height"), bitmap.height)
            val fullAspect = frame.getInt("display_width").toDouble() / frame.getInt("display_height")
            assertEquals("Delivered pixels must retain the complete display aspect ratio", fullAspect,
                bitmap.width.toDouble() / bitmap.height, 1.0 / bitmap.height)
        } finally { bitmap.recycle() }
    }
    private fun checkNativePromptPixels(shot: JSONObject, windowId: Int) {
        val bytes = Base64.decode(shot.getJSONObject("data").getString("image_base64"), Base64.DEFAULT)
        val delivered = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        val native = requireNotNull(ui.takeScreenshot())
        var reference: Bitmap? = null
        try {
            val scaled = Bitmap.createScaledBitmap(native, delivered.width, delivered.height, true)
            reference = scaled
            val bounds = Rect().also { rect -> service.windows.first { it.id == windowId }.getBoundsInScreen(rect) }
            val left = (bounds.left.toLong() * delivered.width / native.width).toInt().coerceIn(0, delivered.width - 1)
            val right = (bounds.right.toLong() * delivered.width / native.width).toInt().coerceIn(left + 1, delivered.width)
            val top = (bounds.top.toLong() * delivered.height / native.height).toInt().coerceIn(0, delivered.height - 1)
            val bottom = (bounds.bottom.toLong() * delivered.height / native.height).toInt().coerceIn(top + 1, delivered.height)
            var error = 0L; var channels = 0L
            for (y in top until bottom step 4) for (x in left until right step 4) {
                val actual = delivered.getPixel(x, y); val expected = scaled.getPixel(x, y)
                for (shift in 0..16 step 8) {
                    error += kotlin.math.abs((actual shr shift and 255) - (expected shr shift and 255))
                    channels++
                }
            }
            assertTrue("The native prompt must cover a nonempty region", channels > 300)
            val mae = error.toDouble() / channels
            report.put("native_permission_region_rgb_mae", mae)
            assertTrue("Production pixels must actually contain the native prompt, not only its metadata (MAE=$mae)", mae < 8.0)
        } finally {
            if (reference !== native) reference?.recycle()
            native.recycle(); delivered.recycle()
        }
    }
    private fun checkDialogCapture(shot: JSONObject, windowId: Int) {
        val data = shot.getJSONObject("data")
        checkFullDisplayCapture(shot)
        assertEquals("The foreground action anchor must still be the dialog", windowId, requireNotNull(foregroundWindowId()))
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
            val dialogBounds = Rect().also { rect -> service.windows.first { it.id == windowId }.getBoundsInScreen(rect) }
            val frame = data.getJSONObject("visual_frame")
            val displayWidth = frame.getInt("display_width"); val displayHeight = frame.getInt("display_height")
            val outsideBackground = pixels.indices.count { index ->
                val x = (index % bitmap.width) * displayWidth / bitmap.width
                val y = (index / bitmap.width) * displayHeight / bitmap.height
                val value = pixels[index]
                val red = value shr 16 and 255; val greenChannel = value shr 8 and 255; val blue = value and 255
                !dialogBounds.contains(x, y) && blue > 20 && blue > red * 1.5 && blue > greenChannel * 1.5
            }
            assertTrue("The current blue underlying page outside the dialog must remain in the composite", outsideBackground > 50)
            report.put("foreground_dialog_marker_pixels", green).put("outside_dialog_background_pixels", outsideBackground)
        } finally { bitmap.recycle() }
    }
    private fun closeWithProductionGesture(name: String) {
        val shot = capture("$name-dialog")
        assertEquals(shot.getJSONObject("observation").getString("screen_id"),
            shot.getJSONObject("data").getJSONObject("visual_frame").getString("screen_id"))
        val windowId = requireNotNull(foregroundWindowId())
        checkDialogCapture(shot, windowId)
        // Dialog launch animations change bounds. Resolve the fixture target only after the
        // delivered screenshot and its foreground identity have been verified.
        val located = target("关闭弹窗")
        assertEquals("The target must belong to the captured foreground dialog", windowId, located.first)
        val command = tap(shot, located.second, "关闭前景弹窗")
        report.put("${name}_intended_target", JSONObject().put("window_id", located.first)
            .put("bounds", JSONArray(listOf(located.second.left, located.second.top, located.second.right, located.second.bottom)))
            .put("display_point", JSONArray(listOf(located.second.exactCenterX(), located.second.exactCenterY())))
            .put("normalized_points", command.getJSONObject("action").getJSONArray("points")))
        val receipt = service.execute(command)
        report.put("${name}_close_receipt", receipt)
        assertEquals(receipt.optString("message"), "ok", receipt.optString("status"))
        assertEquals("accepted", receipt.getJSONObject("data").getString("action_state"))
        var closedState: JSONObject? = null
        await("The fixture must report one completed dialog close") {
            stateOrNull()?.takeIf { it.optString("session") == session && it.optInt("closed") == 1 }
                ?.also { closedState = it } != null
        }
        assertEquals(0, requireNotNull(closedState).getInt("background_clicks"))
        assertEquals(1, requireNotNull(closedState).getInt("closed"))
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
        checkFullDisplayCapture(base)
        val baseWindow = requireNotNull(foregroundWindowId())
        clickFixture("打开普通弹窗")
        await("A real AlertDialog must become foreground") { has("关闭弹窗") }
        await("The production service must observe the dialog foreground transition") {
            foregroundWindowId()?.let { it != baseWindow } == true
        }
        assertNotEquals(baseWindow, target("关闭弹窗").first)
        closeWithProductionGesture("ordinary")
        report.put("foreground_dialog_closed_without_underlying_click", true)
    }

    @Test fun popupAppearingAfterScreenshotMakesTheOldActionStaleWithoutAnyClick() = scenario("late") {
        open()
        val base = capture("before-popup")
        val baseWindow = requireNotNull(foregroundWindowId())
        val oldCommand = tap(base, target("底层操作").second, "截图时可见的底层操作")
        report.put("source_frame", base.getJSONObject("data").getJSONObject("visual_frame"))
            .put("source_window_id", baseWindow)
        val requestedAt = SystemClock.elapsedRealtime()
        clickFixture("延迟显示弹窗")
        var dialogWindow: Int? = null
        await("The delayed dialog must belong to this fixture session in a new window") {
            dialogWindow = readNodes { nodes ->
                val currentSessionWindows = nodes.filter {
                    it.packageName?.toString() == fixture && it.contentDescription?.startsWith("popup-result:") == true &&
                        JSONObject(it.contentDescription.toString().substringAfter("popup-result:")).optString("session") == session
                }.map { it.windowId }.toSet()
                nodes.firstOrNull { it.isVisibleToUser && it.isClickable && it.text?.toString() == "关闭弹窗" &&
                    it.windowId != baseWindow && it.windowId in currentSessionWindows }?.windowId
            }
            dialogWindow != null
        }
        report.put("dialog_node_window_id", dialogWindow).put("fixture_wait_ms", SystemClock.elapsedRealtime() - requestedAt)
            .put("before_dispatch_ui_windows", windowEvidence(ui.windows))
            .put("before_dispatch_service_windows", windowEvidence(service.windows))
            .put("before_dispatch_navigation_generation", service.javaClass.getDeclaredField("navigationGeneration")
                .apply { isAccessible = true }.getInt(service))
        // Do not wait for the service cache: a real cross-client window race must remain visible.
        val receipt = service.execute(oldCommand)
        report.put("stale_receipt", receipt).put("after_dispatch_ui_windows", windowEvidence(ui.windows))
            .put("after_dispatch_service_windows", windowEvidence(service.windows))
            .put("after_dispatch_fixture_state", stateOrNull() ?: JSONObject.NULL)
        val after = ui.takeScreenshot()
        try { if (after != null) File(folder, "after-old-action-native.png").outputStream().use {
            after.compress(Bitmap.CompressFormat.PNG, 100, it)
        } } finally { after?.recycle() }
        assertEquals("stale", receipt.optString("status"))
        assertEquals("not_dispatched", receipt.getJSONObject("data").getString("action_state"))
        assertEquals("No part of the stale gesture may execute", 0, receipt.getJSONObject("data").getInt("completed_strokes"))
        assertTrue("Navigation may be detected before dispatch or during the touch handoff; no other veto is accepted",
            receipt.getJSONObject("data").getString("reason_code") in setOf("source_navigation_changed", "gesture_context_changed"))
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

    @Test fun notificationPermissionFullDisplayCaptureAndAvailableTargetGesture() = scenario("permission") {
        val originallyGranted = context.packageManager.checkPermission(permission, fixture) == PackageManager.PERMISSION_GRANTED
        val originalFlags = permissionFlags()
        assertTrue("Do not modify administrator-fixed notification permissions", originalFlags.none { it in setOf("POLICY_FIXED", "SYSTEM_FIXED") })
        val userFlags = setOf("USER_SET", "USER_FIXED")
        var changed = false
        var promptNeedsCleanup = false
        var promptWindowId: Int? = null
        try {
            changed = true
            if (originallyGranted) ui.revokeRuntimePermission(fixture, permission)
            clearUserPermissionFlags()
            open()
            val baseWindow = requireNotNull(foregroundWindowId())
            clickFixture("请求通知权限")
            await("The native permission controller must become the foreground window") {
                foregroundWindowId()?.let { it != baseWindow } == true
            }
            val promptWindow = requireNotNull(foregroundWindowId())
            promptWindowId = promptWindow
            promptNeedsCleanup = true
            SystemClock.sleep(400)
            val native = ui.takeScreenshot()
            try { if (native != null) File(folder, "permission-native.png").outputStream().use { native.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { native?.recycle() }
            val receipt = capture("permission-production")
            checkFullDisplayCapture(receipt)
            assertEquals("The native prompt must remain foreground throughout capture", promptWindow, requireNotNull(foregroundWindowId()))
            checkNativePromptPixels(receipt, promptWindow)
            report.put("permission_receipt", JSONObject(receipt.toString()).apply { optJSONObject("data")?.remove("image_base64") })
            val observation = receipt.getJSONObject("observation")
            val observedPackage = observation.getString("package_name")
            assertTrue("The capture must bind to the permission controller or its rootless window identity",
                observedPackage.contains("permissioncontroller") || observedPackage == "window:$promptWindow")
            report.put("permission_tree_available", observation.optBoolean("tree_available"))
                .put("native_permission_full_display_capture_verified", true)
            val deny = readNodes { nodes -> nodes.firstOrNull {
                it.windowId == promptWindow && it.isVisibleToUser && it.isEnabled && it.isClickable &&
                    it.viewIdResourceName?.endsWith(":id/permission_deny_button") == true
            }?.let { node -> Rect().also(node::getBoundsInScreen).takeUnless { it.isEmpty } } }
            if (deny != null) {
                val action = service.execute(tap(receipt, deny, "拒绝本次通知权限"))
                report.put("permission_deny_receipt", action)
                assertEquals(action.optString("message"), "ok", action.optString("status"))
                assertEquals("accepted", action.getJSONObject("data").getString("action_state"))
                await("The production gesture must dismiss the real permission prompt") {
                    stateOrNull()?.optBoolean("permission_completed") == true
                }
                promptNeedsCleanup = false
                assertFalse("The executor must choose Deny", state().getBoolean("permission_granted"))
                assertEquals(0, state().getInt("background_clicks"))
                val recovered = capture("after-production-deny")
                assertEquals(fixture, recovered.getJSONObject("observation").getString("package_name"))
                report.put("permission_automatically_clicked_by_app", true).put("production_permission_action_verified", true)
            } else {
                // Never use guessed coordinates or a manual driver as evidence of app/model recognition.
                report.put("permission_automatically_clicked_by_app", false).put("production_permission_action_verified", false)
                    .put("passed_scope", "Full-display native prompt capture only; no readable Deny target for this fixed-command test")
                    .put("model_permission_selection", "Not tested here; requires the separate real-model app test")
            }
        } finally {
            if (changed) {
                if (promptNeedsCleanup && promptWindowId != null && foregroundWindowId() == promptWindowId) {
                    report.put("manual_choice_used_only_for_cleanup", true)
                    runCatching { manualPermissionChoice() }
                        .onFailure { report.put("manual_cleanup_error", it.toString().take(300)) }
                }
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

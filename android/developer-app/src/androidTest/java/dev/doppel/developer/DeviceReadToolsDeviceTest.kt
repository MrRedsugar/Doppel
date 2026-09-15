@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")

package dev.doppel.developer

import android.Manifest
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.SystemClock
import android.provider.CalendarContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DeviceReadTools
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DoppelAccessibilityService
import dev.doppel.sdk.LoginAssist
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Actual Android providers and focused clipboard Activity. Fixture contents only; no model requests. */
class DeviceReadToolsDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val automation by lazy { inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }
    private val fixture = "dev.doppel.testapp"
    private fun execute(kind: String, args: JSONObject = JSONObject()) = DeviceReadTools.execute(context, kind, args)
    private fun preflight() {
        assertTrue("Do not interrupt a running user task", DeviceWorkerService.instance?.isPaused != false)
        assertFalse(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        assertFalse("Preserve an active user login session", LoginAssist.sensitiveSessionActive())
        automation
        if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
    }
    private fun launch() {
        context.startActivity(Intent().setClassName(fixture, "$fixture.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        find { it.text?.toString() == "模拟场景" }.recycle()
    }
    private fun report(name: String, value: JSONObject) {
        val folder = File(context.getExternalFilesDir(null), "full-feature/native-read-tools").apply { mkdirs() }
        File(folder, "$name.json").writeText(value.put("model_network_calls", 0).toString(2))
    }

    @Test fun foregroundClipboardReadReturnsToAppAndHidesSensitiveOrVerificationContent() {
        preflight(); val before = protectedState(); var passed = false
        try {
            launch(); click("剪贴板读取验证"); click("写入普通剪贴板")
            val text = execute("read_clipboard")
            assertEquals(text.optString("message"), "ok", text.getString("status"))
            assertEquals("Doppel clipboard fixture 20260916", text.getJSONObject("data").getString("text"))
            assertTrue(text.getJSONObject("data").getBoolean("screen_changed"))
            assertFalse("The planner receives the truncation flag inside data", text.getJSONObject("data").getBoolean("truncated"))
            assertFalse("Tool metadata must not bypass the data envelope", text.has("truncated"))
            await("Clipboard reading must return to the source app") { DoppelAccessibilityService.instance?.foregroundPackage() == fixture }
            click("写入代码讨论剪贴板")
            val discussion = execute("read_clipboard")
            assertEquals("Please review this code before merging.", discussion.getJSONObject("data").getString("text"))
            click("写入敏感剪贴板")
            val secret = execute("read_clipboard")
            assertEquals("ok", secret.getString("status")); assertTrue(secret.getJSONObject("data").getBoolean("sensitive"))
            assertFalse(secret.toString().contains("fixture-clipboard-secret"))
            click("写入验证码剪贴板")
            val code = execute("read_clipboard")
            assertEquals("ok", code.getString("status")); assertFalse(code.toString().contains("246810"))
            val cancelled = DeviceReadTools.execute(context, "read_clipboard", JSONObject()) { false }
            assertEquals("cancelled", cancelled.getString("status"))
            assertEquals(fixture, DoppelAccessibilityService.instance?.foregroundPackage())
            passed = true
        } finally {
            runCatching { click("恢复原剪贴板") }.getOrElse { throw AssertionError("The fixture must restore the original clipboard", it) }
            val preserved = before == protectedState()
            report("clipboard", JSONObject().put("passed", passed && preserved).put("original_state_preserved", preserved)
                .put("real_focused_activity", true).put("sensitive_and_code_hidden", passed))
            assertTrue(preserved)
        }
    }

    @Test fun activeNotificationsAreActuallyReadAndOldCodesNeverLeaveTheDevice() {
        preflight(); val before = protectedState()
        val hadPostPermission = context.packageManager.checkPermission(Manifest.permission.POST_NOTIFICATIONS, fixture) == PackageManager.PERMISSION_GRANTED
        var binding: AutoCloseable? = null; var passed = false
        try {
            if (!LoginAssist(context).notificationAccess()) {
                val missing = execute("read_notifications")
                assertEquals("blocked", missing.getString("status"))
                assertEquals("notification_permission_required", missing.getJSONObject("data").getString("reason_code"))
            }
            binding = NotificationReadTestBinding.connect(inst)
            if (!hadPostPermission) automation.grantRuntimePermission(fixture, Manifest.permission.POST_NOTIFICATIONS)
            launch(); click("验证码通知验证"); click("发送普通测试通知"); click("发送旧测试验证码")
            var data = JSONObject()
            await("The actual listener must return the posted fixture notification") {
                val read = execute("read_notifications", JSONObject().put("package_name", fixture))
                assertEquals(read.optString("message"), "ok", read.getString("status"))
                data = read.getJSONObject("data")
                data.getJSONArray("items").let { rows -> (0 until rows.length()).any { rows.getJSONObject(it).optString("title") == "Doppel 普通通知测试" } }
            }
            assertFalse(data.toString().contains("135790"))
            assertTrue(data.getJSONArray("items").let { rows -> (0 until rows.length()).any { rows.getJSONObject(it).optBoolean("verification_code_hidden") } })
            assertFalse(data.getJSONObject("login_status").getBoolean("code_ready"))
            click("发送代码讨论通知")
            await("Ordinary code discussion must remain readable and app filtering must be exact") {
                val filtered = execute("read_notifications", JSONObject().put("package_name", fixture)).getJSONObject("data").getJSONArray("items")
                assertTrue((0 until filtered.length()).all { filtered.getJSONObject(it).getString("package_name") == fixture })
                (0 until filtered.length()).any { filtered.getJSONObject(it).optString("text") == "Please review this code before merging." }
            }
            click("发送分离标题验证码")
            val service = requireNotNull(DoppelAccessibilityService.instance)
            assertTrue(service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS))
            await("The actual notification shade must be foreground") { service.foregroundPackage() == "com.android.systemui" }
            var codeBounds: Rect? = null
            await("The test notification must show its fixed code in a separate native text node") {
                val nodes = all(automation.rootInActiveWindow)
                try {
                    val codeNode = nodes.firstOrNull { it.isVisibleToUser && it.text?.toString()?.trim() == "246810" }
                    codeBounds = codeNode?.let { Rect().also(it::getBoundsInScreen) }
                    codeBounds?.isEmpty == false
                } finally { nodes.forEach { it.recycle() } }
            }
            assertFalse("Split notification fields must not leak into observation", service.observe().toString().contains("246810"))
            var shot = JSONObject()
            await("The production SystemUI screenshot must retain only locally masked code pixels") {
                shot = service.execute(JSONObject().put("id", UUID.randomUUID().toString()).put("run_id", "native-read-privacy")
                    .put("kind", "screenshot").put("split_agent", true))
                shot.optString("status") == "ok"
            }
            assertFalse(shot.optJSONObject("observation").toString().contains("246810"))
            val screenshotData = shot.getJSONObject("data")
            assertTrue(screenshotData.getInt("privacy_mask_count") > 0)
            val frame = screenshotData.getJSONObject("visual_frame")
            assertEquals("com.android.systemui", frame.getString("package_name"))
            val bytes = android.util.Base64.decode(screenshotData.getString("image_base64"), android.util.Base64.NO_WRAP)
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                val bounds = requireNotNull(codeBounds)
                val x = (bounds.centerX().toLong() * bitmap.width / frame.getInt("display_width")).toInt().coerceIn(0, bitmap.width - 1)
                val y = (bounds.centerY().toLong() * bitmap.height / frame.getInt("display_height")).toInt().coerceIn(0, bitmap.height - 1)
                assertEquals("The actual code location must be covered in the exported image", 0xff333333.toInt(), bitmap.getPixel(x, y))
            } finally { bitmap.recycle() }
            assertTrue(service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            await("Return from notifications to the fixture") { service.foregroundPackage() == fixture }
            passed = true
        } finally {
            if (DoppelAccessibilityService.instance?.foregroundPackage() == "com.android.systemui")
                DoppelAccessibilityService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            runCatching { click("清理测试通知") }
            binding?.close()
            if (!hadPostPermission) automation.revokeRuntimePermission(fixture, Manifest.permission.POST_NOTIFICATIONS)
            val preserved = before == protectedState()
            report("notifications", JSONObject().put("passed", passed && preserved).put("original_state_preserved", preserved)
                .put("fixture_active_notification_read", passed).put("old_code_hidden", passed))
            assertTrue(preserved)
        }
    }

    @Test fun calendarReadsOwnRealProviderFixtureWithinDateWindowWithoutModifyingExistingEvents() {
        preflight(); val before = protectedState(); var passed = false
        val account = "doppel-read-fixture-${UUID.randomUUID()}"
        var calendarId: Long? = null
        val eventIds = mutableListOf<Long>()
        val calendarUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        try {
            assertEquals("error", execute("read_calendar", JSONObject().put("days", 32)).getString("status"))
            assertEquals("error", execute("read_calendar", JSONObject().put("days", 1).put("start_date", "2026-99-99")).getString("status"))
            if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
                assertEquals("calendar_permission_required", execute("read_calendar", JSONObject().put("days", 1)).getJSONObject("data").getString("reason_code"))
            automation.adoptShellPermissionIdentity(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            val values = ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, account); put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(CalendarContract.Calendars.NAME, account); put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Doppel 只读测试")
                put(CalendarContract.Calendars.OWNER_ACCOUNT, account); put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(CalendarContract.Calendars.VISIBLE, 1); put(CalendarContract.Calendars.SYNC_EVENTS, 1)
                put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, java.util.TimeZone.getDefault().id)
            }
            calendarId = ContentUris.parseId(requireNotNull(context.contentResolver.insert(calendarUri, values)))
            val start = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 12); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
            fun event(title: String, begin: Long) {
                val row = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calendarId); put(CalendarContract.Events.TITLE, title)
                    put(CalendarContract.Events.DTSTART, begin); put(CalendarContract.Events.DTEND, begin + 1_800_000)
                    put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                }
                eventIds += ContentUris.parseId(requireNotNull(context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, row)))
            }
            event("$account-today", start); event("$account-outside", start + 10L * 86_400_000)
            val result = execute("read_calendar", JSONObject().put("days", 1))
            assertEquals(result.optString("message"), "ok", result.getString("status"))
            val data = result.getJSONObject("data")
            assertTrue(data.getJSONArray("items").let { rows -> (0 until rows.length()).any { rows.getJSONObject(it).getString("title") == "$account-today" } })
            assertFalse(data.toString().contains("$account-outside"))
            assertTrue(data.getJSONArray("items").length() <= 50)
            val futureDate = java.time.Instant.ofEpochMilli(start + 10L * 86_400_000).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
            val future = execute("read_calendar", JSONObject().put("days", 1).put("start_date", futureDate))
            assertEquals("ok", future.getString("status"))
            assertTrue(future.getJSONObject("data").getJSONArray("items").let { rows -> (0 until rows.length()).any { rows.getJSONObject(it).getString("title") == "$account-outside" } })
            passed = true
        } finally {
            try {
                eventIds.forEach { assertEquals("Remove only the created test event", 1, context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, it), null, null)) }
                calendarId?.let { assertEquals("Remove only the created test calendar", 1, context.contentResolver.delete(calendarUri, "${CalendarContract.Calendars._ID} = ?", arrayOf(it.toString()))) }
            } finally { automation.dropShellPermissionIdentity() }
            val preserved = before == protectedState()
            report("calendar", JSONObject().put("passed", passed && preserved).put("original_state_preserved", preserved)
                .put("fixture_events_removed", true).put("date_window_verified", passed))
            assertTrue(preserved)
        }
    }

    private fun all(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { all(node.getChild(it)) }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        await("The disposable fixture must expose its expected control") {
            val nodes = all(automation.rootInActiveWindow)
            found = nodes.firstOrNull { it.packageName?.toString() == fixture && predicate(it) }
            nodes.filter { it !== found }.forEach { it.recycle() }; found != null
        }
        return requireNotNull(found)
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = find { it.text?.toString() == label }
        while (node != null) {
            val current = node; current.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            if (current.isClickable) { try { assertTrue(current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) } finally { current.recycle() }; inst.waitForIdleSync(); return }
            node = current.parent; current.recycle()
        }
        error("The fixture control is not clickable")
    }
    private fun await(message: String, timeout: Long = 10000, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) { if (predicate()) return; SystemClock.sleep(100) }
        assertTrue(message, predicate())
    }
    private fun protectedState(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        for (name in listOf("doppel", "doppel_auto_triggers", "doppel_credential_vault", "doppel_login", "doppel_automatic_unlock_state"))
            put(name, context.getSharedPreferences(name, 0).all.toMap())
        for (name in listOf("direct-runs-v1.json", "schedules-v1.json", "credential-vault-v1.bin", "model-providers-v1.bin", "automatic-unlock-v1.bin")) {
            val file = File(context.noBackupFilesDir, name)
            put(name, file.takeIf { it.isFile }?.readBytes()?.let { MessageDigest.getInstance("SHA-256").digest(it).toList() })
        }
    }
}

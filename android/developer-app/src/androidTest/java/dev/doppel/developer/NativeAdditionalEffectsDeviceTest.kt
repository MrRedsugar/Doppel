@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")
package dev.doppel.developer

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import dev.doppel.sdk.*
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.AssumptionViolatedException
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/** Real native effects using fresh production frames. Never grants notification-policy or shell-bridge permissions. */
class NativeAdditionalEffectsDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val ui by lazy { inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }
    private lateinit var service: DoppelAccessibilityService
    private lateinit var folder: File
    private lateinit var report: JSONObject
    private val rows = JSONArray()
    private val fixture = "dev.doppel.testapp"
    private val runId = "native-additional-${UUID.randomUUID()}"
    private var imageIndex = 0
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 8000
        do { if (condition()) return; SystemClock.sleep(80) } while (SystemClock.elapsedRealtime() < end)
        assertTrue(message, condition())
    }
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else
        listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun <T> withNode(description: String, block: (AccessibilityNodeInfo) -> T): T {
        val all = nodes(ui.rootInActiveWindow)
        return try { block(all.single { it.packageName?.toString() == fixture && it.contentDescription?.toString() == description }) }
        finally { all.forEach { it.recycle() } }
    }
    private fun foreground() = runCatching { service.foregroundPackage() }.getOrDefault("")
    private fun open(mode: String = "events") {
        val session = UUID.randomUUID().toString()
        context.startActivity(Intent().setClassName(fixture, "$fixture.GestureEffectsFixtureActivity")
            .putExtra("mode", mode).putExtra("session", session).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("The disposable fixture must be foreground") {
            val all = nodes(ui.rootInActiveWindow)
            try { all.any { it.packageName?.toString() == fixture && it.contentDescription?.toString()?.contains(session) == true } }
            finally { all.forEach { it.recycle() } }
        }
        SystemClock.sleep(300)
    }
    private fun command(kind: String) = JSONObject().put("kind", kind).put("id", UUID.randomUUID().toString())
        .put("run_id", runId).put("split_agent", true).put("mode", "full")
    private fun capture(): JSONObject {
        var shot = JSONObject()
        repeat(4) { if (shot.optString("status") != "ok") { SystemClock.sleep(350); shot = service.execute(command("screenshot")) } }
        assertEquals(shot.optString("message"), "ok", shot.optString("status"))
        File(folder, "frame-${imageIndex++}.png").writeBytes(Base64.decode(shot.getJSONObject("data").getString("image_base64"), Base64.DEFAULT))
        return shot
    }
    private fun act(kind: String, args: JSONObject = JSONObject(), split: Boolean = false): JSONObject {
        val shot = capture()
        val request = command(if (split) "split_action" else kind).put("source", shot.getJSONObject("data").getJSONObject("visual_frame"))
            .put("screen_id", shot.getJSONObject("observation").getString("screen_id"))
        if (split) request.put("action", JSONObject(args.toString()).put("status", "located").put("action", kind).put("target", "独立编辑器操作"))
        else args.keys().forEach { request.put(it, args.get(it)) }
        return service.execute(request).also { rows.put(JSONObject().put("kind", kind).put("receipt", it)); save() }
    }
    private fun accepted(result: JSONObject) { assertEquals(result.toString(), "ok", result.optString("status")) }
    private fun save() { File(folder, "report.json").writeText(report.toString(2)) }
    private fun scenario(name: String, test: () -> Unit) {
        ui
        assertNull("Do not replace an existing Worker", DeviceWorkerService.instance)
        assertFalse("Do not interrupt an existing task", DirectRuntime.get(context).hasUnfinishedRun())
        assertFalse(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        service = requireNotNull(DoppelAccessibilityService.instance)
        folder = File(context.getExternalFilesDir(null), "native-additional/$name-${System.currentTimeMillis()}").apply { check(mkdirs()) }
        report = JSONObject().put("test", name).put("status", "running").put("model_calls", 0).put("cases", rows)
        try { test(); report.put("status", "passed") }
        catch (unsupported: AssumptionViolatedException) { report.put("status", "unsupported").put("reason", unsupported.message); throw unsupported }
        catch (failure: Throwable) { report.put("status", "failed").put("failure", failure.toString().take(1200)); throw failure }
        finally { service.stopActionFeedback(); save() }
    }
    private fun recentsVisible(): Boolean {
        val all = nodes(ui.rootInActiveWindow)
        return try { all.any {
            val id = it.viewIdResourceName.orEmpty().lowercase()
            val label = it.text?.toString().orEmpty()
            it.isVisibleToUser && (listOf("overview_panel", "recents_view", "task_view", "clear_all", "clearall", "recents_container").any(id::contains) ||
                label in setOf("全部清除", "清除全部", "关闭全部", "Clear all", "CLEAR ALL"))
        } } finally { all.forEach { it.recycle() } }
    }

    @Test fun sourceBoundHomeRecentsAndBackChangeActualSystemPage() = scenario("navigation") {
        open()
        val launcher = requireNotNull(context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)).activityInfo.packageName
        accepted(act("home")); await("Home must show the actual launcher") { foreground() == launcher }
        assertFalse("Home must not already be the overview panel", recentsVisible()); capture()
        accepted(act("recents")); await("A visible system overview marker must prove Recents opened") { recentsVisible() }
        report.put("recents_ui_verified", true); capture()
        accepted(act("back")); await("Back must close overview and return to launcher") { foreground() == launcher && !recentsVisible() }
        // An actual Activity back-stack transition, separately from closing the system overview.
        open(); accepted(act("back")); await("Back must finish the root fixture Activity") { foreground() == launcher }
        report.put("home_package", launcher).put("back_closed_fixture", true); capture()
    }

    @Test fun ringVolumeAndRelativeAdjustmentHaveRealSystemEffects() = volume("ring", AudioManager.STREAM_RING)
    @Test fun alarmVolumeAndRelativeAdjustmentHaveRealSystemEffects() = volume("alarm", AudioManager.STREAM_ALARM)
    private fun volume(name: String, stream: Int) = scenario("volume-$name") {
        val audio = context.getSystemService(AudioManager::class.java)
        val notices = context.getSystemService(NotificationManager::class.java)
        val original = audio.getStreamVolume(stream); val originalMute = audio.isStreamMute(stream)
        val originalRinger = audio.ringerMode; val originalFilter = notices.currentInterruptionFilter
        assumeTrue("Device exposes fixed volume; no real adjustment is possible", !audio.isVolumeFixed)
        assumeTrue("Preserve silent/vibrate mode rather than changing notification policy", stream != AudioManager.STREAM_RING || originalRinger == AudioManager.RINGER_MODE_NORMAL)
        val min = audio.getStreamMinVolume(stream); val max = audio.getStreamMaxVolume(stream)
        assumeTrue("No nonzero intermediate level exists for this stream", max - min >= 3)
        open()
        try {
            val set = act("volume", JSONObject().put("stream", name).put("percent", 50))
            if (set.optString("status") != "ok" && (audio.isVolumeFixed || set.optString("message").contains("未允许")))
                assumeTrue("System policy does not allow this stream adjustment; no permission was granted", false)
            accepted(set)
            val middle = (min + (max - min) * .5).roundToInt()
            assertEquals(middle, audio.getStreamVolume(stream))
            accepted(act("adjust_volume", JSONObject().put("stream", name).put("direction", "up")))
            assertEquals((middle + 1).coerceAtMost(max), audio.getStreamVolume(stream))
            accepted(act("adjust_volume", JSONObject().put("stream", name).put("direction", "down")))
            assertEquals(middle, audio.getStreamVolume(stream))
            assertEquals("Volume testing must not alter the interruption filter", originalFilter, notices.currentInterruptionFilter)
            report.put("verified_levels", JSONArray(listOf(middle, (middle + 1).coerceAtMost(max), middle)))
        } finally {
            audio.setStreamVolume(stream, original, 0)
            if (audio.isStreamMute(stream) != originalMute)
                audio.adjustStreamVolume(stream, if (originalMute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
            assertEquals(original, audio.getStreamVolume(stream)); assertEquals(originalMute, audio.isStreamMute(stream))
            assertEquals(originalRinger, audio.ringerMode); assertEquals(originalFilter, notices.currentInterruptionFilter)
            report.put("original_stream_restored", true)
        }
    }

    @Test fun copyCurrentSelectionPreservesSourceAndPastesOnlySelectedText() = scenario("copy-current") {
        open("editor")
        try {
            withNode("editor-source") { node ->
                assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 6)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 12)
                }))
            }
            accepted(act("copy", JSONObject().put("selection", "current")))
            withNode("editor-source") { assertEquals("alpha middle omega", it.text?.toString()) }
            withNode("editor-destination") { assertTrue(it.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) }
            accepted(act("paste"))
            withNode("editor-destination") { assertEquals("middle", it.text?.toString()) }
            withNode("editor-source") { assertEquals("alpha middle omega", it.text?.toString()) }
            report.put("actual_clipboard_substring_verified_by_paste", true); capture()
        } finally {
            withNode("editor-restore-clipboard") { assertTrue(it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
            report.put("original_clipboard_restored", true)
        }
    }

    @Test fun currentSelectionCutsOnlySelectedTextAndPastesAtCursor() = scenario("clipboard-cut-cursor") {
        open("editor")
        fun select(description: String, start: Int, end: Int) = withNode(description) { node ->
            if (!node.isFocused) assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            if (node.textSelectionStart != start || node.textSelectionEnd != end)
                assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
                }))
            assertTrue(node.refresh())
            assertEquals(start, node.textSelectionStart); assertEquals(end, node.textSelectionEnd)
        }
        try {
            select("editor-source", 6, 12)
            accepted(act("copy", JSONObject().put("selection", "current")))
            withNode("editor-source") { assertEquals("alpha middle omega", it.text?.toString()) }
            withNode("editor-destination") { assertTrue(it.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) }
            accepted(act("paste"))
            withNode("editor-destination") { assertEquals("middle", it.text?.toString()) }
            select("editor-source", 6, 12)
            accepted(act("cut", JSONObject().put("selection", "current")))
            withNode("editor-source") { assertEquals("alpha  omega", it.text?.toString()) }
            select("editor-source", 6, 6)
            accepted(act("paste"))
            withNode("editor-source") { assertEquals("alpha middle omega", it.text?.toString()) }
            val empty = act("cut", JSONObject().put("selection", "current"))
            assertEquals("A cursor alone must not cut any text", "error", empty.optString("status"))
            withNode("editor-source") { assertEquals("alpha middle omega", it.text?.toString()) }
            report.put("current_selection_cut_verified", true).put("cursor_insertion_verified", true); capture()
        } finally {
            withNode("editor-restore-clipboard") { assertTrue(it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
            report.put("original_clipboard_restored", true)
        }
    }

    @Test fun splitTypeAndEnterProduceActualSearchSubmission() = scenario("split-editor") {
        val useTaskIme = InstrumentationRegistry.getArguments().getString("native_ime") == "true"
        fun readSetting(key: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("settings get secure $key"))
            .use { String(it.readBytes(), Charsets.UTF_8).trim().takeUnless { value -> value == "null" }.orEmpty() }
        val originalImes = readSetting("enabled_input_methods")
        val originalIme = readSetting("default_input_method")
        fun setting(key: String, value: String) {
            require(value.isEmpty() || value.matches(Regex("[A-Za-z0-9_.$/:;,-]+")))
            val command = if (value.isEmpty()) "settings delete secure $key" else "settings put secure $key $value"
            android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use { it.readBytes() }
        }
        var editorOpened = false
        try {
            if (useTaskIme) {
                val ownIme = "${context.packageName}/dev.doppel.sdk.ShellBridgeImeService"
                setting("enabled_input_methods", (originalImes.split(':').filter { it.isNotBlank() } + ownIme).distinct().joinToString(":"))
                setting("default_input_method", ownIme)
            }
            open("editor"); editorOpened = true
            withNode("editor-source") { assertTrue(it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
            if (useTaskIme) await("The explicitly enabled task IME must bind to the fixture") {
                ShellBridgeImeService.capability().optBoolean("input_available") &&
                    ShellBridgeImeService.capability().optString("package_name") == fixture
            }
            // IME binding precedes its window event. Capture after the actual focus/window transition.
            ui.waitForIdle(500, 5000)
            accepted(act("type", JSONObject().put("text", "中文搜索 42"), split = true))
            withNode("editor-source") { assertEquals("中文搜索 42", it.text?.toString()) }
            report.put("native_type_effect_verified", true)
            val capability = ShellBridgeImeService.capability()
            if (!capability.optBoolean("input_available") || capability.optString("package_name") != fixture) {
                val unavailable = act("enter", split = true)
                assertEquals("error", unavailable.optString("status"))
                assertEquals("input_connection_unavailable", unavailable.getJSONObject("data").optString("reason_code"))
                withNode("editor-result") { assertEquals("等待提交", it.text?.toString()) }
                assumeTrue("Doppel task IME is not selected/bound; enter cannot submit. Type passed; no keyboard configuration was changed.", false)
            }
            accepted(act("enter", split = true))
            await("The real EditText listener must receive exactly one SEARCH submission") {
                runCatching { withNode("editor-result") { it.text?.toString() == "提交 1：中文搜索 42" } }.getOrDefault(false)
            }
            report.put("actual_search_submission", "提交 1：中文搜索 42"); capture()
        } finally {
            try {
                if (editorOpened) {
                    withNode("editor-restore-clipboard") { assertTrue(it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
                    report.put("original_clipboard_restored", true)
                }
            } finally {
                if (useTaskIme) {
                    setting("default_input_method", originalIme); setting("enabled_input_methods", originalImes)
                    assertEquals(originalIme, readSetting("default_input_method"))
                    assertEquals(originalImes, readSetting("enabled_input_methods"))
                    report.put("original_ime_restored", true)
                }
            }
        }
    }
}

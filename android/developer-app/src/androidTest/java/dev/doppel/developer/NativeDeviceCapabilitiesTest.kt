@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")

package dev.doppel.developer

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DoppelAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Actual SystemUI, MediaStore screenshot records, AudioManager and native editable text; no model calls. */
class NativeDeviceCapabilitiesTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val automation by lazy { inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }
    private val fixture = "dev.doppel.testapp"
    private val runId = "native-tools-${UUID.randomUUID()}"
    private fun await(message: String, ready: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10000
        do { if (ready()) return; SystemClock.sleep(80) } while (SystemClock.elapsedRealtime() < until)
        assertTrue(message, ready())
    }
    private fun all(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else
        listOf(node) + (0 until node.childCount).flatMap { all(node.getChild(it)) }
    private fun find(visible: Boolean = true, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        await("The fixture control must be available") {
            val nodes = all(automation.rootInActiveWindow)
            found = nodes.firstOrNull { it.packageName?.toString() == fixture && (!visible || it.isVisibleToUser) && predicate(it) }
            nodes.filter { it !== found }.forEach { it.recycle() }
            found != null
        }
        return requireNotNull(found)
    }
    private fun click(text: String) {
        var node: AccessibilityNodeInfo? = find(visible = false) { it.text?.toString() == text }
        while (node != null) {
            val current = node
            current.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            if (current.isClickable) {
                try { assertTrue("The fixture button must accept its click: $text", current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
                finally { current.recycle() }
                inst.waitForIdleSync(); return
            }
            node = current.parent; current.recycle()
        }
        error("The fixture control is not clickable: $text")
    }
    private fun command(kind: String) = JSONObject().put("kind", kind).put("id", UUID.randomUUID().toString()).put("run_id", runId).put("split_agent", true)
    private fun capture(service: DoppelAccessibilityService): JSONObject {
        var shot = JSONObject()
        await("A current production screenshot is required for native commands") {
            shot = service.execute(command("screenshot"))
            shot.optString("status") == "ok" && shot.optJSONObject("data")?.optJSONObject("visual_frame") != null
        }
        return shot
    }
    private fun act(service: DoppelAccessibilityService, kind: String, args: JSONObject = JSONObject()): JSONObject {
        val shot = capture(service)
        val request = command(kind).put("source", shot.getJSONObject("data").getJSONObject("visual_frame"))
            .put("screen_id", shot.getJSONObject("observation").getString("screen_id"))
        args.keys().forEach { request.put(it, args.get(it)) }
        val result = service.execute(request)
        assertEquals(result.optString("message"), "ok", result.getString("status"))
        assertEquals("accepted", result.getJSONObject("data").getString("action_state"))
        return result
    }
    private fun screenshotIds(): Set<Long> {
        // UiAutomation tokenizes arguments itself; shell SQL quoting is not supported here.
        val query = "content query --uri content://media/external/images/media --projection _id:relative_path"
        val output = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(query)).use { String(it.readBytes(), Charsets.UTF_8) }
        check(!output.contains("Permission Denial") && !output.contains("Error while accessing")) { "Cannot verify native screenshot storage" }
        return output.lineSequence().filter { it.contains("Screenshots/") }
            .mapNotNull { Regex("_id=([0-9]+)").find(it)?.groupValues?.get(1)?.toLong() }.toSet()
    }
    private fun openMain(reset: Boolean = false) {
        context.startActivity(Intent().setClassName(fixture, "$fixture.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or if (reset) Intent.FLAG_ACTIVITY_CLEAR_TASK else Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        await("The fixture application must be foreground") { automation.rootInActiveWindow?.let { root -> try { root.packageName?.toString() == fixture } finally { root.recycle() } } == true }
        if (reset) find { it.text?.toString() == "模拟场景" }.recycle()
    }
    private fun windowDiagnostic(service: DoppelAccessibilityService, stage: String): JSONObject {
        fun root(node: AccessibilityNodeInfo?): JSONObject {
            if (node == null) return JSONObject().put("available", false)
            return try {
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                JSONObject().put("available", true).put("package_name", node.packageName?.toString().orEmpty())
                    .put("window_id", node.windowId).put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
            } finally { node.recycle() }
        }
        return JSONObject().put("stage", stage).put("elapsed_ms", SystemClock.elapsedRealtime())
            .put("service_root", root(service.activeRoot())).put("automation_root", root(automation.rootInActiveWindow))
            .put("windows", JSONArray(service.windows.map { window ->
                val bounds = Rect(); window.getBoundsInScreen(bounds)
                JSONObject().put("id", window.id).put("type", window.type).put("layer", window.layer)
                    .put("focused", window.isFocused).put("active", window.isActive)
                    .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))).put("root", root(window.root))
            }))
    }

    @Test fun realNativeSystemActionsAndClipboardMutationsProduceObservableEffects() {
        assertTrue("Do not interrupt a user task", DeviceWorkerService.instance?.isPaused != false)
        assertFalse(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        automation
        AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        val service = requireNotNull(DoppelAccessibilityService.instance)
        val audio = context.getSystemService(AudioManager::class.java)
        val originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val originalMute = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        val report = JSONObject().put("model_network_calls", 0).put("scope", "Real native actions, no model inference")
        val folder = File(context.getExternalFilesDir(null), "full-feature/native-device-tools").apply { mkdirs() }
        val windows = JSONArray(); report.put("window_diagnostics", windows)
        var stage = "setup"
        var clipboardSaved = false
        var passed = false
        try {
            // Reset a preceding test's child page only before saving the original clipboard.
            // Later returns retain this Activity instance and its in-memory clipboard backup.
            openMain(reset = true); click("剪贴板读取验证"); click("写入普通剪贴板"); clipboardSaved = true
            click("返回场景")
            context.startActivity(Intent().setClassName(fixture, "$fixture.InteractionFixtureActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            click("模拟登录")
            val marker = "native-copy-cut-paste-${UUID.randomUUID().toString().take(8)}"
            val input = find { it.isEditable && it.hintText?.toString() == "搜索关键词" }
            try {
                assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, marker) }))
                assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            } finally { input.recycle() }
            act(service, "copy", JSONObject().put("selection", "all"))
            find { it.isEditable && it.hintText?.toString() == "搜索关键词" }.let { selected ->
                try { assertEquals(marker, selected.text?.toString()) } finally { selected.recycle() }
            }
            report.put("copy_preserves_text", true)
            act(service, "cut", JSONObject().put("selection", "all"))
            find { it.isEditable && it.hintText?.toString() == "搜索关键词" }.let { try { assertTrue(it.isShowingHintText || it.text.isNullOrEmpty()) } finally { it.recycle() } }
            act(service, "paste")
            find { it.isEditable && it.hintText?.toString() == "搜索关键词" }.let { try { assertEquals(marker, it.text?.toString()) } finally { it.recycle() } }
            report.put("copy_cut_paste_verified", true)

            for (kind in listOf("notifications", "quick_settings")) {
                stage = "${kind}_opening"
                report.put("${kind}_receipt", act(service, kind).getJSONObject("data"))
                windows.put(windowDiagnostic(service, stage))
                await("The native panel must actually become the focused SystemUI window") {
                    service.foregroundPackage() == "com.android.systemui"
                }
                stage = "${kind}_capture"
                val panel = capture(service)
                assertEquals("com.android.systemui", panel.getJSONObject("observation").getString("package_name"))
                assertEquals("com.android.systemui", panel.getJSONObject("data").getJSONObject("visual_frame").getString("package_name"))
                assertEquals("accessibility_window", panel.getJSONObject("data").getString("capture_backend"))
                File(folder, "$kind.png").writeBytes(android.util.Base64.decode(panel.getJSONObject("data").getString("image_base64"), android.util.Base64.NO_WRAP))
                report.put("${kind}_opened", true)
                    .put("${kind}_window_capture_verified", true)
                windows.put(windowDiagnostic(service, stage).put("visual_frame", panel.getJSONObject("data").getJSONObject("visual_frame")))
                stage = "${kind}_closing"
                // Match the app's real close-and-observe path, including its 500 ms settle delay.
                val closes = JSONArray(); report.put("${kind}_close_receipts", closes)
                repeat(2) {
                    // Expanded quick settings may first collapse into the ordinary notification shade.
                    if (service.foregroundPackage() == "com.android.systemui") {
                        closes.put(act(service, "back").getJSONObject("data"))
                        windows.put(windowDiagnostic(service, "${stage}_${it + 1}"))
                    }
                }
                await("Closing the native panel must return to the fixture") { service.foregroundPackage() == fixture }
                windows.put(windowDiagnostic(service, stage))
            }
            stage = "volume"
            val changed = act(service, "adjust_volume", JSONObject().put("stream", "media").put("direction", if (originalVolume > 0) "down" else "up"))
            assertEquals(changed.getJSONObject("data").getInt("after_level"), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertNotEquals(originalVolume, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            act(service, "volume", JSONObject().put("stream", "media").put("percent", 50))
            report.put("media_volume_verified", true)
            SystemClock.sleep(2000)

            val beforeScreenshots = screenshotIds()
            stage = "system_screenshot"
            act(service, "system_screenshot")
            var newScreenshots = emptySet<Long>()
            await("The system screenshot must create a real new MediaStore screenshot record") {
                newScreenshots = screenshotIds() - beforeScreenshots
                newScreenshots.isNotEmpty()
            }
            report.put("system_screenshot_saved", true).put("created_screenshot_media_ids", JSONArray(newScreenshots.toList()))
                .put("screenshot_cleanup", "Only these newly created fixture screenshot IDs may be removed by the host; no gallery item is deleted here")
            passed = true
        } catch (failure: Throwable) {
            report.put("failed_stage", stage).put("failure", failure.message.orEmpty())
            runCatching { windows.put(windowDiagnostic(service, "failure_$stage")) }
            runCatching {
                val shot = service.execute(command("screenshot"))
                val data = shot.optJSONObject("data") ?: JSONObject()
                report.put("failure_capture", JSONObject().put("status", shot.optString("status"))
                    .put("message", shot.optString("message")).put("visual_frame", data.optJSONObject("visual_frame"))
                    .put("capture_backend", data.optString("capture_backend")))
                if (shot.optString("status") == "ok") File(folder, "failure-production.png")
                    .writeBytes(android.util.Base64.decode(data.getString("image_base64"), android.util.Base64.NO_WRAP))
            }
            runCatching {
                automation.takeScreenshot()?.let { bitmap ->
                    try { File(folder, "failure-ui.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { bitmap.recycle() }
                }
            }
            throw failure
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            if (audio.isStreamMute(AudioManager.STREAM_MUSIC) != originalMute)
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (originalMute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
            assertEquals(originalVolume, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            if (service.foregroundPackage() == "com.android.systemui") {
                automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                SystemClock.sleep(500)
            }
            if (clipboardSaved) {
                openMain(); click("剪贴板读取验证"); click("恢复原剪贴板")
                report.put("original_clipboard_restored", true)
            }
            report.put("original_volume_restored", true).put("passed", passed)
            File(folder, "report.json").writeText(report.toString(2))
        }
    }
}

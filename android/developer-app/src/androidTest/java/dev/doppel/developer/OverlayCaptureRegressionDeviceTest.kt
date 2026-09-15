package dev.doppel.developer

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.ActionFeedbackGeometry
import dev.doppel.sdk.CompanionOverlay
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DoppelAccessibilityService
import dev.doppel.sdk.Gateway
import dev.doppel.sdk.VoiceActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.util.UUID

/**
 * Opt-in compositor/capture component regression over the REAL foreground game.
 * Pre-open an idle Arknights screen and stop all Doppel tasks/workers. This test
 * never launches an Activity, injects input, captures a fabricated game, or calls
 * a model. The running JSON below is presentation state for CompanionOverlay.
 *
 * -e class dev.doppel.developer.OverlayCaptureRegressionDeviceTest
 * -e overlay_capture_live true -e overlay_capture_label unique-label
 * Optional: -e overlay_capture_cycles 3 -e overlay_capture_rebind true
 * Evidence: external files/overlay-capture/<label>/report.json and real PNGs.
 */
class OverlayCaptureRegressionDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val automation by lazy { inst.getUiAutomation(1).apply {
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    } }

    private fun <T> main(block: () -> T): T {
        var result: T? = null
        inst.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
    private fun type(name: String) = Class.forName("dev.doppel.sdk.$name", true, CompanionOverlay::class.java.classLoader)
    private fun invoke(owner: Any, name: String, vararg arguments: Any?): Any? {
        val method = owner.javaClass.methods.single { it.name == name && it.parameterCount == arguments.size }
        return try { method.invoke(owner, *arguments) }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }
    private fun await(message: String, timeout: Long = 3000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        assertTrue(message, condition())
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun taskBytes(): ByteArray? = File(context.noBackupFilesDir, "direct-runs-v1.json")
        .takeIf { it.isFile }?.readBytes()

    private fun foregroundGame(): JSONObject {
        var rows = JSONArray()
        var foreground = ""
        val deadline = SystemClock.elapsedRealtime() + 1500
        do {
            rows = JSONArray()
            automation.windows.forEach { window ->
                val root = window.root
                try {
                    val name = root?.packageName?.toString().orEmpty()
                    rows.put(JSONObject().put("type", window.type).put("focused", window.isFocused)
                        .put("active", window.isActive).put("package", name))
                    if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused) foreground = name
                } finally { @Suppress("DEPRECATION") root?.recycle() }
            }
            if (foreground.isNotEmpty()) break
            Thread.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        assertTrue("Keep the actual Bilibili/official Arknights game in foreground; test never navigates there",
            foreground in setOf("com.hypergryph.arknights.bilibili", "com.hypergryph.arknights"))
        return JSONObject().put("foreground_package", foreground).put("windows", rows)
    }

    private fun bindAlreadyEnabledService() {
        if (DoppelAccessibilityService.instance != null) return
        await("Accessibility may bind after instrumentation launch", 1500) {
            DoppelAccessibilityService.instance != null || args.getString("overlay_capture_rebind") == "true"
        }
        if (DoppelAccessibilityService.instance != null) return
        assertEquals("Only explicit overlay_capture_rebind=true may refresh the already-enabled service", "true",
            args.getString("overlay_capture_rebind"))
        val own = ComponentName(context, DoppelAccessibilityService::class.java)
        val original = Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services").orEmpty()
        require(original.matches(Regex("[A-Za-z0-9_.$/:]+")))
        val entries = original.split(':').filter { it.isNotBlank() }
        assertTrue("Grant production accessibility permission before this component test", entries.any { ComponentName.unflattenFromString(it) == own })
        val other = entries.filter { ComponentName.unflattenFromString(it) != own }.joinToString(":")
        fun shell(command: String) { ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() } }
        try {
            if (other.isEmpty()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $other")
        } finally { shell("settings put secure enabled_accessibility_services $original") }
        assertEquals(original, Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services"))
        await("Production accessibility service rebinds; all other services remain unchanged", 8000) {
            DoppelAccessibilityService.instance != null
        }
    }

    private fun overlayState(overlay: CompanionOverlay): JSONObject = main {
        val root = field(overlay, "root") as View
        val edge = field(overlay, "edge") as? View
        fun window(view: View?): JSONObject {
            val layout = view?.layoutParams as? WindowManager.LayoutParams
            return JSONObject().put("attached", view?.isAttachedToWindow == true)
                .put("window_alpha", layout?.alpha ?: -1f).put("view_alpha", view?.alpha ?: -1f)
                .put("visibility", view?.visibility ?: -1).put("window_type", layout?.type ?: -1)
                .put("flags", layout?.flags ?: -1)
        }
        JSONObject().put("companion", window(root)).put("edge", window(edge))
            .put("companion_params_alpha", (field(overlay, "params") as WindowManager.LayoutParams).alpha)
    }

    private fun assertAlpha(state: JSONObject, alpha: Double) {
        for (name in listOf("companion", "edge")) {
            val window = state.getJSONObject(name)
            assertTrue("$name must remain attached while capture toggles", window.getBoolean("attached"))
            assertEquals("$name compositor WindowManager alpha", alpha, window.getDouble("window_alpha"), 0.0001)
        }
        assertEquals(alpha, state.getDouble("companion_params_alpha"), 0.0001)
    }

    @Test fun captureReadinessOverActualArknights() {
        assumeTrue("Real-game screenshot component regression is opt-in", args.getString("overlay_capture_live") == "true")
        val label = args.getString("overlay_capture_label") ?: "capture-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        require(label.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val cycles = (args.getString("overlay_capture_cycles")?.toIntOrNull() ?: 3).also { require(it in 1..10) }
        val folder = File(context.getExternalFilesDir(null), "overlay-capture/$label")
        check(!folder.exists() && folder.mkdirs()) { "Use a fresh label; never overwrite earlier evidence" }
        val started = SystemClock.elapsedRealtime()
        val report = JSONObject().put("status", "running").put("label", label).put("cycles", JSONArray())
            .put("model_calls", 0).put("game_input_actions", 0).put("screen_source", "UiAutomation.takeScreenshot; real foreground Arknights")
        var stage = "preconditions"
        fun save() { report.put("stage", stage).put("elapsed_ms", SystemClock.elapsedRealtime() - started); File(folder, "report.json").writeText(report.toString(2)) }
        fun shot(name: String): JSONObject {
            val foreground = foregroundGame()
            val at = SystemClock.elapsedRealtime()
            val bitmap = requireNotNull(automation.takeScreenshot()) { "Real compositor screenshot unavailable" }
            val file = File(folder, "$name.png")
            try {
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                return foreground.put("file", file.name).put("width", bitmap.width).put("height", bitmap.height)
                    .put("sha256", digest(file.readBytes())).put("capture_ms", SystemClock.elapsedRealtime() - at)
            } finally { bitmap.recycle() }
        }
        val prefs = Gateway(context).prefs
        val position = listOf("companion_y", "companion_right_edge").associateWith { prefs.all[it] }
        val originalTaskBytes = taskBytes()
        var overlay: CompanionOverlay? = null
        var feedback: Any? = null
        var completion: Any? = null
        var failure: Throwable? = null
        var pauseCallbacks = 0
        try {
            save()
            assertNull("Stop the worker before this isolated component test", DeviceWorkerService.instance)
            assertFalse("Close the voice editor first", VoiceActivity.isVisible)
            assertTrue("Overlay permission must be configured in advance", Settings.canDrawOverlays(context))
            assertTrue(context.getSystemService(PowerManager::class.java).isInteractive)
            assertFalse(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
            originalTaskBytes?.let { bytes ->
                val runs = JSONArray(String(bytes, Charsets.UTF_8))
                repeat(runs.length()) { assertTrue("End unfinished tasks before testing UI components",
                    runs.getJSONObject(it).optString("status") in setOf("completed", "failed", "cancelled")) }
            }
            report.put("foreground_before", foregroundGame())
            bindAlreadyEnabledService()
            val service = requireNotNull(DoppelAccessibilityService.instance)
            stage = "baseline"; save()
            report.put("baseline", shot("00-real-game-baseline"))
            stage = "create_production_overlays"; save()
            main {
                overlay = CompanionOverlay(context) { pauseCallbacks++ }.also { it.show() }
                feedback = type("ActionFeedbackOverlay").getConstructor(Context::class.java).newInstance(service)
                completion = type("TaskCompletionDelivery").getConstructor(Context::class.java).newInstance(context)
            }
            val companion = requireNotNull(overlay)
            companion.display(JSONObject().put("id", "overlay-component-$label").put("status", "running")
                .put("message", "截图组件回归"), "正在查看屏幕")
            await("Production companion and running edge must both attach") {
                val state = overlayState(companion)
                state.getJSONObject("companion").getBoolean("attached") && state.getJSONObject("edge").getBoolean("attached")
            }
            Thread.sleep(160) // QA presentation only, never used as task screenshot readiness.
            repeat(cycles) { index ->
                val row = JSONObject().put("cycle", index + 1)
                report.getJSONArray("cycles").put(row)
                stage = "cycle_${index + 1}_before"; save()
                row.put("before_state", overlayState(companion).also { assertAlpha(it, 1.0) })
                row.put("before", shot("cycle-${index + 1}-before"))
                stage = "cycle_${index + 1}_hide"; save()
                val hideAt = SystemClock.elapsedRealtime()
                val ready = companion.hideForScreenshot()
                val hideMs = SystemClock.elapsedRealtime() - hideAt
                row.put("hide_ready", ready).put("hide_ms", hideMs); save()
                assertTrue("Background-game companion hide must acknowledge without waiting for Choreographer", ready)
                assertTrue("Companion hide exceeded 750 ms: $hideMs", hideMs < 750)
                row.put("hidden_state", overlayState(companion).also { assertAlpha(it, 0.0) })
                row.put("hidden", shot("cycle-${index + 1}-hidden"))
                stage = "cycle_${index + 1}_restore"; save()
                companion.restoreAfterScreenshot()
                await("Window alpha must restore to one") {
                    val state = overlayState(companion)
                    state.getJSONObject("companion").optDouble("window_alpha") == 1.0 &&
                        state.getJSONObject("edge").optDouble("window_alpha") == 1.0
                }
                row.put("restored_state", overlayState(companion).also { assertAlpha(it, 1.0) })
                Thread.sleep(80)
                row.put("restored", shot("cycle-${index + 1}-restored")); save()
            }
            stage = "clear_action_feedback"; save()
            assertTrue(companion.hideForScreenshot())
            val baseline = report.getJSONObject("baseline")
            val width = baseline.getInt("width"); val height = baseline.getInt("height")
            val geometry = requireNotNull(ActionFeedbackGeometry.create("tap", listOf(width / 3, height / 3, width / 2, height / 2), width, height))
                .copy(durationMs = 5000)
            val clearRows = JSONArray(); report.put("feedback_clear", clearRows)
            for (kind in listOf("pointer", "message")) {
                if (kind == "pointer") invoke(requireNotNull(feedback), "begin", geometry)
                else invoke(requireNotNull(feedback), "message", "截图组件检查 · 无游戏操作")
                await("Production $kind feedback must attach") { main { field(requireNotNull(feedback), if (kind == "pointer") "view" else "messageView") is View } }
                val row = JSONObject().put("kind", kind); clearRows.put(row)
                row.put("before", shot("feedback-$kind-before"))
                val attachedBeforeClear = main {
                    (field(requireNotNull(feedback), if (kind == "pointer") "view" else "messageView") as? View)?.isAttachedToWindow == true
                }
                row.put("attached_before_clear", attachedBeforeClear)
                assertTrue("$kind must still exist when clearBeforeScreenshot is measured", attachedBeforeClear)
                val at = SystemClock.elapsedRealtime()
                val cleared = invoke(requireNotNull(feedback), "clearBeforeScreenshot") as Boolean
                val elapsed = SystemClock.elapsedRealtime() - at
                row.put("ready", cleared).put("clear_ms", elapsed); save()
                assertTrue("Feedback clear must finish over a foreground game", cleared)
                assertTrue("Feedback clear exceeded 750 ms: $elapsed", elapsed < 750)
                main { assertNull(field(requireNotNull(feedback), "view")); assertNull(field(requireNotNull(feedback), "messageView")) }
                row.put("after", shot("feedback-$kind-after")); save()
            }
            // Remove every test window: the delivery path has no visible View,
            // and the game's Activity remains foreground throughout this test.
            main { companion.close() }; overlay = null
            stage = "completion_without_view"; save()
            val completionRows = JSONArray(); report.put("completion_without_view", completionRows)
            repeat(5) { index ->
                main { assertNull(field(requireNotNull(completion), "view")) }
                val at = SystemClock.elapsedRealtime()
                val ready = invoke(requireNotNull(completion), "hideBeforeCapture") as Boolean
                val elapsed = SystemClock.elapsedRealtime() - at
                completionRows.put(JSONObject().put("iteration", index + 1).put("ready", ready).put("elapsed_ms", elapsed))
                save()
                assertTrue("No-view completion hide must acknowledge without a frame callback", ready)
                assertTrue("No-view completion hide should be immediate, not its one-second timeout: $elapsed", elapsed < 250)
                invoke(requireNotNull(completion), "setCaptureHidden", false)
            }
            report.put("after_components", shot("99-real-game-after-components"))
            assertEquals("The test must never trigger companion input", 0, pauseCallbacks)
            assertNull("No worker must be started", DeviceWorkerService.instance)
            assertTrue("Task state must be byte-for-byte unchanged", originalTaskBytes?.contentEquals(taskBytes()) ?: (taskBytes() == null))
            report.put("status", "passed")
        } catch (error: Throwable) {
            failure = error
            report.put("status", "failed").put("failure_stage", stage).put("failure_class", error.javaClass.simpleName)
                .put("failure_message", error.message.orEmpty().take(500))
        } finally {
            try {
                main {
                    overlay?.close()
                    feedback?.let { invoke(it, "clear") }
                    completion?.let { invoke(it, "close") }
                }
                val editor = prefs.edit()
                position.forEach { (key, value) -> when (value) {
                    null -> editor.remove(key)
                    is Int -> editor.putInt(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                } }
                check(editor.commit())
                report.put("test_windows_removed", true).put("companion_position_restored", true)
            } catch (error: Throwable) {
                if (failure == null) failure = error
                report.put("status", "failed").put("cleanup_failure", error.javaClass.simpleName)
            }
            save()
            inst.sendStatus(0, Bundle().apply { putString("stream", "\nOverlay component evidence: ${folder.absolutePath}/report.json\n") })
        }
        failure?.let { throw AssertionError("Overlay capture regression failed at $stage; see report.json", it) }
    }
}

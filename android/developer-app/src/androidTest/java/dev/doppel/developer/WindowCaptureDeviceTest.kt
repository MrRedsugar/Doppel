package dev.doppel.developer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.ActionFeedbackGeometry
import dev.doppel.sdk.CompanionOverlay
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DoppelAccessibilityService
import dev.doppel.sdk.FirstUseConsent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One real Bilibili Arknights window capture on API 34+, no model/game input.
 * -e window_capture_live true -e window_capture_label unique-label
 * Evidence: external files/window-capture/<label>/report.json and PNGs.
 * Keep the actual game fullscreen and all required permissions already granted.
 */
class WindowCaptureDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val handler = Handler.createAsync(Looper.getMainLooper())
    private fun <T> main(block: () -> T): T {
        val task = FutureTask(block); check(handler.post(task))
        try { return task.get(2000, TimeUnit.MILLISECONDS) }
        finally { handler.removeCallbacks(task); if (!task.isDone) task.cancel(false) }
    }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun call(owner: Any, name: String, vararg args: Any?): Any? = owner.javaClass.declaredMethods.single {
        (it.name == name || it.name.startsWith(name + "$")) && it.parameterCount == args.size && !java.lang.reflect.Modifier.isStatic(it.modifiers)
    }.apply { isAccessible = true }.invoke(owner, *args)
    private fun await(message: String, timeout: Long = 5000, ready: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) { if (ready()) return; Thread.sleep(40) }
        assertTrue(message, ready())
    }
    private fun tasks() = File(context.noBackupFilesDir, "direct-runs-v1.json").takeIf { it.isFile }?.readBytes()
    private fun pixels(bitmap: Bitmap): JSONObject {
        val values = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(values, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return JSONObject().put("width", bitmap.width).put("height", bitmap.height).put("pixel_count", values.size)
            .put("nonblack_rgb_pixels", values.count { (it and 0x00ffffff) != 0 })
            .put("opaque_pixels", values.count { it ushr 24 == 255 })
    }
    private fun appearance(overlay: CompanionOverlay, feedback: Any): JSONObject = main {
        fun view(value: View?): JSONObject {
            val params = value?.layoutParams as? WindowManager.LayoutParams
            return JSONObject().put("attached", value?.isAttachedToWindow == true).put("visibility", value?.visibility ?: -1)
                .put("view_alpha", value?.alpha ?: -1f).put("window_alpha", params?.alpha ?: -1f)
        }
        JSONObject().put("capture_hidden", field(overlay, "captureHidden"))
            .put("companion", view(field(overlay, "root") as View)).put("edge", view(field(overlay, "edge") as? View))
            .put("feedback", view(field(feedback, "view") as? View))
    }
    private fun visible(state: JSONObject): Boolean = !state.getBoolean("capture_hidden") && listOf("companion", "edge", "feedback").all {
        val item = state.getJSONObject(it)
        item.getBoolean("attached") && item.getInt("visibility") == View.VISIBLE && item.getDouble("window_alpha") == 1.0 && item.getDouble("view_alpha") > 0.0
    }

    @Test fun realGameWindowOmitsVisibleAssistantOverlays() {
        assumeTrue(args.getString("window_capture_live") == "true")
        assertTrue("Window screenshot API requires Android 14+", Build.VERSION.SDK_INT >= 34)
        val label = args.getString("window_capture_label") ?: "window-${System.currentTimeMillis()}"
        require(label.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val folder = File(context.getExternalFilesDir(null), "window-capture/$label")
        check(!folder.exists() && folder.mkdirs())
        val report = JSONObject().put("status", "running").put("model_calls", 0).put("game_input_actions", 0)
            .put("api", Build.VERSION.SDK_INT).put("test_type", "real foreground game, production window screenshot, visible assistant overlays")
        var stage = "preconditions"
        fun save() { report.put("stage", stage); File(folder, "report.json").writeText(report.toString(2)) }
        fun png(name: String, bitmap: Bitmap): JSONObject {
            File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            return pixels(bitmap).put("file", "$name.png")
        }
        var taskBefore: ByteArray? = null
        var haveTaskBaseline = false
        var restore: (() -> Unit)? = null
        var feedback: Any? = null
        var failure: Throwable? = null
        val sampler = Executors.newSingleThreadScheduledExecutor()
        try {
            save()
            val automation = inst.getUiAutomation(1)
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            fun gameWindow(): Int {
                return automation.windows.firstOrNull { window ->
                    val root = window.root
                    try { window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused &&
                        root?.packageName?.toString() == "com.hypergryph.arknights.bilibili" }
                    finally { @Suppress("DEPRECATION") root?.recycle() }
                }?.id ?: -1
            }
            if (gameWindow() < 0) {
                context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage("com.hypergryph.arknights.bilibili"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                await("Installed Bilibili game window must appear; no game navigation is injected", 10000) { gameWindow() >= 0 }
                report.put("existing_game_launched", true)
            }
            if (DoppelAccessibilityService.instance == null) {
                stage = "rebind_already_enabled_service"; save()
                AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst) { report.put("accessibility_binding", it); save() }
            }
            val service = requireNotNull(DoppelAccessibilityService.instance)
            check(FirstUseConsent.accept(context)); FirstUseConsent.finishGuide(context)
            if (DeviceWorkerService.instance == null) {
                stage = "start_paused_worker"; save()
                context.startForegroundService(Intent(context, DeviceWorkerService::class.java).setAction(DeviceWorkerService.PAUSE))
                await("Paused worker overlay must bind") { main { DeviceWorkerService.instance?.let { it.isPaused && field(it, "overlay") != null } == true } }
                Thread.sleep(400)
            }
            val worker = requireNotNull(DeviceWorkerService.instance)
            assertTrue("Never capture-diagnose during a running task", worker.isPaused)
            taskBefore = tasks(); haveTaskBaseline = true
            val overlay = requireNotNull(main { field(worker, "overlay") as? CompanionOverlay })
            val originalRevision = worker.companionRevision
            val feedbackObject = requireNotNull(call(service, "getFeedback")); feedback = feedbackObject
            assertNull("No preexisting feedback may be replaced", main { field(feedbackObject, "view") })
            assertNull("No preexisting feedback message may be replaced", main { field(feedbackObject, "messageView") })
            stage = "show_production_overlays"; save()
            main {
                val root = field(overlay, "root") as View
                val oldState = requireNotNull(root.javaClass.methods.single { it.name == "getState" && it.parameterCount == 0 }.invoke(root))
                val oldEdge = (field(overlay, "edge") as? View)?.isAttachedToWindow == true
                val setState = root.javaClass.methods.single { it.name == "setState" && it.parameterCount == 1 }
                restore = { main { setState.invoke(root, oldState); call(overlay, "updateEdge", oldEdge) } }
                val running = requireNotNull(oldState.javaClass.enumConstants).single { (it as Enum<*>).name == "RUNNING" }
                setState.invoke(root, running); call(overlay, "updateEdge", true)
            }
            val dimensions = requireNotNull(automation.takeScreenshot())
            val width = dimensions.width; val height = dimensions.height; dimensions.recycle()
            val centerX = width / 2; val centerY = height / 2
            val geometry = requireNotNull(ActionFeedbackGeometry.create("tap", listOf(centerX - 20, centerY - 20, centerX + 20, centerY + 20), width, height))
                .copy(durationMs = 20000)
            call(feedbackObject, "begin", geometry)
            await("Production companion, edge and pointer must be visible") { visible(appearance(overlay, feedbackObject)) }
            Thread.sleep(120)
            val beforeState = appearance(overlay, feedbackObject)
            report.put("overlay_before", beforeState)
            report.put("visible_windows", JSONArray(automation.windows.map { window ->
                val rect = Rect(); window.getBoundsInScreen(rect)
                val node = window.root
                try { JSONObject().put("id",window.id).put("type",window.type).put("focused",window.isFocused)
                    .put("active",window.isActive).put("package",node?.packageName?.toString().orEmpty())
                    .put("bounds",JSONArray(listOf(rect.left,rect.top,rect.right,rect.bottom))) }
                finally { @Suppress("DEPRECATION") node?.recycle() }
            }))
            val fullBefore = requireNotNull(automation.takeScreenshot())
            report.put("full_screen_before", png("01-full-screen-overlays-visible", fullBefore)); save()
            stage = "production_window_capture"; save()
            val sampleCount = AtomicInteger(); val hiddenSamples = AtomicInteger(); val sampleErrors = AtomicInteger()
            val sampling = sampler.scheduleWithFixedDelay({
                try { if (!visible(appearance(overlay, feedbackObject))) hiddenSamples.incrementAndGet(); sampleCount.incrementAndGet() }
                catch (_: Throwable) { sampleErrors.incrementAndGet() }
            }, 0, 20, TimeUnit.MILLISECONDS)
            val capturedAt = SystemClock.elapsedRealtime()
            val result = try { service.execute(JSONObject().put("id", UUID.randomUUID().toString()).put("run_id", "window-capture-$label")
                .put("kind", "screenshot").put("split_agent", true).put("mode", "full")) }
                finally { sampling.cancel(false) }
            report.put("capture_ms", SystemClock.elapsedRealtime() - capturedAt)
                .put("visible_samples", sampleCount.get()).put("hidden_samples", hiddenSamples.get()).put("sample_errors", sampleErrors.get())
            val data = result.optJSONObject("data") ?: JSONObject()
            report.put("receipt", JSONObject(result.toString()).apply { optJSONObject("data")?.remove("image_base64") })
            report.put("overlay_after", appearance(overlay, feedbackObject)); save()
            assertEquals(result.optString("message"), "ok", result.optString("status"))
            assertEquals("accessibility_windows", data.getString("capture_backend"))
            assertFalse(data.getBoolean("overlay_cleanup_performed"))
            assertFalse("Pixel processing must stay off the UI thread",data.getBoolean("capture_pixels_on_main_thread"))
            val nativeCapture = data.getJSONObject("native_window_capture")
            assertTrue(nativeCapture.getInt("foreground_window_id") >= 0)
            assertTrue(nativeCapture.getJSONArray("windows").length() > 0)
            val bytes = Base64.decode(data.getString("image_base64"), Base64.NO_WRAP)
            val windowBitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                report.put("window_capture", png("02-production-game-window", windowBitmap))
                val fullAfter = requireNotNull(automation.takeScreenshot())
                try { report.put("full_screen_after", png("03-full-screen-overlays-still-visible", fullAfter)) }
                finally { fullAfter.recycle() }
                // Pixel comparison is evidence for visual review, not exact equality:
                // the real game and assistant animations advance between captures.
                val scaled = Bitmap.createScaledBitmap(fullBefore, windowBitmap.width, windowBitmap.height, true)
                val bounds = requireNotNull(main { overlay.bounds() })
                val region = Rect(bounds.left * windowBitmap.width / width, bounds.top * windowBitmap.height / height,
                    bounds.right * windowBitmap.width / width, bounds.bottom * windowBitmap.height / height)
                var changed = 0; var compared = 0
                for (y in region.top.coerceAtLeast(0) until region.bottom.coerceAtMost(windowBitmap.height))
                    for (x in region.left.coerceAtLeast(0) until region.right.coerceAtMost(windowBitmap.width)) {
                        compared++; if (scaled.getPixel(x, y) != windowBitmap.getPixel(x, y)) changed++
                    }
                report.put("companion_region_compared_pixels", compared).put("companion_region_changed_pixels", changed)
                if (scaled !== fullBefore) scaled.recycle()
                assertTrue("Game window screenshot must contain real RGB content", report.getJSONObject("window_capture").getInt("nonblack_rgb_pixels") > windowBitmap.width * windowBitmap.height / 100)
            } finally { windowBitmap.recycle(); fullBefore.recycle() }
            assertTrue(visible(report.getJSONObject("overlay_after")))
            assertEquals("Capture must not hide any sampled assistant overlay", 0, hiddenSamples.get())
            assertEquals("Sampler must remain responsive", 0, sampleErrors.get())
            assertTrue("Actual Bilibili game must remain foreground", gameWindow() >= 0)
            assertTrue(worker.isPaused && DeviceWorkerService.instance === worker && worker.companionRevision == originalRevision)
            report.put("status", "passed").put("visual_review_required", true)
        } catch (error: Throwable) {
            failure = error
            report.put("status", "failed").put("failure_stage", stage).put("error", error.javaClass.simpleName).put("message", error.message.orEmpty().take(600))
        } finally {
            sampler.shutdownNow()
            try { feedback?.let { main { call(it, "clear") } }; restore?.invoke() }
            catch (error: Throwable) { if (failure == null) failure = error; report.put("cleanup_error", error.javaClass.simpleName) }
            if (haveTaskBaseline) {
                val after = tasks(); val unchanged = taskBefore?.contentEquals(after) ?: (after == null)
                report.put("task_bytes_unchanged", unchanged)
                if (!unchanged && failure == null) failure = AssertionError("Task bytes changed during capture-only test")
            }
            if (failure != null) report.put("status", "failed")
            save()
            inst.sendStatus(0, Bundle().apply { putString("stream", "\nWindow screenshot evidence: ${folder.absolutePath}/report.json\n") })
        }
        failure?.let { throw AssertionError("Window screenshot regression failed at $stage; see report.json", it) }
    }
}

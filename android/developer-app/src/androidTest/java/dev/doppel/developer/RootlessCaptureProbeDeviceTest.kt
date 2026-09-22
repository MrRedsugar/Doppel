@file:Suppress("DEPRECATION")

package dev.doppel.developer

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DirectRuntime
import dev.doppel.sdk.DoppelAccessibilityService
import dev.doppel.sdk.LoginAssist
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Opt-in, read-only comparison on the existing foreground page. No model or gesture calls. */
class RootlessCaptureProbeDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private data class Shot(val result: JSONObject, val bitmap: Bitmap? = null)

    private fun windows(service: DoppelAccessibilityService): List<JSONObject> = service.windows.map { window ->
        try {
            val bounds = Rect().also(window::getBoundsInScreen)
            val root = window.root
            try {
                JSONObject().put("id", window.id).put("type", window.type).put("layer", window.layer)
                    .put("display_id", window.displayId).put("focused", window.isFocused).put("active", window.isActive)
                    .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                    .put("root_readable", root != null).put("package", root?.packageName?.toString().orEmpty())
            } finally { root?.recycle() }
        } finally { window.recycle() }
    }

    private fun nativeCapture(service: DoppelAccessibilityService, windowId: Int?): Shot {
        val pending = CompletableFuture<Shot>()
        // Shared executor remains available to release a screenshot delivered after our deadline.
        val executor = ForkJoinPool.commonPool()
        val started = SystemClock.elapsedRealtime()
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(value: AccessibilityService.ScreenshotResult) {
                var hardware: Bitmap? = null
                var software: Bitmap? = null
                try {
                    hardware = Bitmap.wrapHardwareBuffer(value.hardwareBuffer, value.colorSpace)
                    software = requireNotNull(hardware?.copy(Bitmap.Config.ARGB_8888, false))
                    if (pending.complete(Shot(JSONObject().put("status", "ok"), software))) software = null
                } catch (error: Exception) {
                    pending.complete(Shot(JSONObject().put("status", "error").put("error_class", error.javaClass.simpleName)))
                } finally {
                    software?.recycle(); hardware?.recycle(); value.hardwareBuffer.close()
                }
            }
            override fun onFailure(errorCode: Int) {
                pending.complete(Shot(JSONObject().put("status", "error").put("error_code", errorCode)))
            }
        }
        try {
            if (windowId != null) service.takeScreenshotOfWindow(windowId, executor, callback)
            else service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)
            return try { pending.get(5, TimeUnit.SECONDS) }
            catch (_: TimeoutException) {
                val timedOut = Shot(JSONObject().put("status", "timeout"))
                // A late callback must dispose its own bitmap instead of modifying completed evidence.
                if (pending.complete(timedOut)) timedOut else pending.get()
            }.also { it.result.put("elapsed_ms", SystemClock.elapsedRealtime() - started) }
        } catch (error: Exception) {
            val failed = Shot(JSONObject().put("status", "error").put("error_class", error.javaClass.simpleName)
                .put("elapsed_ms", SystemClock.elapsedRealtime() - started))
            if (!pending.complete(failed)) pending.getNow(failed).bitmap?.recycle()
            return failed
        }
    }

    /** Query only public discovery APIs. Reflection reads our own event cache for diagnostics. */
    private fun discoverWindowIds(service: DoppelAccessibilityService): JSONObject {
        fun node(value: AccessibilityNodeInfo?): Any = value?.let {
            try { JSONObject().put("window_id", it.windowId).put("package", it.packageName?.toString().orEmpty()) }
            finally { it.recycle() }
        } ?: JSONObject.NULL
        val all = service.windowsOnAllDisplays
        val displays = JSONArray()
        for (index in 0 until all.size()) {
            val entries = JSONArray()
            all.valueAt(index).forEach { window ->
                try {
                    val parent = window.parent
                    val parentId = try { parent?.id } finally { parent?.recycle() }
                    val children = JSONArray()
                    for (childIndex in 0 until window.childCount) window.getChild(childIndex)?.let {
                        try { children.put(it.id) } finally { it.recycle() }
                    }
                    entries.put(JSONObject().put("id", window.id).put("type", window.type)
                        .put("parent_id", parentId ?: JSONObject.NULL).put("child_ids", children)
                        .put("root", node(window.root)))
                } finally { window.recycle() }
            }
            displays.put(JSONObject().put("display_id", all.keyAt(index)).put("windows", entries))
        }
        @Suppress("UNCHECKED_CAST")
        val packages = service.javaClass.getDeclaredField("windowPackages").apply { isAccessible = true }
            .get(service) as Map<Int, String>
        return JSONObject().put("all_displays", displays)
            .put("active_root", node(service.rootInActiveWindow))
            .put("input_focus", node(service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)))
            .put("accessibility_focus", node(service.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)))
            .put("event_window_packages", JSONObject(packages.toMap().mapKeys { it.key.toString() }))
    }

    @Test fun compareForegroundProductionWindowAndDisplayCaptures() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("rootless_capture_probe") == "true")
        assertTrue("Window capture requires Android 14+", Build.VERSION.SDK_INT >= 34)
        assertNull("Do not probe during a running Worker", DeviceWorkerService.instance)
        assertFalse("Do not probe during an unfinished task", DirectRuntime.get(context).hasUnfinishedRun())
        assertFalse(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        assertFalse("Do not export a login-assistance session", LoginAssist.sensitiveSessionActive())
        assertFalse("Do not export login settings", LoginAssist.settingsVisible)
        val label = args.getString("rootless_capture_label") ?: "probe-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        require(label.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val folder = File(context.getExternalFilesDir(null), "rootless-capture/$label")
        check(!folder.exists() && folder.mkdirs())
        val report = JSONObject().put("status", "recording").put("api", Build.VERSION.SDK_INT)
            .put("model_calls", 0).put("input_actions", 0).put("started_at", System.currentTimeMillis())
        fun save() { File(folder, "report.json").writeText(report.toString(2)) }
        fun saveShot(name: String, shot: Shot): JSONObject = shot.result.also { value ->
            shot.bitmap?.let { bitmap ->
                try {
                    File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    value.put("file", "$name.png").put("width", bitmap.width).put("height", bitmap.height)
                } finally { bitmap.recycle() }
            }
        }
        try {
            save()
            val ui = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val previousService = DoppelAccessibilityService.instance
            val coldDiscovery = args.getString("cold_window_discovery") == "true"
            if (previousService == null || coldDiscovery) {
                report.put("rebind", AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst))
            }
            val service = requireNotNull(DoppelAccessibilityService.instance)
            if (coldDiscovery) {
                assertNotSame("Discovery must use a newly created service with no old in-memory window map", previousService, service)
                assertNull("Cold discovery must not receive a cached ID", args.getString("cached_window_id"))
                report.put("fresh_service_instance", true).put("framework_cache_cleared", service.clearCache())
                    .put("discovery_initial", discoverWindowIds(service))
                SystemClock.sleep(2200)
                report.put("discovery_after_2200ms", discoverWindowIds(service)); save()
            }
            val before = windows(service)
            report.put("windows_before", JSONArray(before))
            val active = service.rootInActiveWindow
            try {
                report.put("active_root_readable", active != null)
                    .put("active_root_package", active?.packageName?.toString().orEmpty())
            } finally { active?.recycle() }
            val target = before.filter {
                it.getInt("display_id") == Display.DEFAULT_DISPLAY && it.optString("package") != context.packageName &&
                    it.getInt("type") != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
            }.sortedWith(compareByDescending<JSONObject> { it.getBoolean("focused") }
                .thenByDescending { it.getBoolean("active") }.thenByDescending { it.getInt("layer") }).firstOrNull()
            report.put("selected_window", target ?: JSONObject.NULL)
            ui.takeScreenshot()?.let { report.put("ground_truth", saveShot("ground-truth", Shot(JSONObject().put("status", "ok"), it))) }
            save()

            if (args.getString("native_capture_only") != "true") {
                val production = service.execute(JSONObject().put("id", UUID.randomUUID().toString())
                    .put("run_id", "rootless-probe-$label").put("kind", "screenshot").put("split_agent", true).put("mode", "full"))
                val data = production.optJSONObject("data")
                data?.optString("image_base64")?.takeIf(String::isNotBlank)?.let { encoded ->
                    File(folder, "production.png").writeBytes(Base64.decode(encoded, Base64.DEFAULT))
                    data.put("image_file", "production.png")
                }
                data?.remove("image_base64")
                report.put("production", production); save()
                SystemClock.sleep(400)
            }
            report.put("service_window", if (target == null) JSONObject().put("status", "no_window")
                else saveShot("service-window", nativeCapture(service, target.getInt("id"))))
            save()
            args.getString("cached_window_id")?.toIntOrNull()?.let { cachedId ->
                require(cachedId >= 0)
                if (target?.getInt("id") == cachedId) SystemClock.sleep(400)
                report.put("cached_window_id", cachedId)
                    .put("cached_window_in_current_list", before.any { it.getInt("id") == cachedId })
                    .put("cached_window", saveShot("cached-window", nativeCapture(service, cachedId)))
                save()
            }
            SystemClock.sleep(400)
            report.put("service_display", saveShot("service-display", nativeCapture(service, null)))
            report.put("windows_after", JSONArray(windows(service))).put("status", "recorded")
        } catch (error: Throwable) {
            report.put("status", "probe_failed").put("error_class", error.javaClass.simpleName)
            throw error
        } finally {
            report.put("finished_at", System.currentTimeMillis()); save()
            inst.sendStatus(0, Bundle().apply { putString("stream", "\nCapture probe evidence: ${folder.absolutePath}/report.json\n") })
        }
    }
}

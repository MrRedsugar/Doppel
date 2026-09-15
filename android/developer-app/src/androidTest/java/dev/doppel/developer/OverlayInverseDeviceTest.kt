@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Optical experiment only: real production views, zero model requests or task execution.
 * Opt in with -e overlay_inverse_experiment true -e sample <unique-label>.
 */
class OverlayInverseDeviceTest {
    @get:org.junit.Rule val terminalTaskPointer: org.junit.rules.TestRule = TerminalTaskPointerTestRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun <T> main(block: () -> T): T {
        var result: T? = null
        inst.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }

    @Test fun captureKnownOverlayLayersAgainstIndependentCleanBackground() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("overlay_inverse_experiment") == "true")
        val context = inst.targetContext
        val ui = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        assertNull("Do not interrupt a real task", DeviceWorkerService.instance)
        assertTrue(AutoTriggerStore(context).list().none { it.enabled })
        assertTrue(Settings.canDrawOverlays(context))
        if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        val service = requireNotNull(DoppelAccessibilityService.instance)
        assertFalse(service.guardVisible)
        val prefs = context.getSharedPreferences("doppel", 0)
        val saved = listOf("companion_y", "companion_right_edge").associateWith { prefs.all[it] }
        val sample = InstrumentationRegistry.getArguments().getString("sample", "device-1")
        require(sample.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val folder = File(context.getExternalFilesDir(null), "overlay-inverse/$sample")
        check(!folder.exists() && folder.mkdirs())
        val report = JSONObject().put("api", android.os.Build.VERSION.SDK_INT).put("model_calls", 0)
            .put("production_changes", false).put("determinate_progress", true).put("cases", JSONArray())
        val softwareLayers = InstrumentationRegistry.getArguments().getString("software_layers") == "true"
        report.put("software_layers", softwareLayers)
        fun save() = File(folder, "capture.json").writeText(report.toString(2))
        fun png(name: String, bitmap: Bitmap) {
            File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        fun shot(name: String): Long {
            val start = SystemClock.elapsedRealtimeNanos()
            val bitmap = requireNotNull(ui.takeScreenshot())
            val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
            png(name, bitmap); bitmap.recycle(); return elapsed
        }
        var overlay: CompanionOverlay? = null
        try {
            context.startActivity(Intent().setClassName("dev.doppel.testapp", "dev.doppel.testapp.OverlayReconstructionFixtureActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            SystemClock.sleep(1200)
            assertEquals("dev.doppel.testapp", ui.rootInActiveWindow?.packageName?.toString())
            report.put("clean_capture_ms", shot("clean-before")); shot("clean-repeat")
            overlay = main { CompanionOverlay(context) {}.also { it.show() } }
            val own = requireNotNull(overlay)
            own.display(JSONObject().put("id", "optical-experiment-only").put("status", "running")
                .put("message", "正在验证截图")
                .put("task_state", JSONObject().put("progress", JSONObject()
                    .put("plan", JSONArray(listOf("记录画面", "核对像素")))
                    .put("completed", 1).put("total_known", true))), "正在思考")
            SystemClock.sleep(400)
            val root = field(own, "root") as SpectrumSurface
            assertFalse("The child progress bar must not animate during the frozen comparison",
                main { root.findViewWithTag<android.widget.ProgressBar>("task_progress_bar").isIndeterminate })
            @Suppress("UNCHECKED_CAST")
            val edges = (field(own, "edgeWindows") as List<SpectrumSurface>).toList()
            assertEquals(4, edges.size)
            val layers = listOf(root) + edges
            if (softwareLayers) main { layers.forEach { it.setLayerType(View.LAYER_TYPE_SOFTWARE, null) } }
            val manager = context.getSystemService(WindowManager::class.java)
            val edgeManager = requireNotNull(field(own, "edgeManager") as WindowManager?)
            val handler = field(own, "handler") as Handler
            fun freeze(time: Long) = main {
                handler.removeCallbacks(field(own, "edgeTick") as Runnable)
                layers.forEach { view ->
                    val clock = field(view, "clock") as Handler
                    clock.removeCallbacks(field(view, "tick") as Runnable)
                    view.perimeterFrameTime = time; view.invalidate()
                }
            }
            fun opacity(value: Float) = main {
                layers.forEach { view ->
                    val params = view.layoutParams as WindowManager.LayoutParams
                    params.alpha = value
                    (if (view === root) manager else edgeManager).updateViewLayout(view, params)
                }
            }
            fun exportLayers(prefix: String): JSONArray = main {
                JSONArray().apply {
                    layers.forEachIndexed { i, view ->
                        val pos = IntArray(2); view.getLocationOnScreen(pos)
                        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                        view.draw(Canvas(bitmap))
                        png("$prefix-layer-$i", bitmap); bitmap.recycle()
                        put(JSONObject().put("file", "$prefix-layer-$i.png").put("x", pos[0]).put("y", pos[1])
                            .put("width", view.width).put("height", view.height)
                            .put("window_alpha", (view.layoutParams as WindowManager.LayoutParams).alpha)
                            .put("hardware_accelerated", view.isHardwareAccelerated)
                            .put("name", if (i == 0) "companion" else "edge-$i"))
                    }
                }
            }
            // Freeze the same production drawing clock first; known timing is the best case.
            for (alpha in listOf(.2f, .8f)) {
                val key = if (alpha < .5f) "opacity-20" else "opacity-80"
                freeze(1000); opacity(alpha); SystemClock.sleep(180)
                val item = JSONObject().put("id", key).put("opacity", alpha)
                item.put("full_capture_ms", shot("$key-full"))
                val start = SystemClock.elapsedRealtimeNanos()
                item.put("layers", exportLayers(key))
                item.put("export_with_png_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000)
                // Controlled frame offsets isolate animation mismatch from background changes.
                for (offset in listOf(33L, 100L)) {
                    freeze(1000 + offset)
                    item.put("layers_late_${offset}ms", exportLayers("$key-late-$offset"))
                }
                freeze(1000); SystemClock.sleep(120)
                // Native API reference while overlays remain visible, no hide/reveal.
                val target = service.windows.first { it.root?.packageName?.toString() == "dev.doppel.testapp" }
                val latch = CountDownLatch(1)
                var native: Bitmap? = null; var nativeError = -1
                val started = SystemClock.elapsedRealtime()
                service.takeScreenshotOfWindow(target.id, context.mainExecutor,
                    object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                            val buffer = result.hardwareBuffer
                            try {
                                val wrapped = requireNotNull(Bitmap.wrapHardwareBuffer(buffer, result.colorSpace))
                                try { native = wrapped.copy(Bitmap.Config.ARGB_8888, false) } finally { wrapped.recycle() }
                            } finally { buffer.close(); latch.countDown() }
                        }
                        override fun onFailure(errorCode: Int) { nativeError = errorCode; latch.countDown() }
                    })
                assertTrue(latch.await(4, TimeUnit.SECONDS))
                item.put("native_capture_ms", SystemClock.elapsedRealtime() - started).put("native_error", nativeError)
                native?.let { png("$key-native", it); it.recycle() }
                assertNotNull("Native target-window reference must exist", native)
                report.getJSONArray("cases").put(item); save()
            }
            main { own.close() }; overlay = null
            SystemClock.sleep(180); shot("clean-after")
            report.put("status", "captured")
        } finally {
            overlay?.let { main { it.close() } }
            prefs.edit().apply {
                saved.forEach { (key, value) -> when (value) {
                    is Int -> putInt(key, value)
                    is Boolean -> putBoolean(key, value)
                    else -> remove(key)
                } }
            }.commit()
            report.put("overlay_preferences_restored", saved.all { prefs.all[it.key] == it.value })
            save()
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

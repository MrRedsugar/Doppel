package dev.doppel.developer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.CompanionOverlay
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.FirstUseConsent
import dev.doppel.sdk.Gateway
import dev.doppel.sdk.TaskPanelActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Android widgets rendered from isolated task receipts; no task, model, or device input. */
class TaskProgressDeviceTest {
    @get:org.junit.Rule val terminalTaskPointer: org.junit.rules.TestRule = TerminalTaskPointerTestRule()
    @Test fun actualCompanionAndTaskPanelShowTheSamePlanWithoutPollingOrModelCalls() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("task_progress_ui_test") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(1)
        val prefs = Gateway(context).prefs
        assertNull("Do not disturb a real worker", DeviceWorkerService.instance)
        assertTrue("Keep the current user's task untouched", prefs.getString("active_run", "").isNullOrBlank())
        assertFalse("Do not replace an existing task panel", TaskPanelActivity.isVisible)
        assertTrue("The host must enable overlays for this isolated emulator test", Settings.canDrawOverlays(context))
        assertTrue(FirstUseConsent.isAccepted(context))
        assertFalse(context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val saved = listOf("companion_y", "companion_right_edge", "companion_draft").associateWith { prefs.all[it] }
        val folder = File(context.getExternalFilesDir(null), "task-progress-verification").apply { mkdirs() }
        var overlay: CompanionOverlay? = null
        var activity: TaskPanelActivity? = null
        var stage = "starting"
        val captures = JSONObject()
        fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        fun await(message: String, condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 5000
            while (SystemClock.elapsedRealtime() < until) { if (condition()) return; Thread.sleep(60) }
            assertTrue(message, condition())
        }
        fun screenshot(name: String, expected: Map<String, View> = emptyMap(), bar: ProgressBar? = null) {
            // A completed View traversal precedes SurfaceFlinger presentation and the
            // system's window entrance animation. This delay is capture-only, never task logic.
            if (expected.isNotEmpty()) Thread.sleep(550)
            assertFalse(context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            val bitmap = requireNotNull(automation.takeScreenshot())
            try {
                File(folder, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                val bounds = JSONObject(); var progressBounds: Rect? = null
                val screen = Rect(0, 0, bitmap.width, bitmap.height)
                instrumentation.runOnMainSync {
                    for ((label, view) in expected) {
                        val location = IntArray(2); view.getLocationOnScreen(location)
                        val rect = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
                        bounds.put(label, rect.toShortString())
                        assertTrue("$label must be on the physical screenshot $screen, got $rect", !rect.isEmpty && screen.contains(rect))
                        assertTrue("$label must be shown in the captured window", view.isShown)
                        if (view === bar) progressBounds = rect
                    }
                }
                if (bar != null) {
                    val rect = requireNotNull(progressBounds)
                    val green = dev.doppel.sdk.UiTheme.green
                    fun filled(pixel: Int) = listOf(16, 8, 0).all { shift ->
                        kotlin.math.abs(((pixel ushr shift) and 255) - ((green ushr shift) and 255)) <= 18
                    }
                    val matches = (1..3).count { filled(bitmap.getPixel(rect.left + rect.width() * it / 12, rect.centerY())) }
                    assertTrue("$name must contain the actual green 1/3 progress fill at $rect, not an earlier compositor frame", matches >= 2)
                    assertFalse("The captured 1/3 bar must not appear complete", filled(bitmap.getPixel(rect.left + rect.width() * 3 / 4, rect.centerY())))
                }
                captures.put(name, JSONObject().put("width", bitmap.width).put("height", bitmap.height).put("physical_bounds", bounds))
            }
            finally { bitmap.recycle() }
        }
        fun awaitDraw(view: View) {
            // Queue idle is earlier than the next Choreographer traversal. Wait until the
            // production window has measured, laid out, and drawn the newly supplied receipt.
            val drawn = CountDownLatch(1)
            var listener: ViewTreeObserver.OnPreDrawListener? = null
            instrumentation.runOnMainSync {
                listener = ViewTreeObserver.OnPreDrawListener {
                    if (view.isLaidOut && !view.isLayoutRequested) {
                        view.viewTreeObserver.removeOnPreDrawListener(listener)
                        view.post { drawn.countDown() }
                    }
                    true
                }
                view.viewTreeObserver.addOnPreDrawListener(listener)
                view.postInvalidateOnAnimation()
            }
            try { assertTrue("$stage must complete an actual window traversal", drawn.await(5, TimeUnit.SECONDS)) }
            finally { instrumentation.runOnMainSync { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(listener) } }
        }
        fun diagnostics() {
            val views = JSONObject()
            instrumentation.runOnMainSync {
                fun record(name: String, view: View) {
                    val visible = Rect(); val hasVisibleRect = view.getGlobalVisibleRect(visible)
                    val position = IntArray(2); view.getLocationOnScreen(position)
                    views.put(name, JSONObject().put("class", view.javaClass.simpleName)
                        .put("visibility", view.visibility).put("shown", view.isShown).put("attached", view.isAttachedToWindow)
                        .put("laid_out", view.isLaidOut).put("layout_requested", view.isLayoutRequested)
                        .put("width", view.width).put("height", view.height)
                        .put("measured_width", view.measuredWidth).put("measured_height", view.measuredHeight)
                        .put("visible", hasVisibleRect).put("visible_bounds", visible.toShortString())
                        .put("local_bounds", Rect(view.left, view.top, view.right, view.bottom).toShortString())
                        .put("screen_position", JSONArray(position.toList())).put("alpha", view.alpha)
                        .put("text", (view as? TextView)?.text?.toString()))
                }
                overlay?.let { own ->
                    for (name in listOf("root", "icon", "label", "taskProgress")) record("companion.$name", field(own, name) as View)
                    record("companion.text_stack", (field(own, "label") as View).parent as View)
                    val progress = field(own, "taskProgress") as View
                    for (name in listOf("task_progress_label", "task_progress_bar")) record("companion.$name", progress.findViewWithTag(name))
                }
                activity?.let { own ->
                    record("panel.root", own.window.decorView)
                    for (name in listOf("title", "taskProgress", "resume", "stop")) record("panel.$name", field(own, name) as View)
                }
            }
            val value = JSONObject().put("stage", stage).put("views", views).toString(2)
            File(folder, "actual-ui-diagnostics.json").writeText(value)
            File(folder, "actual-${stage.replace(' ', '-')}-diagnostics.json").writeText(value)
        }
        fun fixture(completed: Int) = JSONObject().put("id", "progress-native-ui-fixture").put("source", "schedule")
            .put("goal", "打开目标页面，完成操作并核对结果").put("status", "running").put("message", "正在思考")
            .put("task_state", JSONObject().put("progress", JSONObject().put("plan", JSONArray(listOf("打开目标页面", "完成所需操作", "核对最终结果")))
                .put("completed", completed).put("total_known", true)))
        try {
            File(folder, "actual-ui-result.json").delete()
            File(folder, "actual-ui-failure.png").delete()
            stage = "companion"
            // Settings can hide third-party overlays while their Views remain attached.
            context.startActivity(Intent().setClassName("dev.doppel.testapp", "dev.doppel.testapp.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("The ordinary fixture app must be foreground before showing its companion") {
                val node = automation.rootInActiveWindow
                val shown = node?.packageName?.toString() == "dev.doppel.testapp"
                @Suppress("DEPRECATION") node?.recycle()
                shown
            }
            instrumentation.runOnMainSync { overlay = CompanionOverlay(context) { error("Fixture must not invoke task controls") }.also { it.show(); it.display(fixture(0), "正在思考") } }
            await("The real overlay must display the initial plan") {
                var ready = false
                instrumentation.runOnMainSync { ready = (field(requireNotNull(overlay), "taskProgress") as View)
                    .findViewWithTag<TextView>("task_progress_label").text.contains("0/3") }
                ready
            }
            instrumentation.runOnMainSync { requireNotNull(overlay).display(fixture(1), "正在思考") }
            await("Progress must repaint even when the companion status cache is unchanged") {
                var ready = false
                instrumentation.runOnMainSync { ready = (field(requireNotNull(overlay), "taskProgress") as View)
                    .findViewWithTag<TextView>("task_progress_label").text.contains("1/3") }
                ready
            }
            awaitDraw(field(requireNotNull(overlay), "root") as View)
            diagnostics()
            instrumentation.runOnMainSync {
                val root = field(requireNotNull(overlay), "root") as View
                val bounds = Rect().also { assertTrue(root.getGlobalVisibleRect(it)) }
                val status = field(requireNotNull(overlay), "label") as TextView
                assertTrue("The thinking state remains visible beside task progress", status.text.contains("思考中"))
                val progress = field(requireNotNull(overlay), "taskProgress") as View
                for ((name, view) in listOf("icon" to field(requireNotNull(overlay), "icon") as View, "status" to status,
                    "progress label" to progress.findViewWithTag<TextView>("task_progress_label"),
                    "progress bar" to progress.findViewWithTag<ProgressBar>("task_progress_bar"))) {
                    val rect = Rect().also { assertTrue("Companion $name must remain visible (${view.width}x${view.height})", view.getGlobalVisibleRect(it)) }
                    assertTrue("Companion $name must fit inside $bounds, got $rect", bounds.contains(rect))
                    assertEquals("Companion must not vertically clip $name", view.height, rect.height())
                }
            }
            val companionProgress = field(requireNotNull(overlay), "taskProgress") as View
            val companionBar = companionProgress.findViewWithTag<ProgressBar>("task_progress_bar")
            screenshot("actual-companion-progress.png", mapOf(
                "icon" to field(requireNotNull(overlay), "icon") as View,
                "status" to field(requireNotNull(overlay), "label") as View,
                "progress label" to companionProgress.findViewWithTag<TextView>("task_progress_label"),
                "progress bar" to companionBar), companionBar)
            diagnostics()
            instrumentation.runOnMainSync { overlay?.close(); overlay = null }
            stage = "task panel"

            // Explicit empty ID prevents both active_run and last_result fallback. Stop its idle
            // redraw before feeding a receipt to the production renderer; no gateway is contacted.
            activity = instrumentation.startActivitySync(Intent(context, TaskPanelActivity::class.java)
                .putExtra("run_id", "").putExtra("pending_input", "").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TaskPanelActivity
            val panel = requireNotNull(activity)
            instrumentation.runOnMainSync {
                (field(panel, "handler") as Handler).removeCallbacks(field(panel, "poll") as Runnable)
                val paused = fixture(1).put("status", "paused").put("message", "你已暂停任务，继续时将重新观察屏幕")
                panel.javaClass.getDeclaredMethod("render", JSONObject::class.java).apply { isAccessible = true }.invoke(panel, paused)
            }
            awaitDraw(panel.window.decorView)
            diagnostics()
            instrumentation.runOnMainSync {
                val progress = field(panel, "taskProgress") as View
                assertEquals(3, progress.findViewWithTag<ProgressBar>("task_progress_bar").max)
                assertEquals(1, progress.findViewWithTag<ProgressBar>("task_progress_bar").progress)
                assertEquals(3, progress.findViewWithTag<TextView>("task_progress_plan").text.lines().size)
                for (name in listOf("resume", "stop")) {
                    val button = field(panel, name) as View
                    val rect = Rect().also { assertTrue("$name control must remain visible below the plan", button.getGlobalVisibleRect(it)) }
                    assertEquals(button.height, rect.height())
                }
                assertTrue((field(panel, "title") as TextView).text.contains("自动任务"))
            }
            val panelProgress = field(panel, "taskProgress") as View
            val panelBar = panelProgress.findViewWithTag<ProgressBar>("task_progress_bar")
            screenshot("actual-task-panel-progress.png", mapOf(
                "title" to field(panel, "title") as View,
                "progress plan" to panelProgress.findViewWithTag<TextView>("task_progress_plan"),
                "progress bar" to panelBar,
                "resume" to field(panel, "resume") as View, "stop" to field(panel, "stop") as View), panelBar)
            diagnostics()
            assertNull(DeviceWorkerService.instance)
            assertTrue(prefs.getString("active_run", "").isNullOrBlank())
            File(folder, "actual-ui-result.json").writeText(JSONObject().put("ok", true)
                .put("scope", "actual production CompanionOverlay and TaskPanelActivity rendering isolated receipts; no task execution")
                .put("same_status_progress_updated", true).put("controls_fully_visible", true)
                .put("model_requests", 0).put("submitted_tasks", 0).put("captures", captures).toString(2))
        } catch (failure: Throwable) {
            runCatching { diagnostics() }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { screenshot("actual-ui-failure.png") }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            instrumentation.runOnMainSync { overlay?.close(); activity?.finishAndRemoveTask() }
            instrumentation.waitForIdleSync()
            val edit = prefs.edit()
            saved.forEach { (key, value) -> when (value) {
                null -> edit.remove(key)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is String -> edit.putString(key, value)
            } }
            assertTrue(edit.commit())
        }
    }

    @Test fun knownUnknownPausedAndRepeatedUpdatesRenderWithoutInventedPercentages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val type = Class.forName("dev.doppel.sdk.TaskProgressView")
        val display = type.getMethod("display", JSONObject::class.java, Boolean::class.javaPrimitiveType)
        val folder = File(context.getExternalFilesDir(null), "task-progress-verification").apply { mkdirs() }
        fun run(status: String, known: Boolean, completed: Int) = JSONObject().put("id", "progress-ui-fixture").put("status", status)
            .put("task_state", JSONObject().put("phase", "打开目标页面").put("progress", JSONObject()
                .put("plan", JSONArray(listOf("打开目标页面", "完成所需操作", "核对最终结果")))
                .put("completed", completed).put("total_known", known)))
        instrumentation.runOnMainSync {
            for (compact in listOf(false, true)) {
                val view = type.getDeclaredConstructor(Context::class.java, Boolean::class.javaPrimitiveType)
                    .newInstance(context, compact) as LinearLayout
                val bar = view.findViewWithTag<ProgressBar>("task_progress_bar")
                val label = view.findViewWithTag<TextView>("task_progress_label")
                val fixture = run("running", true, 1)
                display.invoke(view, fixture, false)
                assertEquals(3, bar.max); assertEquals(1, bar.progress); assertFalse(bar.isIndeterminate)
                assertEquals(View.VISIBLE, bar.visibility)
                assertTrue(label.text.toString(), label.text.toString().contains("1/3"))
                if (!compact) {
                    val plan = view.findViewWithTag<TextView>("task_progress_plan")
                    assertEquals(3, plan.text.lines().size)
                    assertTrue(plan.text.contains("✓ 打开目标页面"))
                    assertTrue(plan.text.contains("完成所需操作")); assertTrue(plan.text.contains("核对最终结果"))
                    assertEquals("完成所需操作", view.findViewWithTag<TextView>("task_progress_current").text.toString())
                }
                val count = view.childCount
                repeat(10) { display.invoke(view, JSONObject(fixture.toString()), false) }
                assertEquals("Polling updates existing widgets instead of appending messages", count, view.childCount)
                val density = context.resources.displayMetrics.density
                val width = ((if (compact) 184 else 330) * density).toInt()
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                view.layout(0, 0, width, view.measuredHeight)
                val bitmap = Bitmap.createBitmap(width, view.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(dev.doppel.sdk.UiTheme.background)
                    view.draw(Canvas(bitmap))
                    File(folder, if (compact) "compact-known.png" else "full-plan-known.png").outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { bitmap.recycle() }
                display.invoke(view, run("running", false, 1), false)
                assertTrue("Unknown running progress uses an indeterminate native bar", bar.isIndeterminate)
                assertTrue(label.text.toString(), label.text.toString().contains("进度待确定"))
                assertFalse("Unknown progress must not make up a percentage", label.text.toString().contains("%"))
                display.invoke(view, run("running", false, 1), true)
                assertFalse("Local pause stops indeterminate motion", bar.isIndeterminate)
                assertEquals(View.GONE, bar.visibility)
                display.invoke(view, run("paused", true, 1), false)
                assertFalse(bar.isIndeterminate); assertEquals(1, bar.progress)
                if (!compact) assertEquals("Paused progress retains its plan", 3, view.findViewWithTag<TextView>("task_progress_plan").text.lines().size)
                display.invoke(view, run("completed", true, 3), false)
                assertFalse(bar.isIndeterminate); assertEquals(3, bar.progress)
                assertTrue(label.text.toString(), label.text.toString().contains("3/3"))
                if (!compact) assertEquals("任务已完成", view.findViewWithTag<TextView>("task_progress_current").text.toString())
                display.invoke(view, null, false)
                assertEquals(View.GONE, view.visibility)
            }
        }
        File(folder, "result.json").writeText(JSONObject().put("ok", true).put("scope", "native full/compact TaskProgressView fixture rendering")
            .put("model_requests", 0).put("submitted_tasks", 0).put("device_actions", 0).toString(2))
    }
}

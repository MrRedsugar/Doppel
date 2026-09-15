package dev.doppel.developer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.CompanionOverlay
import dev.doppel.sdk.DeviceWorkerService
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Real installed OpenCalc cross-UID input component test; zero models, not game acceptance.
 * -e calculator_touch_pass true -e calculator_touch_pass_label unique-label
 * App/task settings and calculator history are untouched; one harmless digit is appended.
 */
class CompanionGesturePassTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.getUiAutomation(1)
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val handler = Handler.createAsync(Looper.getMainLooper())
    private val calculator = "com.darkempire78.opencalculator"

    private fun <T> main(block: () -> T): T {
        val work = FutureTask(block); check(handler.post(work))
        try { return work.get(2000, TimeUnit.MILLISECONDS) }
        finally { handler.removeCallbacks(work); if (!work.isDone) work.cancel(false) }
    }
    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) { if (condition()) return; Thread.sleep(40) }
        assertTrue(message, condition())
    }
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun call(owner: Any, name: String, vararg args: Any?): Any? {
        val method = owner.javaClass.declaredMethods.single {
            (it.name == name || it.name.startsWith(name + "$")) && it.parameterCount == args.size && !java.lang.reflect.Modifier.isStatic(it.modifiers)
        }.apply { isAccessible = true }
        return try { method.invoke(owner, *args) } catch (error: InvocationTargetException) { throw error.targetException }
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        if (root.packageName?.toString() != calculator) { @Suppress("DEPRECATION") root.recycle(); return null }
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { child -> visit(child)?.let { return it } }
            @Suppress("DEPRECATION") node.recycle()
            return null
        }
        return visit(root)
    }
    private fun input(): String? {
        val node = find { it.viewIdResourceName == "$calculator:id/input" } ?: return null
        return try { node.text?.toString().orEmpty() } finally { @Suppress("DEPRECATION") node.recycle() }
    }
    private fun inject(action: Int, x: Float, y: Float, down: Long) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { assertTrue("Cross-UID touch injection failed", automation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }

    @Test fun coveredForeignAppTargetReceivesGestureAndTheEntryRecoversAfterFinally() {
        assumeTrue("Real calculator component test is opt-in", args.getString("calculator_touch_pass") == "true")
        val label = args.getString("calculator_touch_pass_label") ?: "calculator-${System.currentTimeMillis()}"
        require(label.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val folder = File(context.getExternalFilesDir(null), "calculator-touch-pass/$label")
        check(!folder.exists() && folder.mkdirs())
        val report = JSONObject().put("status", "running").put("package", calculator).put("model_calls", 0)
            .put("test_type", "real third-party calculator; cross-UID overlay input component; not autonomous acceptance")
        var stage = "preconditions"
        val started = SystemClock.elapsedRealtime()
        fun save() { report.put("stage", stage).put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            File(folder, "report.json").writeText(report.toString(2)) }
        fun shot(name: String) {
            val image = requireNotNull(automation.takeScreenshot())
            try { File(folder, "$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { image.recycle() }
        }
        val taskFile = File(context.noBackupFilesDir, "direct-runs-v1.json")
        val taskBefore = taskFile.takeIf { it.isFile }?.readBytes()
        var overlay: CompanionOverlay? = null
        var own = false
        var lease: AutoCloseable? = null
        var restorePosition: (() -> Unit)? = null
        var failure: Throwable? = null
        try {
            save()
            assertTrue("Overlay permission must already be enabled", Settings.canDrawOverlays(context))
            val worker = DeviceWorkerService.instance
            assertTrue("Do not run component diagnostics during a live task", worker == null || worker.isPaused)
            val serviceInfo = automation.serviceInfo
            serviceInfo.flags = serviceInfo.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            automation.serviceInfo = serviceInfo
            val launcher = context.packageManager.getLaunchIntentForPackage(calculator)
            assertNotNull("Installed OpenCalc is required; no test fixture is used", launcher)
            stage = "open_real_calculator"; save()
            context.startActivity(requireNotNull(launcher).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitUntil("Real OpenCalc keypad and expression must be visible") { input() != null && find {
                it.text?.toString() == "7" && it.isClickable && it.isVisibleToUser
            }?.let { @Suppress("DEPRECATION") it.recycle(); true } == true }
            val target = Rect()
            val digit = requireNotNull(find { it.text?.toString() == "7" && it.isClickable && it.isVisibleToUser })
            try { digit.getBoundsInScreen(target) } finally { @Suppress("DEPRECATION") digit.recycle() }
            val beforeInput = requireNotNull(input())
            report.put("target_bounds", "${target.left},${target.top},${target.right},${target.bottom}").put("input_before", beforeInput)
            shot("00-calculator-before")
            stage = "cover_digit_with_production_overlay"; save()
            val entry = main {
                val existing = worker?.let { field(it, "overlay") as? CompanionOverlay }
                (existing ?: CompanionOverlay(context) { error("The diagnostic must not activate voice or task controls") }.also { own = true; it.show() })
                    .also { overlay = it }
            }
            report.put("borrowed_worker_overlay", !own)
            waitUntil("Production companion must attach") { main { entry.bounds()?.isEmpty == false } }
            main {
                val params = field(entry, "params") as WindowManager.LayoutParams
                val previousX = params.x; val previousY = params.y
                restorePosition = { main { params.x = previousX; params.y = previousY; call(entry, "updateLayout") } }
                val bounds = requireNotNull(entry.bounds())
                params.x += target.centerX() - bounds.centerX(); params.y += target.centerY() - bounds.centerY()
                check(call(entry, "updateLayout") == true)
            }
            waitUntil("Companion must cover the exact calculator coordinate") { main { entry.bounds()?.contains(target.centerX(), target.centerY()) == true } }
            shot("01-companion-covers-digit")
            stage = "acquire_pass"; save()
            val revision = worker?.companionRevision
            val current: () -> Boolean = { DeviceWorkerService.instance === worker && (worker == null || worker.isPaused && worker.companionRevision == revision) }
            val acquiredAt = SystemClock.elapsedRealtime()
            lease = call(entry, "beginGestureTouchPass", SystemClock.elapsedRealtimeNanos(), 6000L, current) as? AutoCloseable
            report.put("begin_ms", SystemClock.elapsedRealtime() - acquiredAt).put("lease_acquired", lease != null)
                .put("handoff", call(entry, "gestureTouchPassDiagnostic")); save()
            assertNotNull("Production companion handoff must acknowledge", lease)
            main {
                val params = field(entry, "params") as WindowManager.LayoutParams
                assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                assertEquals(0f, params.alpha, 0f)
            }
            stage = "inject_actual_calculator_tap"; save()
            val down = SystemClock.uptimeMillis()
            try {
                inject(MotionEvent.ACTION_DOWN, target.exactCenterX(), target.exactCenterY(), down)
                inject(MotionEvent.ACTION_UP, target.exactCenterX(), target.exactCenterY(), down)
            } catch (error: Throwable) { inject(MotionEvent.ACTION_CANCEL, target.exactCenterX(), target.exactCenterY(), down); throw error }
            waitUntil("Real calculator did not receive the covered digit tap") { input()?.let { it != beforeInput && it.endsWith("7") } == true }
            val afterInput = requireNotNull(input())
            report.put("input_after", afterInput).put("underlying_app_received_digit", true)
            shot("02-calculator-received-digit"); save()
            stage = "restore_and_verify_real_overlay_input"; save()
            requireNotNull(lease).close(); lease = null
            waitUntil("Temporary pass must restore touchable opaque companion") { main {
                val params = field(entry, "params") as WindowManager.LayoutParams
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0 && params.alpha == 1f
            } }
            val traversed = CountDownLatch(1)
            main {
                val root = field(entry, "root") as View
                val listener = object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean { root.viewTreeObserver.removeOnPreDrawListener(this); traversed.countDown(); return true }
                }
                root.viewTreeObserver.addOnPreDrawListener(listener); root.requestLayout(); root.invalidate()
            }
            assertTrue("Restored entry input window must traverse", traversed.await(2, TimeUnit.SECONDS))
            Thread.sleep(80)
            val restoredDown = SystemClock.uptimeMillis()
            var intercepted = false
            try {
                inject(MotionEvent.ACTION_DOWN, target.exactCenterX(), target.exactCenterY(), restoredDown)
                // Read immediately after synchronous input delivery, then cancel before
                // the real production 400ms voice hold callback can fire.
                intercepted = field(entry, "touching") as Boolean
            } finally { inject(MotionEvent.ACTION_CANCEL, target.exactCenterX(), target.exactCenterY(), restoredDown) }
            assertTrue("Restored production overlay must receive actual DOWN", intercepted)
            waitUntil("Production overlay must receive actual CANCEL") { !(field(entry, "touching") as Boolean) }
            assertEquals("Restored overlay must prevent a second calculator input", afterInput, input())
            report.put("overlay_received_down", intercepted).put("overlay_received_cancel", true).put("calculator_unchanged_during_overlay_probe", true)
            shot("03-companion-restored")
            assertTrue("Worker identity or pause state changed", current())
            report.put("status", "passed")
        } catch (error: Throwable) {
            failure = error
            report.put("status", "failed").put("failure_stage", stage).put("error", error.javaClass.simpleName).put("message", error.message.orEmpty().take(600))
        } finally {
            try { lease?.close(); restorePosition?.invoke(); if (own) main { overlay?.close() } }
            catch (error: Throwable) { report.put("cleanup_error", error.javaClass.simpleName); if (failure == null) failure = error }
            val after = taskFile.takeIf { it.isFile }?.readBytes()
            val unchanged = taskBefore?.contentEquals(after) ?: (after == null)
            report.put("task_bytes_unchanged", unchanged)
            if (!unchanged && failure == null) failure = AssertionError("Task state changed during the component test")
            if (failure != null) report.put("status", "failed")
            save()
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nReal calculator handoff evidence: ${folder.absolutePath}/report.json\n") })
        }
        failure?.let { throw AssertionError("Calculator touch-pass test failed at $stage; see report.json", it) }
    }
}

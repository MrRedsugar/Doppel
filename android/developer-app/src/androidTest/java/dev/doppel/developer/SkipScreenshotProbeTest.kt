@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")

package dev.doppel.developer

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.SurfaceControl
import android.view.View
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DoppelAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Capability probe only. No global hidden-API exemption, system policy edit, model or saved task.
 * -e skip_screenshot_probe true [-e skip_screenshot_bypass true]
 */
class SkipScreenshotProbeTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val executor = ForkJoinPool.commonPool()
    private fun <T> main(block: () -> T): T {
        var result: T? = null
        var failure: Throwable? = null
        inst.runOnMainSync { try { result = block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun await(message: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 7000
        do { if (condition()) return; SystemClock.sleep(60) } while (SystemClock.elapsedRealtime() < until)
        assertTrue(message, condition())
    }
    private fun exceptionChain(error: Throwable): List<String> {
        val chain = mutableListOf<String>()
        var current: Throwable? = error
        while (current != null && chain.size < 8) {
            chain += current.javaClass.name
            current = current.cause.takeUnless { it === current }
        }
        return chain
    }
    private fun exceptionName(error: Throwable) = exceptionChain(error).last()

    private fun capture(service: DoppelAccessibilityService): Bitmap {
        val pending = CompletableFuture<Bitmap>()
        service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                var hardware: Bitmap? = null
                var pixels: Bitmap? = null
                try {
                    hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    pixels = requireNotNull(hardware?.copy(Bitmap.Config.ARGB_8888, false))
                    if (pending.complete(pixels)) pixels = null
                } catch (error: Exception) { pending.completeExceptionally(error) }
                finally { pixels?.recycle(); hardware?.recycle(); result.hardwareBuffer.close() }
            }
            override fun onFailure(errorCode: Int) {
                pending.completeExceptionally(IllegalStateException("native_capture_error_$errorCode"))
            }
        })
        return try { pending.get(5, TimeUnit.SECONDS) }
        catch (error: TimeoutException) {
            if (!pending.completeExceptionally(error)) return pending.get()
            throw error
        }
    }
    private fun durableDigest(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (name in listOf("direct-runs-v1.json", "schedules-v1.json", "model-providers-v1.bin", "credential-vault-v1.bin")) {
            File(context.noBackupFilesDir, name).takeIf(File::isFile)?.let { digest.update(it.readBytes()) }
        }
        for (name in listOf("doppel", "doppel_auto_triggers")) {
            digest.update(context.getSharedPreferences(name, 0).all.toSortedMap().toString().toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun noScheduledWork() {
        val runFile = File(context.noBackupFilesDir, "direct-runs-v1.json")
        val runs = if (runFile.isFile) dev.doppel.sdk.SplitTaskEngine.readPersistedRuns(runFile.readText()) else JSONArray()
        assertTrue("Never interrupt an unfinished user task", (0 until runs.length()).all {
            runs.getJSONObject(it).optString("status") in setOf("completed", "failed", "cancelled")
        })
        val scheduleFile = File(context.noBackupFilesDir, "schedules-v1.json")
        val schedules = if (scheduleFile.isFile) JSONObject(scheduleFile.readText()).getJSONArray("items") else JSONArray()
        val rules = JSONArray(context.getSharedPreferences("doppel_auto_triggers", 0).getString("rules", "[]"))
        assertFalse("Do not race enabled automatic tasks", (0 until schedules.length()).any { schedules.getJSONObject(it).optBoolean("enabled") } ||
            (0 until rules.length()).any { rules.getJSONObject(it).optBoolean("enabled") })
    }

    @Test fun plainReflectionSkipFlagExcludesOnlyTheOwnedOverlayAndRestoresIt() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("skip_screenshot_probe") == "true")
        val bypass = InstrumentationRegistry.getArguments().getString("skip_screenshot_bypass") == "true"
        assertTrue(Build.VERSION.SDK_INT >= 34)
        inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        assertNull(DeviceWorkerService.instance)
        noScheduledWork()
        assertFalse(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        assertTrue(context.getSystemService(PowerManager::class.java).isInteractive)
        // Refresh only an already-granted service, as in the other device fixtures.
        if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        val service = requireNotNull(DoppelAccessibilityService.instance)
        assertFalse(service.guardVisible)
        val beforeState = durableDigest()
        val label = "${if (bypass) "targeted-bypass" else "ordinary"}-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val folder = File(context.filesDir, "skip-screenshot-probe/$label").apply { check(mkdirs()) }
        val report = JSONObject().put("passed", false).put("model_calls", 0)
            .put("reflection", if (bypass) "targeted_hiddenapibypass_6.1" else "ordinary_java")
            .put("hidden_api_policy_changed", false).put("global_exemptions_set", false)
            .put("native_capture", "AccessibilityService.takeScreenshot(Display.DEFAULT_DISPLAY)")
        var stage = "open_fixture"
        var reflectiveMethod: String? = null
        var view: View? = null
        var surface: SurfaceControl? = null
        var skipMethod: Method? = null
        var failure: Throwable? = null
        val wm = service.getSystemService(WindowManager::class.java)
        fun save() { File(folder, "report.json").writeText(report.toString(2)) }
        fun setSkip(enabled: Boolean) {
            val committed = CountDownLatch(1)
            SurfaceControl.Transaction().use { transaction ->
                reflectiveMethod = "SurfaceControl.Transaction.setSkipScreenshot"
                requireNotNull(skipMethod).invoke(transaction, requireNotNull(surface), enabled)
                transaction.addTransactionCommittedListener(executor) { committed.countDown() }
                transaction.apply()
                assertTrue("Surface transaction must actually commit", committed.await(4, TimeUnit.SECONDS))
            }
            report.put(if (enabled) "skip_on_committed" else "skip_off_committed", true)
        }
        fun fraction(image: Bitmap, region: Rect, color: Int): Double {
            var matching = 0; var count = 0
            for (y in region.top until region.bottom) for (x in region.left until region.right) {
                val actual = image.getPixel(x, y)
                if (listOf(0, 8, 16).all { shift -> kotlin.math.abs((actual shr shift and 255) - (color shr shift and 255)) <= 8 }) matching++
                count++
            }
            return matching.toDouble() / count
        }
        try {
            context.startActivity(Intent().setClassName("dev.doppel.testapp", "dev.doppel.testapp.PopupFixtureActivity")
                .putExtra("session", label).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("The disposable blue application page must become active") {
                val root = service.rootInActiveWindow
                try { root?.packageName?.toString() == "dev.doppel.testapp" } finally { root?.recycle() }
            }
            SystemClock.sleep(400)
            val display = Rect(wm.currentWindowMetrics.bounds)
            val area = Rect(display.width() / 3, display.height() * 3 / 4,
                display.width() * 2 / 3, display.height() * 7 / 8)
            val committed = CountDownLatch(1)
            stage = "attach_owned_opaque_overlay"
            view = main {
                View(service).apply {
                    setBackgroundColor(Color.RED)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    wm.addView(this, WindowManager.LayoutParams(area.width(), area.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.OPAQUE).apply {
                        gravity = Gravity.TOP or Gravity.LEFT; x = area.left; y = area.top; alpha = 1f
                        setFitInsetsTypes(0)
                    })
                    viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                    invalidate()
                }
            }
            assertTrue("The owned opaque overlay must render before capture", committed.await(4, TimeUnit.SECONDS))
            val actualArea = main {
                val own = requireNotNull(view)
                val position = IntArray(2); own.getLocationOnScreen(position)
                Rect(position[0], position[1], position[0] + own.width, position[1] + own.height)
            }
            report.put("overlay_bounds", JSONArray(listOf(actualArea.left, actualArea.top, actualArea.right, actualArea.bottom)))
            val interior = Rect(actualArea).apply { inset(6, 6) }
            fun shot(name: String): Pair<Double, Double> {
                SystemClock.sleep(400) // Native full-display API limits captures; no retry or alternate backend.
                val image = capture(service)
                try {
                    assertTrue(Rect(0, 0, image.width, image.height).contains(interior))
                    File(folder, "$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    val red = fraction(image, interior, Color.RED)
                    val blue = fraction(image, interior, Color.rgb(30, 40, 90))
                    report.put(name, JSONObject().put("red_fraction", red).put("underlying_blue_fraction", blue))
                    save()
                    return red to blue
                } finally { image.recycle() }
            }
            stage = "capture_visible"
            assertTrue("Initial raw full-display capture must contain the actual red overlay", shot("01-visible").first > .98)
            stage = "reflect_owned_surface"
            surface = main {
                val root = if (bypass) {
                    reflectiveMethod = "View.getRootSurfaceControl(public)"
                    requireNotNull(requireNotNull(view).rootSurfaceControl)
                } else {
                    reflectiveMethod = "View.getViewRootImpl"
                    requireNotNull(View::class.java.getDeclaredMethod("getViewRootImpl").apply { isAccessible = true }.invoke(view))
                }
                report.put("root_surface_control_class", root.javaClass.name)
                reflectiveMethod = "ViewRootImpl.getSurfaceControl"
                if (bypass) HiddenApiBypass.invoke(root.javaClass, root, "getSurfaceControl") as SurfaceControl
                else root.javaClass.getDeclaredMethod("getSurfaceControl").apply { isAccessible = true }.invoke(root) as SurfaceControl
            }
            assertTrue(requireNotNull(surface).isValid)
            reflectiveMethod = "SurfaceControl.Transaction.setSkipScreenshot"
            skipMethod = if (bypass) HiddenApiBypass.getDeclaredMethod(SurfaceControl.Transaction::class.java,
                "setSkipScreenshot", SurfaceControl::class.java, java.lang.Boolean.TYPE)
            else SurfaceControl.Transaction::class.java.getDeclaredMethod("setSkipScreenshot", SurfaceControl::class.java, java.lang.Boolean.TYPE)
                .apply { isAccessible = true }
            stage = "commit_skip_on"; setSkip(true)
            stage = "capture_skip_on"
            val skipped = shot("02-skip-on")
            assertTrue("Skip-on must expose current blue application pixels under the overlay", skipped.first < .01 && skipped.second > .98)
            assertTrue("SkipScreenshot must not hide the actual view", main {
                requireNotNull(view).let { it.isAttachedToWindow && it.isShown && it.alpha == 1f && (it.layoutParams as WindowManager.LayoutParams).alpha == 1f }
            })
            stage = "commit_skip_off"; setSkip(false)
            stage = "capture_skip_off"
            assertTrue("Skip-off must restore the same opaque overlay in full-display capture", shot("03-skip-off").first > .98)
            report.put("passed", true)
        } catch (error: Throwable) {
            failure = error
            report.put("failure_stage", stage).put("failure_method", reflectiveMethod ?: JSONObject.NULL)
                .put("exception_class", exceptionName(error)).put("exception_chain", JSONArray(exceptionChain(error)))
        } finally {
            try {
                if (surface?.isValid == true && skipMethod != null) {
                    setSkip(false)
                    report.put("finally_skip_reset", true)
                }
            } catch (error: Throwable) {
                report.put("cleanup_exception_class", exceptionName(error)).put("cleanup_exception_chain", JSONArray(exceptionChain(error))).put("passed", false)
                if (failure == null) failure = error
            } finally {
                main { view?.takeIf { it.isAttachedToWindow }?.let(wm::removeViewImmediate) }
                report.put("owned_overlay_removed", view?.isAttachedToWindow != true)
            }
            val unchanged = beforeState == durableDigest()
            report.put("user_history_and_configuration_unchanged", unchanged)
            if (!unchanged) { report.put("passed", false); if (failure == null) failure = AssertionError("durable_state_changed") }
            save()
            inst.sendStatus(0, Bundle().apply { putString("stream", "\nSkip screenshot probe: ${folder.absolutePath}/report.json\n") })
        }
        if (failure != null) throw AssertionError("Skip screenshot probe failed at ${report.optString("failure_stage", "cleanup")}; ${report.optString("exception_class", "cleanup_error")}; see report.json")
    }
}

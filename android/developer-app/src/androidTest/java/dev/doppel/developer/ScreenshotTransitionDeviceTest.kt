package dev.doppel.developer

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
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

/** Real Activity transitions and production screenshots; no model, task submission or game input.
 * Host must confirm BOTH packages have no worker or active/armed automatic task before instrumentation.
 * -e screenshot_transition_live true [-e screenshot_transition_cycles 3]
 * Existing enabled services are retained; only this test's paused worker is started/stopped.
 */
class ScreenshotTransitionDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun <T> main(block: () -> T): T {
        var result: T? = null
        inst.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun await(message: String, timeout: Long = 6000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) { if (condition()) return; Thread.sleep(40) }
        assertTrue(message, condition())
    }
    private fun fileBytes(name: String) = File(context.noBackupFilesDir, name).takeIf { it.isFile }?.readBytes()
    private fun unchanged(before: ByteArray?, after: ByteArray?) = before?.contentEquals(after) ?: (after == null)
    private fun windows(service: DoppelAccessibilityService): JSONArray = JSONArray(service.windows.map { window ->
        val bounds = Rect().also(window::getBoundsInScreen)
        val node = window.root
        try {
            JSONObject().put("id", window.id).put("type", window.type).put("layer", window.layer)
                .put("focused", window.isFocused).put("active", window.isActive).put("root_available", node != null)
                .put("package", node?.packageName?.toString().orEmpty())
                .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
        } finally { @Suppress("DEPRECATION") node?.recycle() }
    })
    private fun companionVisible(worker: DeviceWorkerService): Boolean = main {
        val overlay = field(worker, "overlay") ?: return@main false
        val view = field(overlay, "root") as View
        val params = view.layoutParams as? WindowManager.LayoutParams
        view.isAttachedToWindow && view.visibility == View.VISIBLE && view.alpha > 0 && (params?.alpha ?: 0f) > 0
    }

    @Test fun productionScreenshotsAcrossRealAppsWithAssistantWindows() {
        assumeTrue(args.getString("screenshot_transition_live") == "true")
        assertTrue("Native window capture requires Android 14+", Build.VERSION.SDK_INT >= 34)
        assertEquals("Use the dedicated developer package", "dev.doppel.developer", context.packageName)
        assertNull("Never replace an existing worker", DeviceWorkerService.instance)
        val prefs = context.getSharedPreferences("doppel", Context.MODE_PRIVATE)
        assertFalse("Do not migrate an existing connection", prefs.getBoolean("artemis_mode", false))
        val activeBefore = prefs.getString("active_run", "").orEmpty()
        val tasksBefore = fileBytes("direct-runs-v1.json")
        val runs = tasksBefore?.let { JSONArray(String(it, Charsets.UTF_8)) } ?: JSONArray()
        val terminal = setOf("completed", "failed", "cancelled")
        assertTrue("Never interrupt an unfinished task", (0 until runs.length()).all {
            runs.getJSONObject(it).optString("status") in terminal
        })
        assertTrue("An existing task pointer must resolve to a confirmed terminal local run", activeBefore.isBlank() ||
            (0 until runs.length()).any {
                val run = runs.getJSONObject(it)
                run.optString("id") == activeBefore && run.optString("status") in terminal
            })
        assertTrue("Existing consent must be accepted before this test", FirstUseConsent.isAccepted(context))
        assertTrue("Existing overlay permission is required", Settings.canDrawOverlays(context))
        assertTrue(context.getSystemService(PowerManager::class.java).isInteractive)
        assertFalse(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val enabledBefore = Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services").orEmpty()
        val automation = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val schedulesBefore = fileBytes("schedules-v1.json")
        val jobs = schedulesBefore?.let { JSONObject(String(it, Charsets.UTF_8)).getJSONArray("items") } ?: JSONArray()
        assertFalse("Do not pause or edit an enabled schedule", (0 until jobs.length()).any { jobs.getJSONObject(it).optBoolean("enabled") })
        val triggerPrefs = context.getSharedPreferences("doppel_auto_triggers", Context.MODE_PRIVATE)
        val triggersBefore = triggerPrefs.getString("rules", null)
        val rules = JSONArray(triggersBefore ?: "[]")
        assertFalse("Do not pause or edit an enabled trigger", (0 until rules.length()).any { rules.getJSONObject(it).optBoolean("enabled") })
        val cycles = (args.getString("screenshot_transition_cycles")?.toIntOrNull() ?: 3).also { require(it in 1..10) }
        val fixturePackage = "dev.doppel.testapp"
        val gamePackage = listOf("com.hypergryph.arknights.bilibili", "com.hypergryph.arknights")
            .firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }
            ?: error("Install the real game before this opt-in test")
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homePackage = requireNotNull(context.packageManager.resolveActivity(home, 0)).activityInfo.packageName
        val targets = listOf(
            Triple("settings", "com.android.settings", Intent(Settings.ACTION_SETTINGS)),
            Triple("home", homePackage, home),
            Triple("native_fixture", fixturePackage, Intent().setClassName(fixturePackage, "$fixturePackage.InteractionFixtureActivity")),
            Triple("arknights", gamePackage, requireNotNull(context.packageManager.getLaunchIntentForPackage(gamePackage)))
        )
        val label = "transition-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val folder = File(context.getExternalFilesDir(null), "screenshot-transition/$label").apply { check(mkdirs()) }
        val receipts = JSONArray()
        val report = JSONObject().put("status", "running").put("model_requests", 0).put("submitted_tasks", 0)
            .put("game_input_actions", 0).put("cycles", cycles).put("receipts", receipts)
            .put("enabled_services_before", enabledBefore).put("scope", "real Activity launches and production screenshot commands; paused worker and touch guard")
        var worker: DeviceWorkerService? = null
        var startedWorker = false
        var connectionChanged = false
        val connectionKeys = listOf("direct_mode", "device_id", "action_feedback", "active_run")
        val oldConnection = connectionKeys.associateWith { prefs.all[it] }
        var failure: Throwable? = null
        var consecutiveRootFailures = 0
        var maxRootFailures = 0
        fun save() = File(folder, "report.json").writeText(report.toString(2))
        try {
            save()
            if (DoppelAccessibilityService.instance == null)
                AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst) { report.put("rebind_completed", it.optString("stage")); save() }
            val service = requireNotNull(DoppelAccessibilityService.instance)
            // A paused worker still polls data-cleanup when it has a device ID. Blank only that
            // test connection; never connect this capture-only fixture to the owner's gateway.
            check(prefs.edit().putBoolean("direct_mode", false).putString("device_id", "").remove("active_run").commit())
            connectionChanged = true
            startedWorker = true
            context.startForegroundService(Intent(context, DeviceWorkerService::class.java).setAction(DeviceWorkerService.PAUSE))
            await("This test's paused worker must show its companion") {
                DeviceWorkerService.instance?.let { it.isPaused && companionVisible(it) } == true
            }
            worker = requireNotNull(DeviceWorkerService.instance)
            service.setTouchGuard(true)
            await("The production touch guard must be installed") { service.guardVisible }
            for (cycle in 1..cycles) for ((name, expectedPackage, intent) in targets) {
                assertTrue("Only the owned paused worker may remain", DeviceWorkerService.instance === worker && worker!!.isPaused)
                assertTrue(prefs.getString("active_run", "").isNullOrBlank())
                context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                // Deliberately do not query a root before capture: that would warm the accessibility
                // cache and hide the transient-missing-root regression being measured.
                var matchedSuccess = false
                repeat(4) { attempt ->
                    if (attempt > 0) Thread.sleep(400)
                    val started = SystemClock.elapsedRealtime()
                    val result = service.execute(JSONObject().put("id", UUID.randomUUID().toString()).put("run_id", label)
                        .put("kind", "screenshot").put("split_agent", true).put("mode", "full"))
                    val data = result.optJSONObject("data") ?: JSONObject()
                    val observedPackage = result.optJSONObject("observation")?.optString("package_name").orEmpty()
                    val receipt = JSONObject().put("cycle", cycle).put("target", name).put("attempt", attempt + 1)
                        .put("elapsed_ms", SystemClock.elapsedRealtime() - started).put("status", result.optString("status"))
                        .put("observed_package", observedPackage).put("expected_package", expectedPackage)
                    for (key in listOf("capture_backend", "window_capture_fallback", "capture_window_id", "overlay_cleanup_performed",
                        "capture_pixels_on_main_thread", "reason_code", "read_diagnostic", "feedback_cleanup"))
                        if (data.has(key)) receipt.put(key, data.get(key))
                    val rootFailed = data.optJSONObject("read_diagnostic")?.optString("reason_code") == "root_unavailable"
                    consecutiveRootFailures = if (rootFailed) consecutiveRootFailures + 1 else 0
                    maxRootFailures = maxOf(maxRootFailures, consecutiveRootFailures)
                    if (result.optString("status") == "ok" && observedPackage == expectedPackage) {
                        assertEquals("Android 14 must capture only the primary window, even beside other windows", "accessibility_window", data.getString("capture_backend"))
                        assertFalse("The companion must remain visible during native window capture", data.getBoolean("overlay_cleanup_performed"))
                        val bytes = Base64.decode(data.getString("image_base64"), Base64.NO_WRAP)
                        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                        try { assertTrue(bitmap.width > 0 && bitmap.height > 0) } finally { bitmap.recycle() }
                        val imageName = "$cycle-$name-${attempt + 1}.png"
                        File(folder, imageName).writeBytes(bytes)
                        receipt.put("image", imageName)
                        matchedSuccess = true
                        assertFalse("Production pixel processing must not use the UI thread", data.optBoolean("capture_pixels_on_main_thread", true))
                    }
                    receipt.put("windows_after", windows(service)).put("guard_visible", service.guardVisible)
                        .put("companion_visible", companionVisible(worker!!))
                    receipts.put(receipt); save()
                }
                assertTrue("A fresh screenshot must identify $name within four captures; see report.json", matchedSuccess)
                assertTrue("Four consecutive missing roots must not exhaust capture recovery", maxRootFailures < 4)
                assertTrue("The touch guard must survive capture", service.guardVisible)
                assertTrue("The companion must be restored after capture", companionVisible(worker!!))
            }
            report.put("status", "passed").put("max_consecutive_root_failures", maxRootFailures)
        } catch (error: Throwable) {
            failure = error
            report.put("status", "failed").put("failure_class", error.javaClass.simpleName)
                .put("max_consecutive_root_failures", maxRootFailures)
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(work: () -> Unit) { try { work() } catch (error: Throwable) {
                if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
            } }
            cleanup {
                if (startedWorker) {
                    DoppelAccessibilityService.instance?.setTouchGuard(false)
                    // Preflight proved none existed; instrumentation owns only this package's worker.
                    context.stopService(Intent(context, DeviceWorkerService::class.java))
                    await("The capture fixture worker must stop") { DeviceWorkerService.instance == null }
                    await("The capture fixture guard must detach") { DoppelAccessibilityService.instance?.guardVisible != true }
                }
            }
            cleanup {
                check(DeviceWorkerService.instance == null) { "Do not restore the real connection while a fixture worker survives" }
                if (connectionChanged) check(prefs.edit().apply { oldConnection.forEach { (key, value) ->
                    when (value) { null -> remove(key); is Boolean -> putBoolean(key, value); is String -> putString(key, value) }
                } }.commit())
            }
            cleanup {
                assertTrue("Preserve all task history", unchanged(tasksBefore, fileBytes("direct-runs-v1.json")))
                assertTrue("Preserve all schedules", unchanged(schedulesBefore, fileBytes("schedules-v1.json")))
                assertEquals(triggersBefore, triggerPrefs.getString("rules", null))
                assertTrue("Restore the exact original task pointer", oldConnection["active_run"] == prefs.all["active_run"])
                assertEquals(enabledBefore, Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services").orEmpty())
                report.put("tasks_and_rules_unchanged", true).put("enabled_services_unchanged", true)
            }
            if (cleanupFailure != null) { report.put("status", "failed").put("cleanup_failure_class", cleanupFailure!!.javaClass.simpleName) }
            save()
            inst.sendStatus(0, Bundle().apply { putString("stream", "\nScreenshot transition evidence: ${folder.absolutePath}/report.json\n") })
            cleanupFailure?.let { if (failure == null) failure = it else failure!!.addSuppressed(it) }
        }
        failure?.let { throw AssertionError("Screenshot transitions failed; see report.json", it) }
    }
}

package dev.doppel.developer

import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.CompanionOverlay
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DoppelAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Zero-model, zero-input handoff diagnostic over the actual foreground game.
 * Prefer the existing paused worker and its production delegate. Never create,
 * resume, cancel or mutate runs. An explicitly permitted standalone path is only
 * a component comparison, and is labelled as such in the report.
 * -e class dev.doppel.developer.CompanionTouchPassDeviceTest
 * -e touch_pass_live true -e touch_pass_label unique-label
 * -e touch_pass_require_worker true (default)
 * The required worker is started with PAUSE when instrumentation restarted its process.
 * -e touch_pass_running_visual true (optional rendering-only contrast; runs stay paused)
 */
class CompanionTouchPassDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val normal = Handler(Looper.getMainLooper())
    private val async = Handler.createAsync(Looper.getMainLooper())

    private fun <T> main(block: () -> T): T {
        val work = FutureTask(block)
        check(async.post(work))
        try { return work.get(1500, TimeUnit.MILLISECONDS) }
        finally { async.removeCallbacks(work); if (!work.isDone) work.cancel(false) }
    }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
    private fun call(owner: Any, prefix: String, vararg args: Any?): Any? {
        val method = owner.javaClass.methods.single { (it.name == prefix || it.name.startsWith(prefix + "$")) && it.parameterCount == args.size }
        return try { method.invoke(owner, *args) }
        catch (error: InvocationTargetException) { throw error.targetException }
    }
    private fun ping(handler: Handler): JSONObject {
        val started = SystemClock.elapsedRealtime()
        val work = FutureTask { SystemClock.elapsedRealtime() - started }
        val row = JSONObject()
        try {
            check(handler.post(work))
            row.put("acknowledged", true).put("dispatch_ms", work.get(250, TimeUnit.MILLISECONDS))
        } catch (error: Throwable) {
            row.put("acknowledged", false).put("error", error.javaClass.simpleName)
        } finally { handler.removeCallbacks(work); if (!work.isDone) work.cancel(false) }
        return row.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
    }
    private fun taskBytes() = File(context.noBackupFilesDir, "direct-runs-v1.json").takeIf { it.isFile }?.readBytes()
    private fun digest(bytes: ByteArray?) = bytes?.let { MessageDigest.getInstance("SHA-256").digest(it)
        .joinToString("") { b -> "%02x".format(b) } } ?: "absent"
    private fun state(overlay: CompanionOverlay): JSONObject = main {
        val root = field(overlay, "root") as View
        val params = field(overlay, "params") as WindowManager.LayoutParams
        JSONObject().put("attached", root.isAttachedToWindow).put("alpha", params.alpha)
            .put("not_touchable", params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            .put("flags", params.flags).put("visibility", root.visibility)
            .put("capture_hidden", field(overlay, "captureHidden"))
            .put("editor_hidden", field(overlay, "editorHidden"))
            .put("edge_attached", (field(overlay, "edge") as? View)?.isAttachedToWindow == true)
            .put("visual_state", call(root, "getState").toString())
    }

    @Test fun handoffOverActualArknights() {
        assumeTrue(args.getString("touch_pass_live") == "true")
        val label = args.getString("touch_pass_label") ?: "handoff-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        require(label.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val folder = File(context.getExternalFilesDir(null), "touch-pass/$label")
        check(!folder.exists() && folder.mkdirs()) { "Use a fresh evidence label" }
        val report = JSONObject().put("status", "running").put("label", label)
            .put("model_calls", 0).put("game_input_actions", 0).put("cycles", JSONArray())
        val started = SystemClock.elapsedRealtime()
        var stage = "preconditions"
        fun save() { report.put("stage", stage).put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            File(folder, "report.json").writeText(report.toString(2)) }
        var before = taskBytes()
        val valid = AtomicBoolean(true)
        var own: CompanionOverlay? = null
        var lease: AutoCloseable? = null
        var restoreVisual: (() -> Unit)? = null
        var failure: Throwable? = null
        try {
            report.put("run_state_sha256_before", digest(before)); save()
            val automation = inst.getUiAutomation(1)
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            val packages = automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
                .mapNotNull { window -> val node = window.root
                    try { node?.packageName?.toString() } finally { @Suppress("DEPRECATION") node?.recycle() } }
            report.put("foreground_packages", JSONArray(packages)); save()
            assertTrue("Keep real Arknights foreground; this test never navigates", packages.any {
                it in setOf("com.hypergryph.arknights.bilibili", "com.hypergryph.arknights") })
            val bitmap = requireNotNull(automation.takeScreenshot())
            try { File(folder, "real-game-before.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
            if (args.getString("touch_pass_running_visual") == "true" && DoppelAccessibilityService.instance == null) {
                stage = "rebind_already_enabled_accessibility"; save()
                val binding = AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst) { diagnostic ->
                    report.put("accessibility_binding", diagnostic); save()
                }
                report.put("accessibility_rebound", binding.getBoolean("rebound"))
                    .put("enabled_services_unchanged", binding.getBoolean("enabled_services_unchanged")); save()
            }
            if (DeviceWorkerService.instance == null && args.getString("touch_pass_require_worker", "true") == "true") {
                stage = "start_paused_worker"; save()
                report.put("run_state_sha256_before_worker_start", digest(before))
                // Never use the default service intent: it means resume().
                context.startForegroundService(Intent(context, DeviceWorkerService::class.java).setAction(DeviceWorkerService.PAUSE))
                val deadline = SystemClock.elapsedRealtime() + 6000
                while (SystemClock.elapsedRealtime() < deadline) {
                    val ready = main { DeviceWorkerService.instance?.let { it.isPaused && field(it, "overlay") != null } == true }
                    if (ready) break
                    Thread.sleep(25)
                }
                // PAUSE may asynchronously reconcile its saved run. Take the immutable
                // handoff baseline after that setup has settled, not before service start.
                var lastBytes = taskBytes()
                var stableSince = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(50)
                    val now = taskBytes()
                    if (!(lastBytes?.contentEquals(now) ?: (now == null))) {
                        lastBytes = now; stableSince = SystemClock.elapsedRealtime()
                    }
                    if (SystemClock.elapsedRealtime() - stableSince >= 400) break
                }
                before = taskBytes()
                report.put("worker_started_with_pause", true).put("run_state_sha256_before", digest(before)); save()
            }
            val worker = DeviceWorkerService.instance
            report.put("worker_path_tested", worker != null)
            if (args.getString("touch_pass_require_worker", "true") == "true") assertNotNull("Existing paused worker required", worker)
            val revision = worker?.companionRevision
            val overlay = if (worker != null) {
                assertTrue("Pause the existing run before the zero-input handoff diagnostic", worker.isPaused)
                requireNotNull(main { field(worker, "overlay") as? CompanionOverlay }) { "Worker has no production overlay; a no-op lease is not success" }
            } else {
                main { CompanionOverlay(context) { error("No diagnostic input permitted") }.also { own = it; it.show() } }
            }
            if (args.getString("touch_pass_running_visual") == "true") {
                stage = "rendering_only_running_contrast"; save()
                main {
                    val root = field(overlay, "root") as View
                    val previousState = requireNotNull(call(root, "getState"))
                    val previousEdge = (field(overlay, "edge") as? View)?.isAttachedToWindow == true
                    val updateEdge = overlay.javaClass.getDeclaredMethod("updateEdge", Boolean::class.javaPrimitiveType)
                        .apply { isAccessible = true }
                    restoreVisual = { main { call(root, "setState", previousState); updateEdge.invoke(overlay, previousEdge) } }
                    val running = requireNotNull(previousState.javaClass.enumConstants).single { (it as Enum<*>).name == "RUNNING" }
                    call(root, "setState", running)
                    updateEdge.invoke(overlay, true)
                }
                // Render real production animation while the actual worker remains paused.
                Thread.sleep(160)
                report.put("rendering_only_running_contrast", true).put("synthetic_run_created", false)
                assertTrue("Already-enabled accessibility service required for full-screen edge contrast",
                    state(overlay).getBoolean("edge_attached"))
            }
            report.put("worker_revision", revision).put("baseline_overlay", state(overlay)); save()
            assertTrue("Production overlay must be attached", state(overlay).getBoolean("attached"))
            val audio = context.getSystemService(AudioManager::class.java)
            fun callInProgress() = audio.mode in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)
            // The actual input path asks once in readInterrupted and again in live().
            val current: () -> Boolean = { valid.get() && !callInProgress() && !callInProgress() &&
                !Thread.currentThread().isInterrupted && if (worker != null)
                    DeviceWorkerService.instance === worker && worker.companionRevision == revision && worker.isPaused
                    else DeviceWorkerService.instance == null }
            report.put("host_check", "test generation live; twice AudioManager.mode; worker identity/revision; paused")
            var allPassed = true
            repeat(3) { index ->
                val row = JSONObject().put("cycle", index + 1)
                report.getJSONArray("cycles").put(row)
                stage = "cycle_${index + 1}_main_ping"; save()
                row.put("normal_main_ping", ping(normal)).put("async_main_ping", ping(async))
                row.put("before", state(overlay)); save()
                stage = "cycle_${index + 1}_begin"; save()
                val at = SystemClock.elapsedRealtime()
                lease = call(worker ?: overlay, if (worker != null) "beginCompanionGestureTouchPass" else "beginGestureTouchPass",
                    SystemClock.elapsedRealtimeNanos(), 500L, current) as? AutoCloseable
                row.put("begin_ms", SystemClock.elapsedRealtime() - at).put("lease_acquired", lease != null)
                    .put("diagnostic", call(overlay, "gestureTouchPassDiagnostic"))
                    .put("main_stack", JSONArray(Looper.getMainLooper().thread.stackTrace.map { it.toString() }))
                save()
                row.put("handoff_state", state(overlay)); save()
                val hidden = row.getJSONObject("handoff_state")
                val handoffPassed = lease != null && hidden.getBoolean("not_touchable") && hidden.getDouble("alpha") == 0.0
                stage = "cycle_${index + 1}_close"; save()
                lease?.close(); lease = null
                val deadline = SystemClock.elapsedRealtime() + 1800
                var restored = state(overlay)
                val baseline = report.getJSONObject("baseline_overlay")
                fun matches() = restored.getDouble("alpha") == baseline.getDouble("alpha") &&
                    restored.getBoolean("not_touchable") == baseline.getBoolean("not_touchable")
                while (!matches() && SystemClock.elapsedRealtime() < deadline) { Thread.sleep(25); restored = state(overlay) }
                row.put("restored_state", restored).put("restored", matches()).put("passed", handoffPassed && matches())
                    .put("diagnostic_after_close", call(overlay, "gestureTouchPassDiagnostic"))
                allPassed = allPassed && handoffPassed && matches(); save()
            }
            assertTrue("Production overlay handoff failed; inspect stage timings", allPassed)
            assertTrue("Worker identity/revision/paused condition changed", current())
            report.put("status", "passed")
        } catch (error: Throwable) {
            failure = error
            report.put("status", "failed").put("failure_stage", stage).put("error", error.javaClass.simpleName)
                .put("message", error.message.orEmpty().take(500))
        } finally {
            valid.set(false)
            try { lease?.close(); restoreVisual?.invoke(); own?.let { main { it.close() } }
                report.put("rendering_contrast_restored", true) }
            catch (error: Throwable) { report.put("cleanup_error", error.javaClass.simpleName); if (failure == null) failure = error }
            val after = taskBytes()
            val unchanged = before?.contentEquals(after) ?: (after == null)
            report.put("run_state_sha256_after", digest(after)).put("run_state_unchanged", unchanged)
            if (!unchanged && failure == null) failure = AssertionError("Run state changed during zero-input diagnostic")
            if (failure != null) report.put("status", "failed")
            save()
            inst.sendStatus(0, Bundle().apply { putString("stream", "\nTouch pass evidence: ${folder.absolutePath}/report.json\n") })
        }
        failure?.let { throw AssertionError("Touch pass diagnostic failed at $stage; see report.json", it) }
    }
}

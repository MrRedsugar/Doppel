package dev.doppel.developer

import android.app.Activity
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DirectCredentials
import dev.doppel.sdk.DirectMode
import dev.doppel.sdk.DirectRuntime
import dev.doppel.sdk.DoppelAccessibilityService
import dev.doppel.sdk.FirstUseConsent
import dev.doppel.sdk.Gateway
import dev.doppel.sdk.LegacyScreenCaptureService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Opt-in, paid, emulator-only acceptance through the real MainActivity composer.
 * Requires adaptive_live=true, emulatorOnly=true and goals_base64 (UTF-8 JSON string array).
 * Only Doppel's real runtime/provider/worker may observe or operate a target application.
 * Runtime completion is recorded separately from independent business-result verification.
 */
class AdaptiveRealAppsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val gateway by lazy { Gateway(context) }
    private val automation by lazy {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }
    private val terminal = setOf("completed", "failed", "cancelled")
    private val ownedIds = linkedSetOf<String>()
    private var activity: Activity? = null

    /** Narrow cleanup for an interrupted harness-owned run; never starts a model or device worker. */
    @Test fun cancelExplicitInterruptedRun() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cancel_interrupted") == "true")
        check(args.getString("emulatorOnly") == "true"); verifyEmulator()
        val id = requireNotNull(args.getString("cancel_run_id"))
        val goal = String(Base64.decode(requireNotNull(args.getString("goal_base64")), Base64.DEFAULT), Charsets.UTF_8)
        val run = gateway.request("GET", "/runs/$id")
        check(run.getString("goal") == goal && run.getString("status") == "paused")
        gateway.request("POST", "/runs/$id/cancel", JSONObject())
        assertEquals("cancelled", gateway.request("GET", "/runs/$id").getString("status"))
    }

    @Test fun executesNaturalGoalsThroughRealComposer() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires adaptive_live=true", args.getString("adaptive_live") == "true")
        check(args.getString("emulatorOnly") == "true") { "Requires explicit emulatorOnly=true" }
        val encoded = requireNotNull(args.getString("goals_base64")) { "Supply goals_base64" }
        require(encoded.length in 1..20000)
        val input = JSONArray(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8))
        require(input.length() in 1..4) { "Supply one to four explicit goals" }
        val goals = (0 until input.length()).map { index ->
            val value = input.get(index)
            require(value is String && value.trim().length in 1..2000)
            value.trim()
        }
        val gameProfile = args.getString("profile") == "game"
        if (gameProfile) require(goals.size == 1) { "Game profile accepts one explicit goal" }
        val maxCalls = args.getString("max_calls", if (gameProfile) "60" else "18")!!.toInt()
            .also { require(it in 1..if (gameProfile) 60 else 18) }
        val maxMs = args.getString("max_ms", if (gameProfile) "600000" else "150000")!!.toLong()
            .also { require(it in 1000..if (gameProfile) 600000 else 150000) }
        val session = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val folder = File(context.filesDir, "adaptive-live/$session").apply { check(mkdirs()) }
        val report = JSONObject().put("session", session).put("started_at", System.currentTimeMillis())
            .put("max_calls", maxCalls).put("max_ms", maxMs).put("profile", if (gameProfile) "game" else "ordinary").put("cases", JSONArray())
            .put("control", "Real MainActivity composer and Doppel runtime/provider/worker only")
            .put("business_result_independently_verified", false)
            .put("limit_semantics", "Observed-call/time watchdog; asynchronous work may exceed a threshold before cancellation")
        val preferences = linkedMapOf<String, Any?>()
        val modePreferences = linkedMapOf<String, Any?>()
        val ownService = ComponentName(context, DoppelAccessibilityService::class.java)
        var ownServiceWasEnabled = false
        var accessibilityWasEnabled = ""
        var accessibilityTouched = false
        var captureCreated = false
        var setupComplete = false
        var failure: Throwable? = null
        var pending: PendingSubmission? = null
        try {
            val feedback = args.getString("feedback_operator") ?: if (args.getString("continuous_agent") == "true") "false" else "true"
            require(feedback in setOf("true", "false"))
            modePreferences["feedback_operator"] = gateway.prefs.all["feedback_operator"]
            check(gateway.prefs.edit().putBoolean("feedback_operator", feedback == "true").commit())
            report.put("feedback_operator", feedback == "true")
            args.getString("continuous_agent")?.let { value ->
                require(value in setOf("true", "false"))
                modePreferences["continuous_agent"] = gateway.prefs.all["continuous_agent"]
                check(gateway.prefs.edit().putBoolean("continuous_agent", value == "true").commit())
                report.put("continuous_agent", value == "true")
            }
            report.put("emulator", verifyEmulator())
            check(DirectMode.isDeveloperBuild(context) && gateway.isDirectMode()) { "Configure developer direct mode first" }
            check(DirectCredentials(context).hasKey() && gateway.isConnected()) { "Existing encrypted credentials are required" }
            check(FirstUseConsent.isAccepted(context)) { "Accept first-use consent before this test" }
            check(Settings.canDrawOverlays(context)) { "Grant overlay permission before this test" }
            // This local check does not pump an existing task through Gateway GET.
            check(!DirectRuntime.get(context).hasUnfinishedRun()) { "An unfinished task already exists; leave it untouched" }
            check(DeviceWorkerService.instance == null) { "Stop the existing worker before this isolated test" }
            for (key in listOf("draft_goal", "mode_index", "active_run", "conversation_tail", "conversation_scope", "conversation_epoch")) {
                preferences[key] = gateway.prefs.all[key]
            }
            ownServiceWasEnabled = enabledServices().any { ComponentName.unflattenFromString(it) == ownService }
            accessibilityWasEnabled = Settings.Secure.getString(context.contentResolver, "accessibility_enabled").orEmpty()
            accessibilityTouched = true
            setOwnAccessibility(ownService, enabled = false, accessibilityWasEnabled)
            await(5000, "Doppel accessibility did not unbind") { DoppelAccessibilityService.instance == null }
            setOwnAccessibility(ownService, enabled = true, accessibilityWasEnabled)
            await(8000, "Doppel accessibility did not bind") { DoppelAccessibilityService.instance != null }
            if (Build.VERSION.SDK_INT in 26..29 && !LegacyScreenCaptureService.isReady) {
                // Actual own explanation page and the genuine system MediaProjection dialog, during setup only.
                captureCreated = true
                LegacyCaptureConsent.authorize(instrumentation)
                check(LegacyScreenCaptureService.isReady)
            }
            setupComplete = true
            writeJson(File(folder, "summary.json"), report)
            for ((index, goal) in goals.withIndex()) {
                check(!DirectRuntime.get(context).hasUnfinishedRun()) { "Another unfinished task appeared; stop the harness" }
                finishActivity()
                val baseline = listRuns().map { it.getString("id") }.toSet()
                gateway.startNewConversation()
                check(gateway.prefs.edit().putInt("mode_index", 2).putString("draft_goal", "")
                    .remove("active_run").commit())
                val record = JSONObject().put("index", index + 1).put("goal", goal)
                    .put("outcome", "not_submitted").put("business_result_independently_verified", false)
                report.getJSONArray("cases").put(record)
                val caseFile = File(folder, "case-${index + 1}.json")
                writeJson(caseFile, record)
                openComposer()
                // Recheck immediately before the only task-submission click.
                check(!DirectRuntime.get(context).hasUnfinishedRun())
                val submittedAt = System.currentTimeMillis()
                val started = SystemClock.elapsedRealtime()
                val submission = PendingSubmission(goal, submittedAt, started, baseline,
                    gateway.prefs.getString("conversation_epoch", "").orEmpty(), record, caseFile)
                pending = submission
                record.put("submitted_at", submittedAt).put("outcome", "submission_pending")
                writeJson(caseFile, record)
                submitGoal(goal)
                var run: JSONObject? = null
                val discoveryDeadline = minOf(started + maxMs, started + 10000)
                while (run == null && SystemClock.elapsedRealtime() < discoveryDeadline) {
                    run = identifyOwnedRun(submission)
                    if (run == null) Thread.sleep(100)
                }
                check(run != null) { "The composer did not expose a uniquely attributable run" }
                val id = run.getString("id")
                record.put("run_id", id)
                val sampledCalls = mutableSetOf<Int>()
                fun sampleObservationOnce(current: JSONObject) {
                    val calls = current.optInt("calls")
                    if (sampledCalls.add(calls)) {
                        sampleObservation(folder, index + 1, id, calls, record)
                        // Retain bounded per-call state so long game runs do not lose earlier receipts/events.
                        writeJson(File(folder, "run-${index + 1}-$calls.json"), current)
                    }
                }
                // Preserve the first observed count (including zero), then sample each new count once.
                sampleObservationOnce(run)
                var stopReason: String
                while (true) {
                    run = gateway.request("GET", "/runs/$id")
                    sampleObservationOnce(run)
                    val elapsed = SystemClock.elapsedRealtime() - started
                    val status = run.optString("status")
                    record.put("run", run).put("calls", run.optInt("calls")).put("elapsed_ms", elapsed)
                    writeJson(caseFile, record)
                    stopReason = when {
                        status in terminal -> status
                        status != "running" -> status.ifBlank { "unknown_status" }
                        run.optInt("calls") >= maxCalls -> "call_limit"
                        elapsed >= maxMs -> "time_limit"
                        else -> ""
                    }
                    if (stopReason.isNotEmpty()) break
                    Thread.sleep(200)
                }
                record.put("outcome", stopReason).put("observed_stop_status", run.optString("status"))
                finalizeRun(id, record, caseFile, started, maxCalls, maxMs, folder, index + 1)
                pending = null
                writeJson(File(folder, "summary.json"), report)
            }
        } catch (error: Throwable) {
            failure = error
            // Do not emit exception messages that might contain provider responses or credential material.
            report.put("failure", errorIdentity(error))
            pending?.record?.put("failure", errorIdentity(error))
        } finally {
            pending?.let { submission ->
                runCatching {
                    // A composer async callback may finish after submission polling failed. Match only our own pending submission.
                    if (submission.record.optString("run_id").isBlank()) {
                        val deadline = SystemClock.elapsedRealtime() + 5000
                        do {
                            identifyOwnedRun(submission)?.let { submission.record.put("run_id", it.getString("id")) }
                            if (submission.record.optString("run_id").isNotBlank()) break
                            Thread.sleep(100)
                        } while (SystemClock.elapsedRealtime() < deadline)
                    }
                    val id = submission.record.optString("run_id")
                    if (id.isNotBlank() && id in ownedIds) {
                        if (!submission.record.has("observed_stop_status")) submission.record.put("outcome", "harness_error")
                        finalizeRun(id, submission.record, submission.file, submission.started, maxCalls, maxMs, folder,
                            submission.record.getInt("index"))
                    } else submission.record.put("cleanup", "No uniquely attributable run; no arbitrary task was cancelled")
                }.onFailure { report.put("pending_cleanup_failure", errorIdentity(it)) }
                writeJson(submission.file, submission.record)
            }
            // Only explicitly attributed IDs are ever controlled. Unknown/preexisting runs are never cancelled.
            for (id in ownedIds) runCatching { cancelOwnedIfUnfinished(id) }
                .onFailure { report.put("owned_run_cleanup_failure", errorIdentity(it)) }
            runCatching { finishActivity() }.onFailure { report.put("activity_cleanup_failure", errorIdentity(it)) }
            // Worker shutdown interrupts the runtime globally, so do it only with no unfinished run.
            val idle = if (setupComplete || accessibilityTouched) runCatching {
                !DirectRuntime.get(context).hasUnfinishedRun()
            }.getOrDefault(false) else false
            if (idle) {
                runCatching {
                    instrumentation.runOnMainSync {
                        if (ownedIds.isNotEmpty()) context.stopService(Intent(context, DeviceWorkerService::class.java))
                        if (captureCreated) context.stopService(Intent(context, LegacyScreenCaptureService::class.java))
                    }
                    if (ownedIds.isNotEmpty()) await(5000, "Own worker did not stop") { DeviceWorkerService.instance == null }
                }.onFailure { report.put("setup_cleanup_failure", errorIdentity(it)) }
                runCatching { restorePreferences(preferences) }.onFailure { report.put("preferences_cleanup_failure", errorIdentity(it)) }
                if (accessibilityTouched) runCatching { setOwnAccessibility(ownService, ownServiceWasEnabled, accessibilityWasEnabled) }
                    .onFailure { report.put("accessibility_cleanup_failure", errorIdentity(it)) }
            } else if (accessibilityTouched) {
                report.put("setup_cleanup", "Preserved shared settings/services because an unfinished task exists or state is unknown")
            }
            // These switches were changed before setup could complete; restore even after a preflight failure.
            runCatching { restorePreferences(modePreferences) }.onFailure { report.put("mode_cleanup_failure", errorIdentity(it)) }
            report.put("finished_at", System.currentTimeMillis()).put("owned_run_ids", JSONArray(ownedIds.toList()))
            writeJson(File(folder, "summary.json"), report)
            writeJson(File(context.filesDir, "adaptive-live/latest-session.json"), JSONObject().put("session", session))
        }
        failure?.let { throw AssertionError("Adaptive live harness failed; inspect adaptive-live/$session (details kept private)") }
        val cases = report.getJSONArray("cases")
        assertTrue("Inspect adaptive-live/$session: runtime completion and watchdog limits must hold for every goal",
            cases.length() == goals.size && (0 until cases.length()).all {
                val record = cases.getJSONObject(it)
                record.optString("outcome") == "completed" && record.optBoolean("reported_completed") &&
                    !record.optBoolean("call_limit_exceeded") && !record.optBoolean("time_limit_exceeded")
            } && !report.has("owned_run_cleanup_failure") && !report.has("setup_cleanup_failure"))
    }

    private data class PendingSubmission(val goal: String, val submittedAt: Long, val started: Long,
        val baseline: Set<String>, val epoch: String, val record: JSONObject, val file: File)

    private fun listRuns(): List<JSONObject> {
        val items = gateway.request("GET", "/runs").getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it) }
    }

    private fun identifyOwnedRun(submission: PendingSubmission): JSONObject? {
        val candidates = listRuns().filter {
            it.optString("id").isNotBlank() && it.optString("id") !in submission.baseline &&
                it.optString("goal") == submission.goal && it.optLong("created_at") >= submission.submittedAt
        }
        check(candidates.size <= 1) { "Ambiguous task ownership" }
        val run = candidates.singleOrNull() ?: return null
        val id = run.getString("id")
        check(gateway.prefs.getString("conversation_epoch", "") == submission.epoch) { "Conversation changed during submission" }
        if (gateway.prefs.getString("active_run", "") != id && gateway.selectedConversationRun() != id) return null
        ownedIds.add(id)
        submission.record.put("run_id", id)
        return run
    }

    private fun cancelOwnedIfUnfinished(id: String): JSONObject {
        check(id in ownedIds)
        val current = gateway.request("GET", "/runs/$id")
        return if (current.optString("status") in terminal) current
        else gateway.request("POST", "/runs/$id/cancel", JSONObject())
    }

    private fun sampleObservation(folder: File, caseIndex: Int, id: String, calls: Int, record: JSONObject) {
        // Developer-only passive diagnostics: never request a new screen, tree, image or device action.
        // Gateway's returned call count and this later memory sample are not an atomic model request.
        runCatching {
            val runtime = DirectRuntime.get(context)
            val engine = checkNotNull(runtime.javaClass.getDeclaredField("engine").apply { isAccessible = true }.get(runtime))
            val sample = synchronized(engine) {
                val observation = engine.javaClass.getDeclaredField("observation").apply { isAccessible = true }
                    .get(engine) as? JSONObject
                JSONObject().put("sample_at", System.currentTimeMillis()).put("run_id", id).put("calls", calls)
                    .put("atomic_model_request", false).put("source", "passive_engine_memory_sample")
                    .put("source_may_lag_model_request", true)
                    .put("observation", observation?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
            }
            val filename = "observation-$caseIndex-$calls.json"
            writeJson(File(folder, filename), sample)
            val files = record.optJSONArray("observation_samples") ?: JSONArray().also { record.put("observation_samples", it) }
            files.put(filename)
        }.onFailure { error ->
            // A missing/renamed private field or a failed diagnostic write must not change task execution.
            runCatching {
                val failures = record.optJSONArray("observation_sample_failures") ?: JSONArray().also {
                    record.put("observation_sample_failures", it)
                }
                failures.put(JSONObject().put("calls", calls).put("class", error.javaClass.simpleName))
            }
        }
    }

    private fun finalizeRun(id: String, record: JSONObject, file: File, started: Long,
        maxCalls: Int, maxMs: Long, folder: File, index: Int) {
        val finalRun = cancelOwnedIfUnfinished(id)
        check(finalRun.optString("status") in terminal) { "Cancellation did not produce a terminal run" }
        val elapsed = SystemClock.elapsedRealtime() - started
        record.put("run", finalRun).put("final_status", finalRun.optString("status"))
            .put("calls", finalRun.optInt("calls")).put("elapsed_ms", elapsed)
            .put("reported_completed", finalRun.optString("status") == "completed")
            .put("call_limit_exceeded", finalRun.optInt("calls") > maxCalls)
            .put("time_limit_exceeded", elapsed > maxMs)
            .put("events", gateway.request("GET", "/runs/$id/events").getJSONArray("items"))
        writeJson(file, record)
        // No UiAutomation screenshots or accessibility inspection while any task is unfinished.
        if (!DirectRuntime.get(context).hasUnfinishedRun()) {
            val bitmap = automation.takeScreenshot()
            if (bitmap == null) record.put("screenshot", "unavailable") else try {
                val target = File(folder, "case-$index-terminal.png")
                target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                record.put("screenshot", target.name).put("screenshot_at", System.currentTimeMillis())
                    .put("screenshot_after_terminal", true)
            } finally { bitmap.recycle() }
        } else record.put("screenshot", "skipped: another task is unfinished")
        writeJson(file, record)
    }

    private fun openComposer() {
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync()
        await(5000, "Actual MainActivity composer did not become available") {
            var ready = false
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().singleOrNull()?.let { activity = it }
                val current = activity
                if (current != null && !current.isDestroyed && !current.isFinishing && current.hasWindowFocus()) {
                    val views = descendants(current.window.decorView).toList()
                    ready = views.filterIsInstance<EditText>().count { it.isShown && it.isEnabled && it.contentDescription == "任务输入" } == 1 &&
                        views.count { it.isShown && it.isEnabled && it.contentDescription == "开始任务" } == 1
                }
            }
            ready
        }
    }

    private fun submitGoal(goal: String) = instrumentation.runOnMainSync {
        val current = checkNotNull(activity)
        check(current.hasWindowFocus() && !current.isFinishing && !current.isDestroyed)
        val views = descendants(current.window.decorView).toList()
        views.filterIsInstance<EditText>().single { it.isShown && it.isEnabled && it.contentDescription == "任务输入" }.setText(goal)
        check(views.single { it.isShown && it.isEnabled && it.contentDescription == "开始任务" }.performClick())
    }

    private fun finishActivity() {
        instrumentation.runOnMainSync { activity?.takeUnless { it.isDestroyed || it.isFinishing }?.finish() }
        activity = null
    }

    private fun enabledServices() = Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services")
        .orEmpty().split(':').filter { it.isNotBlank() && it != "null" }

    private fun setOwnAccessibility(own: ComponentName, enabled: Boolean, previousGlobal: String) {
        if (Build.VERSION.SDK_INT >= 29) automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            // Merge with the current list on each write; never restore an obsolete list of other services.
            val others = enabledServices().filter { ComponentName.unflattenFromString(it) != own }
            val next = if (enabled) others + own.flattenToString() else others
            writeSecureSetting("enabled_accessibility_services", next.joinToString(":"))
            writeSecureSetting("accessibility_enabled", if (next.isNotEmpty()) "1" else previousGlobal)
        } finally { if (Build.VERSION.SDK_INT >= 29) automation.dropShellPermissionIdentity() }
    }

    private fun writeSecureSetting(name: String, value: String) {
        check(name in setOf("enabled_accessibility_services", "accessibility_enabled"))
        check(value.isEmpty() || value.matches(Regex("""[\p{L}\p{N}_./:$-]+""")))
        if (Build.VERSION.SDK_INT >= 29) {
            check(Settings.Secure.putString(context.contentResolver, name, value.ifEmpty { null }))
        } else {
            // Android 8/9: executeShellCommand tokenizes directly; shell quotes become literal values.
            val command = if (value.isEmpty()) "settings delete secure $name" else "settings put secure $name $value"
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { stream ->
                val buffer = ByteArray(1024)
                while (stream.read(buffer) != -1) { /* Drain without logging device data. */ }
            }
        }
        check(Settings.Secure.getString(context.contentResolver, name).orEmpty() == value) { "Secure setting write failed" }
    }

    private fun verifyEmulator(): JSONObject {
        fun property(name: String): String {
            check(name in setOf("ro.kernel.qemu", "ro.boot.qemu"))
            return ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("getprop $name"))
                .bufferedReader().use { it.readText().trim() }
        }
        val qemu = property("ro.kernel.qemu")
        val bootQemu = property("ro.boot.qemu")
        val values = listOf(Build.FINGERPRINT, Build.MODEL, Build.BRAND, Build.PRODUCT, Build.HARDWARE, Build.MANUFACTURER)
        val identity = values.joinToString(" ").lowercase(Locale.ROOT)
        val marker = listOf("generic", "emulator", "sdk_gphone", "sdk_google", "goldfish", "ranchu", "ldplayer", "leidian", "mumu", "nox")
            .firstOrNull { identity.contains(it) }
        // LDPlayer's default profile hides qemu and reports a mobile SoC; its native ABI remains x86.
        val ldProfile = Build.MODEL == "LDY-ANO0" && Build.SUPPORTED_ABIS.any { it in setOf("x86", "x86_64") }
        check(qemu == "1" || bootQemu == "1" || marker != null || ldProfile) { "No convincing Android emulator identity; refusing device execution" }
        return JSONObject().put("fingerprint", Build.FINGERPRINT).put("model", Build.MODEL)
            .put("brand", Build.BRAND).put("product", Build.PRODUCT).put("hardware", Build.HARDWARE)
            .put("manufacturer", Build.MANUFACTURER).put("sdk", Build.VERSION.SDK_INT)
            .put("ro.kernel.qemu", qemu).put("ro.boot.qemu", bootQemu).put("matched_marker", marker ?: if (ldProfile) "LDY_x86" else JSONObject.NULL)
    }

    private fun restorePreferences(saved: Map<String, Any?>) {
        if (saved.isEmpty()) return
        val editor: SharedPreferences.Editor = gateway.prefs.edit()
        for ((key, value) in saved) when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Boolean -> editor.putBoolean(key, value)
            else -> error("Unexpected saved preference type")
        }
        check(editor.commit())
    }

    private fun errorIdentity(error: Throwable) = JSONObject().put("class", error.javaClass.simpleName)
        .put("location", error.stackTrace.firstOrNull { it.className.startsWith("dev.doppel") }
            ?.let { "${it.fileName}:${it.lineNumber}" } ?: "unknown")

    private fun writeJson(file: File, value: JSONObject) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(value.toString(2).toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }

    private fun await(timeoutMs: Long, message: String, ready: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do { if (ready()) return; Thread.sleep(50) } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError(message)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}

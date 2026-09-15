package dev.doppel.developer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DirectCredentials
import dev.doppel.sdk.DirectMode
import dev.doppel.sdk.DirectRuntime
import dev.doppel.sdk.DoppelAccessibilityService
import dev.doppel.sdk.FirstUseConsent
import dev.doppel.sdk.Gateway
import dev.doppel.sdk.ResultStore
import dev.doppel.sdk.TaskPanelActivity
import dev.doppel.sdk.VoiceActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Paid, opt-in acceptance. No fake gateway, intercepted provider, fabricated screen or device command. */
class DirectLiveAcceptanceTest {
    companion object {
        private const val GOAL = "打开系统设置，告诉我手机型号"
        private const val MAX_MS = 90000L
        private const val MAX_CALLS = 12
        private val terminal = setOf("completed", "failed", "cancelled")
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val evidenceFile get() = File(context.filesDir, "direct-live-acceptance.json")

    @Test
    fun testSettingsAndPhoneModelViaRealVoiceComposer() {
        val keyFile = File(context.filesDir, "direct-qa-key.txt")
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Paid live acceptance requires explicit opt-in; no provider request was sent", args.getString("direct_live") == "true" || keyFile.isFile)
        assumeTrue("Scoped secure-settings identity requires Android 10 or newer", Build.VERSION.SDK_INT >= 29)
        val gateway = Gateway(context)
        val prefs = gateway.prefs
        assertTrue("Live QA requires the developer build marker", DirectMode.isDeveloperBuild(context))
        assertNull("Live QA must not stop an existing worker", DeviceWorkerService.instance)
        assertTrue("Existing active task must be ended before this test", prefs.getString("active_run", "").isNullOrBlank())
        assertTrue("Existing pending task must be ended before this test", prefs.getString("voice_pending_worker_run", "").isNullOrBlank())
        assertFalse("Close the existing voice editor before this test", VoiceActivity.isVisible)
        assertFalse("Close the existing task panel before this test", TaskPanelActivity.isVisible)
        assertTrue("Grant developer overlay permission first", Settings.canDrawOverlays(context))
        val runtime = DirectRuntime.get(context)
        assertFalse("Existing local tasks must be ended before this test", runtime.hasUnfinishedRun())

        val consentPrefs = context.getSharedPreferences("doppel_consent", Context.MODE_PRIVATE)
        val credentialPrefs = context.getSharedPreferences("doppel_direct_credentials", Context.MODE_PRIVATE)
        val before = prefs.all.toMap()
        val consentBefore = consentPrefs.all.toMap()
        val credentialsBefore = credentialPrefs.all.toMap()
        val credentials = DirectCredentials(context)
        val hadCredential = credentials.hasKey()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "${context.packageName}.developer.mimo.v1"
        val hadAlias = store.containsAlias(alias)
        var importedKey = false
        var activity: Activity? = null
        var runId: String? = null
        var snapshot: JSONObject? = null
        var eventList = JSONArray()
        var screen = JSONObject()
        var outcome = "failed"
        var failureCode = "setup_incomplete"
        var sawWorker = false
        var observedWorker: DeviceWorkerService? = null
        var capReached = false
        var runDeleted = false
        var start = 0L
        var previousServices: String? = null
        var previousAccessibility: String? = null
        var accessibilityChanged = false
        var primaryFailure: Throwable? = null
        val baselineRuns = if (gateway.isDirectMode()) runIds(gateway) else emptySet()
        val startedAt = System.currentTimeMillis()
        try {
            // This instrumentation process must own a fresh service binding without suppressing other services.
            instrumentation.getUiAutomation(1)
            val services = readSecure("enabled_accessibility_services")
            previousServices = services
            previousAccessibility = readSecure("accessibility_enabled")
            val own = ComponentName(context, DoppelAccessibilityService::class.java)
            val others = services.orEmpty().split(':').filter { it.isNotBlank() && it != "null" && ComponentName.unflattenFromString(it) != own }
            accessibilityChanged = true
            putSecure("enabled_accessibility_services", others.joinToString(":"))
            waitUntil(5000) { DoppelAccessibilityService.instance == null }
            putSecure("enabled_accessibility_services", (others + own.flattenToString()).joinToString(":"))
            putSecure("accessibility_enabled", "1")
            waitUntil(8000) { DoppelAccessibilityService.instance != null }
            if (!hadCredential) {
                assertTrue("Supply a private direct-qa-key.txt or configure an existing MiMo key", keyFile.isFile)
                assertTrue("Private key file size is invalid", keyFile.length() in 16..1024)
                val bytes = keyFile.readBytes()
                try {
                    credentials.save(String(bytes, Charsets.UTF_8).trim())
                    importedKey = true
                } finally {
                    bytes.fill(0)
                    check(keyFile.delete()) { "Temporary private key file could not be deleted" }
                }
            } else if (keyFile.isFile) {
                // Existing credentials are authoritative; remove the unused one-shot staging file.
                check(keyFile.delete()) { "Unused temporary private key file could not be deleted" }
            }
            if (!gateway.isDirectMode()) DirectMode.configure(context, true)
            assertTrue("Phone-direct connection is not ready", gateway.isConnected())
            assertTrue(FirstUseConsent.accept(context))
            check(prefs.edit().putInt("mode_index", 1).putString("draft_goal", "").putString("companion_draft", "")
                .remove("voice_pending_worker_run").remove("voice_pending_worker_generation").commit())
            val previousIds = runIds(gateway) + baselineRuns
            start = SystemClock.elapsedRealtime()
            main {
                context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            instrumentation.waitForIdleSync()
            val voice = instrumentation.startActivitySync(Intent(context, VoiceActivity::class.java)
                .putExtra(VoiceActivity.EXTRA_OPEN_KEYBOARD, true).putExtra("initial_text", "")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = voice
            instrumentation.waitForIdleSync()
            main {
                val editor = descendants(voice.window.decorView).filterIsInstance<EditText>().first { it.isShown }
                assertTrue("The real task editor must be editable", editor.isEnabled)
                editor.requestFocus(); editor.setText(GOAL)
                val submit = descendants(voice.window.decorView).filterIsInstance<Button>().first { it.isShown && it.text.toString() == "开始任务" }
                assertTrue("The real submit control must be enabled", submit.isEnabled)
                assertTrue("The real submit control did not accept the click", submit.performClick())
            }
            failureCode = "task_not_created"
            while (SystemClock.elapsedRealtime() - start < MAX_MS) {
                sawWorker = sawWorker || DeviceWorkerService.instance != null
                DeviceWorkerService.instance?.let { observedWorker = it }
                if (runId == null) {
                    val runs = gateway.request("GET", "/runs").getJSONArray("items")
                    runId = (0 until runs.length()).map { runs.getJSONObject(it) }
                        .firstOrNull { it.getString("id") !in previousIds && it.optString("goal") == GOAL && it.optLong("created_at") >= startedAt }
                        ?.getString("id")
                }
                val currentId = runId
                if (currentId != null) {
                    val current = gateway.request("GET", "/runs/$currentId")
                    snapshot = current
                    val state = current.getString("status")
                    if (state in terminal) break
                    if (current.optInt("calls") >= MAX_CALLS) {
                        capReached = true; failureCode = "call_limit"
                        cancel(gateway, currentId); snapshot = gateway.request("GET", "/runs/$currentId"); break
                    }
                    if (state in setOf("paused", "awaiting_approval", "awaiting_input")) {
                        failureCode = "unexpected_${state}"; break
                    }
                    failureCode = "time_limit"
                }
                Thread.sleep(50)
            }
            if (runId != null && snapshot?.optString("status") !in terminal && SystemClock.elapsedRealtime() - start >= MAX_MS) {
                capReached = true; cancel(gateway, runId!!); snapshot = gateway.request("GET", "/runs/$runId")
            }
            val id = runId ?: error("Task was not created through VoiceActivity")
            val run = snapshot ?: error("Task snapshot is unavailable")
            eventList = gateway.request("GET", "/runs/$id/events?after=0").getJSONArray("items")
            if (run.optString("status") == "completed") failureCode = "completion_validation"
            assertTrue("The actual device worker never started", sawWorker)
            assertFalse("The live task exceeded its acceptance budget", capReached)
            assertTrue("The live task exceeded 12 provider calls", run.optInt("calls") in 1..MAX_CALLS)
            assertEquals("The actual task must complete", "completed", run.optString("status"))
            val events = (0 until eventList.length()).map { eventList.getJSONObject(it).optString("message") }
            assertTrue("No successful device observation was recorded", events.any { it == "当前界面已更新" })
            assertTrue("The agent did not execute a successful device mutation", run.optInt("successful_mutations") > 0)
            val rawScreen = DoppelAccessibilityService.instance!!.observe()
            screen = JSONObject().put("screen_id", rawScreen.getString("screen_id")).put("package_name", rawScreen.optString("package_name"))
                .put("width", rawScreen.optInt("width")).put("height", rawScreen.optInt("height"))
                .put("node_count", rawScreen.optJSONArray("nodes")?.length() ?: 0)
            assertEquals("Settings must actually be the foreground application", "com.android.settings", rawScreen.optString("package_name"))
            assertTrue("The final summary must state the actual device model", normalized(run.optString("message")).contains(normalized(Build.MODEL)))
            outcome = "passed"; failureCode = ""
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val flowMs = if (start == 0L) 0L else SystemClock.elapsedRealtime() - start
            var cleanupFailure = false
            var voiceDrained = activity == null
            var workerDrained = false
            var accessibilityRestored = !accessibilityChanged
            val workerForCleanup = DeviceWorkerService.instance ?: observedWorker
            try {
                main { activity?.takeUnless { it.isDestroyed || it.isFinishing }?.finish() }
                instrumentation.waitForIdleSync()
                activity?.let { owner ->
                    val io = VoiceActivity::class.java.getDeclaredField("io").apply { isAccessible = true }.get(owner) as ExecutorService
                    voiceDrained = io.awaitTermination(6, TimeUnit.SECONDS)
                    check(voiceDrained) { "Voice task submission did not drain" }
                }
                if (gateway.isDirectMode()) {
                    // Also collect a late-created task owned by this test before restoring its connection.
                    val candidates = gateway.request("GET", "/runs").getJSONArray("items")
                    val owned = (0 until candidates.length()).map { candidates.getJSONObject(it) }.filter {
                        it.optString("goal") == GOAL && it.optLong("created_at") >= startedAt && it.getString("id") !in baselineRuns
                    }
                    if (runId == null) runId = owned.firstOrNull()?.getString("id")
                    owned.forEach { run ->
                        val id = run.getString("id")
                        if (snapshot == null && id == runId) snapshot = run
                        if (run.optString("status") !in terminal) cancel(gateway, id)
                    }
                    val worker = workerForCleanup
                    main { worker?.cancel(); context.stopService(Intent(context, DeviceWorkerService::class.java)) }
                    waitUntil(6000) { DeviceWorkerService.instance == null }
                    workerDrained = drainWorker(worker)
                    check(workerDrained) { "Device worker did not drain" }
                    val deletedIds = mutableSetOf<String>()
                    owned.forEach { run ->
                        val id = run.getString("id")
                        gateway.request("DELETE", "/runs/$id")
                        deletedIds.add(id)
                        ResultStore(context).use { it.erase(id) }
                    }
                    runDeleted = runId != null && runId !in runIds(gateway)
                    // Connection switching resolves active_run, so remove only references to our deleted runs.
                    synchronized(prefs) {
                        val editor = prefs.edit()
                        if (prefs.getString("active_run", "") in deletedIds) editor.remove("active_run")
                        if (prefs.getString("voice_pending_worker_run", "") in deletedIds)
                            editor.remove("voice_pending_worker_run").remove("voice_pending_worker_generation")
                        check(editor.commit()) { "Deleted test task references could not be cleared" }
                    }
                    if (before["direct_mode"] != true) DirectMode.configure(context, false)
                    if (importedKey) {
                        if (gateway.isDirectMode()) DirectMode.configure(context, false)
                        credentials.delete()
                    }
                }
            } catch (failure: Throwable) { cleanupFailure = true; primaryFailure?.addSuppressed(failure) }
            finally {
                // No test-created worker may continue under restored server credentials.
                runCatching {
                    main { DeviceWorkerService.instance?.cancel(); context.stopService(Intent(context, DeviceWorkerService::class.java)) }
                    waitUntil(6000) { DeviceWorkerService.instance == null }
                    workerDrained = drainWorker(workerForCleanup)
                    check(workerDrained) { "Device worker I/O has not stopped" }
                }.onFailure { cleanupFailure = true }
                if (!voiceDrained) runCatching {
                    val owner = activity
                    if (owner != null) {
                        val io = VoiceActivity::class.java.getDeclaredField("io").apply { isAccessible = true }.get(owner) as ExecutorService
                        voiceDrained = io.awaitTermination(3, TimeUnit.SECONDS)
                    }
                }.onFailure { cleanupFailure = true }
                if (DeviceWorkerService.instance == null && voiceDrained && workerDrained) {
                    runCatching { restore(prefs, before) }.onFailure { cleanupFailure = true }
                } else cleanupFailure = true
                runCatching { restore(consentPrefs, consentBefore) }.onFailure { cleanupFailure = true }
                if (!hadCredential) runCatching { restore(credentialPrefs, credentialsBefore) }.onFailure { cleanupFailure = true }
                if (!hadAlias) runCatching { if (store.containsAlias(alias)) store.deleteEntry(alias) }.onFailure { cleanupFailure = true }
                if (keyFile.isFile && !keyFile.delete()) cleanupFailure = true
                if (accessibilityChanged) {
                    runCatching {
                        DoppelAccessibilityService.instance?.let { it.stopActionFeedback(); it.setTouchGuard(false) }
                        putSecure("enabled_accessibility_services", previousServices)
                    }.onFailure { cleanupFailure = true }
                    runCatching { putSecure("accessibility_enabled", previousAccessibility) }.onFailure { cleanupFailure = true }
                    accessibilityRestored = runCatching {
                        readSecure("enabled_accessibility_services") == previousServices && readSecure("accessibility_enabled") == previousAccessibility
                    }.getOrDefault(false)
                    if (!accessibilityRestored) cleanupFailure = true
                }
                val evidence = JSONObject().put("status", if (cleanupFailure) "failed" else outcome).put("failure_code", if (cleanupFailure) "cleanup_failed" else failureCode)
                    .put("task_failure_code", failureCode)
                    .put("primary_failure_type", primaryFailure?.javaClass?.simpleName ?: JSONObject.NULL)
                    .put("goal", GOAL).put("provider", "xiaomi-mimo").put("model", "mimo-v2.5-pro")
                    .put("device_model", Build.MODEL).put("device_manufacturer", Build.MANUFACTURER).put("android_sdk", Build.VERSION.SDK_INT)
                    .put("flow_elapsed_ms", flowMs).put("max_elapsed_ms", MAX_MS).put("max_model_calls", MAX_CALLS)
                    .put("worker_observed", sawWorker).put("direct_runtime", true).put("gateway_fixture", false)
                    .put("credential_reused", hadCredential).put("temporary_key_removed", !keyFile.exists())
                    .put("test_run_deleted", runDeleted).put("worker_stopped", DeviceWorkerService.instance == null)
                    .put("voice_submission_drained", voiceDrained).put("worker_io_drained", workerDrained)
                    .put("accessibility_settings_restored", accessibilityRestored)
                    .put("preferences_restored", prefs.all == before).put("consent_restored", consentPrefs.all == consentBefore)
                    .put("final_screen", screen).put("run", snapshot?.let(::safeSnapshot) ?: JSONObject.NULL)
                    .put("events", safeEvents(eventList))
                runCatching { writeEvidence(evidence) }.onFailure { cleanupFailure = true; primaryFailure?.addSuppressed(it) }
            }
            if (cleanupFailure) {
                val cleanup = AssertionError("Live acceptance cleanup failed; inspect the private evidence file")
                val primary = primaryFailure
                if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private fun cancel(gateway: Gateway, id: String) {
        DirectRuntime.interrupt(context, "Live acceptance budget or cleanup stop")
        main { DeviceWorkerService.instance?.cancel() }
        gateway.request("POST", "/runs/$id/cancel", JSONObject())
    }
    private fun drainWorker(worker: DeviceWorkerService?): Boolean {
        if (worker == null) return true
        return listOf("executor", "submissionExecutor").map { name ->
            val io = DeviceWorkerService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(worker) as ExecutorService
            io.awaitTermination(8, TimeUnit.SECONDS)
        }.all { it }
    }
    private fun readSecure(name: String): String? = Settings.Secure.getString(context.contentResolver, name)
    private fun putSecure(name: String, value: String?) {
        require(name in setOf("enabled_accessibility_services", "accessibility_enabled"))
        val automation = instrumentation.getUiAutomation(1)
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            check(Settings.Secure.putString(context.contentResolver, name, value)) { "Accessibility fixture setting write failed" }
            check(readSecure(name) == value) { "Accessibility fixture setting was not applied" }
        } finally { automation.dropShellPermissionIdentity() }
    }
    private fun normalized(value: String) = value.lowercase().filter { it.isLetterOrDigit() }
    private fun safeSnapshot(run: JSONObject): JSONObject = JSONObject().apply {
        listOf("id", "status", "calls", "created_at", "updated_at", "prompt_tokens", "completion_tokens", "consecutive_stale", "successful_mutations").forEach { if (run.has(it)) put(it, run.get(it)) }
        val rawSummary = run.optString("message")
        put("summary_contains_actual_model", normalized(rawSummary).contains(normalized(Build.MODEL)))
        if (run.optString("status") == "paused") {
            put("host_diagnostic", redactHostDiagnostic(rawSummary))
            put("diagnostic_source", "host_pause_message")
            run.optJSONObject("device_diagnostic")?.let { put("device_diagnostic", safeDeviceDiagnostic(it)) }
        }
        // Only persist the independently verified model fact, not arbitrary generated personal details.
        put("verified_summary", if (normalized(rawSummary).contains(normalized(Build.MODEL))) "手机型号：${Build.MODEL}" else "未核实设备型号")
    }
    private fun safeDeviceDiagnostic(raw: JSONObject): JSONObject = JSONObject().apply {
        val enums = mapOf(
            "kind" to setOf("tap", "long_press", "type", "login_phone", "login_code", "scroll", "observe", "wait", "launch", "back", "home", "recents", "notifications", "quick_settings", "split_screen", "unknown"),
            "status" to setOf("ok", "error", "blocked", "cancelled", "stale", "unknown"),
            "direction" to setOf("up", "down", "left", "right", "unknown"),
            "source" to setOf("execution", "planning_observation")
        )
        enums.forEach { (name, allowed) -> (raw.opt(name) as? String)?.takeIf { it in allowed }?.let { put(name, it) } }
        (raw.opt("target") as? String)?.takeIf { it.length <= 120 && it.matches(Regex("n[0-9]+(?:_[0-9]+)*")) }?.let { put("target", it) }
        listOf("node_present", "enabled", "clickable", "long_clickable", "editable", "scrollable", "password", "requested_action_advertised").forEach { name ->
            (raw.opt(name) as? Boolean)?.let { put(name, it) }
        }
        fun actionId(value: Any?): Int? = when (value) {
            is Int -> value.takeIf { it > 0 }
            is Long -> value.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
            else -> null
        }
        actionId(raw.opt("requested_action_id"))?.let { put("requested_action_id", it) }
        raw.optJSONArray("action_ids")?.let { ids ->
            put("action_ids", JSONArray((0 until ids.length()).asSequence().mapNotNull { actionId(ids.opt(it)) }.distinct().take(32).toList()))
        }
    }
    private fun redactHostDiagnostic(value: String): String = value.take(1200)
        .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[redacted-email]")
        .replace(Regex("(?i)(?:bearer\\s+|sk-)[A-Za-z0-9._-]+"), "[redacted-key]")
        .replace(Regex("[A-Za-z0-9_-]{32,}"), "[redacted-token]")
        .replace(Regex("[0-9]{6,}"), "[redacted-number]")
        .replace(Regex("[\\r\\n\\t]+"), " ").take(600)
    private fun safeEvents(events: JSONArray): JSONArray = JSONArray().apply {
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i); val message = event.optString("message")
            val kind = when {
                message == "当前界面已更新" -> "successful_observation"
                message.startsWith("launch：") -> "launch_result"
                message.startsWith("正在执行：") -> "device_action_queued"
                else -> "progress"
            }
            put(JSONObject().put("kind", kind).put("created_at", event.optLong("created_at")))
        }
    }
    private fun runIds(gateway: Gateway): Set<String> {
        val runs = gateway.request("GET", "/runs").getJSONArray("items")
        return (0 until runs.length()).map { runs.getJSONObject(it).getString("id") }.toSet()
    }
    private fun writeEvidence(value: JSONObject) {
        evidenceFile.writeText(value.toString(2), Charsets.UTF_8)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nDirect live acceptance: ${value.optString("status")}; evidence files/direct-live-acceptance.json\n") })
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun <T> main(block: () -> T): T {
        val value = AtomicReference<T>(); val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync { try { value.set(block()) } catch (error: Throwable) { failure.set(error) } }
        failure.get()?.let { throw it }; return value.get()
    }
    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate() && SystemClock.elapsedRealtime() < until) Thread.sleep(50)
        check(predicate()) { "Live acceptance cleanup did not finish" }
    }
    private fun restore(prefs: SharedPreferences, values: Map<String, *>) {
        val editor = prefs.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        check(editor.commit()) { "Live acceptance preference restore failed" }
    }
}

package dev.doppel.developer

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.doppel.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicitly opted-in real game acceptance. All game decisions and gestures belong to Doppel. */
class ArknightsLiveTest {
    @Test fun executesUserGoalThroughCompanion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Real game test requires arknights_live=true", args.getString("arknights_live") == "true")
        val context = instrumentation.targetContext
        val gateway = Gateway(context)
        val goal = "帮我清理理智，用10倍代理刷一次1-7，再打一下TR-9教学关"
        val evidence = File(context.filesDir, "arknights-live.json")
        val keyFile = File(context.filesDir, "direct-qa-key.txt")
        val startedAt = System.currentTimeMillis()
        val limitMs = args.getString("max_ms", "900000")!!.toLong().coerceIn(30000, 1200000)
        val resumeId = args.getString("resume_run_id")
        var resumeBaseline = 0L
        var id: String? = null
        var activity: Activity? = null
        var outcome = "not_started"
        var run = JSONObject()
        var cause: Throwable? = null
        var stage = "setup"
        var gamePackage: String? = null
        var installedGamePackages = emptyList<String>()
        val requestedGamePackage = args.getString("game_package")
        try {
            assertTrue(DirectMode.isDeveloperBuild(context))
            check(resumeId == null || args.getString("replace_run_id") == null)
            stage = "game_package_selection"
            val supportedGamePackages = listOf("com.hypergryph.arknights", "com.hypergryph.arknights.bilibili")
            installedGamePackages = supportedGamePackages.filter { candidate ->
                runCatching { context.packageManager.getPackageInfo(candidate, 0) }.isSuccess
            }
            gamePackage = if (requestedGamePackage == null) {
                check(installedGamePackages.size == 1) {
                    "Install exactly one Arknights channel or explicitly select an installed channel with game_package"
                }
                installedGamePackages.single()
            } else {
                check(requestedGamePackage in supportedGamePackages) { "game_package must select the official or bilibili Arknights channel" }
                check(requestedGamePackage in installedGamePackages) { "The selected Arknights channel must be installed" }
                requestedGamePackage
            }
            assertNotNull("The selected game must be launchable", context.packageManager.getLaunchIntentForPackage(gamePackage))
            stage = "setup"
            args.getString("replace_run_id")?.let { previous ->
                val old = gateway.request("GET", "/runs/$previous")
                check(old.optString("goal") == goal && old.optString("status") == "paused")
                gateway.request("POST", "/runs/$previous/cancel", JSONObject())
            }
            if (resumeId == null) assertFalse("End the existing local task before a new real game test", DirectRuntime.get(context).hasUnfinishedRun())
            else {
                val existing = gateway.request("GET", "/runs/$resumeId")
                check(existing.optString("goal") == goal && existing.optString("status") == "paused")
                id = resumeId; resumeBaseline = existing.optLong("updated_at")
            }
            assertTrue("Grant overlay permission before real game acceptance", Settings.canDrawOverlays(context))
            // Only enables Doppel's own service and leaves other accessibility services intact.
            instrumentation.getUiAutomation(1)
            val own = ComponentName(context, DoppelAccessibilityService::class.java)
            val services = Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services").orEmpty()
                .split(':').filter { it.isNotBlank() && it != "null" && ComponentName.unflattenFromString(it) != own }
            val automation = instrumentation.getUiAutomation(1)
            fun writeSecureSetting(name: String, value: String) {
                if (Build.VERSION.SDK_INT >= 29) {
                    Settings.Secure.putString(context.contentResolver, name, value)
                } else {
                    check(name in setOf("enabled_accessibility_services", "accessibility_enabled"))
                    // UiAutomation tokenizes the command directly; shell quotes would become literal values.
                    check(value.isEmpty() || value.matches(Regex("""[\p{L}\p{N}_./:$-]+""")))
                    val command = if (value.isEmpty()) "settings delete secure $name" else "settings put secure $name $value"
                    ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { input ->
                        val buffer = ByteArray(1024)
                        while (input.read(buffer) != -1) { /* Drain before validating the write. */ }
                    }
                    check(Settings.Secure.getString(context.contentResolver, name).orEmpty() == value) { "Secure setting was not applied" }
                }
            }
            if (Build.VERSION.SDK_INT >= 29) automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
            try {
                writeSecureSetting("enabled_accessibility_services", services.joinToString(":"))
                val unbind = SystemClock.elapsedRealtime() + 5000
                while (DoppelAccessibilityService.instance != null && SystemClock.elapsedRealtime() < unbind) Thread.sleep(50)
                writeSecureSetting("enabled_accessibility_services", (services + own.flattenToString()).joinToString(":"))
                writeSecureSetting("accessibility_enabled", "1")
            } finally { if (Build.VERSION.SDK_INT >= 29) automation.dropShellPermissionIdentity() }
            val bind = SystemClock.elapsedRealtime() + 8000
            while (DoppelAccessibilityService.instance == null && SystemClock.elapsedRealtime() < bind) Thread.sleep(50)
            assertNotNull("Doppel accessibility must actually bind", DoppelAccessibilityService.instance)
            val credentials = DirectCredentials(context)
            if (!credentials.hasKey()) {
                assertTrue("Stage the private one-shot QA key", keyFile.isFile && keyFile.length() in 16..1024)
                val bytes = keyFile.readBytes()
                try { credentials.save(String(bytes, Charsets.UTF_8).trim()) }
                finally { bytes.fill(0); check(keyFile.delete()) }
            } else if (keyFile.exists()) check(keyFile.delete())
            check(FirstUseConsent.accept(context))
            if (Build.VERSION.SDK_INT in 26..29 && !LegacyScreenCaptureService.isReady) {
                stage = "screen_capture_authorization"
                LegacyCaptureConsent.authorize(instrumentation)
            }
            stage = "setup"
            DirectMode.configure(context, true)
            check(gateway.prefs.edit().putInt("mode_index", 2).putBoolean("action_feedback", true)
                .putBoolean("completion_speech", false).putString("draft_goal", "").putString("companion_draft", "")
                .putString("active_run", resumeId.orEmpty()).remove("voice_pending_worker_run").commit())
            if (resumeId == null) instrumentation.runOnMainSync {
                context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val voice = instrumentation.startActivitySync(Intent(context, if (resumeId == null) VoiceActivity::class.java else TaskPanelActivity::class.java)
                .putExtra(VoiceActivity.EXTRA_OPEN_KEYBOARD, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = voice
            instrumentation.waitForIdleSync()
            stage = "voice_editor_ready"
            var editor: EditText? = null
            var submit: Button? = null
            val readyAt = SystemClock.elapsedRealtime() + 5000
            while (SystemClock.elapsedRealtime() < readyAt) {
                instrumentation.runOnMainSync {
                    // A landscape game can recreate this sheet while it is being opened.
                    val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filter { it.javaClass == voice.javaClass }.singleOrNull()
                    if (resumed != null) activity = resumed
                    val current = resumed ?: activity ?: voice
                    editor = descendants(current.window.decorView).filterIsInstance<EditText>().firstOrNull { it.isShown && it.isEnabled }
                    submit = descendants(current.window.decorView).filterIsInstance<Button>().firstOrNull {
                        it.isShown && it.isEnabled && it.text.toString() == if (resumeId == null) "开始任务" else "继续执行"
                    }
                }
                if ((resumeId != null || editor != null) && submit != null) break
                Thread.sleep(50)
            }
            if (resumeId == null) assertNotNull("The actual companion editor must become visible", editor)
            assertNotNull("The actual start task control must become visible", submit)
            stage = "task_submission"
            instrumentation.runOnMainSync {
                if (resumeId == null) editor!!.setText(goal)
                val start = submit!!
                assertTrue(start.isEnabled && start.performClick())
            }
            stage = "task_execution"
            val deadline = SystemClock.elapsedRealtime() + limitMs
            while (SystemClock.elapsedRealtime() < deadline) {
                if (id == null) {
                    val runs = gateway.request("GET", "/runs").getJSONArray("items")
                    id = (0 until runs.length()).map { runs.getJSONObject(it) }.firstOrNull {
                        it.optString("goal") == goal && it.optLong("created_at") >= startedAt
                    }?.getString("id")
                }
                if (id != null) {
                    run = gateway.request("GET", "/runs/$id")
                    if (resumeId != null && run.optString("status") == "paused" && run.optLong("updated_at") <= resumeBaseline) {
                        Thread.sleep(50); continue
                    }
                    val status = run.optString("status")
                    evidence.writeText(JSONObject().put("goal", goal).put("run", run)
                        .put("game_package", gamePackage).put("installed_game_packages", JSONArray(installedGamePackages))
                        .put("game_package_selection", if (requestedGamePackage == null) "auto_unique" else "explicit")
                        .put("elapsed_ms", System.currentTimeMillis() - startedAt)
                        .put("game_control", "Doppel model and executor only").toString(2))
                    if (status in setOf("completed", "failed", "cancelled", "paused", "awaiting_approval", "awaiting_input")) {
                        outcome = status; break
                    }
                }
                Thread.sleep(250)
            }
            if (outcome == "not_started") outcome = "time_limit"
        } catch (error: Throwable) { cause = error; outcome = "test_error" }
        finally {
            // Preserve task/events/screenshots and configured emulator for further diagnosis.
            val record = JSONObject().put("goal", goal).put("outcome", outcome)
                .put("game_package", gamePackage ?: JSONObject.NULL).put("installed_game_packages", JSONArray(installedGamePackages))
                .put("game_package_selection", if (requestedGamePackage == null) "auto_unique" else "explicit")
                .put("resumed_existing_task", resumeId != null)
                .put("elapsed_ms", System.currentTimeMillis() - startedAt).put("run", run)
                .put("game_control", "Doppel model and executor only")
            id?.let { runId ->
                runCatching {
                    record.put("events", gateway.request("GET", "/runs/$runId/events").getJSONArray("items"))
                    record.put("screenshots", gateway.request("GET", "/runs/$runId/screenshots"))
                    if (run.optString("status") == "running") {
                        gateway.request("POST", "/runs/$runId/pause", JSONObject())
                        record.put("run", gateway.request("GET", "/runs/$runId"))
                    }
                }
            }
            cause?.let { record.put("failure_class", it.javaClass.simpleName).put("failure_stage", stage)
                .put("failure_location", it.stackTrace.firstOrNull { frame -> frame.className.startsWith("dev.doppel") }?.let { frame -> "${frame.fileName}:${frame.lineNumber}" }) }
            evidence.writeText(record.toString(2))
            instrumentation.runOnMainSync { activity?.takeUnless { it.isDestroyed || it.isFinishing }?.finish() }
            if (keyFile.exists()) check(keyFile.delete())
        }
        assertEquals("See arknights-live.json for actual task outcome", "completed", outcome)
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DoppelAccessibilityService
import dev.doppel.sdk.SplitTaskEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Both production engine modes + native PackageManager/input; deterministic model replies, no network calls. */
class NativeAppLaunchDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val fixture = "dev.doppel.testapp"
    private val settings = "com.android.settings"

    private fun await(message: String, ready: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 8000
        do { if (ready()) return; SystemClock.sleep(80) } while (SystemClock.elapsedRealtime() < until)
        assertTrue(message, ready())
    }
    private fun request(kind: String, runId: String) = JSONObject().put("id", UUID.randomUUID().toString())
        .put("run_id", runId).put("kind", kind).put("split_agent", true)
    private fun reply(decision: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
        .put("message", JSONObject().put("content", JSONObject().put("decision", decision).put("state", JSONObject.NULL).toString()))))
    private fun foreground(service: DoppelAccessibilityService) = runCatching { service.observe().optString("package_name") }.getOrDefault("")
    private fun protectedState(): Map<String, Any?> {
        val state = linkedMapOf<String, Any?>()
        for (name in listOf("doppel", "doppel_auto_triggers", "doppel_credential_vault", "doppel_login", "doppel_automatic_unlock_state"))
            state[name] = context.getSharedPreferences(name, 0).all.toMap()
        for (name in listOf("direct-runs-v1.json", "schedules-v1.json", "credential-vault-v1.bin", "model-providers-v1.bin", "automatic-unlock-v1.bin")) {
            val file = File(context.noBackupFilesDir, name)
            state[name] = file.takeIf { it.isFile }?.readBytes()?.let { MessageDigest.getInstance("SHA-256").digest(it).toList() }
        }
        return state
    }
    private fun capture(engine: SplitTaskEngine, service: DoppelAccessibilityService, folder: File, name: String): JSONObject {
        val command = engine.poll().getJSONObject("command")
        assertEquals("screenshot", command.getString("kind"))
        var shot = JSONObject()
        await("The current application must produce a real production screenshot") {
            shot = service.execute(command)
            shot.optString("status") == "ok" && shot.optJSONObject("data")?.optString("image_base64")?.isNotBlank() == true
        }
        File(folder, "$name.png").writeBytes(Base64.decode(shot.getJSONObject("data").getString("image_base64"), Base64.NO_WRAP))
        assertTrue(engine.result(shot).getBoolean("accepted"))
        return shot
    }

    @Test fun bothModesLaunchSettingsDirectlyFromAnotherAppAndRejectUnavailableOrStaleSources() {
        assertTrue("Do not operate during a user task", DeviceWorkerService.instance?.isPaused != false)
        assertFalse(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        val service = requireNotNull(DoppelAccessibilityService.instance) { "Enable the existing accessibility service before testing" }
        val fixtureIntent = requireNotNull(context.packageManager.getLaunchIntentForPackage(fixture))
        assertNotNull(context.packageManager.getLaunchIntentForPackage(settings))
        val before = protectedState()
        val folder = File(context.getExternalFilesDir(null), "full-feature/native-app-launch").apply { mkdirs() }
        val checks = JSONArray()
        var passed = false
        try {
            for (direct in listOf(false, true)) {
                val mode = if (direct) "direct" else "ab"
                context.startActivity(Intent(fixtureIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                await("The source must be the test application, not the launcher") { foreground(service) == fixture }
                SystemClock.sleep(600)
                val engine = SplitTaskEngine(null, {}, enhancementEnabled = { !direct })
                val runId = engine.create(JSONObject().put("goal", "直接打开系统设置").put("device_id", "direct-this-phone").put("mode", "full")).getString("id")
                capture(engine, service, folder, "$mode-before")
                engine.accept(engine.takeWork()!!, reply(JSONObject().put("kind", "list_apps").put("query", "com.android.settings")))
                val listingCommand = engine.poll().getJSONObject("command")
                assertEquals("list_apps", listingCommand.getString("kind"))
                val listing = service.execute(listingCommand)
                assertEquals("ok", listing.getString("status"))
                val rows = listing.getJSONObject("data").getJSONArray("apps")
                val app = (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.optString("package_name") == settings }
                assertTrue(app.getString("label").isNotBlank())
                assertEquals(fixture, foreground(service))
                engine.result(listing)
                val planner = engine.takeWork()!!
                assertFalse(planner.grounding)
                assertTrue(planner.payload.toString().contains("app_list_result"))
                val decision = JSONObject().put("kind", "launch").put("package_name", app.getString("package_name"))
                    .put("target", "打开系统设置").put("expected", "系统设置页面出现").put("screen_context", "当前在测试应用")
                engine.accept(planner, reply(decision))
                var launch = engine.poll().getJSONObject("command")
                assertEquals("launch", launch.getString("kind"))
                assertEquals(fixture, foreground(service))
                var launched = service.execute(launch)
                val attempts = JSONArray()
                val modeCheck = JSONObject().put("mode", mode).put("launch_attempts", attempts)
                checks.put(modeCheck)
                fun recordAttempt() {
                    attempts.put(JSONObject().put("command_id", launch.getString("id"))
                        .put("source_capture_id", launch.getJSONObject("source").getString("capture_id"))
                        .put("status", launched.optString("status")).put("data", launched.optJSONObject("data")))
                }
                recordAttempt()
                // A late window/rotation event can invalidate a valid screenshot. Feed that receipt
                // through the real engine and re-plan only from a new frame; never replay the command.
                var recoveries = 0
                while (launched.optString("status") == "stale" && recoveries < 2) {
                    assertEquals("source_navigation_changed", launched.getJSONObject("data").getString("reason_code"))
                    assertEquals("not_dispatched", launched.getJSONObject("data").getString("action_state"))
                    assertEquals(fixture, foreground(service))
                    assertTrue(engine.result(launched).getBoolean("accepted"))
                    val old = launch
                    recoveries++
                    capture(engine, service, folder, "$mode-refresh-$recoveries")
                    val recoveryPlanner = requireNotNull(engine.takeWork())
                    assertFalse(recoveryPlanner.grounding)
                    assertFalse(recoveryPlanner.payload.toString().contains("app_list_result"))
                    engine.accept(recoveryPlanner, reply(decision))
                    launch = engine.poll().getJSONObject("command")
                    assertEquals("launch", launch.getString("kind"))
                    assertNotEquals(old.getString("id"), launch.getString("id"))
                    assertNotEquals(old.getJSONObject("source").getString("capture_id"), launch.getJSONObject("source").getString("capture_id"))
                    launched = service.execute(launch)
                    recordAttempt()
                }
                assertEquals(launched.toString(), "ok", launched.getString("status"))
                assertEquals("accepted", launched.getJSONObject("data").getString("action_state"))
                assertTrue(launched.getJSONObject("data").getLong("post_action_delay_ms") >= 500)
                engine.result(launched)
                await("Native launch must actually bring system settings forward") { foreground(service) == settings }
                val settingsShot = capture(engine, service, folder, "$mode-settings")
                assertTrue(engine.internalRun(runId).getJSONObject("last_receipt").getBoolean("launch_verified"))
                val next = engine.takeWork()!!
                assertFalse(next.grounding)
                assertFalse(next.payload.toString().contains("app_list_result"))

                val stale = service.execute(JSONObject(launch.toString()).put("id", UUID.randomUUID().toString()))
                assertEquals("stale", stale.getString("status"))
                val unknown = service.execute(request("launch", runId).put("package_name", "dev.doppel.nonexistent.${UUID.randomUUID().toString().replace("-", "")}")
                    .put("source", settingsShot.getJSONObject("data").getJSONObject("visual_frame")))
                assertEquals("error", unknown.getString("status"))
                assertEquals("app_unavailable", unknown.getJSONObject("data").getString("reason_code"))
                assertEquals(settings, foreground(service))
                modeCheck.put("source_package", fixture).put("launched_package", settings)
                    .put("native_list", true).put("launch_verified", true).put("stale_source_rejected", true).put("unknown_package_rejected", true)
                    .put("grounding_requests", 0).put("list_repeated_in_next_prompt", false).put("fresh_frame_recoveries", recoveries)
                engine.control(runId, "cancel", JSONObject())
            }
            passed = true
        } finally {
            val preserved = before == protectedState()
            File(folder, "report.json").writeText(JSONObject().put("passed", passed && preserved).put("checks", checks)
                .put("model_network_calls", 0).put("original_state_preserved", preserved)
                .put("scope", "Production A-only and A/B engine with deterministic replies, native app query/launch and screenshots; no real model evaluation or user task creation").toString(2))
            assertTrue("Existing credentials, model settings, schedules, rules and task history must stay unchanged", preserved)
        }
    }
}

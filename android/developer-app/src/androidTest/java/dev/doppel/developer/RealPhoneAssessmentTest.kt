@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")
package dev.doppel.developer

import android.app.Activity
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in observation of the user's physical phone, configured models and ordinary UI handlers.
 * No fixture provider, credential changes, grant changes, or automatic queue resume.
 * An observed outcome is not an assertion that a real-world goal succeeded.
 */
class RealPhoneAssessmentTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val gateway by lazy { Gateway(context) }
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun descriptions(activity: Activity, value: String): View =
        all(activity.window.decorView).first { it.isShown && it.contentDescription?.toString() == value }
    private fun visible(activity: Activity): JSONArray {
        var result = JSONArray()
        inst.runOnMainSync {
            result = JSONArray(all(activity.window.decorView).filterIsInstance<TextView>()
                .filter { it.isShown && it !is EditText }.map { it.text.toString() }.filter(String::isNotBlank))
        }
        return result
    }
    private fun runs() = gateway.request("GET", "/runs").getJSONArray("items")
    private fun ids() = runs().let { rows -> (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }.toSet() }
    private fun configuration(): JSONObject {
        val providers = ModelProviders(context)
        val routing = providers.routing()
        return JSONObject().put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
            .put("model", Build.MODEL).put("version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            .put("onboarding_complete", FirstUseConsent.isAccepted(context) && !FirstUseConsent.needsGuide(context))
            .put("direct_mode", gateway.isDirectMode()).put("models_ready", providers.isReady())
            .put("primary", routing.primary.model).put("grounding", routing.enhancement.model)
            .put("enhancement_enabled", routing.enhancementEnabled)
            .put("queue_paused", gateway.prefs.getBoolean("queue_dispatch_paused", false))
            .put("accessibility_bound", DoppelAccessibilityService.instance != null)
            .put("unlock_configured", AutomaticUnlockCredentials.hasSaved(context))
            .put("unlock_enabled", AutomaticUnlockCredentials.isEnabled(context))
            .put("vault_pin_configured", CredentialVault(context).hasPin())
            .put("payment_enabled", PaymentConsent(context).currentId() != null)
    }

    @Test fun observeConfiguredPhone() {
        assertEquals("true", args.getString("real_phone_assessment"))
        assertEquals("dev.doppel.developer", context.packageName)
        assertEquals("23116PN5BC", Build.MODEL)
        val case = args.getString("case_id") ?: error("Explicit case_id required")
        require(case.matches(Regex("[a-zA-Z0-9_-]{1,70}")))
        val action = args.getString("assessment_action") ?: "inspect"
        require(action in setOf("inspect", "message"))
        val ui = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val folder = File(context.getExternalFilesDir(null), "real-phone-assessment/$case").apply { mkdirs() }
        val report = JSONObject().put("case_id", case).put("started_at", System.currentTimeMillis())
            .put("configuration", configuration()).put("input_method", "normal_UI_handler")
        val originalIds = ids()
        var own: String? = null
        var activity: Activity? = null
        try {
            activity = inst.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            inst.waitForIdleSync()
            report.put("initial_ui", visible(activity))
            if (action == "message") {
                val existing = runs()
                assertTrue("Do not interfere with earlier unfinished tasks", (0 until existing.length()).all {
                    TaskPresentation.terminal(existing.getJSONObject(it).optString("status")) })
                assertTrue("Preserve the user's draft", gateway.prefs.getString("draft_goal", "").isNullOrBlank())
                val text = String(android.util.Base64.decode(requireNotNull(args.getString("message_base64")), android.util.Base64.DEFAULT), Charsets.UTF_8)
                require(text.length in 1..2000)
                if (args.getString("new_conversation") != "false") {
                    inst.runOnMainSync { descriptions(activity, "新任务").performClick() }
                    inst.waitForIdleSync()
                }
                val previousMessages = gateway.conversationMessages().toString()
                inst.runOnMainSync {
                    (descriptions(activity, "任务输入") as EditText).setText(text)
                    descriptions(activity, "开始任务").performClick()
                }
                val started = SystemClock.elapsedRealtime()
                val deadline = started + (args.getString("deadline_s")?.toLong() ?: 180).coerceIn(15, 300) * 1000
                val maxCalls = (args.getString("max_calls")?.toInt() ?: 24).coerceIn(1, 40)
                while (SystemClock.elapsedRealtime() < deadline) {
                    val added = ids() - originalIds
                    check(added.size <= 1) { "Submission created multiple runs" }
                    own = added.firstOrNull()
                    if (own != null) {
                        val run = gateway.request("GET", "/runs/$own")
                        report.put("run", run)
                        if (!report.has("created_after_ms")) report.put("created_after_ms", SystemClock.elapsedRealtime() - started)
                        val state = run.optString("status")
                        if (TaskPresentation.terminal(state) || state in setOf("paused", "awaiting_input", "awaiting_approval") ||
                            state == "queued" && SystemClock.elapsedRealtime() - started > 12000 || run.optInt("calls") >= maxCalls) break
                    } else if (gateway.conversationMessages().toString() != previousMessages) {
                        report.put("chat_messages", gateway.conversationMessages()).put("reply_after_ms", SystemClock.elapsedRealtime() - started)
                        break
                    }
                    Thread.sleep(350)
                }
                report.put("observed_after_ms", SystemClock.elapsedRealtime() - started)
                own?.let { report.put("events", gateway.request("GET", "/runs/$it/events").getJSONArray("items")) }
            }
            report.put("final_app_ui", visible(activity)).put("observation_complete", true)
        } catch (failure: Throwable) {
            report.put("observation_error", failure.javaClass.simpleName)
            throw failure
        } finally {
            ui.takeScreenshot()?.let { bitmap ->
                try { File(folder, "final-screen.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
            own?.let { id ->
                if (!TaskPresentation.terminal(gateway.request("GET", "/runs/$id").optString("status"))) {
                    val done = CountDownLatch(1)
                    TaskControl.request(context, id, "cancel") { value, error ->
                        report.put("cleanup_status", value?.optString("status") ?: "failed").put("cleanup_error", error ?: JSONObject.NULL)
                        done.countDown()
                    }
                    check(done.await(15, TimeUnit.SECONDS)) { "Own task cancellation timed out" }
                }
            }
            report.put("finished_at", System.currentTimeMillis())
            File(folder, "report.json").writeText(report.toString(2))
            activity?.let { inst.runOnMainSync { it.finish() } }
        }
    }
}

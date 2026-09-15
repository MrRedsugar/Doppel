package dev.doppel.developer

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.Gateway
import dev.doppel.sdk.ModelApi
import dev.doppel.sdk.ModelProviders
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** One real first decision against Android Settings; isolated engine, no returned action executed. */
class TaskProgressModelDeviceTest {
    @get:org.junit.Rule val terminalTaskPointer: org.junit.rules.TestRule = TerminalTaskPointerTestRule()
    @Test fun primaryReturnsCoarsePlanAlongsideItsNormalFirstDecision() {
        val inst = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("progress_live") == "true")
        val context = inst.targetContext
        assertNull("Requires an idle worker", DeviceWorkerService.instance)
        assertTrue("Requires no active task", Gateway(context).prefs.getString("active_run", "").isNullOrBlank())
        val folder = File(context.getExternalFilesDir(null), "task-progress-verification").apply { mkdirs() }
        val report = JSONObject().put("ok", false).put("model_requests", 0).put("device_actions", 0)
            .put("scope", "one real primary decision on real Android Settings screenshot; isolated engine; no returned action executed")
        val started = SystemClock.elapsedRealtime()
        var stage = "open_settings"
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val automation = inst.getUiAutomation(1)
            val deadline = SystemClock.elapsedRealtime() + 5000
            var settingsVisible = false
            while (SystemClock.elapsedRealtime() < deadline) {
                val root = automation.rootInActiveWindow
                settingsVisible = root?.packageName?.toString() == "com.android.settings"
                @Suppress("DEPRECATION") root?.recycle()
                if (settingsVisible) break
                Thread.sleep(100)
            }
            assertTrue("Real settings must be visible", settingsVisible)
            Thread.sleep(500)
            val bitmap = requireNotNull(automation.takeScreenshot())
            val bytes = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
            val width = bitmap.width; val height = bitmap.height; bitmap.recycle()
            File(folder, "model-input-settings.png").writeBytes(bytes.toByteArray())
            stage = "isolated_engine"
            val type = Class.forName("dev.doppel.sdk.SplitTaskEngine")
            val save: (String) -> Unit = {}
            val now: () -> Long = { System.currentTimeMillis() }
            val catalog: () -> JSONObject = { JSONObject().put("items", JSONArray()) }
            val reference: (String, String, String) -> JSONObject = { _, _, _ -> JSONObject() }
            val enhancement: () -> Boolean = { false }
            val engine = type.constructors.single { it.parameterCount == 7 }
                .newInstance(null, save, now, catalog, reference, enhancement, catalog)
            fun invoke(name: String, vararg args: Any?): Any? = type.methods.single { it.name == name && it.parameterCount == args.size }.invoke(engine, *args)
            val run = invoke("create", JSONObject().put("goal", "打开设置，找到本机型号信息并告诉我手机型号")
                .put("device_id", "direct-this-phone").put("mode", "full")) as JSONObject
            val id = run.getString("id")
            val capture = (invoke("poll") as JSONObject).getJSONObject("command")
            invoke("result", JSONObject().put("run_id", id).put("command_id", capture.getString("id")).put("status", "ok")
                .put("observation", JSONObject().put("package_name", "com.android.settings"))
                .put("data", JSONObject().put("image_base64", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
                    .put("mime_type", "image/png").put("visual_frame", JSONObject().put("capture_id", "progress-live-settings")
                        .put("display_width", width).put("display_height", height).put("rotation", 0))))
            val work = requireNotNull(invoke("takeWork"))
            val payload = work.javaClass.getMethod("getPayload").invoke(work) as JSONObject
            stage = "primary_configuration"
            val providers = ModelProviders(context)
            val selected = providers.resolve("primary")
            report.put("model", selected.selection.model).put("vision", selected.vision.name)
                .put("credentials_present", providers.hasCredentials(selected.provider.id))
            stage = "model_call"
            val response = ModelApi(context).complete(payload) { report.put("model_requests", 1) }
            response.optJSONObject("usage")?.let { usage ->
                report.put("usage", JSONObject().apply {
                    for (key in listOf("prompt_tokens", "completion_tokens", "total_tokens")) (usage.opt(key) as? Number)?.let { put(key, it) }
                })
            }
            response.optJSONObject("_doppel_request")?.let { report.put("wire", it) }
            stage = "accept_response"
            invoke("accept", work, response, null)
            val accepted = invoke("get", id) as JSONObject
            val progress = accepted.getJSONObject("task_state").getJSONObject("progress")
            report.put("progress", progress).put("calls", accepted.getInt("calls"))
                .put("next_command_kind", (invoke("poll") as JSONObject).optJSONObject("command")?.optString("kind"))
            assertEquals("One normal primary request also carries the plan", 1, accepted.getInt("calls"))
            assertTrue("Primary must supply a coarse initial plan", progress.getJSONArray("plan").length() in 1..5)
            // The plan's actual wording is independently reviewed in the report; no keyword gate in production.
            report.put("ok", true)
        } catch (failure: Throwable) {
            report.put("failure_type", failure.javaClass.simpleName).put("failure_stage", stage)
            throw AssertionError("Progress primary verification failed; see sanitized report (no provider error or credentials exported)")
        } finally {
            report.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            File(folder, "model-plan-result.json").writeText(report.toString(2))
        }
    }
}

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")

package dev.doppel.developer

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Two real model calls on the existing Meituan prompt; no host navigation or fallback clicks. */
class MeituanPermissionLiveTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val meituan = "com.sankuai.meituan"
    private val locationPermissions = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    private fun <T> main(block: () -> T): T {
        var result: T? = null
        inst.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun permissionDenied() = locationPermissions.all {
        context.packageManager.checkPermission(it, meituan) == PackageManager.PERMISSION_DENIED
    }
    private fun sha(file: File): String? = file.takeIf(File::isFile)?.readBytes()?.let { bytes ->
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
    private fun durableState(): Map<String, Any?> = mapOf(
        "tasks" to sha(File(context.noBackupFilesDir, "direct-runs-v1.json")),
        "providers" to sha(File(context.noBackupFilesDir, "model-providers-v1.bin")),
        "active_run" to Gateway(context).prefs.getString("active_run", null),
        "rules" to context.getSharedPreferences("doppel_auto_triggers", 0).getString("rules", null),
    )

    @Test fun realPrimaryAndGrounderDenyTheExistingNativePrompt() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("meituan_permission_live") == "true")
        assertTrue(Build.VERSION.SDK_INT >= 34)
        assertNull("Do not interrupt a real worker", DeviceWorkerService.instance)
        assertFalse("Do not interrupt an existing task", DirectRuntime.get(context).hasUnfinishedRun())
        assertTrue("Keep this isolated from enabled control triggers", AutoTriggerStore(context).list().none { it.enabled })
        assertFalse(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        assertTrue(Settings.canDrawOverlays(context))
        assertTrue("The existing prompt must not already have location permission", permissionDenied())
        if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        val service = requireNotNull(DoppelAccessibilityService.instance)
        assertFalse(service.guardVisible)
        val foreground = service.windows.first {
            it.isFocused && it.type in setOf(AccessibilityWindowInfo.TYPE_APPLICATION, AccessibilityWindowInfo.TYPE_SYSTEM)
        }
        val promptWindow = foreground.id
        val durableBefore = durableState()
        val prefs = Gateway(context).prefs
        val overlayBefore = listOf("companion_y", "companion_right_edge").associateWith { prefs.all[it] }
        val sample = InstrumentationRegistry.getArguments().getString("sample") ?: "live-${System.currentTimeMillis()}"
        require(sample.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val folder = File(context.getExternalFilesDir(null), "meituan-permission-live/$sample")
        check(!folder.exists() && folder.mkdirs())
        val report = JSONObject().put("passed", false).put("scope", "isolated_model_pipeline_not_full_task_engine")
            .put("primary_system", "production SplitTaskEngine.PLANNER").put("model_calls", 0).put("network_connections", 0)
            .put("model_retries", 0).put("host_fallback_actions", 0).put("persisted_tasks_created", 0)
            .put("permission_window_id", promptWindow).put("location_permission_before", "denied")
        val started = SystemClock.elapsedRealtime()
        val runId = "meituan-permission-${UUID.randomUUID()}"
        var overlay: CompanionOverlay? = null
        var stage = "show_overlay"
        fun save() = File(folder, "report.json").writeText(report.toString(2))
        fun command(kind: String) = JSONObject().put("id", UUID.randomUUID().toString()).put("run_id", runId)
            .put("kind", kind).put("mode", "full").put("split_agent", true)
        fun screenshot(name: String, maxAttempts: Int = 2): JSONObject {
            // Match the engine's local retry for a changing window; never replay a model or gesture.
            val attempts = JSONArray()
            var result = service.execute(command("screenshot"))
            fun recordAttempt() {
                attempts.put(JSONObject().put("status", result.optString("status"))
                    .put("data", JSONObject(result.optJSONObject("data")?.toString() ?: "{}").apply { remove("image_base64") }))
            }
            recordAttempt()
            while (result.optString("status") == "stale" && attempts.length() < maxAttempts) {
                SystemClock.sleep(500)
                result = service.execute(command("screenshot"))
                recordAttempt()
            }
            report.put("${name}_capture_attempts", attempts)
            report.put("${name}_capture_status", result.optString("status"))
            result.optJSONObject("data")?.let { data ->
                report.put("${name}_capture", JSONObject().apply {
                    for (key in listOf("capture_backend", "capture_elapsed_ms", "overlay_cleanup_performed", "visual_frame"))
                        if (data.has(key)) put(key, data.get(key))
                })
                data.optString("image_base64").takeIf(String::isNotBlank)?.let { encoded ->
                    File(folder, "$name.png").writeBytes(Base64.decode(encoded, Base64.DEFAULT))
                }
            }
            assertEquals("Production screenshot must succeed; no alternate image source", "ok", result.optString("status"))
            assertEquals("accessibility_skip_screenshot", result.getJSONObject("data").getString("capture_backend"))
            assertFalse(result.getJSONObject("data").getBoolean("overlay_cleanup_performed"))
            assertFalse(result.getJSONObject("data").has("overlay_reconstruction"))
            assertFalse(result.getJSONObject("data").has("native_window_capture"))
            return result
        }
        fun complete(role: String, payload: JSONObject): JSONObject {
            check(report.getInt("model_calls") < 2)
            report.put("model_calls", report.getInt("model_calls") + 1)
            save()
            val callStarted = SystemClock.elapsedRealtime()
            val diagnostic = JSONObject().put("role", role)
            report.put(role, diagnostic)
            try {
                val response = ModelApi(context).complete(payload) {
                    report.put("network_connections", report.getInt("network_connections") + 1)
                }
                response.optJSONObject("usage")?.let { usage -> diagnostic.put("usage", JSONObject().apply {
                    for (key in listOf("prompt_tokens", "completion_tokens", "total_tokens"))
                        (usage.opt(key) as? Number)?.let { put(key, it) }
                }) }
                response.optJSONObject("_doppel_request")?.let { wire -> diagnostic.put("wire", JSONObject().apply {
                    for (key in listOf("model", "enable_thinking", "thinking", "reasoning_effort", "response_format", "schema_name", "schema_strict", "tool_choice"))
                        if (wire.has(key)) put(key, wire.get(key))
                }) }
                val choice = response.optJSONArray("choices")?.optJSONObject(0)
                diagnostic.put("finish_reason", choice?.optString("finish_reason"))
                    .put("content_length", choice?.optJSONObject("message")?.optString("content")?.length)
                return SplitAgentProtocol.content(response, payload.getJSONObject("response_format"), role,
                    onParsed = { parsed ->
                        // Save only the structured decision/result, never reasoning_content or provider raw messages.
                        parsed.optJSONObject(if (role == "primary") "decision" else "result")?.let {
                            val body = it
                            diagnostic.put("parsed", JSONObject().apply {
                                val fields = if (role == "primary") listOf("kind", "target", "expected", "screen_context")
                                    else listOf("status", "action", "points", "duration_ms", "reason")
                                for (key in fields) if (body.has(key)) put(key, body.get(key))
                                body.optJSONObject("assessment")?.optString("alignment")?.let { alignment ->
                                    put("assessment", JSONObject().put("alignment", alignment))
                                }
                            })
                        }
                    })
            } finally { diagnostic.put("elapsed_ms", SystemClock.elapsedRealtime() - callStarted); save() }
        }
        try {
            val providers = ModelProviders(context)
            report.put("models", JSONObject().apply {
                for (role in listOf("primary", "grounding")) {
                    val selected = providers.resolve(role)
                    assertEquals("Configured model must already support vision", ModelVision.VERIFIED, selected.vision)
                    put(role, selected.selection.model)
                }
            })
            overlay = main { CompanionOverlay(context) {}.also { it.show() } }
            val temporaryOverlay = requireNotNull(overlay)
            temporaryOverlay.display(JSONObject().put("id", runId).put("status", "running").put("message", "验证权限弹窗"), "正在思考")
            SystemClock.sleep(250)
            stage = "capture_before"
            val before = screenshot("before")
            val observation = before.getJSONObject("observation")
            val packageName = observation.getString("package_name")
            assertTrue("Preserve the existing native permission prompt; do not navigate to create one",
                packageName.contains("permissioncontroller") || packageName == "window:$promptWindow")
            report.put("tree_available_before", observation.optBoolean("tree_available"))
            val encoded = before.getJSONObject("data").getString("image_base64")
            val frame = before.getJSONObject("data").getJSONObject("visual_frame")
            if (InstrumentationRegistry.getArguments().getString("meituan_navigation_probe") == "true") {
                val samples = JSONArray()
                report.put("scope", "navigation_probe_zero_models").put("navigation_samples", samples)
                val store = service.javaClass.getDeclaredField("visualCaptures").apply { isAccessible = true }.get(service) as VisualCaptureStore
                repeat(16) { index ->
                    if (index == 8) temporaryOverlay.display(JSONObject().put("id", runId).put("status", "running").put("message", "处理权限弹窗"), "正在执行")
                    val current = service.javaClass.getDeclaredField("navigationGeneration").apply { isAccessible = true }.getInt(service)
                    @Suppress("UNCHECKED_CAST")
                    val retained = synchronized(store) {
                        (store.javaClass.getDeclaredField("captures").apply { isAccessible = true }.get(store) as Map<String, VisualCapture>)[frame.getString("capture_id")]
                    }
                    samples.put(JSONObject().put("sample", index).put("current_generation", current)
                        .put("source_generation", retained?.navigation?.navigationGeneration)
                        .put("source_window", retained?.navigation?.windowId)
                        .put("source_present", retained != null))
                    SystemClock.sleep(400)
                }
                report.put("passed", true)
                return
            }
            val plannerSystem = SplitTaskEngine::class.java.getDeclaredField("PLANNER").apply { isAccessible = true }.get(null) as String
            val primaryRequest = SplitAgentProtocol.request("primary", JSONArray()
                .put(JSONObject().put("role", "system").put("content", plannerSystem))
                .put(JSONObject().put("role", "user").put("content", JSONArray()
                    .put(JSONObject().put("type", "text").put("text", "任务：拒绝当前美团权限请求，不授予权限，完成后停止。下面是当前最新截图，请决定下一步。"))
                    .put(SplitAgentProtocol.image(encoded)))))
            stage = "primary"
            val primary = complete("primary", primaryRequest)
            // Wire decision.kind=tap is normalized to the engine's kind=execute/action=tap shape.
            assertEquals("A must request a screen action", "execute", primary.optString("kind"))
            assertEquals("Only A's one tap may execute in this isolated test", "tap", primary.optString("action"))
            val intent = JSONObject().put("action", primary.getString("action"))
                .put("target", SplitAgentProtocol.text(primary, "target"))
                .put("expected", SplitAgentProtocol.text(primary, "expected", 1500))
            primary.optString("screen_context").takeIf(String::isNotBlank)?.let { intent.put("screen_context", it) }
            report.put("intent_sent_to_b", intent)
            stage = "grounding"
            val grounding = complete("grounding", SplitAgentProtocol.grounder(encoded, intent))
            val action = SplitAgentProtocol.reviewedGrounding(grounding, "tap", intent,
                frame.getInt("display_width"), frame.getInt("display_height"))
            report.put("reviewed_grounding", action)
            assertEquals("B must locate and accept A's intent", "located", action.optString("status"))
            action.put("target", intent.getString("target"))
            stage = "production_action"
            assertNull("No real worker may have started during model calls", DeviceWorkerService.instance)
            assertFalse(DirectRuntime.get(context).hasUnfinishedRun())
            temporaryOverlay.display(JSONObject().put("id", runId).put("status", "running").put("message", "处理权限弹窗"), "正在执行")
            val receipt = service.execute(command("split_action").put("source", frame).put("action", action))
            report.put("action_receipt", JSONObject(receipt.toString()).apply { remove("observation") })
            assertEquals("Production coordinate action must succeed", "ok", receipt.optString("status"))
            assertEquals("accepted", receipt.getJSONObject("data").getString("action_state"))
            stage = "verify_real_result"
            // Honor the production executor's normal delay before observing the action result.
            SystemClock.sleep(receipt.getJSONObject("data").getLong("post_action_delay_ms"))
            val deadline = SystemClock.elapsedRealtime() + 7000
            while (SystemClock.elapsedRealtime() < deadline && service.windows.any { it.id == promptWindow }) SystemClock.sleep(100)
            assertFalse("The native permission window must actually disappear", service.windows.any { it.id == promptWindow })
            assertTrue("Neither coarse nor fine location may be granted", permissionDenied())
            report.put("location_permission_after", "denied").put("permission_window_dismissed", true)
            // Meituan may itself open app settings after denial. That is not an unsuccessful deny.
            // Only local observation may retry while this transition finishes; never replay the action/models.
            screenshot("after", maxAttempts = 6)
            assertTrue("Location must remain denied after the destination page settles", permissionDenied())
            assertEquals(2, report.getInt("model_calls"))
            report.put("foreground_after", service.foregroundPackage()).put("passed", true)
        } catch (failure: Throwable) {
            report.put("failure_stage", stage).put("failure_type", failure.javaClass.simpleName)
            failure.stackTrace.firstOrNull { it.className.startsWith("dev.doppel.sdk.") }?.let {
                report.put("failure_origin", "${it.className}.${it.methodName}:${it.lineNumber}")
            }
            // Whitelist static parser diagnostics; never persist a provider response or arbitrary error text.
            failure.message?.takeIf { it == "模型输出未完整结束" || it == "模型响应长度无效" ||
                it.matches(Regex("模型输出不是严格 JSON（位置 [0-9]+）")) }?.let { report.put("parse_error", it) }
            if (failure is SplitSchemaViolation) report.put("schema_error", failure.diagnostic())
            if (failure is ModelHttpFailure) report.put("http_status", failure.status)
            throw AssertionError("Meituan live A/B verification failed at $stage; see sanitized report")
        } finally {
            overlay?.let { main { it.close() } }
            service.stopActionFeedback()
            prefs.edit().apply {
                overlayBefore.forEach { (key, value) -> when (value) {
                    is Int -> putInt(key, value)
                    is Boolean -> putBoolean(key, value)
                    else -> remove(key)
                } }
            }.commit()
            val unchanged = durableBefore == durableState()
            report.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                .put("durable_tasks_rules_routing_unchanged", unchanged)
                .put("overlay_preferences_restored", overlayBefore.all { prefs.all[it.key] == it.value })
            if (!unchanged) report.put("passed", false)
            save()
            assertTrue("Live capture/model test must not modify tasks, rules, or model routing", unchanged)
        }
    }
}

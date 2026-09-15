package dev.doppel.sdk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

data class ModelProbeResult(val vision: ModelVision, val message: String, val elapsedMs: Long)

class ModelApi(context: Context) {
    private val app = context.applicationContext
    private val providers = ModelProviders(app)
    fun complete(payload: JSONObject, onConnection: (HttpURLConnection) -> Unit = {}): JSONObject {
        FirstUseConsent.requireAccepted(app)
        val role = payload.optString("_doppel_role", "primary")
        val target = providers.requestTarget(role)
        check(target.vision == ModelVision.VERIFIED) { "请在模型设置中验证所选模型支持图片" }
        return completeSelection(target, role, payload, onConnection)
    }
    private fun completeSelection(target: ModelRequestTarget, role: String, payload: JSONObject,
                                  onConnection: (HttpURLConnection) -> Unit = {}): JSONObject {
        val body = ModelWirePolicy.prepare(payload, target.provider, target.selection.model, role)
        val usageLedger = ModelUsageLedger(app.noBackupFilesDir)
        // Initialize before the request so importing retained runs cannot count this response twice.
        val ledgerReady = runCatching { usageLedger.initialize() }.isSuccess
        val response = ModelTransport().request(target.provider, target.secret, "/chat/completions", body,
            if (role == "primary") 90000 else 60000, onConnection) { FirstUseConsent.requireAccepted(app) }
        if (!ledgerReady || runCatching { usageLedger.record(response.optJSONObject("usage")) }.isFailure) {
            // A bookkeeping failure must not turn a paid, usable response into a repeated model call.
            response.put("_doppel_usage_unrecorded", true)
            android.util.Log.w("DoppelUsage", "Model response received; local usage could not be saved")
        }
        val transport = response.remove("_doppel_transport") as? JSONObject
        return response.put("_doppel_request", ModelRequestDiagnostic.wireMetadata(body).apply {
                transport?.let { put("transport", it) }
                (response.optJSONObject("usage")?.optJSONObject("completion_tokens_details")?.opt("reasoning_tokens") as? Number)
                    ?.takeIf { it.toLong() >= 0 }?.let { put("reasoning_tokens", it.toLong()) }
                val sizes = ModelRequestDiagnostic.payloadSizeMetadata(body)
                sizes.keys().forEach { key -> put(key, sizes.get(key)) }
            })
    }
    fun discoverModels(providerId: String, onConnection: (HttpURLConnection) -> Unit = {}): List<String> {
        FirstUseConsent.requireAccepted(app)
        val target = providers.requestTarget(providerId, "")
        val response = ModelTransport().request(target.provider, target.secret, "/models", timeoutMs = 30000,
            onConnection = onConnection) { FirstUseConsent.requireAccepted(app) }
        val data = response.optJSONArray("data") ?: return emptyList()
        return (0 until minOf(data.length(), 3000)).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { id -> id.length in 1..200 && id.all { char -> char.code in 33..126 } } }.distinct().sorted()
    }
    fun probeVision(providerId: String, model: String, role: String = "primary", onConnection: (HttpURLConnection) -> Unit = {}): ModelProbeResult {
        FirstUseConsent.requireAccepted(app)
        require(model.length in 1..200) { "请先填写模型名称" }
        val target = providers.requestTarget(providerId, model)
        val challenge = ModelSyntheticImage.create()
        val message = JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", "Read the six large colored vertical bands in this image from left to right. Reply with exactly six English color names separated by spaces. Use only RED, BLUE, GREEN, YELLOW, PURPLE, ORANGE. Do not explain."))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", challenge.dataUrl))))
        val payload = JSONObject().put("messages", JSONArray().put(message)).put("max_tokens", if (role == "primary") 2048 else 512)
        val started = System.nanoTime()
        var detail = ""
        val result = try {
            val response = completeSelection(target, role, payload, onConnection)
            val choice = response.optJSONArray("choices")?.optJSONObject(0)
            val content = choice?.optJSONObject("message")?.optString("content").orEmpty()
            ModelVisionProbe.classify(200, content, challenge.expected).also {
                detail = if (it == ModelVision.VERIFIED) "已正确识别测试图片" else "已连接，但未正确识别测试图片；视觉能力待验证"
            }
        } catch (failure: ModelHttpFailure) {
            detail = if (failure.unsupportedVision) "平台明确表示此模型不支持图片，请更换模型" else failure.message.orEmpty()
            if (failure.unsupportedVision) ModelVision.UNSUPPORTED else ModelVision.UNKNOWN
        } catch (failure: Exception) {
            detail = if (failure is IOException || failure is IllegalStateException) failure.message.orEmpty() else "验证未完成，请检查连接后重试"
            ModelVision.UNKNOWN
        }
        providers.recordVision(providerId, model, result, target.fingerprint)
        return ModelProbeResult(result, detail, (System.nanoTime() - started) / 1000000)
    }
}


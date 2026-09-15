package dev.doppel.sdk

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object DirectPayload {
    const val ENDPOINT = "https://api.xiaomimimo.com/v1/chat/completions"
    const val PLANNER = "mimo-v2.5-pro"
    const val VISION = "mimo-v2.5"
    const val ASR = "mimo-v2.5-asr"
    fun chat(model: String, messages: JSONArray, tools: JSONArray? = null, deliberative: Boolean = false): JSONObject {
        require(model in setOf(PLANNER, VISION))
        // Strategic decisions can reason before a local action segment. Point grounding keeps its
        // short compatibility budget. Callers that replay assistant tool calls must also retain
        // their complete reasoning_content as required by MiMo; this engine rebuilds observations.
        return JSONObject().put("model", model).put("messages", messages).put("stream", false)
            .put("max_completion_tokens", if (deliberative) 6144 else 1600)
            .put("thinking", JSONObject().put("type", if (deliberative) "enabled" else "disabled"))
            .also { if (tools != null) it.put("tools", tools).put("tool_choice", "auto") }
    }
    fun speech(encoded: String) = JSONObject().put("model", ASR).put("stream", false)
        .put("asr_options", JSONObject().put("language", "auto"))
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "input_audio").put("input_audio", JSONObject().put("data", "data:audio/wav;base64,$encoded"))))))
    fun wavDuration(wav: ByteArray): Int {
        require(wav.size in 46..960044 && wav.size % 2 == 0) { "录音需为 30 秒以内的 PCM16 单声道音频" }
        val data = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        require(String(wav, 0, 4, Charsets.US_ASCII) == "RIFF" && data.getInt(4) == wav.size - 8 &&
            String(wav, 8, 8, Charsets.US_ASCII) == "WAVEfmt " && data.getInt(16) == 16 &&
            data.getShort(20).toInt() == 1 && data.getShort(22).toInt() == 1 && data.getInt(24) == 16000 &&
            data.getInt(28) == 32000 && data.getShort(32).toInt() == 2 && data.getShort(34).toInt() == 16 &&
            String(wav, 36, 4, Charsets.US_ASCII) == "data" && data.getInt(40) == wav.size - 44) { "录音格式无效" }
        return ((wav.size - 44) * 1000 + 31999) / 32000
    }
}

internal class DirectMimoProvider(private val context: Context) {
    companion object {
        private val diagnosticLock = Any()
        private val speechSlots = java.util.concurrent.Semaphore(2)
        private val previewSlot = java.util.concurrent.Semaphore(1)
    }
    private fun record(trace: DirectProviderTrace) {
        val diagnostic = trace.snapshot()
        android.util.Log.i("DoppelProvider", diagnostic.toString())
        synchronized(diagnosticLock) { try {
            val folder = java.io.File(context.noBackupFilesDir, "direct-provider-diagnostics")
            check(folder.isDirectory || folder.mkdirs())
            val file = android.util.AtomicFile(java.io.File(folder, "${trace.model}.json"))
            val stream = file.startWrite()
            try { stream.write(diagnostic.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
            catch (failure: Exception) { file.failWrite(stream) }
        } catch (_: Exception) { /* Diagnostics must never alter the request result or trigger a retry. */ } }
    }
    fun complete(payload: JSONObject, timeoutSeconds: Long = 45, onConnection: (HttpURLConnection) -> Unit = {}): JSONObject {
        val trace = DirectProviderTrace(payload.optString("model"))
        try {
            FirstUseConsent.requireAccepted(context)
            trace.enter("credentials")
            val key = DirectCredentials(context).read()
            trace.enter("consent"); FirstUseConsent.requireAccepted(context)
            return DirectMimoTransport().complete(payload, key, timeoutSeconds, onConnection, { FirstUseConsent.requireAccepted(context) }, trace)
        } catch (failure: Exception) {
            if (!trace.failed) trace.fail(failure)
            throw failure
        } finally { record(trace) }
    }
    fun speech(wav: ByteArray, final: Boolean, onConnection: (HttpURLConnection) -> Unit): JSONObject {
        val duration = DirectPayload.wavDuration(wav)
        check(speechSlots.tryAcquire()) { "语音服务繁忙，请稍后重试" }
        var previewAcquired = false
        try {
            if (!final) { previewAcquired = previewSlot.tryAcquire(); check(previewAcquired) { "语音预览正在识别" } }
            val started = System.nanoTime()
            val response = complete(DirectPayload.speech(Base64.encodeToString(wav, Base64.NO_WRAP)), 25, onConnection)
            val choice = response.getJSONArray("choices").getJSONObject(0)
            val text = choice.getJSONObject("message").optString("content").trim()
            check(choice.optString("finish_reason") == "stop" && text.length in 1..8000) { "语音结果为空或不完整，请重新录音" }
            return JSONObject().put("text", text).put("model", DirectPayload.ASR).put("duration_ms", duration)
                .put("elapsed_ms", (System.nanoTime() - started) / 1000000)
        } finally { if (previewAcquired) previewSlot.release(); speechSlots.release() }
    }
}

package dev.doppel.sdk

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Only these five allowlisted fields may leave the transport for local diagnostics. */
internal class DirectProviderTrace(model: String, private val nanos: () -> Long = System::nanoTime) {
    val model = model.takeIf { it in setOf(DirectPayload.PLANNER, DirectPayload.VISION, DirectPayload.ASR) } ?: "unknown"
    private val started = nanos()
    var stage = "consent"
        private set
    private var httpStatus: Int? = null
    private var errorClass = "none"
    val failed: Boolean get() = errorClass != "none"
    val elapsedMs: Long get() = ((nanos() - started) / 1000000).coerceAtLeast(0)
    fun enter(value: String) {
        require(value in setOf("consent", "credentials", "open", "configure", "serialize", "request_body", "response_headers", "response_body", "response_parse", "complete"))
        stage = value
    }
    fun status(value: Int) { httpStatus = value.takeIf { it in 100..599 } }
    fun fail(error: Exception, deadline: Boolean = false) {
        val name = error.javaClass.simpleName
        errorClass = if (deadline) "DirectDeadlineException" else name.takeIf { it in setOf(
            "JSONException", "SocketTimeoutException", "UnknownHostException", "SSLHandshakeException", "SSLPeerUnverifiedException", "SSLException",
            "SocketException", "EOFException", "ProtocolException", "IOException", "InterruptedIOException", "SecurityException",
            "IllegalStateException", "IllegalArgumentException", "NullPointerException", "DirectHttpStatusException", "DirectDeadlineException", "DirectResponseLimitException"
        ) } ?: "OtherException"
    }
    fun snapshot() = JSONObject().put("model", model).put("stage", stage).put("http_status", httpStatus ?: JSONObject.NULL)
        .put("elapsed_ms", elapsedMs).put("error_class", errorClass)
}

private class DirectHttpStatusException : IllegalStateException()
private class DirectDeadlineException : IllegalStateException()
private class DirectResponseLimitException : IllegalStateException()

/** One request only. Injectable connection supports failure reproduction without a provider call. */
internal class DirectMimoTransport(private val open: () -> HttpURLConnection = { URL(DirectPayload.ENDPOINT).openConnection() as HttpURLConnection }) {
    companion object {
        private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "direct-timeouts").apply { isDaemon = true } }
    }
    fun complete(payload: JSONObject, key: String, timeoutSeconds: Long, onConnection: (HttpURLConnection) -> Unit,
                 consent: () -> Unit, trace: DirectProviderTrace): JSONObject {
        require(timeoutSeconds in 1..120) { "模型请求期限无效" }
        var connection: HttpURLConnection? = null
        var expiry: java.util.concurrent.ScheduledFuture<*>? = null
        val timedOut = AtomicBoolean(false)
        var status: Int? = null
        try {
            trace.enter("open")
            val active = open(); connection = active
            expiry = watchdog.schedule({ timedOut.set(true); active.disconnect() }, timeoutSeconds, TimeUnit.SECONDS)
            fun deadline() { if (timedOut.get() || trace.elapsedMs >= timeoutSeconds * 1000) throw DirectDeadlineException() }
            trace.enter("configure")
            active.requestMethod = "POST"; active.connectTimeout = 8000; active.readTimeout = (timeoutSeconds * 1000).toInt()
            active.instanceFollowRedirects = false; active.doOutput = true
            active.setRequestProperty("Authorization", "Bearer $key")
            active.setRequestProperty("Content-Type", "application/json")
            trace.enter("serialize")
            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= 6 * 1024 * 1024) { "模型请求过大" }
            active.setFixedLengthStreamingMode(bytes.size); onConnection(active)
            trace.enter("consent"); consent(); deadline()
            trace.enter("request_body")
            active.outputStream.use { trace.enter("consent"); consent(); deadline(); trace.enter("request_body"); it.write(bytes) }
            trace.enter("response_headers")
            status = active.responseCode; trace.status(status)
            deadline()
            if (status !in 200..299) throw DirectHttpStatusException()
            trace.enter("response_body")
            val out = java.io.ByteArrayOutputStream()
            active.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    deadline()
                    val count = input.read(buffer); if (count < 0) break
                    if (out.size() + count > 262144) throw DirectResponseLimitException()
                    out.write(buffer, 0, count)
                }
            }
            trace.enter("response_parse"); deadline()
            val response = JSONObject(out.toString("UTF-8"))
            deadline(); trace.enter("complete")
            return response
        } catch (failure: Exception) {
            trace.fail(failure, timedOut.get())
            val message = when {
                failure is DirectHttpStatusException -> when (status) {
                    401, 403 -> "MiMo 凭据无效或无权访问此模型"
                    402 -> "MiMo 账户余额不足"
                    429 -> "MiMo 请求限流，请稍后手动继续"
                    else -> "MiMo 请求未完成 ($status)，未自动重试"
                }
                trace.stage == "response_parse" -> "MiMo 响应解析失败，调用计费结果可能未知；已暂停，未自动重试"
                failure is DirectResponseLimitException -> "MiMo 响应超过读取上限；已暂停，未自动重试"
                failure is DirectDeadlineException || timedOut.get() -> "MiMo 请求达到执行期限，调用计费结果可能未知；已暂停，未自动重试"
                trace.stage == "serialize" -> "MiMo 请求数据无效或过大；请求未发送"
                else -> "MiMo 请求在${when (trace.stage) { "open", "configure" -> "连接准备"; "request_body" -> "连接或发送"; "response_headers" -> "等待响应"; "response_body" -> "读取响应"; else -> "本机检查" }}阶段中断，调用计费结果可能未知；已暂停，未自动重试"
            }
            // Never attach the provider exception as cause: it may carry URLs, body fragments or credentials.
            throw IllegalStateException(message)
        } finally {
            expiry?.cancel(false)
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }
}

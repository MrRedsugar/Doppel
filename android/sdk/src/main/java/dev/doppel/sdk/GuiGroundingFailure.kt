package dev.doppel.sdk

import org.json.JSONObject

internal class GuiGroundingHttpException(val status: Int) : java.io.IOException("GUI HTTP request failed")

/** Fixed diagnostic vocabulary only; response bodies, exception messages and endpoints never leave the client. */
internal object GuiGroundingFailure {
    private val codes = setOf("http_error", "timeout", "connection_failed", "network_error",
        "cancelled", "invalid_request_or_response", "local_unavailable")

    fun from(error: Exception): JSONObject {
        val code = when (error) {
            is GuiGroundingHttpException -> "http_error"
            is java.net.SocketTimeoutException -> "timeout"
            is java.net.ConnectException, is java.net.UnknownHostException -> "connection_failed"
            is java.util.concurrent.CancellationException -> "cancelled"
            is org.json.JSONException, is IllegalArgumentException -> "invalid_request_or_response"
            is java.io.IOException -> "network_error"
            else -> "local_unavailable"
        }
        return JSONObject().put("code", code).apply {
            if (error is GuiGroundingHttpException && error.status in 100..599) put("http_status", error.status)
        }
    }

    fun sanitize(raw: JSONObject?): JSONObject? {
        raw ?: return null
        val code = raw.opt("code") as? String ?: return null
        if (code !in codes) return null
        val result = JSONObject().put("code", code)
        if (code == "http_error") {
            val status = raw.opt("http_status") as? Int ?: return null
            if (status !in 100..599) return null
            result.put("http_status", status)
        }
        return result
    }
}

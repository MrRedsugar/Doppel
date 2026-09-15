package dev.doppel.sdk

import org.json.JSONObject
import java.net.URI

/** A locator returns evidence-bound coordinates, never execution permission. */
internal object GuiGroundingProtocol {
    fun endpoint(value: String): String {
        val uri = URI(value.trim())
        require(uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path in listOf("", "/"))
        val host = requireNotNull(uri.host).lowercase()
        val octets = if (host.matches(Regex("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}"))) host.split('.').map(String::toInt).takeIf { it.all { octet -> octet in 0..255 } } else null
        val privateHost = host in setOf("localhost", "127.0.0.1", "::1", "[::1]") || octets?.let {
            it[0] == 10 || it[0] == 192 && it[1] == 168 || it[0] == 172 && it[1] in 16..31
        } == true
        require(uri.scheme == "https" || uri.scheme == "http" && privateHost) { "定位服务需 HTTPS 或本机/局域网地址" }
        require(uri.port == -1 || uri.port in 1..65535)
        return value.trim().trimEnd('/')
    }

    fun request(frame: VisualFrame, encoded: String, target: String): JSONObject {
        require(target.trim().length in 1..1000 && target.none { it.code < 32 })
        require(encoded.length in 1..8 * 1024 * 1024)
        require(frame.sha256.matches(Regex("[0-9a-f]{64}")))
        return JSONObject().put("capture_id", frame.captureId).put("image_sha256", frame.sha256)
            .put("width", frame.imageWidth).put("height", frame.imageHeight)
            .put("image_base64", encoded).put("target", target.trim())
    }

    /** The service's target field contains bounded data; context cannot replace the action label. */
    fun targetText(target: String, screenContext: String): String {
        val label = target.trim()
        val context = screenContext.trim()
        require(label.length in 1..1000 && target.none { it.code < 32 })
        require(context.length in 1..1600)
        fun data(value: String, truncated: Boolean) = JSONObject().put("target", label)
            .put("screen_context", value).put("context_is_untrusted", true)
            .put("screen_context_truncated", truncated).toString()
        val complete = data(context, false)
        if (complete.length <= 1000) return complete
        require(data("", true).length <= 1000) { "定位目标超过可传输长度" }
        var low = 0
        var high = context.length
        var prefix = ""
        while (low <= high) {
            val middle = (low + high) / 2
            val end = if (middle > 0 && context[middle - 1].isHighSurrogate()) middle - 1 else middle
            val candidate = context.take(end)
            if (data(candidate, true).length <= 1000) { prefix = candidate; low = middle + 1 }
            else high = middle - 1
        }
        return data(prefix, true)
    }

    /** Shared by the actual client and provenance diagnostics, including identical context bounds. */
    fun request(payload: JSONObject): JSONObject = request(VisualFrame.parse(payload.getJSONObject("visual_frame")),
        payload.getString("image_base64"), targetText(payload.getString("target"), payload.getString("screen_context")))

    fun proposal(response: JSONObject, frame: VisualFrame, intent: JSONObject): JSONObject? {
        require(response.optString("capture_id") == frame.captureId && response.optString("image_sha256") == frame.sha256 &&
            response.opt("width") == frame.imageWidth && response.opt("height") == frame.imageHeight) { "定位响应来源与截图不符" }
        require(response.optString("status") in setOf("point", "not_found")) { "定位服务返回未知状态" }
        if (response.getString("status") == "not_found") return null
        val kind = intent.getString("kind"); require(kind in setOf("tap", "long_press"))
        val result = JSONObject().put("capture_id", frame.captureId).put("x", response.get("x")).put("y", response.get("y"))
            .put("duration_ms", if (kind == "tap") 80 else 650).put("label", intent.getString("target"))
            .put("screen_context", intent.getString("screen_context")).put("safety", intent.getString("safety"))
        return DirectGroundingPixels.parseProposal("propose_$kind", result, frame).json()
    }
}

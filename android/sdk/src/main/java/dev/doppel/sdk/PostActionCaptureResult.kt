package dev.doppel.sdk

import org.json.JSONObject

/** An accepted input and its result-frame read are distinct facts. This never grants input replay. */
internal object PostActionCaptureResult {
    const val KEY = "post_action_read_diagnostic"
    private val transitions = setOf("capture_screen_changed", "capture_geometry_changed", "capture_companion_changed")
    private val readiness = setOf("root_unavailable", "empty_frame")
    private val reasons = transitions + readiness + setOf("capture_not_authorized", "capture_revoked", "backend_disconnected",
        "backend_result_unconfirmed", "host_authorization_revoked", "source_unavailable", "source_changed")
    private val takeovers = setOf("login", "verification", "payment", "interruption", "screen_capture_required", "sensitive", "uncertain")
    private val delivery = listOf("image_base64", "mime_type", "visual_frame", "capture_backend")
    private val diagnosticFields = listOf("reason_code", "human_takeover", "read_diagnostic", "visual_diagnostic", "shell_diagnostic")

    /** Adds only capture-owned delivery fields; the input receipt and pre-input diagnostic are untouched. */
    fun attach(actionData: JSONObject, captureResult: JSONObject): JSONObject {
        val captureData = captureResult.optJSONObject("data") ?: JSONObject()
        val raw = JSONObject().put("status", captureResult.opt("status") ?: JSONObject.NULL)
        for (key in diagnosticFields) if (captureData.has(key)) raw.put(key, captureData.opt(key))
        val diagnostic = requireNotNull(sanitize(raw))
        actionData.put(KEY, diagnostic)
        delivery.forEach(actionData::remove)
        actionData.remove("capture_observation")
        if (diagnostic.has("human_takeover")) actionData.put("human_takeover", diagnostic.getString("human_takeover"))
        if (diagnostic.optString("status") == "ok" && !actionData.has("human_takeover")) {
            for (key in delivery) {
                when (val value = captureData.opt(key)) {
                    is String -> if (key != "visual_frame") actionData.put(key, value)
                    is JSONObject -> if (key == "visual_frame") actionData.put(key, JSONObject(value.toString()))
                }
            }
            CaptureObservationBinding.sanitize(captureData.optJSONObject("capture_observation"))?.let { actionData.put("capture_observation", it) }
            actionData.remove("screenshot_error")
        } else actionData.put("screenshot_error", takeoverMessage(actionData)
            ?: (captureResult.opt("message") as? String)?.take(500)?.takeIf { it.isNotBlank() }
            ?: "动作后的屏幕采集未确认")
        return actionData
    }

    /** Fixed host wording only; screenshot/model/process prose is never a takeover explanation. */
    fun takeoverMessage(actionData: JSONObject): String? {
        if (!actionData.has("human_takeover")) return null
        return when (actionData.opt("human_takeover") as? String) {
            "login" -> "当前页面涉及登录资料或验证码，已停止截图。请完成当前登录步骤后继续。"
            "verification" -> "当前页面需要完成验证。请处理验证后继续任务。"
            "payment" -> "当前付款步骤需要你确认。请核对金额和订单，处理后继续任务。"
            "interruption" -> "检测到来电或其他系统干扰。请处理干扰后继续任务。"
            "screen_capture_required" -> "屏幕采集授权尚未就绪。请在 Doppel 设置的“屏幕识别”中重新授权后继续。"
            "sensitive" -> "当前页面涉及敏感资料，已停止截图。请手动处理后继续任务。"
            "uncertain" -> "当前画面无法安全确认。请核对页面后继续任务。"
            else -> "当前屏幕采集需要你处理。请检查页面和屏幕识别授权后继续。"
        }
    }

    /** Bounded host facts only. Malformed safety/diagnostic fields remain fail-closed. */
    fun sanitize(value: JSONObject?): JSONObject? {
        value ?: return null
        val status = (value.opt("status") as? String)?.takeIf { it in setOf("ok", "error", "blocked", "cancelled", "stale") } ?: "unknown"
        return JSONObject().put("status", status).apply {
            if (value.has("reason_code")) put("reason_code", (value.opt("reason_code") as? String)?.takeIf { it in reasons } ?: "unknown")
            if (value.has("human_takeover")) put("human_takeover",
                (value.opt("human_takeover") as? String)?.takeIf { it in takeovers } ?: "unknown")
            if (value.has("read_diagnostic")) put("read_diagnostic", DeviceReadDiagnostic.sanitize(value.optJSONObject("read_diagnostic"))
                ?: JSONObject().put("error_class", "OtherException"))
            if (value.has("visual_diagnostic")) {
                val original = value.optJSONObject("visual_diagnostic")
                put("visual_diagnostic", JSONObject()
                    .put("reason_code", (original?.opt("reason_code") as? String)?.takeIf { it in transitions } ?: "unknown")
                    .put("stage", (original?.opt("stage") as? String)?.takeIf { it == "capture" } ?: "unknown"))
            }
            if (value.has("shell_diagnostic")) put("shell_diagnostic",
                runCatching { ShellBridgeDiagnostic.sanitize(value.optJSONObject("shell_diagnostic")) }.getOrNull()
                    ?: JSONObject().put("reason_code", "unknown"))
        }
    }

    /** The caller must additionally require accepted, verified input and a bounded read-only phase. */
    fun knownTransition(value: JSONObject?): Boolean {
        val safe = sanitize(value) ?: return false
        if (safe.has("human_takeover") || safe.has("shell_diagnostic")) return false
        return when (safe.optString("status")) {
            "stale" -> {
                val reason = safe.optString("reason_code")
                val visual = safe.optJSONObject("visual_diagnostic")
                !safe.has("read_diagnostic") && reason in transitions &&
                    visual?.optString("reason_code") == reason && visual.optString("stage") == "capture"
            }
            "error" -> {
                val read = safe.optJSONObject("read_diagnostic")
                !safe.has("visual_diagnostic") && read?.optString("error_class") == "ScreenNotReadyException" &&
                    read.optString("reason_code") in readiness &&
                    (!safe.has("reason_code") || safe.optString("reason_code") == read.optString("reason_code"))
            }
            else -> false
        }
    }
}

package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

/** Only fixed protocol names and JSON types are retained, never values or unknown keys. */
internal object DirectGroundingToolDiagnostic {
    private val proposalKinds = mapOf("propose_tap" to "tap", "propose_long_press" to "long_press", "propose_swipe" to "swipe")
    private val fields = listOf("kind", "capture_id", "x", "y", "end_x", "end_y", "duration_ms", "label", "screen_context", "safety", "reason")

    fun from(toolName: String?, args: JSONObject): JSONObject {
        val types = JSONObject()
        for (field in fields) {
            if (!args.has(field)) continue
            val value = args.opt(field)
            val type = when {
                value == null || value === JSONObject.NULL -> "null"
                value is String -> "string"
                value is Number -> "number"
                value is Boolean -> "boolean"
                value is JSONObject -> "object"
                value is JSONArray -> "array"
                else -> throw IllegalArgumentException("Unsupported JSON type")
            }
            types.put(field, type)
        }
        return JSONObject().put("tool_name", toolName?.takeIf { it in proposalKinds || it == "cannot_ground" } ?: "unknown")
            .put("field_types", types).put("unknown_field_count", (args.length() - types.length()).coerceIn(0, 128))
            .apply {
                val expectedKind = proposalKinds[toolName]
                val kind = args.opt("kind") as? String
                if (expectedKind != null && kind != null) put("expected_kind_matches", kind == expectedKind)
            }
    }
}

/** Developer diagnostics contain only our source location and fixed validation categories. */
internal object DirectValidationDiagnostic {
    private val classes = setOf("VisualValidationException", "IllegalStateException", "IllegalArgumentException", "JSONException",
        "NullPointerException", "IndexOutOfBoundsException", "NoSuchElementException", "ClassCastException", "UnsupportedOperationException")
    private val sources = mapOf(
        "DirectGroundingPixels.kt" to setOf("dev.doppel.sdk.DirectGroundingPixels"),
        "DirectTaskEngine.kt" to setOf("dev.doppel.sdk.DirectTaskEngine", "dev.doppel.sdk.DirectTaskEngine\$Companion"),
        "VisualGesture.kt" to setOf("dev.doppel.sdk.VisualGesture", "dev.doppel.sdk.VisualGesture\$Companion",
            "dev.doppel.sdk.VisualFrame", "dev.doppel.sdk.VisualFrame\$Companion", "dev.doppel.sdk.VisualGestureKt", "dev.doppel.sdk.VisualGesturePermits"))

    fun from(error: Exception): JSONObject {
        val frame = error.stackTrace.firstOrNull { it.className in sources[it.fileName].orEmpty() }
        return JSONObject().put("error_class", error.javaClass.simpleName.takeIf { it in classes } ?: "OtherException")
            .put("source_file", frame?.fileName ?: "unknown").put("source_line", frame?.lineNumber?.takeIf { it in 1..20000 } ?: 0)
            .apply {
                if (error is VisualValidationException) {
                    put("validation_code", error.reason.code)
                    error.field?.let { put("field", it) }
                }
            }
    }
}

internal object DirectStaleReason {
    private val messages = linkedMapOf(
        "source_capture_missing" to "来源截图已失效，请重新观察",
        "source_frame_mismatch" to "来源截图已过期或应用、尺寸、方向已变化",
        "verification_capture_failed" to "无法核验当前目标像素，请重新观察",
        "verification_frame_missing" to "目标像素核验缺少来源帧",
        "screen_context_changed" to "当前应用或画面标识已变化，旧视觉动作未执行",
        "target_pixels_changed" to "目标区域像素已变化，旧视觉动作未执行",
        "target_or_screen_changed" to "目标区域或当前画面已变化，旧视觉动作未执行",
        "capture_screen_changed" to "截图期间页面发生变化，请重新观察",
        "capture_geometry_changed" to "截图尺寸或方向已变化，请重新观察",
        "capture_companion_changed" to "助手窗口在截图期间重建，请重新观察",
        "navigation_changed" to "页面导航已变化，请重新观察",
        "target_not_visible" to "目标不在可见屏幕内，请重新观察")

    fun from(data: JSONObject, message: String): JSONObject {
        val code = (data.opt("reason_code") as? String)?.takeIf { it in messages }
            ?: messages.entries.firstOrNull { it.value == message }?.key ?: "stale_unknown"
        return JSONObject().put("reason_code", code).put("message", messages[code] ?: "设备报告页面或目标已变化，请重新观察")
    }
}

internal object DirectVisualDiagnostic {
    private val reasons = setOf("source_capture_missing", "source_frame_mismatch", "verification_capture_failed", "verification_frame_missing",
        "screen_context_changed", "target_pixels_changed", "capture_screen_changed", "capture_geometry_changed", "capture_companion_changed",
        "companion_touch_pass_unavailable", "touch_guard_handoff_unavailable", "gesture_context_changed", "visual_pixels_verified")
    private val captureReasons = setOf("capture_screen_changed", "capture_geometry_changed", "capture_companion_changed")
    fun sanitize(value: JSONObject?): JSONObject? {
        value ?: return null
        val reason = (value.opt("reason_code") as? String)?.takeIf { it in reasons } ?: return null
        return JSONObject().put("reason_code", reason).apply {
            (value.opt("stage") as? String)?.takeIf { it in setOf("source", "verify", "capture", "input") }?.let { put("stage", it) }
            for (key in listOf("frame_matches", "pixels_match", "semantic_screen_matches", "package_matches", "geometry_matches",
                "rotation_matches", "age_matches", "navigation_anchor_present", "window_matches", "navigation_matches", "semantic_changed_during_capture"))
                (value.opt(key) as? Boolean)?.let { put(key, it) }
            for (key in listOf("source_age_ms", "verification_age_ms")) {
                val number = when (val raw = value.opt(key)) { is Int -> raw.toLong(); is Long -> raw; else -> null }
                number?.takeIf { it in -45000L..86400000L }?.let { put(key, it) }
            }
            (value.opt("capture_reason_code") as? String)?.takeIf { it in captureReasons }?.let { put("capture_reason_code", it) }
            pixelCheck(value.optJSONObject("pixel_check"))?.let { put("pixel_check", it) }
        }
    }

    private fun pixelCheck(value: JSONObject?): JSONObject? {
        value ?: return null
        val matches = value.opt("matches") as? Boolean ?: return null
        val mode = (value.opt("mode") as? String)?.takeIf { it in setOf("strict", "target_anchor", "rejected") } ?: return null
        val reason = (value.opt("reason") as? String)?.takeIf {
            it in setOf("dimensions", "stable", "core_changed", "path_changed", "anchor_missing", "context_changed")
        } ?: return null
        return JSONObject().put("matches", matches).put("mode", mode).put("reason", reason).apply {
            (value.opt("endpoint_anchors") as? Boolean)?.let { put("endpoint_anchors", it) }
            for ((key, range) in listOf("path_points" to 1L..512L, "core_contrast_min" to 0L..255L)) {
                val number = when (val raw = value.opt(key)) { is Int -> raw.toLong(); is Long -> raw; else -> null }
                number?.takeIf { it in range }?.let { put(key, it) }
            }
            fun boundedNumber(key: String, maximum: Double) {
                (value.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..maximum }?.let { put(key, it) }
            }
            listOf("global_mean", "local_mean_max", "core_mean_max", "context_mean_max").forEach { boundedNumber(it, 255.0) }
            listOf("global_changed_fraction", "local_changed_fraction_max", "core_changed_fraction_max", "core_edge_fraction_min",
                "core_edge_agreement_min", "context_changed_fraction_max").forEach { boundedNumber(it, 1.0) }
        }
    }
}

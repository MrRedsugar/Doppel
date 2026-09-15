package dev.doppel.sdk

import org.json.JSONObject

internal enum class ScreenReadinessReason(val code: String, val explanation: String) {
    ROOT("root_unavailable", "当前活动窗口尚未提供可读取的根节点"),
    EMPTY_FRAME("empty_frame", "截图尚未呈现可见内容，请等待画面就绪")
}

internal class ScreenNotReadyException(val reason: ScreenReadinessReason = ScreenReadinessReason.ROOT) : IllegalStateException(reason.explanation)

internal object DeviceReadDiagnostic {
    private val classes = setOf("ScreenNotReadyException", "IllegalStateException", "IllegalArgumentException", "NullPointerException",
        "SecurityException", "JSONException", "IndexOutOfBoundsException", "NoSuchElementException", "ClassCastException", "UnsupportedOperationException", "ConcurrentModificationException", "OtherException")
    fun from(error: Exception): JSONObject {
        val frame = error.stackTrace.firstOrNull { it.className == "dev.doppel.sdk.DoppelAccessibilityService" && it.fileName == "DoppelAccessibilityService.kt" }
        return JSONObject().put("error_class", error.javaClass.simpleName.takeIf { it in classes } ?: "OtherException")
            .put("source_file", if (frame != null) "DoppelAccessibilityService.kt" else "unknown")
            .put("source_line", frame?.lineNumber?.takeIf { it in 1..20000 } ?: 0)
            .apply { if (error is ScreenNotReadyException) put("reason_code", error.reason.code) }
    }
    fun sanitize(value: JSONObject?): JSONObject? {
        value ?: return null
        val name = (value.opt("error_class") as? String)?.takeIf { it in classes } ?: "OtherException"
        val file = (value.opt("source_file") as? String)?.takeIf { it == "DoppelAccessibilityService.kt" } ?: "unknown"
        val line = (value.opt("source_line") as? Int)?.takeIf { it in 1..20000 && file != "unknown" } ?: 0
        return JSONObject().put("error_class", name).put("source_file", file).put("source_line", line).apply {
            if (name == "ScreenNotReadyException") {
                (value.opt("reason_code") as? String)?.takeIf { code -> ScreenReadinessReason.entries.any { it.code == code } }?.let { put("reason_code", it) }
            }
        }
    }
}

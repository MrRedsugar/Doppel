package dev.doppel.sdk

internal data class CompletionPresentation(val title: String, val text: String, val speak: Boolean) {
    companion object {
        fun from(status: String, message: String): CompletionPresentation? {
            val title = when (status) {
                "completed" -> "已完成"
                "failed" -> "未完成"
                "cancelled" -> "已停止"
                else -> return null
            }
            // The runtime's verified summary is authoritative. Do not guess task type from keywords.
            val text = message.trim().ifEmpty { if (status == "completed") "任务已完成" else title }.take(12000)
            return CompletionPresentation(title, text, status != "cancelled")
        }
    }
}

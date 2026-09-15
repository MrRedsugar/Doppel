package dev.doppel.sdk

internal enum class AssistantVisualState(val animated: Boolean, val label: String) {
    IDLE(true, ""),
    /** The agent is waiting for/processing a model decision; no device action is in flight. */
    THINKING(true, "思考中"),
    /** A command has been dispatched to the device. RUNNING is kept as the compatibility name. */
    RUNNING(true, "执行中"),
    PAUSED(false, "已暂停"),
    WAITING(false, "需要你确认"), COMPLETE(false, "已完成"),
    FAILED(false, "未完成"), OFFLINE(false, "连接中断"), RECOVERING(false, "等待恢复"), QUEUED(true, "排队中");

    companion object {
        fun resolve(status: String, workerState: String, locallyPaused: Boolean): AssistantVisualState = when {
            workerState == "正在创建任务" -> QUEUED
            workerState == "创建未确认，草稿已保留" -> FAILED
            status == "completed" -> COMPLETE
            status == "failed" -> FAILED
            status in setOf("awaiting_input", "awaiting_approval") -> WAITING
            status == "cancelled" || workerState == "已停止" -> PAUSED
            status == "paused" || status == "running" && locallyPaused -> PAUSED
            workerState.contains("连接中断") -> OFFLINE
            workerState == WorkerLoopDiagnostic.SERVICE_RECOVERY -> RECOVERING
            status == "running" && workerState in setOf("正在思考", "正在分析当前画面", "等待模型响应") -> THINKING
            status == "running" -> RUNNING
            status == "queued" -> QUEUED
            else -> IDLE
        }
    }
}

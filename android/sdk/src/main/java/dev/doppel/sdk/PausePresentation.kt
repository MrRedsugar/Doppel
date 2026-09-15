package dev.doppel.sdk

import org.json.JSONObject

/** Presentation hints never grant permission, resume a run, or infer task success. */
internal data class PausePresentation(val category: String, val reason: String, val nextStep: String, val userInitiated: Boolean = false) {
    val detail get() = "$category\n$reason\n\n下一步：$nextStep"
    val compact get() = if (userInitiated) "" else category
    val surfaceReason get() = when {
        userInitiated -> "任务进度已保留，准备好后可以继续。"
        reason == "执行已暂停，但未收到具体原因。" -> "暂时没有收到具体原因。"
        else -> reason.removeSuffix("，任务已暂停").removeSuffix("，已暂停")
    }
    val surfaceDetail get() = "$surfaceReason\n\n$nextStep"

    companion object {
        fun manual(message: String) = message in setOf("任务已暂停，继续时将重新观察屏幕", "你已暂停任务，继续时将重新观察屏幕")
        fun generic(message: String) = message.isBlank() || message.trim() in setOf("已暂停", "本机执行已暂停", "paused")
        fun from(run: JSONObject): PausePresentation? {
            if (run.optString("status") != "paused") return null
            val message = TaskPresentation.message(run.optString("message"))
            val reason = message.take(4000).takeUnless(::generic) ?: "执行已暂停，但未收到具体原因。"
            val takeover = run.optJSONObject("pending_request")?.optString("reason").orEmpty()
            return when {
                manual(message) ->
                    PausePresentation("主动暂停", reason, "准备好后点击继续执行。", true)
                message.startsWith("已达到本机任务调用或时间上限") ->
                    PausePresentation("任务上限", reason, "查看已完成的进度，结束此任务后拆分为较小的新任务；继续不会重置上限。")
                takeover == "verification" || message.contains("手动完成安全验证") ->
                    PausePresentation("需要安全验证", reason, "在当前应用手动完成验证，再点击继续重新识别屏幕。")
                takeover in setOf("payment", "login") || message.contains("付款授权未开启") || message.startsWith("敏感输入、支付验证") ->
                    PausePresentation("需要手动处理", reason, "核对当前页面并手动处理提示的敏感步骤，完成后再继续。")
                message.startsWith("视觉目标涉及") ->
                    PausePresentation("操作边界", reason, "在当前应用手动确认或处理后再继续。")
                message.startsWith("当前视觉目标无法确认") || message.startsWith("连续三次界面变化") ->
                    PausePresentation("画面未确认", reason, "核对当前页面，可补充目标或待页面稳定后继续；仍无法识别时结束任务并保留记录。")
                message.contains("无障碍服务未启用") || message.contains("屏幕共享") || message.contains("截图授权") ->
                    PausePresentation("设备权限", reason, "检查无障碍与屏幕识别权限，恢复所需权限后继续。")
                message.contains("电话需要你处理") || message.contains("闹钟正在响铃") || takeover == "interruption" ->
                    PausePresentation("系统中断", reason, "先处理电话、闹钟或系统提示，再点击继续。")
                message.contains("超时") || message.contains("请求执行期限") || message.contains("连接") || message.contains("网络") ->
                    PausePresentation("连接或服务异常", reason, "检查网络和模型服务状态后再继续；已发出的请求可能已经计费。")
                message.contains("记录") || message.contains("结果保存") || message.contains("结果清理") || message.contains("重复派发") ->
                    PausePresentation("执行记录异常", reason, "先核对上一步是否实际生效；不要重复提交可能已完成的操作，可保留记录排查。")
                else -> PausePresentation("执行中断", reason, "查看当前屏幕和任务记录，确认情况后选择继续、补充说明或结束任务。")
            }
        }
    }
}

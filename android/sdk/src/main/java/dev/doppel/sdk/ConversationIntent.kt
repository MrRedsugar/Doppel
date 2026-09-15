package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

/** Structured routing result for the composer. No keyword based dispatch. */
internal data class ConversationIntent(
    val kind: Kind,
    val confidence: Double,
    val goal: String = "",
    val title: String = "",
    val question: String = ""
) {
    enum class Kind { TASK, CONVERSATION, UNCERTAIN }

    fun canStartTask(threshold: Double = 0.72): Boolean =
        kind == Kind.TASK && confidence >= threshold && goal.isNotBlank()

    companion object {
        fun failureMessage(error: Exception): String = when (error) {
            is ModelHttpFailure, is GatewayHttpException, is IllegalStateException ->
                error.message?.take(200)?.takeIf { it.isNotBlank() } ?: "消息处理失败，请检查连接设置"
            is java.io.IOException -> "无法连接当前服务，请检查网络和连接设置后重试"
            else -> "消息处理失败，请稍后重试"
        }
        const val SYSTEM_PROMPT = """
你是 Doppel 的消息路由器。判断用户消息是 task（需要访问手机当前数据或操作手机才能完成）、conversation（只需回答或讨论）还是 uncertain（信息不足，先澄清）。设备任务支持查询和打开应用、读取通知/剪贴板/日历、系统控制和界面操作；仅查看或总结手机实际数据也属于 task。讨论这些功能、解释操作方法或复盘已有结果仍属于 conversation。结合上下文判断真实目的，不要按关键词机械判断。只输出 JSON：{"intent":"task|conversation|uncertain","confidence":0到1,"task_goal":"task目标，否则空","title":"不超过30字","question":"uncertain时的问题，否则空"}。
"""

        fun request(messages: JSONArray, maxItems: Int = 12): JSONArray {
            val recent = JSONArray()
            val start = maxOf(0, messages.length() - maxItems)
            for (i in start until messages.length()) {
                val message = messages.optJSONObject(i) ?: continue
                val role = message.optString("role")
                // Routing must never upload screenshots or tool payloads. Only
                // plain text user/assistant messages are useful here.
                val content = (message.opt("content") as? String)?.trim().orEmpty()
                if (role in setOf("user", "assistant") && content.isNotBlank()) {
                    recent.put(JSONObject().put("role", role).put("content", content.take(2000)))
                }
            }
            return JSONArray().put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                .put(JSONObject().put("role", "user").put("content", JSONObject().put("messages", recent).toString()))
        }

        fun parse(raw: String): ConversationIntent {
            val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val value = runCatching { JSONObject(text) }.getOrNull()
                ?: return ConversationIntent(Kind.UNCERTAIN, 0.0, question = "你希望我直接操作手机，还是只回答这个问题？")
            val kind = when (value.optString("intent")) {
                "task" -> Kind.TASK
                "conversation" -> Kind.CONVERSATION
                else -> Kind.UNCERTAIN
            }
            val confidence = value.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
            val goal = value.optString("task_goal").trim().take(8000)
            if (kind == Kind.TASK && goal.isBlank()) return ConversationIntent(Kind.UNCERTAIN, minOf(confidence, .49), question = "请说明你希望我在手机上完成什么？")
            return ConversationIntent(kind, confidence, goal, value.optString("title").trim().take(120),
                value.optString("question").trim().take(500).ifBlank { if (kind == Kind.UNCERTAIN) "你希望我直接操作手机，还是只回答这个问题？" else "" })
        }
    }
}

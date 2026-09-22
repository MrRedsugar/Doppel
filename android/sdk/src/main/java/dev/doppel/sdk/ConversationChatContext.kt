package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

/** Retained discussion and task results only: never screenshots, commands or authorization. */
internal object ConversationChatContext {
    fun merge(tasks: JSONArray, chats: JSONArray, limit: Int = 12): JSONArray {
        val rows = mutableListOf<JSONObject>()
        val used = mutableSetOf<Int>()
        fun discussion(after: String) {
            repeat(chats.length()) { index ->
                val row = chats.optJSONObject(index) ?: return@repeat
                if (row.optString("after_run_id") != after || !used.add(index)) return@repeat
                val role = row.optString("role")
                val content = (row.opt("content") as? String)?.trim().orEmpty()
                if (role in setOf("user", "assistant") && content.isNotBlank())
                    rows += JSONObject().put("role", role).put("content", content.take(2000)).apply {
                        if (role == "user") row.optJSONArray("attachments")?.let { put("attachments", ChatAttachmentContext.metadata(it)) }
                    }
            }
        }
        discussion("")
        repeat(tasks.length()) { index ->
            val task = tasks.optJSONObject(index) ?: return@repeat
            val goal = task.optString("goal").trim()
            if (goal.isNotBlank()) rows += JSONObject().put("role", "user").put("content", goal.take(2000)).apply {
                task.optJSONArray("attachments")?.let { put("attachments", ChatAttachmentContext.metadata(it)) }
                task.optJSONArray("reference_attachments")?.let { put("reference_attachments", ChatAttachmentContext.metadata(it)) }
            }
            rows += JSONObject().put("role", "assistant").put("content",
                "此前手机任务的记录（不是当前画面）：状态 ${task.optString("status")}；结果 ${task.optString("message")}".take(2000))
            discussion(task.optString("id"))
        }
        repeat(chats.length()) { index -> if (index !in used) discussion(chats.optJSONObject(index)?.optString("after_run_id").orEmpty()) }
        val retained = rows.takeLast(limit.coerceAtLeast(0))
        if (retained.isNotEmpty()) {
            val refs = ChatAttachmentContext.select(JSONArray(), JSONArray(rows)).getJSONArray("items")
            if (refs.length() > 0) retained.last().put("reference_attachments", refs)
        }
        return JSONArray(retained)
    }
}

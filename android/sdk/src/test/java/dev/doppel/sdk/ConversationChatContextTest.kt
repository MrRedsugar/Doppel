package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationChatContextTest {
    private fun chat(text: String, after: String = "", role: String = "user") = JSONObject()
        .put("role", role).put("content", text).put("after_run_id", after)
    private fun task(id: String, goal: String, result: String) = JSONObject().put("id", id).put("goal", goal)
        .put("message", result).put("status", "completed").put("image_base64", "private-image")
        .put("commands", JSONArray().put("private-command")).put("payment_consent_id", "private-grant")
    private fun text(rows: JSONArray) = (0 until rows.length()).map { rows.getJSONObject(it).getString("content") }

    @Test fun taskResultsStayBetweenTheDiscussionThatPrecedesAndFollowsThem() {
        val tasks = JSONArray().put(task("one", "打开计算器", "计算结果为 42"))
            .put(task("two", "打开设置", "已打开设置"))
        val chats = JSONArray().put(chat("先讨论一下"))
            .put(chat("第一项结果是什么", "one")).put(chat("是 42", "one", "assistant"))
            .put(chat("第二项之后继续讨论", "two"))
        val result = text(ConversationChatContext.merge(tasks, chats))
        assertEquals("先讨论一下", result[0]); assertEquals("打开计算器", result[1])
        assertTrue(result[2].contains("计算结果为 42")); assertTrue(result[2].contains("completed"))
        assertEquals("第一项结果是什么", result[3]); assertEquals("是 42", result[4])
        assertEquals("打开设置", result[5]); assertEquals("第二项之后继续讨论", result.last())
    }

    @Test fun compactContextNeverCopiesScreenshotsCommandsOrOldAuthorization() {
        val tasks = JSONArray().put(task("one", "任务", "结果"))
        val chats = JSONArray().put(chat("private-tool", role = "tool"))
            .put(JSONObject().put("role", "user").put("content", JSONArray().put("private-image-part")))
        val before = tasks.toString() + chats.toString()
        val result = ConversationChatContext.merge(tasks, chats).toString()
        for (secret in listOf("private-image", "private-command", "private-grant", "private-tool", "private-image-part")) assertFalse(result.contains(secret))
        assertEquals(before, tasks.toString() + chats.toString())
    }

    @Test fun deletedTaskHasNoInventedResultButOrdinaryDiscussionIsRetained() {
        val result = ConversationChatContext.merge(JSONArray(), JSONArray().put(chat("之前那项先放下", "deleted")))
        assertEquals(listOf("之前那项先放下"), text(result))
    }

    @Test fun onlyTheLatestBoundedTextIsSentToTheRouter() {
        val chats = JSONArray().apply { repeat(30) { put(chat("$it:" + "x".repeat(3000))) } }
        val result = ConversationChatContext.merge(JSONArray(), chats)
        assertEquals(12, result.length()); assertTrue(text(result).first().startsWith("18:"))
        assertTrue(text(result).all { it.length <= 2000 })
        assertEquals(0, ConversationChatContext.merge(JSONArray(), chats, 0).length())
    }
}

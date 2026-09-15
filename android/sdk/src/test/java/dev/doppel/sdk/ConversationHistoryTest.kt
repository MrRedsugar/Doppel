package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationHistoryTest {
    private fun previous(id: String = "old") = JSONObject().put("id", id).put("device_id", DirectRuntime.DEVICE_ID)
        .put("goal", "查看测试账本").put("message", "午饭16.26元，饮料3元，合计19.26元")
        .put("status", "completed").put("mode", "full").put("created_at", 1000)
        .put("payment_consent_id", "private-grant").put("execution_context", JSONObject().put("secret", "private-cache"))
    private fun create(e: DirectTaskEngine, parent: String? = "old") = e.create(JSONObject().put("device_id", DirectRuntime.DEVICE_ID)
        .put("goal", "再和另一个账本对比").put("mode", "ask").apply { if (parent != null) put("parent_run_id", parent) })
    private fun model(e: DirectTaskEngine): String {
        val c = e.poll().getJSONObject("command")
        e.result(JSONObject().put("run_id", c.getString("run_id")).put("command_id", c.getString("id")).put("status", "ok")
            .put("observation", JSONObject().put("screen_id", "fresh").put("package_name", "dev.book").put("width", 100).put("height", 200).put("nodes", JSONArray())))
        return e.takeWork()!!.payload.toString()
    }
    @Test fun `follow up receives previous findings without old authorization or action cache`() {
        val e = DirectTaskEngine(JSONArray().put(previous()).toString(), {})
        val run = create(e)
        assertEquals("ask", run.getString("mode"))
        val input = model(e)
        assertTrue(input.contains("19.26")); assertTrue(input.contains("查看测试账本"))
        assertFalse(input.contains("private-grant")); assertFalse(input.contains("private-cache"))
    }
    @Test fun `explicit new conversation does not inherit previous findings`() {
        val e = DirectTaskEngine(JSONArray().put(previous()).toString(), {})
        create(e, null); assertFalse(model(e).contains("19.26"))
    }
    @Test fun `deleting earlier source removes it from future model input`() {
        val e = DirectTaskEngine(JSONArray().put(previous()).toString(), {})
        create(e); e.delete("old"); assertFalse(model(e).contains("19.26"))
    }
    @Test fun `unknown parent cannot silently lose context`() {
        val e = DirectTaskEngine(null, {})
        assertThrows(IllegalArgumentException::class.java) { create(e) }
    }
    @Test fun `conversation relationship survives process restore`() {
        var stored = ""
        val e = DirectTaskEngine(JSONArray().put(previous()).toString(), { stored = it })
        val child = create(e); val restored = DirectTaskEngine(stored, {})
        assertEquals("old", restored.get(child.getString("id")).optString("parent_run_id"))
    }

    @Test fun `thread transcript includes current run and explicit chat roles`() {
        val parent = previous("old").put("title", "查看账本").put("conversation_messages", JSONArray()
            .put(JSONObject().put("id", "m1").put("role", "user").put("text", "查看测试账本").put("kind", "request").put("created_at", 1)))
        val child = JSONObject().put("id", "new").put("parent_run_id", "old").put("goal", "再比较一次")
            .put("title", "比较账本").put("message", "已完成").put("status", "completed").put("created_at", 2)
            .put("conversation_messages", JSONArray().put(JSONObject().put("id", "m2").put("role", "assistant").put("text", "已完成").put("kind", "progress").put("created_at", 3)))
        val result = ConversationHistory.thread("new") { id -> when (id) { "old" -> parent; "new" -> child; else -> null } }
        assertEquals(2, result.length())
        assertEquals("old", result.getJSONObject(0).getString("id"))
        assertEquals("new", result.getJSONObject(1).getString("id"))
        assertEquals("assistant", result.getJSONObject(1).getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("比较账本", result.getJSONObject(1).getString("title"))
    }

    @Test fun `background task is excluded from transcript`() {
        val automatic = JSONObject().put("id", "auto").put("conversation_enabled", false)
            .put("goal", "自动执行").put("status", "completed")
        assertEquals(0, ConversationHistory.thread("auto") { automatic }.length())
    }

    @Test fun `title generation is local and bounded`() {
        assertEquals("打开设置", ConversationTitle.fromGoal("请帮我 打开设置"))
        assertEquals("新任务", ConversationTitle.fromGoal("   "))
        assertTrue(ConversationTitle.fromGoal("x".repeat(100)).length <= 37)
    }
}

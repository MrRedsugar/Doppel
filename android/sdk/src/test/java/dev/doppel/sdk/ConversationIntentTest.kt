package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationIntentTest {
    @Test fun taskSubmissionKeepsOriginalConstraintsSeparateFromRouterInterpretation() {
        val original = "Open Meituan. Report its location page. Do not place orders or change system permissions."
        val routed = ConversationIntent.parse("""{"intent":"task","confidence":1,"task_goal":"打开美团的定位页面","title":"定位页面"}""")
        val request = ConversationIntent.taskRequest(original, "direct-this-phone", "ask", routed.title, routed.goal)
        assertEquals(original, request.getString("goal"))
        assertEquals(routed.goal, request.getString("task_context"))
        assertEquals("ask", request.getString("mode"))
        assertEquals("定位页面", request.getString("title"))
        assertFalse(ConversationIntent.taskRequest(original, "remote-device", "assist", null).has("task_context"))
        assertFalse(ConversationIntent.taskRequest(original, "direct-this-phone", "assist", null, original).has("task_context"))
        assertEquals("x".repeat(8000), ConversationIntent.taskRequest("x".repeat(8000), "direct-this-phone", "assist", null).getString("goal"))
        assertTrue(runCatching { ConversationIntent.taskRequest("x".repeat(8001), "direct-this-phone", "assist", null, "简短改写") }.isFailure)
    }

    @Test fun routingKeepsTheLatestUserMessageIncludingItsTailConstraints() {
        val latest = "x".repeat(11900) + " 不要修改权限。"
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "h".repeat(3000)))
            .put(JSONObject().put("role", "assistant").put("content", "a".repeat(3000)))
            .put(JSONObject().put("role", "user").put("content", latest))
        val rows = JSONObject(ConversationIntent.request(messages).getJSONObject(1).getString("content")).getJSONArray("messages")
        assertEquals(2000, rows.getJSONObject(0).getString("content").length)
        assertEquals(2000, rows.getJSONObject(1).getString("content").length)
        assertEquals(latest, rows.getJSONObject(2).getString("content"))
        messages.getJSONObject(2).put("content", "x".repeat(12001))
        assertTrue(runCatching { ConversationIntent.request(messages) }.isFailure)
    }

    @Test fun onlyConversationCanChangeVisibleVersionedMemories() {
        val visible = JSONObject().put("items", JSONArray().put(JSONObject().put("id", "saved-1").put("revision", 2)))
        val change = JSONObject().put("op", "upsert").put("id", "saved-1").put("expected_revision", 2).put("content", "下次优先选择步行方案")
        val raw = JSONObject().put("memory_changes", JSONArray().put(change))
        val conversation = ConversationIntent(ConversationIntent.Kind.CONVERSATION, 1.0)
        assertEquals(1, ConversationIntent.memoryChanges(raw, conversation, visible).length())
        for (kind in listOf(ConversationIntent.Kind.TASK, ConversationIntent.Kind.UNCERTAIN))
            assertEquals(0, ConversationIntent.memoryChanges(raw, ConversationIntent(kind, 1.0), visible).length())
        change.put("expected_revision", 1)
        assertTrue(runCatching { ConversationIntent.memoryChanges(raw, conversation, visible) }.isFailure)
        change.put("expected_revision", 2).put("id", "not-in-context")
        assertTrue(runCatching { ConversationIntent.memoryChanges(raw, conversation, visible) }.isFailure)
    }

    @Test fun chatEvidenceIncludesActualStepsWithinBudget() {
        val timeline = JSONArray()
        repeat(80) { timeline.put(JSONObject().put("message", "事件 $it " + "记录".repeat(500))) }
        val evidence = TaskReviewAssistant.chatEvidence(JSONObject().put("run_id", "run-1").put("goal", "查询路线")
            .put("timeline", timeline).put("task_state", JSONObject().put("facts", "事实".repeat(5000))))
        assertTrue(evidence.toString().length <= 6000)
        assertTrue(evidence.getJSONArray("timeline").length() in 1..12)
        assertTrue(evidence.toString().contains("事件 79"))
        assertFalse(evidence.toString().contains("事件 0 "))
    }

    @Test fun taskRequiresStructuredGoalAndConfidence() {
        val result = ConversationIntent.parse("{\"intent\":\"task\",\"confidence\":0.9,\"task_goal\":\"打开相册\"}")
        assertTrue(result.canStartTask())
        assertFalse(ConversationIntent.parse("{\"intent\":\"task\",\"confidence\":0.9}").canStartTask())
    }

    @Test fun malformedOutputFailsClosed() {
        assertEquals(ConversationIntent.Kind.UNCERTAIN, ConversationIntent.parse("not json").kind)
        assertFalse(ConversationIntent.parse("{\"intent\":\"conversation\",\"confidence\":1}").canStartTask())
    }

    @Test fun routingPromptDropsNonTextContext() {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hello"))
            .put(JSONObject().put("role", "tool").put("content", "secret"))
        val request = ConversationIntent.request(messages)
        assertTrue(request.optJSONObject(0).optString("content").contains("不要按关键词"))
        assertFalse(request.optJSONObject(1).optString("content").contains("secret"))
    }

    @Test fun connectionFailuresDescribeTheCauseWithoutEchoingProviderOrNetworkBodies() {
        val setup = "请在模型设置中验证并启用本机连接，或完成网关连接"
        assertEquals(setup, ConversationIntent.failureMessage(IllegalStateException(setup)))
        val auth = ConversationIntent.failureMessage(ModelHttpFailure(401, false, "private provider body"))
        assertTrue(auth.contains("API Key"))
        assertFalse(auth.contains("private provider body"))
        assertEquals("额度不足", ConversationIntent.failureMessage(GatewayHttpException(402, "额度不足")))
        assertFalse(ConversationIntent.failureMessage(java.io.IOException("private network details")).contains("private"))
    }
}

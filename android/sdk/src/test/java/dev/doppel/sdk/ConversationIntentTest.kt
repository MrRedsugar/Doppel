package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationIntentTest {
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

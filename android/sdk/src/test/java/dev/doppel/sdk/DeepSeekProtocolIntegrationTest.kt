package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeepSeekProtocolIntegrationTest {
    private fun response(value: JSONObject, role: String = "primary"): JSONObject {
        val format = SplitOutputSchema.format(role)
        val wire = ModelWirePolicy.prepare(JSONObject().put("response_format", format), ModelProvider.deepseek(), "deepseek-flash", role)
        val function = JSONObject().put("name", format.getJSONObject("json_schema").getString("name")).put("arguments", value.toString())
        return JSONObject().put("_doppel_request", ModelRequestDiagnostic.wireMetadata(wire))
            .put("usage", JSONObject().put("prompt_tokens", 123).put("completion_tokens", 40))
            .put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls")
                .put("message", JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
                    .put("tool_calls", JSONArray().put(JSONObject().put("id", "call_test").put("type", "function").put("function", function))))))
    }

    private fun waitValue() = JSONObject("""{"decision":{"kind":"wait","duration_ms":100,"reason":"test","wait_condition":"ready","evidence":"synthetic"},"state":null}""")

    @Test fun strictToolResponseUsesNormalTaskParserAndPreservesRawResponse() {
        val raw = response(waitValue()); val before = raw.toString()
        val metadata = raw.getJSONObject("_doppel_request")
        assertEquals("enabled", metadata.getString("thinking"))
        assertEquals("low", metadata.getString("reasoning_effort"))
        assertEquals("auto", metadata.getString("tool_choice"))
        assertEquals("strict_tool", metadata.getString("response_format"))
        val result = SplitAgentProtocol.content(raw, SplitOutputSchema.format("primary"), "primary")
        assertEquals("wait", result.getString("kind")); assertEquals(100, result.getInt("duration_ms"))
        assertEquals(before, raw.toString())
    }

    @Test fun thinkingAutoParsesActionContractAndDoesNotExposeReasoningAsTaskState() {
        val value = JSONObject("""{"decision":{"kind":"tap","target":"visible unit card","expected":"selected card","screen_context":"paused battle"},"state":null}""")
        val raw = response(value)
        raw.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            .put("reasoning_content", "synthetic private reasoning")
        val result = SplitAgentProtocol.content(raw, SplitOutputSchema.format("primary"), "primary")
        assertEquals("execute", result.getString("kind")); assertEquals("tap", result.getString("action"))
        assertEquals("visible unit card", result.getString("target"))
        assertEquals("selected card", result.getString("expected"))
        assertFalse(result.has("points")); assertFalse(result.has("reasoning_content"))
    }

    @Test fun autoMetadataWithoutThinkingAndPlainJsonFallbackAreRejected() {
        val missingThinking = response(waitValue()).apply { getJSONObject("_doppel_request").remove("thinking") }
        assertThrows(IllegalArgumentException::class.java) {
            SplitAgentProtocol.content(missingThinking, SplitOutputSchema.format("primary"), "primary")
        }
        val plain = response(waitValue()).apply {
            getJSONArray("choices").getJSONObject(0).apply {
                put("finish_reason", "stop")
                getJSONObject("message").remove("tool_calls")
                getJSONObject("message").put("content", waitValue().toString())
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SplitAgentProtocol.content(plain, SplitOutputSchema.format("primary"), "primary")
        }
    }

    @Test fun legacyForcedPrimaryMetadataStillUsesTheSameActionParser() {
        val format = SplitOutputSchema.format("primary")
        val wire = JSONObject().put("response_format", format).put("thinking", JSONObject().put("type", "disabled"))
            .also(DeepSeekStructuredOutput::prepare)
        val raw = response(waitValue()).put("_doppel_request", ModelRequestDiagnostic.wireMetadata(wire))
        assertEquals("forced", raw.getJSONObject("_doppel_request").getString("tool_choice"))
        val result = SplitAgentProtocol.content(raw, format, "primary")
        assertEquals("wait", result.getString("kind")); assertEquals(100, result.getInt("duration_ms"))
    }

    @Test fun providerToolDoesNotBypassLocalLengthLimitsOrAllowSchemaRoleMismatch() {
        val invalid = waitValue().apply { getJSONObject("decision").put("reason", "x".repeat(501)) }
        assertThrows(SplitSchemaViolation::class.java) {
            SplitAgentProtocol.content(response(invalid), SplitOutputSchema.format("primary"), "primary")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SplitAgentProtocol.content(response(waitValue()), SplitOutputSchema.format("grounding"), "grounding")
        }
    }

    @Test fun refusalFromGrounderRemainsRefusalAndDoesNotCreateCoordinates() {
        val raw = response(JSONObject("""{"result":{"status":"not_found","reason":"target absent"}}"""), "grounding")
        val result = SplitAgentProtocol.content(raw, SplitOutputSchema.format("grounding"), "grounding")
        assertEquals("not_found", result.getString("status"))
        assertFalse(result.has("points")); assertFalse(result.has("action"))
    }
}

package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeepSeekStructuredOutputTest {
    private fun wire() = JSONObject().put("response_format", SplitOutputSchema.format("primary")).also(DeepSeekStructuredOutput::prepare)
    private fun thinkingWire() = JSONObject().put("response_format", SplitOutputSchema.format("primary"))
        .put("thinking", JSONObject().put("type", "enabled")).also(DeepSeekStructuredOutput::prepare)
    private fun result() = JSONObject("""{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"a","type":"function","function":{"name":"doppel_a_ab_v3","arguments":"{\"decision\":{\"kind\":\"ask_user\",\"message\":\"help\"},\"state\":null}"}}]}}]}""")

    @Test fun preservesCanonicalSchemaAndProviderDiscriminatorsWithoutUnsupportedBounds() {
        val format = SplitOutputSchema.format("primary")
        val before = format.toString()
        val body = JSONObject().put("response_format", format)
        DeepSeekStructuredOutput.prepare(body)
        assertEquals(before, format.toString())
        assertFalse(body.has("response_format"))
        val function = body.getJSONArray("tools").getJSONObject(0).getJSONObject("function")
        assertTrue(function.getBoolean("strict"))
        val schema = function.getJSONObject("parameters")
        assertFalse(schema.getBoolean("additionalProperties"))
        val branches = schema.getJSONObject("properties").getJSONObject("decision").getJSONArray("anyOf")
        assertEquals(format.getJSONObject("json_schema").getJSONObject("schema").getJSONObject("properties").getJSONObject("decision").getJSONArray("anyOf").length(), branches.length())
        assertFalse(schema.toString().contains("\"maxLength\"")); assertFalse(schema.toString().contains("\"maxItems\""))
        assertTrue(schema.toString().contains("\"maximum\"")); assertTrue(schema.toString().contains("\"enum\""))
        assertEquals("null", schema.getJSONObject("properties").getJSONObject("state").getJSONArray("anyOf").getJSONObject(1).getString("type"))
    }

    @Test fun refusesTruncatedDuplicateWrongOrConflictingToolOutputs() {
        val variants = listOf<(JSONObject)->Unit>(
            { it.getJSONArray("choices").getJSONObject(0).put("finish_reason", "length") },
            { it.getJSONArray("choices").put(it.getJSONArray("choices").getJSONObject(0)) },
            { message(it).getJSONArray("tool_calls").put(message(it).getJSONArray("tool_calls").getJSONObject(0)) },
            { function(it).put("name", "unrequested_action") },
            { message(it).getJSONArray("tool_calls").getJSONObject(0).put("type", "custom") },
            { message(it).put("refusal", "cannot comply") },
            { message(it).put("content", "different result") },
            { function(it).put("arguments", "{} trailing") },
            { function(it).put("arguments", "{\"a\":1,\"a\":2}") },
            { function(it).put("arguments", JSONObject()) },
            { function(it).put("arguments", "[]") },
        )
        variants.forEach { modify ->
            val raw = result().also(modify)
            assertThrows(IllegalArgumentException::class.java) { DeepSeekStructuredOutput.normalize(raw, wire()) }
        }
    }

    @Test fun normalizedEnvelopeIsCopiedAndProbeRemainsPlain() {
        val raw = result(); val before = raw.toString()
        val parsed = DeepSeekStructuredOutput.normalize(raw, wire())
        assertEquals("stop", parsed.getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
        assertEquals(before, raw.toString())
        assertEquals(function(raw).getString("arguments"), message(parsed).getString("content"))
        assertSame(raw, DeepSeekStructuredOutput.normalize(raw, JSONObject().put("messages", JSONArray())))
    }

    @Test fun thinkingAutoAcceptsOneStrictResultWhileKeepingReasoningOutOfArguments() {
        val wire = thinkingWire()
        assertEquals("auto", wire.getString("tool_choice"))
        assertEquals(1, wire.getJSONArray("tools").length())
        assertTrue(wire.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getBoolean("strict"))
        val raw = result().apply { message(this).put("reasoning_content", "synthetic private reasoning") }
        val before = raw.toString()
        val parsed = DeepSeekStructuredOutput.normalize(raw, wire)
        assertEquals(before, raw.toString())
        assertEquals("stop", parsed.getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
        assertEquals(function(raw).getString("arguments"), message(parsed).getString("content"))
        assertFalse(message(parsed).getString("content").contains("synthetic private reasoning"))
    }

    @Test fun thinkingAutoCannotReturnPlainTextMultipleToolsWrongToolOrTruncatedArguments() {
        val variants = listOf<(JSONObject)->Unit>(
            { it.getJSONArray("choices").getJSONObject(0).put("finish_reason", "length") },
            { it.getJSONArray("choices").getJSONObject(0).put("finish_reason", "stop")
                message(it).remove("tool_calls"); message(it).put("content", "{\"decision\":{\"kind\":\"home\"},\"state\":null}") },
            { message(it).getJSONArray("tool_calls").put(message(it).getJSONArray("tool_calls").getJSONObject(0)) },
            { function(it).put("name", "unrequested_action") },
            { message(it).put("content", "different decision") },
            { function(it).put("arguments", "{\"decision\":") }
        )
        variants.forEach { mutate ->
            assertThrows(IllegalArgumentException::class.java) {
                DeepSeekStructuredOutput.normalize(result().also(mutate), thinkingWire())
            }
        }
    }

    @Test fun autoRequiresThinkingAndOneStrictDeclaredResult() {
        val variants = listOf<(JSONObject)->Unit>(
            { it.remove("thinking") },
            { it.put("thinking", JSONObject().put("type", "disabled")) },
            { it.put("tool_choice", "required") },
            { it.getJSONArray("tools").getJSONObject(0).getJSONObject("function").put("strict", false) },
            { it.getJSONArray("tools").put(it.getJSONArray("tools").getJSONObject(0)) }
        )
        variants.forEach { mutate ->
            assertThrows(IllegalArgumentException::class.java) {
                DeepSeekStructuredOutput.normalize(result(), thinkingWire().also(mutate))
            }
        }
    }

    @Test fun refusesUnrelatedToolsAndMismatchedForcedFunction() {
        assertThrows(IllegalArgumentException::class.java) {
            DeepSeekStructuredOutput.prepare(JSONObject().put("response_format", SplitOutputSchema.format("primary")).put("tools", JSONArray()))
        }
        val wrong = wire().apply { getJSONObject("tool_choice").getJSONObject("function").put("name", "other") }
        assertThrows(IllegalArgumentException::class.java) { DeepSeekStructuredOutput.normalize(result(), wrong) }
    }

    private fun message(raw: JSONObject) = raw.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
    private fun function(raw: JSONObject) = message(raw).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function")
}

package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StrictModelJsonTest {
    private val valid = """{"result":{"status":"located","action":"tap","points":[[400,500]],"duration_ms":60,"assessment":{"alignment":"consistent"}}}"""
    private fun response(content: String) = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("finish_reason", "stop").put("message", JSONObject().put("content", content))))

    @Test fun malformedProviderCommandsAreRejectedBeforeUnwrapping() {
        val invalid = listOf(
            valid.replace("\"result\"", "result"),
            valid.replace("\"result\"", "'result'"),
            valid.replace("\"duration_ms\":60", "\"duration_ms\":060"),
            valid.replace("\"duration_ms\":60", "\"duration_ms\":0x3c"),
            valid.replace("\"duration_ms\":60", "\"duration_ms\":+60"),
            valid.replace("\"duration_ms\":60", "\"duration_ms\":60."),
            valid.replace("\"duration_ms\":60", "\"duration_ms\":6e"),
            valid.replace("\"duration_ms\":60", "\"duration_ms\":60,\"duration_ms\":80"),
            valid.replace("\"duration_ms\":60", "\"duration_ms\":60,\"duration_\\u006ds\":80"),
            valid.replace("[400,500]", "[400,500,]"),
            valid.replace("\"action\":\"tap\",", "\"action\":\"tap\",/* comment */"),
            valid.dropLast(1) + ",}",
            valid + " {}",
            "\u00A0" + valid,
            "```json\n$valid\n```"
        )
        val schema = SplitOutputSchema.format("grounding", expectedAction = "tap")
        for (content in invalid) {
            try {
                SplitAgentProtocol.content(response(content), schema, "grounding")
                fail("malformed provider command was accepted: $content")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun validJsonKeepsNumbersUnicodeEscapesAndStringContents() {
        val content = " \r\n" + valid.replace("[400,500]", "[4e2,5.0E+2]") + "\t"
        val parsed = SplitAgentProtocol.content(response(content), SplitOutputSchema.format("grounding", expectedAction = "tap"), "grounding")
        assertEquals(400.0, parsed.getJSONArray("points").getJSONArray(0).getDouble(0), 0.0)
        val refused = """{"result":{"status":"not_found","reason":"未找到\u4fdd存按钮；标签中含 \"x\"、\\ 和换行\n"}}"""
        val explanation = SplitAgentProtocol.content(response(refused), SplitOutputSchema.format("grounding", expectedAction = "tap"), "grounding")
        assertTrue(explanation.getString("reason").contains("保存"))
        assertTrue(explanation.getString("reason").contains("\n"))
    }

    @Test fun rawControlsUnsupportedEscapesAndDeepNestingAreRejected() {
        for (invalid in listOf("{\"a\":\"raw\nline\"}", """{"a":"\x41"}""", """{"a":"\u00ZZ"}""",
            "{\"a\":" + "[".repeat(30) + "0" + "]".repeat(30) + "}")) {
            try { StrictModelJson.objectValue(invalid); fail("malformed JSON accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun legacyCallersKeepFenceCompatibilityWithoutWeakeningStructuredRequests() {
        val fenced = "```json\n{\"kind\":\"wait\"}\n```"
        assertEquals("wait", SplitAgentProtocol.content(response(fenced)).getString("kind"))
    }

    @Test fun diagnosticShapeContainsOnlyBoundedNamesAndTypes() {
        val value = JSONObject().put("decision", JSONObject().put("kind", "secret-kind").put("target", "secret-target")
            .put("points", JSONArray("[[401,502],[601,702]]"))).put("state", JSONObject.NULL)
            .put("private text as a key", "secret-value")
        val shape = StrictModelJson.shape(value)
        val fields = shape.getJSONObject("fields")
        assertEquals("null", fields.getJSONObject("state").getString("type"))
        assertEquals("string", fields.getJSONObject("decision").getJSONObject("fields").getJSONObject("target").getString("type"))
        for (sensitive in listOf("secret-", "private text", "401", "502", "601", "702")) assertFalse(shape.toString().contains(sensitive))
        val many = JSONObject().apply { repeat(200) { put("field_$it", "secret-$it") } }
        val bounded = StrictModelJson.shape(many)
        assertEquals(32, bounded.getJSONObject("fields").length())
        assertTrue(bounded.getBoolean("truncated"))
    }
}

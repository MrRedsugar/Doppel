package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelRequestDiagnosticTest {
    @Test fun deepseekWireMetadataRecordsActualModeAndCountsSchemaWithoutRetainingPrivateText() {
        val payload = JSONObject().put("response_format", SplitOutputSchema.format("primary"))
            .put("max_completion_tokens", 6500).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "private-screen-task")))
        val wire = ModelWirePolicy.prepare(payload, ModelProvider.deepseek(), "deepseek-flash", "primary")
        val metadata = ModelRequestDiagnostic.wireMetadata(wire)
        assertEquals("enabled", metadata.getString("thinking"))
        assertEquals("low", metadata.getString("reasoning_effort"))
        assertEquals("auto", metadata.getString("tool_choice"))
        assertEquals("strict_tool", metadata.getString("response_format"))
        assertEquals("doppel_a_ab_v3", metadata.getString("schema_name"))
        assertTrue(metadata.getBoolean("schema_strict")); assertTrue(metadata.getBoolean("client_length_validation"))
        assertEquals(6500, metadata.getInt("max_tokens"))
        assertEquals(wire.getJSONArray("tools").toString().length, ModelRequestDiagnostic.payloadSizeMetadata(wire).getInt("schema_chars"))
        assertFalse(metadata.toString().contains("private-screen-task")); assertFalse(metadata.toString().contains("properties"))
    }

    @Test fun deepseekGroundingMetadataRetainsDisabledThinkingAndForcedStrictTool() {
        val request = JSONObject().put("response_format", SplitOutputSchema.format("grounding", expectedAction = "tap"))
            .put("thinking", JSONObject().put("type", "enabled")).put("reasoning_effort", "high")
        val wire = ModelWirePolicy.prepare(request, ModelProvider.deepseek(), "deepseek-flash", "grounding")
        val metadata = ModelRequestDiagnostic.wireMetadata(wire)
        assertEquals("disabled", metadata.getString("thinking"))
        assertFalse(metadata.has("reasoning_effort"))
        assertEquals("forced", metadata.getString("tool_choice"))
        assertEquals("strict_tool", metadata.getString("response_format"))
        assertEquals("doppel_b_tap_v1", metadata.getString("schema_name"))
        assertTrue(metadata.getBoolean("schema_strict"))
        assertTrue(metadata.getBoolean("client_length_validation"))
    }

    @Test fun autoMetadataIdentifiesTheUniqueStrictContractAndPropagatesToPreparedDiagnostics() {
        val function = JSONObject().put("name", "doppel_a_ab_v2").put("strict", true)
            .put("description", "private-contract-description")
            .put("parameters", JSONObject().put("properties", JSONObject().put("private-field", "private-schema-value")))
        val request = JSONObject().put("thinking", JSONObject().put("type", "enabled"))
            .put("reasoning_effort", "low").put("tool_choice", "auto")
            .put("tools", JSONArray().put(JSONObject().put("type", "function").put("function", function)))
        val original = request.toString()
        val metadata = ModelRequestDiagnostic.wireMetadata(request)
        val prepared = diagnostic(request)
        val expected = mapOf<String, Any>("thinking" to "enabled", "reasoning_effort" to "low", "tool_choice" to "auto",
            "response_format" to "strict_tool", "schema_name" to "doppel_a_ab_v2", "schema_strict" to true,
            "client_length_validation" to true)
        assertEquals(expected.keys, metadata.keys().asSequence().toSet())
        expected.forEach { (key, value) ->
            assertEquals(key, value, metadata.get(key))
            assertEquals(key, value, prepared.get(key))
        }
        assertFalse(metadata.toString().contains("private"))
        assertFalse(prepared.toString().contains("private"))
        assertEquals(original, request.toString())

        val invalidVariants = listOf<(JSONObject) -> Unit>(
            { it.remove("thinking") },
            { it.put("thinking", JSONObject().put("type", "disabled")) },
            { it.remove("tool_choice") },
            { it.put("tool_choice", "private-tool-choice") },
            { it.getJSONArray("tools").getJSONObject(0).getJSONObject("function").put("strict", false) },
            { it.getJSONArray("tools").getJSONObject(0).getJSONObject("function").put("name", "private-contract-name") },
            { it.getJSONArray("tools").put(tool("action")) }
        )
        invalidVariants.forEachIndexed { index, modify ->
            val variant = JSONObject(original).also(modify)
            val result = ModelRequestDiagnostic.wireMetadata(variant)
            for (key in listOf("response_format", "schema_name", "schema_strict", "client_length_validation", "tool_choice")) {
                assertFalse("variant $index must not claim $key", result.has(key))
            }
            assertFalse("variant $index leaked private text", result.toString().contains("private"))
        }
    }

    @Test fun absentOrInvalidThinkingAndToolFieldsDoNotInventModesOrRetainArbitraryStrings() {
        val invalidPayloads = listOf(
            JSONObject(),
            JSONObject().put("thinking", JSONObject().put("type", "private-thinking"))
                .put("reasoning_effort", "private-effort").put("tool_choice", "private-tool-choice"),
            JSONObject().put("thinking", "private-thinking").put("enable_thinking", "private-enabled")
                .put("reasoning_effort", JSONObject().put("private", "private-effort"))
                .put("tool_choice", JSONObject().put("type", "private-tool-type").put("function", "private-function")),
            JSONObject().put("thinking", JSONObject().put("type", JSONObject.NULL))
                .put("reasoning_effort", JSONArray().put("private-effort")).put("tool_choice", JSONObject.NULL)
        )
        invalidPayloads.forEachIndexed { index, request ->
            val result = ModelRequestDiagnostic.wireMetadata(request)
            assertEquals("invalid payload $index", 0, result.length())
            assertFalse(diagnostic(request).toString().contains("private"))
        }
    }

    @Test fun wireMetadataContainsOnlyActualWireSettingsWithoutTraversingRequestData() {
        val original = JSONObject().put("model", "qwen3.8-flash").put("enable_thinking", true)
            .put("response_format", SplitOutputSchema.format("primary"))
            .put("messages", "screenshot-private").put("headers", "private-key").put("_doppel_role", "primary")
        val wire = ModelWirePolicy.prepare(original, ModelProvider.qwen(), "qwen3.8-flash", "primary")
        val metadata = ModelRequestDiagnostic.wireMetadata(wire)
        assertFalse(metadata.getBoolean("enable_thinking")); assertEquals("qwen3.8-flash", metadata.getString("model"))
        assertEquals("json_schema", metadata.getString("response_format")); assertTrue(metadata.getBoolean("schema_strict"))
        assertEquals("doppel_a_ab_v3", metadata.getString("schema_name"))
        assertEquals(5, metadata.length()); assertFalse(metadata.toString().contains("private"))
    }
    @Test fun reportsActualStructuredOutputAndDisabledThinkingWithoutCopyingSchema() {
        val format = SplitOutputSchema.format("grounding", expectedAction = "tap")
        format.getJSONObject("json_schema").put("description", "private-description")
        val result = diagnostic(JSONObject().put("response_format", format).put("enable_thinking", false))
        assertEquals("json_schema", result.getString("response_format"))
        assertTrue(result.getBoolean("schema_strict")); assertFalse(result.getBoolean("enable_thinking"))
        assertEquals("doppel_b_tap_v1", result.getString("schema_name"))
        assertFalse(result.toString().contains("private-description")); assertFalse(result.toString().contains("properties"))
    }
    // Synthetic one-pixel PNG; unrelated to any captured device or account.
    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/W9sAAAAASUVORK5CYII="
    private val sha = "06b5378be92db104507e166b9fd885382c9d94bd004ead66ea8baf8963d5075c"
    private fun image(url: Any = "data:image/png;base64,$png") = JSONObject().put("type", "image_url")
        .put("image_url", JSONObject().put("url", url))
    private fun message(content: Any) = JSONObject().put("role", "user").put("content", content)
    private fun payload(vararg messages: JSONObject) = JSONObject().put("model", "mimo-v2.5")
        .put("messages", JSONArray(messages.toList()))
    private fun diagnostic(payload: JSONObject, source: JSONObject? = null) = ModelRequestDiagnostic.from(payload, source, 7, 1234)

    @Test fun readsActualMessageImageBytesIndependentlyFromClaimedVisualSource() {
        val source = JSONObject().put("screen_id", "screen-current").put("package_name", "example.app")
            .put("capture_id", "capture-current").put("sha256", "a".repeat(64)).put("evidence_id", "receipt-current")
        val result = diagnostic(payload(message(JSONArray().put(image()))), source)
        assertEquals("prepared", result.getString("state"))
        assertEquals("mimo-v2.5", result.getString("model"))
        assertEquals(1234L, result.getLong("prepared_at"))
        assertEquals(7, result.getInt("call"))
        assertEquals("a".repeat(64), result.getJSONObject("current_source").getString("sha256"))
        assertEquals(sha, result.getJSONArray("images").getJSONObject(0).getString("sha256"))
        assertEquals(68, result.getJSONArray("images").getJSONObject(0).getInt("bytes"))
        assertEquals(1, result.getInt("image_count"))
    }

    @Test fun countsPlainAndPartTextWithoutRecordingBodyArgumentsKeysOrReasoning() {
        val text = JSONObject().put("type", "text").put("text", "hello")
        val first = message("AB").put("reasoning_content", "reasoning-secret")
            .put("tool_calls", JSONArray().put(JSONObject().put("arguments", "argument-secret")))
        val reasoning = JSONObject().put("type", "reasoning").put("text", "private-part-reasoning")
        val request = payload(first, message(JSONArray().put(text).put(image()).put(reasoning)))
            .put("headers", JSONObject().put("Authorization", "header-secret")).put("key", "key-secret")
            .put("tools", JSONArray().put(JSONObject().put("parameters", "schema-secret")))
        val source = JSONObject().put("screen_id", "screen-current").put("text", "source-secret")
            .put("image_base64", png).put("headers", JSONObject().put("key", "nested-secret"))
        val result = diagnostic(request, source)
        assertEquals(7, result.getInt("text_chars"))
        assertEquals(2, result.getInt("message_count"))
        val serialized = result.toString()
        for (forbidden in listOf("AB", "hello", png, "reasoning-secret", "private-part-reasoning", "argument-secret", "header-secret", "key-secret", "schema-secret", "source-secret", "nested-secret")) {
            assertFalse(forbidden, serialized.contains(forbidden))
        }
        assertEquals(setOf("screen_id"), result.getJSONObject("current_source").keys().asSequence().toSet())
    }

    @Test fun aTextOnlyRequestDoesNotInventAnImageFromVisualMetadata() {
        val source = JSONObject().put("capture_id", "old-capture").put("sha256", "b".repeat(64))
        val result = diagnostic(payload(message("Only text")), source)
        assertEquals(0, result.getJSONArray("images").length())
        assertEquals(0, result.getInt("image_count"))
        assertEquals(9, result.getInt("text_chars"))
    }

    @Test fun malformedOrRemoteImagesAreMarkedInvalidWithoutInterruptingOtherParts() {
        val parts = JSONArray().put(image("data:image/png;base64,%%%"))
            .put(image("https://example.invalid/private-image?key=do-not-save"))
            .put(image()).put(JSONObject().put("type", "text").put("text", "done"))
        val result = diagnostic(payload(message(parts)))
        assertTrue(result.getJSONArray("images").getJSONObject(0).getBoolean("invalid"))
        assertTrue(result.getJSONArray("images").getJSONObject(1).getBoolean("invalid"))
        assertEquals(sha, result.getJSONArray("images").getJSONObject(2).getString("sha256"))
        assertEquals(4, result.getInt("text_chars"))
        assertFalse(result.toString().contains("do-not-save"))
    }

    @Test fun rejectsBase64WhoseDecodedBytesAreNotPng() {
        val result = diagnostic(payload(message(JSONArray().put(image("data:image/png;base64,aGVsbG8=")))))
        val record = result.getJSONArray("images").getJSONObject(0)
        assertTrue(record.getBoolean("invalid"))
        assertFalse(record.has("sha256"))
    }

    @Test fun imageListAndEncodedWorkAreBounded() {
        val parts = JSONArray(); repeat(6) { parts.put(image()) }
        val capped = diagnostic(payload(message(parts)))
        assertEquals(3, capped.getJSONArray("images").length())
        assertTrue(capped.getBoolean("truncated"))
        val oversized = diagnostic(payload(message(JSONArray().put(image("data:image/png;base64," + "A".repeat(6 * 1024 * 1024 + 1))))))
        assertTrue(oversized.getJSONArray("images").getJSONObject(0).getBoolean("invalid"))
        assertTrue(oversized.getBoolean("truncated"))
    }

    @Test fun capsAggregateEncodedBytesRatherThanOnlyEachImage() {
        val encoded = "A".repeat(4 * 1024 * 1024)
        val result = diagnostic(payload(message(JSONArray().put(image("data:image/png;base64,$encoded")).put(image("data:image/png;base64,$encoded")))))
        assertEquals(2, result.getJSONArray("images").length())
        assertTrue(result.getJSONArray("images").getJSONObject(1).getBoolean("invalid"))
        assertTrue(result.getBoolean("truncated"))
        assertTrue(result.toString().length < 2000)
    }

    @Test fun boundsMetadataAndMessageTraversalAndToleratesWrongTypes() {
        val messages = JSONArray(); repeat(1000) { messages.put(message("x")) }
        val request = JSONObject().put("model", JSONObject().put("key", "private-key")).put("messages", messages)
        val source = JSONObject().put("screen_id", "s".repeat(1000)).put("sha256", "not-a-sha")
            .put("capture_id", JSONArray().put("private-source"))
        val result = diagnostic(request, source)
        assertTrue(result.getBoolean("truncated"))
        assertTrue(result.getInt("text_chars") in 1..64)
        assertTrue(result.getJSONObject("current_source").optString("screen_id").length <= 128)
        assertFalse(result.getJSONObject("current_source").has("sha256"))
        assertFalse(result.toString().contains("private-key"))
        assertFalse(result.toString().contains("private-source"))
        assertEquals(0, diagnostic(JSONObject().put("messages", "bad")).getJSONArray("images").length())
    }

    @Test fun preservesImageMessageOrderAndLeavesThePayloadUntouched() {
        val request = payload(message(JSONArray().put(image("data:image/png;base64,invalid"))), message(JSONArray().put(image())))
        val original = request.toString()
        val result = diagnostic(request)
        assertTrue(result.getJSONArray("images").getJSONObject(0).getBoolean("invalid"))
        assertEquals(sha, result.getJSONArray("images").getJSONObject(1).getString("sha256"))
        assertEquals(original, request.toString())
    }

    @Test fun oversizedContentArraysAreExplicitlyPartialInsteadOfTraversedWithoutLimit() {
        val parts = JSONArray()
        repeat(1000) { parts.put(JSONObject().put("type", "text").put("text", "x")) }
        parts.put(image())
        val result = diagnostic(payload(message(parts)))
        assertTrue(result.getBoolean("truncated"))
        assertTrue(result.getInt("text_chars") in 1..64)
        assertEquals(0, result.getJSONArray("images").length())
    }

    @Test fun recordsOfferedToolsAndLaunchEnumCountWithoutCopyingSchemaValues() {
        val packages = JSONArray().put("private.package.one").put("private.package.two")
        val launch = tool("launch").getJSONObject("function").put("description", "private-description")
            .put("parameters", JSONObject().put("properties", JSONObject().put("package_name", JSONObject().put("enum", packages)))
                .put("private-schema-key", "private-schema-value"))
        val request = payload(message("private-message")).put("tools", JSONArray().put(tool("action"))
            .put(JSONObject().put("type", "function").put("function", launch)).put(tool("plan_task")))
        val original = request.toString()
        val result = diagnostic(request)
        assertEquals(listOf("action", "launch", "plan_task"), offeredNames(result))
        assertEquals(2, result.getInt("launch_package_count"))
        assertFalse(result.toString().contains("private"))
        assertEquals(original, request.toString())
    }

    @Test fun distinguishesAbsentLaunchFromEmptyOrUnconstrainedLaunchSchema() {
        val absent = diagnostic(payload().put("tools", JSONArray().put(tool("navigate"))))
        assertEquals(listOf("navigate"), offeredNames(absent))
        assertEquals(0, absent.getInt("launch_package_count"))
        val emptyLaunch = tool("launch")
        emptyLaunch.getJSONObject("function").put("parameters", JSONObject().put("properties",
            JSONObject().put("package_name", JSONObject().put("enum", JSONArray()))))
        val empty = diagnostic(payload().put("tools", JSONArray().put(emptyLaunch)))
        assertEquals(listOf("launch"), offeredNames(empty))
        assertEquals(0, empty.getInt("launch_package_count"))
        val unconstrained = diagnostic(payload().put("tools", JSONArray().put(tool("launch"))))
        assertTrue(unconstrained.has("launch_package_count"))
        assertTrue(unconstrained.isNull("launch_package_count"))
    }

    @Test fun toolDiagnosticsExcludeUnknownNamesAndDoNotClaimCompletenessAfterTraversalCap() {
        val malformed = JSONArray().put(JSONObject.NULL).put(JSONObject().put("type", "function")
            .put("function", JSONObject().put("name", JSONObject().put("secret", "private-name"))))
            .put(tool("private_secret_name")).put(tool("read_skill_resource"))
        val safe = diagnostic(payload().put("tools", malformed))
        assertEquals(listOf("read_skill_resource"), offeredNames(safe))
        assertFalse(safe.toString().contains("private"))
        assertTrue(safe.getBoolean("truncated"))
        val large = JSONArray(); repeat(1000) { large.put(tool("action")) }; large.put(tool("launch"))
        val capped = diagnostic(payload().put("tools", large))
        assertTrue(capped.getJSONArray("offered_tool_names").length() <= 64)
        assertFalse(offeredNames(capped).contains("launch"))
        assertTrue(capped.getBoolean("truncated"))
        assertTrue(capped.isNull("launch_package_count"))
        val noTools = diagnostic(payload().put("tools", "private-invalid-tools"))
        assertEquals(0, noTools.getJSONArray("offered_tool_names").length())
        assertFalse(noTools.toString().contains("private"))
    }

    private fun tool(name: String) = JSONObject().put("type", "function")
        .put("function", JSONObject().put("name", name))

    private fun offeredNames(diagnostic: JSONObject): List<String> = diagnostic.getJSONArray("offered_tool_names").let { names ->
        (0 until names.length()).map { names.getString(it) }
    }
}

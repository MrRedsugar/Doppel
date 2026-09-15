package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelProviderPolicyTest {
    @Test fun deepseekPrimaryEnablesLowThinkingWithoutUnsupportedForcedToolChoice() {
        val payload = JSONObject().put("max_completion_tokens", 6500).put("enable_thinking", true)
            .put("thinking", JSONObject().put("type", "enabled")).put("reasoning_effort", "high")
            .put("response_format", SplitOutputSchema.format("primary")).put("messages", JSONArray())
        val before = payload.toString()
        val wire = ModelWirePolicy.prepare(payload, ModelProvider.deepseek(), "deepseek-flash", "primary")
        assertEquals("https://api.deepseek.com/beta", ModelProvider.deepseek().baseUrl)
        assertEquals("enabled", wire.getJSONObject("thinking").getString("type"))
        assertEquals("low", wire.getString("reasoning_effort"))
        assertEquals("auto", wire.getString("tool_choice"))
        assertEquals(6500, wire.getInt("max_tokens"))
        assertFalse(wire.has("max_completion_tokens")); assertFalse(wire.has("enable_thinking"))
        assertEquals("deepseek-flash", wire.getString("model"))
        assertTrue(wire.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getBoolean("strict"))
        assertFalse(wire.has("response_format"))
        assertEquals(before, payload.toString())
        assertEquals(ModelSelection("qwen-cn", "qwen3.8-max"), ModelRouting.defaults().enhancement)
    }

    @Test fun deepseekVisionProbeEnablesLowThinkingWithoutInventingATool() {
        val provider = ModelProvider("custom-ds", "DS", "https://api.deepseek.com/beta")
        val wire = ModelWirePolicy.prepare(JSONObject().put("max_tokens", 512), provider, "deepseek-flash", "primary")
        assertEquals(512, wire.getInt("max_tokens"))
        assertEquals("enabled", wire.getJSONObject("thinking").getString("type"))
        assertEquals("low", wire.getString("reasoning_effort"))
        assertFalse(wire.has("tools")); assertFalse(wire.has("tool_choice")); assertFalse(wire.has("response_format"))
    }

    @Test fun deepseekGroundingStaysNonThinkingAndPreservesForcedStrictResult() {
        val payload = JSONObject().put("_doppel_role", "grounding").put("max_completion_tokens", 1800)
            .put("thinking", JSONObject().put("type", "enabled")).put("reasoning_effort", "max")
            .put("enable_thinking", true).put("response_format", SplitOutputSchema.format("grounding", expectedAction = "tap"))
        val wire = ModelWirePolicy.prepare(payload, ModelProvider.deepseek(), "deepseek-flash", "grounding")
        assertEquals("disabled", wire.getJSONObject("thinking").getString("type"))
        assertFalse(wire.has("reasoning_effort")); assertFalse(wire.has("enable_thinking")); assertFalse(wire.has("_doppel_role"))
        assertEquals(1800, wire.getInt("max_tokens"))
        assertEquals("function", wire.getJSONObject("tool_choice").getString("type"))
        assertEquals("doppel_b_tap_v1", wire.getJSONObject("tool_choice").getJSONObject("function").getString("name"))
        assertTrue(wire.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getBoolean("strict"))
    }

    @Test fun deepseekStrictOutputRequiresOfficialBetaPathWithoutSilentlyDowngrading() {
        val payload = JSONObject().put("response_format", SplitOutputSchema.format("primary"))
        for (url in listOf("https://api.deepseek.com", "https://api.deepseek.com/v1")) {
            assertThrows(IllegalArgumentException::class.java) {
                ModelWirePolicy.prepare(payload, ModelProvider.deepseek().copy(baseUrl = url), "deepseek-flash", "primary")
            }
        }
        val otherHost = ModelProvider.deepseek().copy(baseUrl = "https://other.test/beta")
        val wire = ModelWirePolicy.prepare(payload, otherHost, "deepseek-flash", "primary")
        assertEquals("json_schema", wire.getJSONObject("response_format").getString("type"))
        assertFalse(wire.has("tools")); assertFalse(wire.has("thinking"))
    }

    @Test fun deepseekRejectsConflictingOutputLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelWirePolicy.prepare(JSONObject().put("max_tokens", 512).put("max_completion_tokens", 6500),
                ModelProvider.deepseek(), "deepseek-flash", "primary")
        }
    }

    @Test fun normalizesBaseOrCompletionEndpointWithoutDuplicatingPaths() {
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", ModelEndpoint.normalize(" https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions/ "))
        assertEquals("https://example.com/v1", ModelEndpoint.normalize("https://example.com/v1/"))
        assertEquals("http://127.0.0.1:8000/v1", ModelEndpoint.normalize("http://127.0.0.1:8000/v1"))
    }
    @Test fun rejectsRemoteCleartextCredentialsQueriesAndFragments() {
        listOf("http://example.com/v1", "https://user:secret@example.com/v1", "https://example.com/v1?key=secret", "https://example.com/#secret", "file:///tmp/api").forEach {
            assertThrows(IllegalArgumentException::class.java) { ModelEndpoint.normalize(it) }
        }
    }
    @Test fun routesGroundingToPrimaryWhileEnhancementIsDisabled() {
        val primary = ModelSelection("custom", "vision-one")
        val enhanced = ModelSelection("other", "vision-two")
        assertEquals(primary, ModelRouting(primary, false, enhanced).select("grounding"))
        assertEquals(enhanced, ModelRouting(primary, true, enhanced).select("grounding"))
        assertEquals(primary, ModelRouting(primary, true, enhanced).select("primary"))
        assertThrows(IllegalArgumentException::class.java) { ModelRouting(primary, true, enhanced).select("oops") }
    }
    @Test fun qwenRolesUseTheirThinkingPolicyAndNeverSendInternalRole() {
        val original = JSONObject().put("_doppel_role", "grounding").put("model", "old-model").put("messages", JSONArray())
            .put("thinking", JSONObject().put("type", "enabled")).put("reasoning_effort", "high")
        val provider = ModelProvider.qwen()
        val grounding = ModelWirePolicy.prepare(original, provider, "qwen3.8-max", "grounding")
        assertEquals("qwen3.8-max", grounding.getString("model"))
        assertFalse(grounding.getBoolean("enable_thinking"))
        assertFalse(grounding.has("reasoning_effort")); assertFalse(grounding.has("_doppel_role")); assertFalse(grounding.has("thinking"))
        val primary = ModelWirePolicy.prepare(original, provider, "qwen3.8-flash", "primary")
        assertFalse(primary.getBoolean("enable_thinking")); assertFalse(primary.has("reasoning_effort"))
        assertEquals("old-model", original.getString("model")); assertTrue(original.has("_doppel_role"))
    }
    @Test fun strictOutputIsPreservedAndBothOfficialQwenRoutesDisableThinking() {
        val payload = JSONObject().put("response_format", SplitOutputSchema.format("primary")).put("messages", JSONArray()).put("enable_thinking", true)
        for (provider in listOf(ModelProvider.qwen(), ModelProvider("workspace", "workspace", "https://test-workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1", "qwen"))) {
            for ((role, model) in listOf("primary" to "qwen3.8-flash", "grounding" to "qwen3.8-max-0902")) {
                val wire = ModelWirePolicy.prepare(payload, provider, model, role)
                assertFalse(wire.getBoolean("enable_thinking"))
                assertEquals("json_schema", wire.getJSONObject("response_format").getString("type"))
                assertTrue(wire.getJSONObject("response_format").getJSONObject("json_schema").getBoolean("strict"))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { ModelWirePolicy.prepare(payload, ModelProvider.qwen(), "qwen-turbo", "primary") }
        assertTrue(payload.getBoolean("enable_thinking"))
    }
    @Test fun compatibleGatewayDoesNotSilentlyRemoveSchemaOrEnableQwenThinking() {
        val payload = JSONObject().put("response_format", SplitOutputSchema.format("grounding", expectedAction = "tap"))
        val wire = ModelWirePolicy.prepare(payload, ModelProvider("custom", "Custom", "https://example.test/v1"), "custom-vision", "grounding")
        fun structure(value:Any?):Any? = when(value) {
            is JSONObject -> value.keys().asSequence().associateWith {structure(value.get(it))}
            is JSONArray -> (0 until value.length()).map {structure(value.get(it))}
            else -> value
        }
        assertEquals(structure(payload.getJSONObject("response_format")), structure(wire.getJSONObject("response_format")))
        assertFalse(wire.has("enable_thinking"))
    }
    @Test fun compatibleProvidersDoNotReceiveQwenOnlyThinkingParameters() {
        val custom = ModelProvider("custom", "Custom", "https://example.com/v1")
        val payload = JSONObject().put("enable_thinking", true).put("reasoning_effort", "low").put("_doppel_role", "primary")
        val wire = ModelWirePolicy.prepare(payload, custom, "vision", "primary")
        assertFalse(wire.has("enable_thinking")); assertFalse(wire.has("reasoning_effort")); assertFalse(wire.has("_doppel_role"))
        assertFalse(wire.getBoolean("stream"))
    }
    @Test fun visionRequiresCorrectSyntheticImageAnswerAndNetworkIsUnknown() {
        val expected = listOf("RED", "BLUE", "GREEN", "YELLOW", "PURPLE", "ORANGE")
        assertEquals(ModelVision.VERIFIED, ModelVisionProbe.classify(200, "red blue green yellow purple orange", expected))
        assertEquals(ModelVision.UNKNOWN, ModelVisionProbe.classify(200, "I can see the image", expected))
        assertEquals(ModelVision.UNKNOWN, ModelVisionProbe.classify(503, "image is not supported", expected))
        assertEquals(ModelVision.UNKNOWN, ModelVisionProbe.classify(401, "invalid key", expected))
        assertEquals(ModelVision.UNSUPPORTED, ModelVisionProbe.classify(400, "This model does not support image inputs", expected))
        assertEquals(ModelVision.UNKNOWN, ModelVisionProbe.classify(400, "Invalid image URL", expected))
    }
    @Test fun headersRejectInjectionAndTransportOverrides() {
        assertEquals("tenant-one", ModelHeaders.parse("{\"X-Tenant\":\"tenant-one\"}")["X-Tenant"])
        listOf("{\"X-Tenant\":\"secret\\r\\nHost: other\"}", "{\"Host\":\"other\"}", "{\"Content-Length\":\"0\"}", "{\"X-Object\":{}}").forEach {
            assertThrows(IllegalArgumentException::class.java) { ModelHeaders.parse(it) }
        }
    }
    @Test fun changingAnOriginRequiresExplicitReplacementOfAllAuthentication() {
        assertFalse(ModelEndpoint.authenticationMustChange("https://api.example.com/v1", "https://api.example.com/v2"))
        assertTrue(ModelEndpoint.authenticationMustChange("https://api.example.com/v1", "https://new.example.com/v1"))
        assertTrue(ModelEndpoint.authenticationMustChange("https://api.example.com/v1", "https://api.example.com:9443/v1"))
    }
}

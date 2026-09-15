package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectPayloadTest {
    @Test fun `planner uses audited MiMo adaptation and no unsupported parameters`() {
        val payload = DirectPayload.chat(DirectPayload.PLANNER, JSONArray().put(JSONObject().put("role", "user").put("content", "test")), DirectTaskEngine.tools())
        assertEquals("mimo-v2.5-pro", payload.getString("model"))
        assertEquals("disabled", payload.getJSONObject("thinking").getString("type"))
        assertEquals(1600, payload.getInt("max_completion_tokens")); assertFalse(payload.has("max_tokens")); assertFalse(payload.has("parallel_tool_calls"))
    }
    @Test fun `ASR matches official input audio payload`() {
        val payload = DirectPayload.speech("YWJj")
        assertEquals("mimo-v2.5-asr", payload.getString("model"))
        val part = payload.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0)
        assertEquals("input_audio", part.getString("type")); assertEquals("data:audio/wav;base64,YWJj", part.getJSONObject("input_audio").getString("data"))
        assertFalse(payload.has("tools")); assertFalse(payload.has("thinking"))
    }
    @Test fun `tool schema has no model controlled consent or screen generation`() {
        val tools = DirectTaskEngine.tools()
        val props = tools.getJSONObject(0).getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
        assertFalse(props.has("payment_consent_id")); assertFalse(props.has("screen_id")); assertFalse(props.has("approved"))
        val navigation = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }.single { it.getString("name") == "navigate" }
        val actions = navigation.getJSONObject("parameters").getJSONObject("properties").getJSONObject("kind").getJSONArray("enum").toString()
        listOf("recents", "notifications", "quick_settings", "split_screen").forEach { assertTrue(actions.contains(it)) }
    }
    @Test fun `static tool schema remains compatible without an observation`() {
        val tools = DirectTaskEngine.tools()
        val parameters = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }
            .single { it.getString("name") == "action" }.getJSONObject("parameters")
        val props = parameters.getJSONObject("properties")
        assertEquals("string", props.getJSONObject("target").getString("type"))
        assertFalse(props.getJSONObject("target").has("enum"))
        val kinds = props.getJSONObject("kind").getJSONArray("enum")
        val supported = (0 until kinds.length()).map { kinds.getString(it) }
        assertTrue(supported.containsAll(listOf("tap", "long_press", "type", "login_phone", "login_code", "scroll")))
        assertTrue(parameters.getJSONArray("required").toString().contains("target"))
    }
    @Test(expected = IllegalArgumentException::class) fun `invalid WAV rejected before paid provider call`() { DirectPayload.wavDuration(ByteArray(44)) }
    @Test fun `existing speech capture WAV is accepted with exact duration`() {
        val audio = CloudSpeechAudio()
        audio.append(ShortArray(16000) { 500 }, 16000)
        assertEquals(1000, DirectPayload.wavDuration(audio.snapshot().wav))
    }
}

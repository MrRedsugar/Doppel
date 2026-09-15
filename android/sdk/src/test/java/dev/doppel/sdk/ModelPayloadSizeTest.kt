package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelPayloadSizeTest {
    @Test fun recordsOnlyCountsWithoutRetainingTextOrImageUrls() {
        val encoded = "data:image/png;base64,private-image"
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", "private-goal"))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", encoded)))
        val payload = JSONObject().put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", "rules"))
            .put(JSONObject().put("role", "user").put("content", content)))
        val result = ModelRequestDiagnostic.payloadSizeMetadata(payload)
        assertEquals(2, result.getInt("message_count"))
        assertEquals(1, result.getInt("image_count"))
        assertEquals(17, result.getInt("text_chars"))
        assertEquals(encoded.length, result.getInt("inline_image_chars"))
        assertFalse(result.toString().contains("private"))
        assertFalse(result.getBoolean("size_counts_truncated"))
    }

    @Test fun countsAreBoundedAndPartialCountsAreExplicit() {
        val messages = JSONArray()
        repeat(100) { messages.put(JSONObject().put("content", "x")) }
        val result = ModelRequestDiagnostic.payloadSizeMetadata(JSONObject().put("messages", messages))
        assertEquals(100, result.getInt("message_count"))
        assertEquals(32, result.getInt("text_chars"))
        assertTrue(result.getBoolean("size_counts_truncated"))
    }
}

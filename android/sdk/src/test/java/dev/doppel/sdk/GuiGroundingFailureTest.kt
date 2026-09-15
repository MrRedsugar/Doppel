package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GuiGroundingFailureTest {
    @Test fun httpFailurePreservesOnlyStatusWithoutTheResponseOrCredentials() {
        val value = GuiGroundingFailure.from(GuiGroundingHttpException(503))
        assertEquals("http_error", value.getString("code"))
        assertEquals(503, value.getInt("http_status"))
        assertEquals(2, value.length())
    }
    @Test fun networkTimeoutAndInvalidResponseAreDistinguishableWithoutExceptionText() {
        val errors = listOf(java.net.SocketTimeoutException("secret") to "timeout",
            java.net.ConnectException("secret") to "connection_failed",
            org.json.JSONException("secret") to "invalid_request_or_response",
            IllegalArgumentException("secret") to "invalid_request_or_response",
            IllegalStateException("secret") to "local_unavailable")
        for ((error, code) in errors) {
            val value = GuiGroundingFailure.from(error)
            assertEquals(code, value.getString("code"))
            assertFalse(value.toString().contains("secret"))
            assertFalse(value.has("http_status"))
        }
    }
    @Test fun sanitizationCannotCarryBodyCoordinatesOrInventAnHttpStatus() {
        val raw = JSONObject().put("code", "http_error").put("http_status", 503).put("body", "secret").put("x", 42)
        assertEquals(2, requireNotNull(GuiGroundingFailure.sanitize(raw)).length())
        for (bad in listOf<Any>("503", 503.5, 999, JSONObject.NULL)) {
            assertNull(GuiGroundingFailure.sanitize(JSONObject(raw.toString()).put("http_status", bad)))
        }
        assertNull(GuiGroundingFailure.sanitize(raw.put("code", "remote-secret")))
    }
}

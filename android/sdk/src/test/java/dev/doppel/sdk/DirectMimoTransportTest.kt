package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class DirectMimoTransportTest {
    private class FakeConnection : HttpURLConnection(URL("https://example.invalid/")) {
        var response = "{\"choices\":[]}".toByteArray()
        var status = 200
        var failure: Exception? = null
        var bodyReads = 0
        var outputWrites = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getOutputStream(): OutputStream = outputWrites
        override fun getResponseCode() = status
        override fun getInputStream(): InputStream {
            bodyReads++
            failure?.let { throw it }
            return ByteArrayInputStream(response)
        }
        override fun getErrorStream(): InputStream = throw AssertionError("Provider error bodies must not be read or retained")
    }
    private fun payload() = JSONObject().put("model", DirectPayload.PLANNER).put("messages", "private-screen-data")
    private fun trace(model: String = DirectPayload.PLANNER) = DirectProviderTrace(model, { 1000000000L })
    private fun execute(connection: FakeConnection, trace: DirectProviderTrace, payload: JSONObject = payload()): JSONObject {
        var creations = 0
        val transport = DirectMimoTransport { creations++; connection }
        try { return transport.complete(payload, "private-api-key", 45, {}, {}, trace) }
        finally { assertEquals(1, creations) }
    }
    @Test fun successfulResponseRetainsOnlyFiveSafeFieldsAndDisconnects() {
        val connection = FakeConnection(); val trace = trace()
        assertTrue(execute(connection, trace).has("choices"))
        assertEquals("complete", trace.snapshot().getString("stage"))
        assertEquals("none", trace.snapshot().getString("error_class"))
        assertEquals(200, trace.snapshot().getInt("http_status"))
        assertEquals(setOf("model", "stage", "http_status", "elapsed_ms", "error_class"), trace.snapshot().keys().asSequence().toSet())
        assertTrue(connection.disconnected)
        assertFalse(trace.snapshot().toString().contains("private"))
    }
    @Test fun malformedJsonIsResponseParseFailureNotConnectionInterruption() {
        val connection = FakeConnection().apply { response = "not-json private-response-content".toByteArray() }
        val trace = trace()
        val error = assertThrows(IllegalStateException::class.java) { execute(connection, trace) }
        assertEquals("response_parse", trace.snapshot().getString("stage"))
        assertEquals("JSONException", trace.snapshot().getString("error_class"))
        assertTrue(error.message!!.contains("解析"))
        assertFalse(error.message!!.contains("private")); assertFalse(trace.snapshot().toString().contains("private"))
    }
    @Test fun readTimeoutPreservesActualStageAndClassWithoutExceptionText() {
        val connection = FakeConnection().apply { failure = SocketTimeoutException("private-api-key private-screen-data") }
        val trace = trace()
        assertThrows(IllegalStateException::class.java) { execute(connection, trace) }
        assertEquals("response_body", trace.snapshot().getString("stage"))
        assertEquals("SocketTimeoutException", trace.snapshot().getString("error_class"))
        assertEquals(200, trace.snapshot().getInt("http_status"))
        assertFalse(trace.snapshot().toString().contains("private"))
    }
    @Test fun serverRejectionNeverReadsProviderBodyOrRetries() {
        val connection = FakeConnection().apply { status = 429 }
        val trace = trace()
        assertThrows(IllegalStateException::class.java) { execute(connection, trace) }
        assertEquals("response_headers", trace.snapshot().getString("stage"))
        assertEquals("DirectHttpStatusException", trace.snapshot().getString("error_class"))
        assertEquals(429, trace.snapshot().getInt("http_status")); assertEquals(0, connection.bodyReads)
    }
    @Test fun untrustedModelAndCustomExceptionNameCannotBecomeDiagnosticContent() {
        val trace = trace("private-screen-data")
        trace.fail(object : Exception("private-api-key") {})
        val safe = trace.snapshot()
        assertEquals("unknown", safe.getString("model"))
        assertEquals("OtherException", safe.getString("error_class"))
        assertFalse(safe.toString().contains("private"))
    }
    @Test fun oversizedResponseIsBoundedAndIdentifiedAsResponseBodyFailure() {
        val connection = FakeConnection().apply { response = ByteArray(262145) { 'x'.code.toByte() } }
        val trace = trace()
        assertThrows(IllegalStateException::class.java) { execute(connection, trace) }
        assertEquals("response_body", trace.snapshot().getString("stage"))
        assertEquals("DirectResponseLimitException", trace.snapshot().getString("error_class"))
        assertTrue(connection.disconnected)
    }
}

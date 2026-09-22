package dev.doppel.sdk.cloud

import org.junit.Assert.*
import org.junit.Test

class CloudSessionTest {
    @Test fun credentialsStayOffUrlsLogsAndPublicPlaintextConnections() {
        val token = "synthetic-secret"
        fun session(url: String, debug: Boolean = false) = CloudSession(url, "account", "session", token, debug)
        assertEquals("https://example.invalid/api", session("https://example.invalid/api/").baseUrl)
        assertFalse(session("https://example.invalid").toString().contains(token))
        for (url in listOf("http://example.invalid", "http://127.0.0.1", "https://user:pass@example.invalid",
            "https://example.invalid?token=secret", "https://example.invalid#secret")) {
            assertTrue(url, runCatching { session(url) }.isFailure)
        }
        assertTrue(runCatching { session("http://example.invalid", true) }.isFailure)
        assertTrue(runCatching { session("http://127.0.0.1.example.invalid", true) }.isFailure)
        assertEquals("http://10.0.2.2:8765", session("http://10.0.2.2:8765/", true).baseUrl)
        assertTrue(runCatching { CloudSession("https://example.invalid", "a", "s", "token\r\nInjected: true") }.isFailure)
    }
}

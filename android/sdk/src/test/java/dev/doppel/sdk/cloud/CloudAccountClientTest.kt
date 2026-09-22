package dev.doppel.sdk.cloud

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class CloudAccountClientTest {
    @Test fun passwordLoginBearerAndRedirectRejection() {
        val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val leaks = AtomicInteger()
        val loginPassword = "  " + "😀".repeat(10)
        val newPassword = "😀".repeat(126) + "  "
        val sessionResponse = AtomicReference(401 to """{"code":"session_revoked","message":"Revoked"}""")
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        val failure = AtomicReference<Throwable?>()
        val listener = thread(isDaemon = true, name = "cloud-http-test") {
            while (!server.isClosed) try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) {
                        val byte = input.read(); check(byte >= 0 && header.length < 8192); header.append(byte.toChar())
                    }
                    val lines = header.lines()
                    val path = lines.first().split(' ')[1]
                    fun field(name: String) = lines.firstOrNull { it.startsWith("$name:", true) }?.substringAfter(':')?.trim()
                    requests.add(path to field("Authorization"))
                    val body = input.readNBytes(field("Content-Length")?.toInt() ?: 0).toString(Charsets.UTF_8)
                    val session = sessionResponse.get()
                    val response = when (path) {
                "/v1/auth/login" -> {
                    val login = JSONObject(body)
                    assertEquals("phone", login.getString("device_kind"))
                    assertEquals(loginPassword, login.getString("password"))
                    """{"account_id":"account","session_id":"session","access_token":"synthetic-token"}"""
                }
                "/v1/auth/password" -> {
                    assertEquals(newPassword, JSONObject(body).getString("new_password"))
                    """{"ok":true}"""
                }
                "/v1/session" -> session.second
                "/v1/auth/logout" -> "{}"
                else -> { leaks.incrementAndGet(); "{}" }
            }.toByteArray(Charsets.UTF_8)
                    val status = when (path) { "/v1/session" -> session.first; "/v1/auth/logout" -> 302; else -> 200 }
                    val redirect = if (path == "/v1/auth/logout") "Location: http://127.0.0.1:${server.localPort}/leak\r\n" else ""
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status Test\r\n${redirect}Content-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(response); flush()
                    }
                }
            } catch (error: Throwable) { if (!server.isClosed) failure.compareAndSet(null, error) }
        }
        try {
            val client = CloudAccountClient(true)
            val base = "http://127.0.0.1:${server.localPort}"
            for (invalid in listOf("x".repeat(11), "😀".repeat(129))) {
                assertTrue(runCatching { client.login(base, "test-account", invalid) }.exceptionOrNull() is IllegalArgumentException)
            }
            assertTrue(requests.isEmpty())
            val session = client.login(base, "test-account", loginPassword)
            assertEquals("synthetic-token", session.token)
            client.changePassword(session, loginPassword, newPassword)
            for (invalid in listOf("x".repeat(11), "😀".repeat(129))) {
                assertTrue(runCatching { client.changePassword(session, loginPassword, invalid) }.exceptionOrNull() is IllegalArgumentException)
                assertTrue(runCatching { client.changePassword(session, invalid, newPassword) }.exceptionOrNull() is IllegalArgumentException)
            }
            assertEquals(2, requests.size)
            val revoked = runCatching { client.readSession(session) }.exceptionOrNull() as CloudHttpException
            assertEquals(401, revoked.status)
            assertEquals("session_revoked", revoked.code)
            sessionResponse.set(200 to """{"account_id":"other","session_id":"session","device_kind":"phone"}""")
            assertEquals("session_mismatch", (runCatching { client.readSession(session) }.exceptionOrNull() as CloudHttpException).code)
            sessionResponse.set(200 to """{"account_id":"account","session_id":"session","device_kind":"phone"}""")
            assertEquals("account", client.readSession(session).getString("account_id"))
            sessionResponse.set(200 to "{}{}")
            assertEquals("invalid_response", (runCatching { client.readSession(session) }.exceptionOrNull() as CloudHttpException).code)
            sessionResponse.set(200 to " ".repeat(65537))
            assertEquals("invalid_response", (runCatching { client.readSession(session) }.exceptionOrNull() as CloudHttpException).code)
            val redirect = runCatching { client.logout(session) }.exceptionOrNull() as CloudHttpException
            assertEquals("redirect_rejected", redirect.code)
            assertEquals(0, leaks.get())
            assertNull(requests.first().second)
            assertTrue(requests.drop(1).all { it.second == "Bearer synthetic-token" })
        } finally { server.close(); listener.join(5000); failure.get()?.let { throw it } }
    }
}

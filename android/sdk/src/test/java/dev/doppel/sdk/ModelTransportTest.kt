package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ModelTransportTest {
    @Test fun sendsConfiguredAuthAndJsonWithCancellationConnectionBeforeSend() {
        val server = FixtureServer()
        val auth = AtomicReference<String>(); val header = AtomicReference<String>(); val body = AtomicReference<String>()
        server.createContext("/v1/chat/completions") { exchange ->
            auth.set(exchange.headers["authorization"]); header.set(exchange.headers["x-tenant"])
            body.set(exchange.body)
            val response = "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}".toByteArray()
            exchange.reply(200, response)
        }
        server.start()
        try {
            var callback = false
            val provider = ModelProvider("test", "Fixture", "http://127.0.0.1:${server.port}/v1")
            val response = ModelTransport().request(provider, ModelSecret("synthetic-key", mapOf("X-Tenant" to "test-tenant")), "/chat/completions",
                JSONObject().put("model", "fixture"), onConnection = { callback = true }, beforeSend = { assertTrue(callback) })
            assertEquals("Bearer synthetic-key", auth.get()); assertEquals("test-tenant", header.get())
            assertEquals("fixture", JSONObject(body.get()).getString("model"))
            assertEquals("OK", response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
            val timing = response.getJSONObject("_doppel_transport")
            val phases = listOf("prepare_ms", "dns_ms", "open_output_ms", "upload_ms", "response_headers_ms", "response_body_ms", "parse_ms")
            assertEquals((phases + "total_ms").toSet(), timing.keys().asSequence().toSet())
            phases.forEach { assertTrue(timing.getLong(it) >= 0) }
            assertTrue(timing.getLong("total_ms") >= phases.sumOf { timing.getLong(it) })
        } finally { server.close() }
    }
    @Test fun refusesRedirectWithoutForwardingSecretsOrIncludingErrorBody() {
        val server = FixtureServer()
        val forwarded = AtomicInteger()
        server.createContext("/v1/models") { exchange ->
            val location = "http://127.0.0.1:${server.port}/leak"
            val body = "synthetic-private-error-key".toByteArray(); exchange.reply(302, body, mapOf("Location" to location))
        }
        server.createContext("/leak") { exchange -> forwarded.incrementAndGet(); exchange.reply(204, byteArrayOf()) }
        server.start()
        try {
            val failure = assertThrows(ModelHttpFailure::class.java) {
                ModelTransport().request(ModelProvider("test", "Fixture", "http://127.0.0.1:${server.port}/v1"), ModelSecret("synthetic-key", emptyMap()), "/models")
            }
            assertEquals(302, failure.status); assertEquals(0, forwarded.get())
            assertFalse(failure.message.orEmpty().contains("synthetic"))
        } finally { server.close() }
    }
    @Test fun interruptedRequestDoesNotReachServer() {
        Thread.currentThread().interrupt()
        try {
            assertThrows(IllegalStateException::class.java) {
                ModelTransport().request(ModelProvider("test", "Fixture", "http://127.0.0.1:1/v1"), ModelSecret("synthetic-key", emptyMap()), "/models")
            }
        } finally { Thread.interrupted() }
    }
    @Test fun freeTierOnly403ExplainsQuotaRestrictionWithoutLeakingProviderBody() {
        for (fields in listOf(
            "\"code\":\"AllocationQuota.FreeTierOnly\"",
            "\"type\":\"AllocationQuota.FreeTierOnly\"",
            "\"code\":\"AllocationQuota.FreeTierOnly\",\"type\":\"AllocationQuota.FreeTierOnly\""
        )) {
            val failure = requestFailure(403, "{\"error\":{$fields,\"message\":\"synthetic-private-detail\",\"param\":null},\"request_id\":\"synthetic-private-request\"}")
            assertEquals(403, failure.status)
            assertTrue(failure.message.orEmpty().contains("免费额度"))
            assertTrue(failure.message.orEmpty().contains("仅免费"))
            assertFalse(failure.message.orEmpty().contains("认证失败"))
            assertFalse(failure.message.orEmpty().contains("synthetic"))
            assertFalse(failure.unsupportedVision)
        }
    }
    @Test fun unrecognizedOrUnstructured403KeepsSafeAuthenticationMessage() {
        for (body in listOf(
            "{\"error\":{\"code\":\"synthetic-private-code\",\"message\":\"AllocationQuota.FreeTierOnly synthetic-private-detail\"}}",
            "{\"error\":{\"code\":\"AllocationQuota.FreeTierOnly.synthetic-private-detail\"}}",
            "{\"error\":{\"code\":null,\"type\":403,\"message\":\"synthetic-private-detail\"}}",
            "{\"message\":\"AllocationQuota.FreeTierOnly synthetic-private-detail\"}",
            "synthetic-private-detail <html>AllocationQuota.FreeTierOnly</html>"
        )) {
            val failure = requestFailure(403, body)
            assertEquals(403, failure.status)
            assertTrue(failure.message.orEmpty().contains("认证失败"))
            assertFalse(failure.message.orEmpty().contains("synthetic"))
            assertFalse(failure.unsupportedVision)
        }
    }
    @Test fun freeTierCodeDoesNotOverride401AuthenticationFailure() {
        val failure = requestFailure(401, "{\"error\":{\"code\":\"AllocationQuota.FreeTierOnly\",\"message\":\"synthetic-private-detail\"}}")
        assertEquals(401, failure.status)
        assertTrue(failure.message.orEmpty().contains("认证失败"))
        assertFalse(failure.message.orEmpty().contains("synthetic"))
    }
    private fun requestFailure(status: Int, body: String): ModelHttpFailure {
        val server = FixtureServer()
        server.createContext("/v1/models") { it.reply(status, body.toByteArray(Charsets.UTF_8)) }
        server.start()
        return try {
            assertThrows(ModelHttpFailure::class.java) {
                ModelTransport().request(ModelProvider("test", "Fixture", "http://127.0.0.1:${server.port}/v1"),
                    ModelSecret("synthetic-key", emptyMap()), "/models")
            }
        } finally { server.close() }
    }
    @Test fun syntheticPngActuallyContainsTheClaimedColors() {
        val challenge = ModelSyntheticImage.create()
        val png = java.util.Base64.getDecoder().decode(challenge.dataUrl.substringAfter(','))
        val input = java.io.DataInputStream(png.inputStream())
        val signature = ByteArray(8).also(input::readFully)
        assertArrayEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10), signature)
        val compressed = java.io.ByteArrayOutputStream()
        var width = 0; var height = 0
        while (input.available() > 0) {
            val length = input.readInt()
            val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
            val data = ByteArray(length).also(input::readFully)
            val expectedCrc = input.readInt()
            val crc = java.util.zip.CRC32().apply { update(type.toByteArray(Charsets.US_ASCII)); update(data) }
            assertEquals(expectedCrc, crc.value.toInt())
            if (type == "IHDR") java.io.DataInputStream(data.inputStream()).use { width = it.readInt(); height = it.readInt() }
            if (type == "IDAT") compressed.write(data)
        }
        assertEquals(384, width); assertEquals(192, height)
        val pixels = java.util.zip.InflaterInputStream(compressed.toByteArray().inputStream()).use { it.readBytes() }
        assertEquals(height * (1 + width * 3), pixels.size)
        val colors = mapOf(0xE32020 to "RED", 0x204FE3 to "BLUE", 0x16AE39 to "GREEN", 0xFFDF16 to "YELLOW", 0x9D20CA to "PURPLE", 0xFF8916 to "ORANGE")
        val observed = (0..5).map {
            val index = 96 * (1 + width * 3) + 1 + (it * 64 + 32) * 3
            val color = ((pixels[index].toInt() and 255) shl 16) or ((pixels[index + 1].toInt() and 255) shl 8) or (pixels[index + 2].toInt() and 255)
            colors[color]
        }
        assertEquals(challenge.expected, observed)
    }
}

private class FixtureServer : AutoCloseable {
    private val server = java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
    val port get() = server.localPort
    private val handlers = mutableMapOf<String, (Exchange) -> Unit>()
    private var worker: Thread? = null
    fun createContext(path: String, handler: (Exchange) -> Unit) { handlers[path] = handler }
    fun start() {
        worker = kotlin.concurrent.thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.net.SocketException) { break }
                socket.use {
                    val input = java.io.BufferedInputStream(socket.getInputStream())
                    fun line(): String {
                        val text = StringBuilder()
                        while (true) { val byte = input.read(); if (byte < 0 || byte == 10) break; if (byte != 13) text.append(byte.toChar()) }
                        return text.toString()
                    }
                    val path = line().split(' ').getOrElse(1) { "/" }
                    val headers = mutableMapOf<String, String>()
                    while (true) { val row = line(); if (row.isEmpty()) break; headers[row.substringBefore(':').lowercase()] = row.substringAfter(':').trim() }
                    val size = headers["content-length"]?.toIntOrNull() ?: 0
                    val bytes = ByteArray(size)
                    java.io.DataInputStream(input).readFully(bytes)
                    val exchange = Exchange(headers, bytes.toString(Charsets.UTF_8), socket.getOutputStream())
                    handlers[path]?.invoke(exchange) ?: exchange.reply(404, byteArrayOf())
                }
            }
        }
    }
    override fun close() { server.close(); worker?.join(2000) }
    class Exchange(val headers: Map<String, String>, val body: String, private val output: java.io.OutputStream) {
        fun reply(status: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) {
            val prefix = "HTTP/1.1 $status Fixture\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n" +
                headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n"
            output.write(prefix.toByteArray(Charsets.US_ASCII)); output.write(body); output.flush()
        }
    }
}

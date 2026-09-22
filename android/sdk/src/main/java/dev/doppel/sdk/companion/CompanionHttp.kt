package dev.doppel.sdk.companion

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.net.InetAddress
import java.util.Locale

/** One bounded HTTP/1.1 request per TLS connection; no streaming, compression or pipelining. */
internal class CompanionHttp(private val router: CompanionRouter) {
    fun serve(input: InputStream, output: OutputStream, peer: InetAddress? = null) {
        val response = try { read(input, peer) } catch (error: CompanionProtocolException) {
            CompanionResponse(error.statusCode, JSONObject().put("error", JSONObject()
                .put("code", error.code).put("message", "请求格式无效").put("retryable", false)))
        }
        val bytes = response.body.toString().toByteArray(Charsets.UTF_8)
        val reason = when (response.statusCode) {
            200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 400 -> "Bad Request"
            401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            409 -> "Conflict"; 410 -> "Gone"; 411 -> "Length Required"; 413 -> "Content Too Large"
            415 -> "Unsupported Media Type"; 422 -> "Unprocessable Content"; 429 -> "Too Many Requests"
            503 -> "Service Unavailable"; 507 -> "Insufficient Storage"; else -> "Internal Server Error"
        }
        val retry = if (response.statusCode == 429) "Retry-After: 60\r\n" else ""
        output.write(("HTTP/1.1 ${response.statusCode} $reason\r\nContent-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n$retry\r\n").toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun read(input: InputStream, peer: InetAddress?): CompanionResponse {
        var headerBytes = 0
        fun line(): String {
            val result = StringBuilder()
            while (true) {
                val value = input.read()
                if (++headerBytes > 16_384 || result.length > 8192) fail(413)
                if (value == 13) {
                    if (input.read() != 10) fail(400)
                    headerBytes++
                    return result.toString()
                }
                if (value !in 32..126) fail(400)
                result.append(value.toChar())
            }
        }
        val request = line().split(' ')
        if (request.size != 3 || request[2] != "HTTP/1.1") fail(400)
        val headers = linkedMapOf<String, String>()
        while (true) {
            val row = line()
            if (row.isEmpty()) break
            val colon = row.indexOf(':')
            if (colon <= 0) fail(400)
            val key = row.substring(0, colon).lowercase(Locale.ROOT)
            if (!key.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) fail(400)
            if (headers.put(key, row.substring(colon + 1).trim()) != null) fail(400)
        }
        if (headers["host"].isNullOrBlank() || "transfer-encoding" in headers || "content-encoding" in headers || "expect" in headers) fail(400)
        val rawLength = headers["content-length"]
        if (rawLength != null && (rawLength.isEmpty() || !rawLength.all { it in '0'..'9' })) fail(400)
        val size = rawLength?.toIntOrNull() ?: if (rawLength != null) fail(413) else 0
        if (size > 65_536) fail(413)
        if (request[0] == "POST") {
            if (rawLength == null) fail(411)
            if (headers["content-type"]?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) != "application/json") fail(415)
        } else if (size != 0) fail(400)
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(bytes, offset, size - offset)
            if (count <= 0) fail(400)
            offset += count
        }
        val body = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { fail(400) }
        return router.handle(request[0], request[1], headers["authorization"], if (request[0] == "POST" && body.isEmpty()) "{}" else body, peer)
    }

    private fun fail(status: Int): Nothing = throw CompanionProtocolException(status, "invalid_request")
}

package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

internal class ModelHttpFailure(val status: Int, val unsupportedVision: Boolean, responseBody: String = "") :
    IOException(safeMessage(status, responseBody)) {
    companion object {
        private fun safeMessage(status: Int, body: String): String {
            // Only exact structured codes select fixed text; never expose provider messages or unknown codes.
            val freeTierOnly = if (status == 403) try {
                val error = JSONObject(body).optJSONObject("error")
                listOf("code", "type").any { error?.opt(it) == "AllocationQuota.FreeTierOnly" }
            } catch (_: Exception) { false } else false
            if (freeTierOnly) return "平台免费额度已用完，当前为“仅免费”模式。请到平台控制台检查额度；如需付费调用，请自行关闭“仅免费”模式并确认账户余额"
            return when (status) {
                401, 403 -> "平台认证失败，请检查 API Key、请求头和账户权限"
                404 -> "平台接口或模型不存在，请检查 API 地址和模型名称"
                429 -> "平台额度不足或请求过于频繁，请检查账户后重试"
                in 300..399 -> "API 地址发生重定向，请直接填写最终 HTTPS 地址"
                in 500..599 -> "模型平台暂时不可用，请稍后重试"
                else -> "模型平台拒绝请求（HTTP $status），请检查模型和连接配置"
            }
        }
    }
}

/** Transport owns no logs and never follows redirects with credentials. */
internal class ModelTransport {
    fun request(provider: ModelProvider, secret: ModelSecret, path: String, payload: JSONObject? = null,
                timeoutMs: Int = 90000, onConnection: (HttpURLConnection) -> Unit = {}, beforeSend: () -> Unit = {}): JSONObject {
        check(!Thread.currentThread().isInterrupted) { "模型请求已取消" }
        val address = ModelEndpoint.normalize(provider.baseUrl) + path
        val uri = java.net.URI(address)
        val connection = uri.toURL().openConnection() as HttpURLConnection
        val transportStarted = System.nanoTime()
        var checkpoint = transportStarted
        val timing = JSONObject()
        fun mark(name: String) {
            val current = System.nanoTime()
            timing.put(name, (current - checkpoint) / 1_000_000)
            checkpoint = current
        }
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000; connection.readTimeout = timeoutMs
            connection.requestMethod = if (payload == null) "GET" else "POST"
            connection.setRequestProperty("Accept", "application/json")
            if (secret.apiKey.isNotBlank()) {
                if (provider.preset == "mimo" && uri.host == "api.xiaomimimo.com") connection.setRequestProperty("api-key", secret.apiKey)
                else connection.setRequestProperty("Authorization", "Bearer ${secret.apiKey}")
            }
            secret.headers.forEach(connection::setRequestProperty)
            onConnection(connection)
            beforeSend()
            check(!Thread.currentThread().isInterrupted) { "模型请求已取消" }
            if (payload != null) {
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val bytes = payload.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                mark("prepare_ms")
                // Populate the same JVM DNS cache used by URLConnection so a
                // cold DNS lookup is measured separately from socket/TLS setup.
                java.net.InetAddress.getAllByName(uri.host)
                mark("dns_ms")
                val stream = connection.outputStream
                // Stream acquisition still includes platform routing/TCP/TLS.
                // Do not attribute this whole interval to inference.
                mark("open_output_ms")
                stream.use { it.write(bytes) }
                mark("upload_ms")
            }
            val status = connection.responseCode
            // Non-streaming: includes server queue/inference and network wait.
            mark("response_headers_ms")
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val output = ByteArrayOutputStream()
            input?.use { source ->
                val buffer = ByteArray(8192)
                while (true) {
                    check(!Thread.currentThread().isInterrupted) { "模型请求已取消" }
                    val count = source.read(buffer); if (count < 0) break
                    check(output.size() + count <= 4 * 1024 * 1024) { "平台响应过大，请缩小输出限制" }
                    output.write(buffer, 0, count)
                }
            }
            mark("response_body_ms")
            val text = output.toString("UTF-8")
            if (status !in 200..299) throw ModelHttpFailure(status,
                ModelVisionProbe.classify(status, text, emptyList()) == ModelVision.UNSUPPORTED, text)
            val response = try { JSONObject(text) } catch (_: Exception) { throw IOException("平台未返回有效 JSON，请检查兼容接口地址") }
            mark("parse_ms")
            timing.put("total_ms", (System.nanoTime() - transportStarted) / 1_000_000)
            return response.put("_doppel_transport", timing)
        } catch (failure: ModelHttpFailure) { throw failure
        } catch (_: SocketTimeoutException) { throw IOException("模型请求超时，视觉能力仍待验证；请检查网络后重试")
        } catch (failure: IllegalStateException) { throw failure
        } catch (_: IOException) { throw IOException("无法连接模型平台，请检查网络与 API 地址后重试")
        } finally { connection.disconnect() }
    }
}

/** Pure generated PNG: no screen, account, photo, or device data enters a capability check. */
internal object ModelSyntheticImage {
    data class Challenge(val dataUrl: String, val expected: List<String>)
    fun create(): Challenge {
        val palette = listOf("RED" to 0xE32020, "BLUE" to 0x204FE3, "GREEN" to 0x16AE39,
            "YELLOW" to 0xFFDF16, "PURPLE" to 0x9D20CA, "ORANGE" to 0xFF8916)
        val random = SecureRandom()
        val bands = List(6) { palette[random.nextInt(palette.size)] }
        val bytes = ByteArrayOutputStream()
        val png = DataOutputStream(bytes)
        png.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            val tag = type.toByteArray(Charsets.US_ASCII)
            png.writeInt(data.size); png.write(tag); png.write(data)
            val checksum = CRC32().apply { update(tag); update(data) }; png.writeInt(checksum.value.toInt())
        }
        val header = ByteArrayOutputStream().also { output -> DataOutputStream(output).use { it.writeInt(384); it.writeInt(192); it.write(byteArrayOf(8, 2, 0, 0, 0)) } }.toByteArray()
        chunk("IHDR", header)
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { pixels ->
            repeat(192) {
                pixels.write(0)
                repeat(384) { x ->
                    val color = if (x % 64 < 3 || x % 64 > 60) 0xFFFFFF else bands[x / 64].second
                    pixels.write(color shr 16 and 255); pixels.write(color shr 8 and 255); pixels.write(color and 255)
                }
            }
        }
        chunk("IDAT", compressed.toByteArray()); chunk("IEND", byteArrayOf())
        return Challenge("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray()), bands.map { it.first })
    }
}

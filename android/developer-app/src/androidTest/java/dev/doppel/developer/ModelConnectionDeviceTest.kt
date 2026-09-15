package dev.doppel.developer

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Public endpoint diagnostics only: no provider settings, credentials, model calls or device input. */
class ModelConnectionDeviceTest {
    companion object {
        private const val HOST = "dashscope.aliyuncs.com"
        private const val PORT = 443
        private const val MODELS_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/models"
        private const val DIRECT_CONNECT_MS = 1500
        private const val TLS_TIMEOUT_MS = 3000
    }

    @Test fun comparePublicAddressAndHttpConnectionTiming() {
        assumeTrue("Opt in with -e model_connection true",
            InstrumentationRegistry.getArguments().getString("model_connection") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val started = SystemClock.elapsedRealtime()
        // A distinct folder protects earlier evidence even when an instrumented run is repeated.
        val folder = File(instrumentation.targetContext.filesDir,
            "model-connection-evidence/${System.currentTimeMillis()}").apply { check(mkdirs()) }
        val report = JSONObject().put("host", HOST).put("port", PORT)
            .put("started_at", System.currentTimeMillis()).put("paid_model_requests", 0)
            .put("credentials_used", false).put("account_data_requested", false)
            .put("device_input_actions", 0).put("response_bodies_read", false)
            .put("http_method", "GET").put("http_url", MODELS_URL)
            .put("ordering", "DNS once, HTTP 15000ms, HTTP 1500ms, then each public DNS address once in resolver order")
            .put("limitations", JSONArray(listOf(
                "The diagnostic may warm DNS/TLS caches; every stage and request order is retained.",
                "HTTP Connection: close prevents reuse between the two probes; application transport is unchanged.",
                "HTTP timeout is per platform connection attempt, not necessarily a whole-request deadline.",
                "Direct sockets test each address explicitly; they do not reveal every internal HTTP fallback attempt.",
                "A completed diagnostic is not proof every address is reachable or the game task succeeded."
            )))
        val httpRows = JSONArray(); val addressRows = JSONArray()
        report.put("http_probes", httpRows).put("address_probes", addressRows)
        fun save() = File(folder, "report.json").writeText(report.toString(2))
        fun status(message: String) = instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nModel connection: $message\n")
        })
        save()
        try {
            val dnsAt = SystemClock.elapsedRealtime()
            val addresses = try {
                InetAddress.getAllByName(HOST).toList().also {
                    report.put("dns", JSONObject().put("elapsed_ms", elapsed(dnsAt)).put("status", "resolved")
                        .put("addresses", JSONArray(it.mapIndexed { index, address -> addressInfo(index, address) })))
                }
            } catch (failure: Exception) {
                report.put("dns", JSONObject().put("elapsed_ms", elapsed(dnsAt)).put("status", "failed")
                    .put("error_class", failure.javaClass.simpleName))
                emptyList()
            }
            save(); status("DNS recorded; starting unauthenticated HTTP probes")
            for (timeout in listOf(15000, 1500)) {
                httpRows.put(probeHttp(timeout)); save()
                status("HTTP connect timeout ${timeout}ms recorded")
            }
            // Default system trust managers; no custom trust store or permissive hostname verifier.
            val tls = SSLContext.getInstance("TLS").apply { init(null, null, null) }
            addresses.forEachIndexed { index, address ->
                addressRows.put(probeAddress(index, address, tls)); save()
                status("address ${index + 1}/${addresses.size} ${family(address)} recorded")
            }
            report.put("diagnostic_completed", true)
        } catch (failure: Exception) {
            report.put("diagnostic_completed", false).put("error_class", failure.javaClass.simpleName)
            throw failure
        } finally {
            report.put("elapsed_ms", elapsed(started)).put("finished_at", System.currentTimeMillis())
            save(); status("evidence: ${folder.absolutePath}/report.json")
        }
    }

    private fun probeHttp(connectTimeout: Int): JSONObject {
        val row = JSONObject().put("connect_timeout_ms", connectTimeout).put("read_timeout_ms", 5000)
            .put("authentication", "none").put("request_body_bytes", 0).put("connection_close", true)
        val started = SystemClock.elapsedRealtime()
        var stage = "open_connection"
        var connection: HttpsURLConnection? = null
        try {
            val opened = URL(MODELS_URL).openConnection() as HttpsURLConnection
            connection = opened
            opened.instanceFollowRedirects = false
            opened.connectTimeout = connectTimeout; opened.readTimeout = 5000
            opened.requestMethod = "GET"; opened.doOutput = false; opened.useCaches = false
            opened.setRequestProperty("Accept", "application/json")
            opened.setRequestProperty("Connection", "close")
            // HttpsURLConnection retains platform trust and hostname verification.
            stage = "connect"
            val connectedAt = SystemClock.elapsedRealtime()
            opened.connect()
            row.put("connect_ms", elapsed(connectedAt))
            stage = "response_headers"
            val headersAt = SystemClock.elapsedRealtime()
            val code = opened.responseCode
            row.put("response_headers_ms", elapsed(headersAt)).put("http_status", code)
                .put("tls_peer_verified", true).put("status", "http_response")
                .put("cipher_suite", opened.cipherSuite)
            // 401/403 are expected without credentials and still prove a verified TLS/HTTP response.
            // Never read an API response body, token, account, model list, or usage data.
        } catch (failure: Exception) {
            row.put("status", "failed").put("failure_stage", stage)
                .put("error_class", failure.javaClass.simpleName)
                .put("cause_class", failure.cause?.javaClass?.simpleName ?: JSONObject.NULL)
        } finally {
            connection?.disconnect()
            row.put("elapsed_ms", elapsed(started))
        }
        return row
    }

    private fun probeAddress(index: Int, address: InetAddress, tls: SSLContext): JSONObject {
        val row = addressInfo(index, address).put("connect_timeout_ms", DIRECT_CONNECT_MS)
            .put("tls_timeout_ms", TLS_TIMEOUT_MS)
        if (!isPublic(address)) return row.put("status", "skipped_non_public_address")
        val started = SystemClock.elapsedRealtime()
        var stage = "tcp_connect"
        var stageStarted = started
        var raw: Socket? = null
        var secure: SSLSocket? = null
        try {
            val socket = Socket(); raw = socket
            socket.soTimeout = TLS_TIMEOUT_MS
            socket.connect(InetSocketAddress(address, PORT), DIRECT_CONNECT_MS)
            row.put("tcp_connect_ms", elapsed(stageStarted))
            stage = "tls_handshake"; stageStarted = SystemClock.elapsedRealtime()
            // HOST, rather than the IP literal, supplies the peer identity and TLS SNI.
            val ssl = tls.socketFactory.createSocket(socket, HOST, PORT, true) as SSLSocket
            secure = ssl; ssl.useClientMode = true; ssl.soTimeout = TLS_TIMEOUT_MS
            val parameters = ssl.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            parameters.serverNames = listOf(SNIHostName(HOST))
            ssl.sslParameters = parameters
            ssl.startHandshake()
            row.put("tls_handshake_ms", elapsed(stageStarted)).put("status", "tls_connected")
                .put("hostname_verification", "HTTPS").put("sni", HOST)
                .put("tls_protocol", ssl.session.protocol).put("cipher_suite", ssl.session.cipherSuite)
            // Deliberately send no HTTP request through the direct per-address socket.
        } catch (failure: Exception) {
            row.put("status", "failed").put("failure_stage", stage)
                .put("failed_stage_elapsed_ms", elapsed(stageStarted))
                .put("error_class", failure.javaClass.simpleName)
                .put("cause_class", failure.cause?.javaClass?.simpleName ?: JSONObject.NULL)
        } finally {
            runCatching { secure?.close() }; runCatching { raw?.close() }
            row.put("elapsed_ms", elapsed(started))
        }
        return row
    }

    private fun elapsed(started: Long) = SystemClock.elapsedRealtime() - started
    private fun family(address: InetAddress) = when (address) {
        is Inet4Address -> "IPv4"
        is Inet6Address -> "IPv6"
        else -> "unknown"
    }
    private fun addressInfo(index: Int, address: InetAddress) = JSONObject().put("resolver_index", index)
        .put("ip", address.hostAddress ?: "").put("family", family(address)).put("public", isPublic(address))

    private fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address.map { it.toInt() and 255 }
        return when (address) {
            is Inet4Address -> bytes[0] !in setOf(0, 127) && bytes[0] < 224 &&
                !(bytes[0] == 100 && bytes[1] in 64..127) &&
                !(bytes[0] == 192 && bytes[1] == 0 && bytes[2] in setOf(0, 2)) &&
                !(bytes[0] == 198 && bytes[1] in setOf(18, 19, 51)) &&
                !(bytes[0] == 203 && bytes[1] == 0 && bytes[2] == 113)
            is Inet6Address -> bytes[0] and 0xe0 == 0x20 // Global unicast 2000::/3 only.
            else -> false
        }
    }
}

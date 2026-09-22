@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.app.KeyguardManager
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.FirstUseConsent
import dev.doppel.sdk.SdkCompanionService
import dev.doppel.sdk.companion.CompanionEndpoint
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Real Android service/Keystore/TLS only. Uses existing consent/configuration; no engine writes. */
class CompanionEndpointDeviceTest {
    @Test fun phonePairingSnapshotRevocationAndStop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertNull("Do not interrupt an existing PC connection", SdkCompanionService.instance)
        assertTrue("Accept normal first-use terms before this test", FirstUseConsent.isAccepted(context))
        assertFalse("Unlock the device normally before testing", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val prefs = context.getSharedPreferences("doppel", 0)
        val connectionKeys = listOf("direct_mode", "base_url", "device_id", "token")
        val before = prefs.all.filterKeys { it in connectionKeys }
        val createdPairs = mutableListOf<String>()
        val evidence = JSONObject().put("kind", "real_android_endpoint").put("started_at_ms", System.currentTimeMillis())
        var endpoint: URI? = null
        var activity: android.app.Activity? = null
        try {
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.runOnMainSync { SdkCompanionService.enable(context) }
            await("Service and DNS-SD ready") {
                SdkCompanionService.instance?.phoneState()?.let { it.optBoolean("service_enabled") && it.optBoolean("discovery_ready") } == true
            }
            val service = requireNotNull(SdkCompanionService.instance)
            fun pair(scopes: Set<String>, rateFlood: Boolean = false): Pair<TlsClient, String> {
                val pairingService = requireNotNull(SdkCompanionService.instance)
                val invitation = URI(pairingService.openPairing())
                assertEquals("doppel-pair", invitation.scheme)
                val values = JSONObject(String(Base64.getUrlDecoder().decode(invitation.rawFragment), Charsets.UTF_8))
                val client = TlsClient(URI(values.getString("endpoint")), Base64.getUrlDecoder().decode(values.getString("spki_sha256")))
                endpoint = client.endpoint
                val request = client.request("POST", "/companion/v1/pairings", null, JSONObject()
                    .put("pairing_id", values.getString("pairing_id")).put("secret", values.getString("secret"))
                    .put("client_name", "device-test-${UUID.randomUUID()}"))
                assertEquals("Pairing request", 201, request.first)
                val id = request.second.getString("pairing_request_id")
                val pollToken = request.second.getString("poll_token")
                assertEquals(202, client.request("GET", "/companion/v1/pairings/$id", "Pairing $pollToken").first)
                if (rateFlood) {
                    var rejected = 0
                    repeat(21) {
                        rejected = client.request("GET", "/companion/v1/snapshot", "Bearer ${"A".repeat(43)}").first
                        assertTrue("Wrong credentials never authorize", rejected == 401 || rejected == 429)
                    }
                    assertEquals("Invalid credentials remain rate limited", 429, rejected)
                    assertEquals("Valid poll survives unrelated failures", 202,
                        client.request("GET", "/companion/v1/pairings/$id", "Pairing $pollToken").first)
                }
                pairingService.decidePairing(id, true, scopes)
                val approved = client.request("GET", "/companion/v1/pairings/$id", "Pairing $pollToken")
                assertEquals(200, approved.first)
                val pairId = approved.second.getString("pair_id")
                val bearer = approved.second.getString("bearer")
                createdPairs += pairId
                assertEquals("Not active before activation", if (rateFlood) 429 else 401,
                    client.request("GET", "/companion/v1/snapshot", "Bearer $bearer").first)
                assertEquals(200, client.request("POST", "/companion/v1/pairings/$id/activate", "Bearer $bearer", JSONObject()).first)
                assertEquals("Activation is idempotent", 200, client.request("POST", "/companion/v1/pairings/$id/activate", "Bearer $bearer", JSONObject()).first)
                assertEquals("Credentials are one-shot", 410, client.request("GET", "/companion/v1/pairings/$id", "Pairing $pollToken").first)
                return client to bearer
            }
            val (stateClient, stateBearer) = pair(setOf("state"))
            assertEquals("Phone cannot act as its own nearby PC", 403,
                stateClient.request("POST", "/companion/v1/presence", "Bearer $stateBearer", JSONObject()).first)
            assertFalse(SdkCompanionService.hasTrustedLanPresence(context))
            evidence.put("self_presence_rejected", true)
            val snapshot = stateClient.request("GET", "/companion/v1/snapshot", "Bearer $stateBearer")
            assertEquals(200, snapshot.first)
            assertEquals("granted", snapshot.second.getString("state_access"))
            assertTrue(snapshot.second.has("device_state"))
            assertFalse("Write milestone remains unavailable", snapshot.second.getJSONObject("capabilities").getBoolean("task_submit"))
            val scope = snapshot.second.getString("scope_id")
            val repeat = stateClient.request("GET", "/companion/v1/snapshot?scope_id=$scope", "Bearer $stateBearer")
            assertEquals(200, repeat.first)
            assertEquals("Read-only polling keeps fact revision", snapshot.second.getLong("revision"), repeat.second.getLong("revision"))
            assertEquals(409, stateClient.request("GET", "/companion/v1/snapshot?scope_id=not-the-current-scope", "Bearer $stateBearer").first)
            evidence.put("state_snapshot", true).put("stable_revision", true)
            val (historyClient, historyBearer) = pair(setOf("history"))
            val minimum = historyClient.request("GET", "/companion/v1/snapshot", "Bearer $historyBearer")
            assertEquals(200, minimum.first)
            assertEquals("denied", minimum.second.getString("state_access"))
            for (key in listOf("device_state", "current_run", "phase", "reason", "revision")) assertFalse("No state disclosure: $key", minimum.second.has(key))
            evidence.put("minimal_handshake", true)
            service.revokePair(createdPairs.first())
            assertEquals(401, stateClient.request("GET", "/companion/v1/snapshot", "Bearer $stateBearer").first)
            evidence.put("revocation_401", true)
            assertTrue("Connection/model configuration unchanged", before == prefs.all.filterKeys { it in connectionKeys })
            instrumentation.runOnMainSync { SdkCompanionService.disable(context) }
            await("Service stopped") { SdkCompanionService.instance == null }
            val address = requireNotNull(endpoint)
            val closed = runCatching { Socket().use { it.connect(InetSocketAddress(address.host, address.port), 1000) } }.isFailure
            assertTrue("Stopped listener must close its port", closed)
            evidence.put("port_closed", true)
            val offlinePairs = CompanionEndpoint.localPairingState(context).getJSONArray("pairs")
            assertTrue("Active pair remains manageable offline", (0 until offlinePairs.length()).any {
                offlinePairs.getJSONObject(it).getString("pair_id") == createdPairs.last()
            })
            createdPairs.forEach { CompanionEndpoint.revokeLocalPair(context, it) }
            createdPairs.clear()
            instrumentation.runOnMainSync { SdkCompanionService.enable(context) }
            await("Service restarted") { SdkCompanionService.instance?.phoneState()?.optBoolean("discovery_ready") == true }
            val restarted = URI(requireNotNull(SdkCompanionService.instance).phoneState().getString("endpoint"))
            assertEquals("Offline revocation survives service restart", 401,
                historyClient.request("GET", "/companion/v1/snapshot", "Bearer $historyBearer", target = restarted).first)
            evidence.put("offline_revocation", true)
            // Keep intentional failure saturation last; this fixture runs in its own instrumentation invocation.
            val (existingClient, existingBearer) = pair(setOf("state"))
            val (floodClient, floodBearer) = pair(setOf("state"), rateFlood = true)
            assertEquals("Existing bearer survives unrelated failures", 200,
                existingClient.request("GET", "/companion/v1/snapshot", "Bearer $existingBearer").first)
            assertEquals("New bearer survives unrelated failures", 200,
                floodClient.request("GET", "/companion/v1/snapshot", "Bearer $floodBearer").first)
            evidence.put("rate_limit_isolates_failures", true).put("passed", true)
        } finally {
            createdPairs.forEach { runCatching { CompanionEndpoint.revokeLocalPair(context, it) } }
            SdkCompanionService.instance?.cancelPairing()
            instrumentation.runOnMainSync { SdkCompanionService.disable(context); activity?.finish() }
            evidence.put("finished_at_ms", System.currentTimeMillis())
            context.getExternalFilesDir(null)?.resolve("companion-endpoint-verification.json")?.writeText(evidence.toString(2))
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        do { if (condition()) return; SystemClock.sleep(100) } while (SystemClock.elapsedRealtime() < end)
        assertTrue(label, condition())
    }

    private class TlsClient(val endpoint: URI, pin: ByteArray) {
        private val ssl = SSLContext.getInstance("TLS").apply {
            val trust = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw java.security.cert.CertificateException("Client trust unused")
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    val certificate = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("Missing peer")
                    certificate.checkValidity()
                    if (!MessageDigest.isEqual(pin, MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)))
                        throw java.security.cert.CertificateException("TLS identity mismatch")
                }
            }
            init(null, arrayOf(trust), null)
        }

        fun request(method: String, path: String, authorization: String?, body: JSONObject? = null, target: URI = endpoint): Pair<Int, JSONObject> {
            val bytes = body?.toString()?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
            return (ssl.socketFactory.createSocket(target.host, target.port) as SSLSocket).use {
                it.soTimeout = 5000
                it.startHandshake()
                val headers = "$method $path HTTP/1.1\r\nHost: ${target.rawAuthority}\r\nConnection: close\r\n" +
                    (authorization?.let { value -> "Authorization: $value\r\n" } ?: "") +
                    (if (body != null) "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" else "") + "\r\n"
                it.outputStream.write(headers.toByteArray(Charsets.US_ASCII)); it.outputStream.write(bytes); it.outputStream.flush()
                val response = it.inputStream.bufferedReader(Charsets.UTF_8).readText()
                val code = response.substringBefore("\r\n").split(' ')[1].toInt()
                code to JSONObject(response.substringAfter("\r\n\r\n"))
            }
        }
    }
}

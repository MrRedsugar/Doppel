package dev.doppel.sdk.companion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

class CompanionTlsTest {
    @Test fun realTlsRejectsAmbiguousFramingBeforeRouting() {
        val directory = Files.createTempDirectory("companion-tls-test").toFile()
        try {
            val password = "fixture-only".toCharArray()
            val keyFile = directory.resolve("test.p12")
            val keytool = File(System.getProperty("java.home"), "bin/keytool" + if (System.getProperty("os.name", "").startsWith("Windows")) ".exe" else "")
            val generation = ProcessBuilder(keytool.path, "-genkeypair", "-alias", "fixture", "-keyalg", "EC",
                "-groupname", "secp256r1", "-dname", "CN=localhost", "-validity", "1", "-storetype", "PKCS12",
                "-keystore", keyFile.path, "-storepass", String(password)).redirectErrorStream(true).start()
            val generationOutput = generation.inputStream.bufferedReader().readText()
            assertEquals(generationOutput, 0, generation.waitFor())
            val store = KeyStore.getInstance("PKCS12").apply { keyFile.inputStream().use { load(it, password) } }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
            val trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            val serverContext = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
            val clientContext = SSLContext.getInstance("TLS").apply { init(null, trusts.trustManagers, null) }
            val host = object : CompanionHost {
                private fun unexpected(): CompanionResponse = error("Unauthenticated fixture must never reach host")
                override fun readSnapshot(auth: CompanionAuthContext, scopeId: String?) = unexpected()
                override fun readRun(auth: CompanionAuthContext, scopeId: String, runId: String) = unexpected()
                override fun readHistory(auth: CompanionAuthContext, scopeId: String, collection: String, cursor: String?, limit: Int) = unexpected()
                override fun readConversation(auth: CompanionAuthContext, scopeId: String, conversationId: String, cursor: String?, limit: Int) = unexpected()
                override fun submitOperation(auth: CompanionAuthContext, body: JSONObject) = unexpected()
                override fun readOperation(auth: CompanionAuthContext, scopeId: String, requestId: String) = unexpected()
            }
            val pairings = CompanionPairings(directory.resolve("pairs.json"))
            val window = pairings.openWindow()
            var observedPeer: InetAddress? = null
            val router = CompanionRouter(host, pairings) { _, peer, _ ->
                observedPeer = peer
                if (!CompanionLanPresence.sameLink(peer, InetAddress.getByName("192.168.1.2"), 24))
                    throw CompanionProtocolException(403, "lan_presence_unavailable")
            }
            CompanionTlsListener(serverContext, InetAddress.getByName("127.0.0.1"), CompanionHttp(router)).use { server ->
                fun request(value: String): String = (clientContext.socketFactory.createSocket("127.0.0.1", server.port) as SSLSocket).use {
                    it.soTimeout = 5000
                    it.startHandshake()
                    assertArrayEquals(store.getCertificate("fixture").publicKey.encoded, it.session.peerCertificates[0].publicKey.encoded)
                    it.outputStream.write(value.toByteArray(Charsets.UTF_8)); it.outputStream.flush()
                    it.inputStream.bufferedReader().readText()
                }
                assertTrue(request("GET /companion/v1/snapshot HTTP/1.1\r\nHost: localhost\r\n\r\n").startsWith("HTTP/1.1 401"))
                assertTrue(request("POST /companion/v1/pairings HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nContent-Length: 2\r\n\r\n").startsWith("HTTP/1.1 400"))
                assertTrue(request("POST /companion/v1/pairings HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n").startsWith("HTTP/1.1 400"))
                assertTrue(request("GET /companion/v1/snapshot HTTP/1.1\r\nHost: localhost\r\n folded: value\r\n\r\n").startsWith("HTTP/1.1 400"))
                val body = JSONObject().put("pairing_id", window.getString("pairing_id"))
                    .put("secret", window.getString("secret")).put("client_name", "Protocol test PC").toString()
                val response = request("POST /companion/v1/pairings HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n$body")
                assertTrue(response.startsWith("HTTP/1.1 201"))
                assertTrue(response.contains("Cache-Control: no-store"))
                assertTrue(response.contains("Connection: close"))
                val json = JSONObject(response.substringAfter("\r\n\r\n"))
                assertEquals("pending", json.getString("state"))
                assertFalse(response.contains(window.getString("secret")))
                val id = json.getString("pairing_request_id")
                pairings.decide(id, true, setOf("state"))
                val bearer = pairings.poll(id, json.getString("poll_token")).body.getString("bearer")
                pairings.activate(id, bearer)
                val presenceResponse = request("POST /companion/v1/presence HTTP/1.1\r\nHost: localhost\r\n" +
                    "Authorization: Bearer $bearer\r\nX-Forwarded-For: 192.168.1.3\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}")
                assertTrue("Header cannot spoof the actual TLS peer", presenceResponse.startsWith("HTTP/1.1 403"))
                assertEquals(InetAddress.getByName("127.0.0.1"), observedPeer)
                assertEquals(422, router.handle("POST", "/companion/v1/presence", "Bearer $bearer", "{\"present\":\"true\"}").statusCode)
                assertEquals(422, router.handle("POST", "/companion/v1/presence", "Bearer $bearer", "{\"address\":\"192.168.1.3\"}").statusCode)
                assertEquals(401, router.handle("POST", "/companion/v1/presence", null, "{}").statusCode)
            }
        } finally { directory.deleteRecursively() }
    }
}

package dev.doppel.sdk.companion

import java.net.InetAddress
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionHandoffTest {
    @Test fun screenScopeIsExplicitPersistedRevocableAndEveryRequestChecksItsActualPeer() {
        val directory = Files.createTempDirectory("companion-handoff").toFile()
        try {
            val file = directory.resolve("pairs.json")
            val pairs = CompanionPairings(file)
            fun pair(scopes: Set<String>): String {
                val invitation = pairs.openWindow()
                val request = pairs.request(invitation.getString("pairing_id"), invitation.getString("secret"), "Test desktop")
                val id = request.getString("pairing_request_id")
                if ("screen_control" in scopes) {
                    try { pairs.decide(id, true, setOf("screen_control")); fail("Screen control requires state") }
                    catch (expected: CompanionProtocolException) { assertEquals(422, expected.statusCode) }
                }
                pairs.decide(id, true, scopes)
                val bearer = pairs.poll(id, request.getString("poll_token")).body.getString("bearer")
                pairs.activate(id, bearer)
                return bearer
            }
            val readOnly = pair(setOf("state"))
            val controller = pair(setOf("state", "screen_control"))
            assertFalse("Old pairing never gains screen permission", "screen_control" in CompanionPairings(file).authenticate(readOnly).grantedScopes)
            assertTrue("New permission survives reload", "screen_control" in CompanionPairings(file).authenticate(controller).grantedScopes)
            var controls = 0
            val host = object : CompanionHost {
                private fun ok() = CompanionResponse(200, JSONObject())
                override fun readSnapshot(auth: CompanionAuthContext, scopeId: String?) = ok()
                override fun readRun(auth: CompanionAuthContext, scopeId: String, runId: String) = ok()
                override fun readHistory(auth: CompanionAuthContext, scopeId: String, collection: String, cursor: String?, limit: Int) = ok()
                override fun readConversation(auth: CompanionAuthContext, scopeId: String, conversationId: String, cursor: String?, limit: Int) = ok()
                override fun readOperation(auth: CompanionAuthContext, scopeId: String, requestId: String) = ok()
                override fun submitOperation(auth: CompanionAuthContext, body: JSONObject) = ok()
                override fun readTaskEvents(auth: CompanionAuthContext) = ok()
                override fun handoff(auth: CompanionAuthContext, operation: String, body: JSONObject, currentLan: () -> Boolean): CompanionResponse {
                    assertTrue(currentLan()); controls++; return ok()
                }
            }
            val peer = InetAddress.getByName("192.168.1.3")
            var link = true
            val router = CompanionRouter(host, pairs, lanAccess = { source -> { link && source == peer } })
            fun request(token: String, source: InetAddress? = peer) = router.handle("POST", "/companion/v1/handoff",
                "Bearer $token", "{\"run_id\":\"task\"}", source)
            assertEquals(403, request(readOnly).statusCode)
            assertEquals(403, request(controller, null).statusCode)
            assertEquals(403, request(controller, InetAddress.getByName("192.168.2.7")).statusCode)
            assertEquals(200, request(controller).statusCode)
            link = false
            assertEquals(403, request(controller).statusCode)
            assertEquals(1, controls)
            assertEquals(200, router.handle("GET", "/companion/v1/task-events", "Bearer $readOnly").statusCode)
            pairs.revoke(pairs.authenticate(controller).pairId)
            link = true
            assertEquals(401, request(controller).statusCode)
            assertEquals(1, controls)
        } finally { directory.deleteRecursively() }
    }
}

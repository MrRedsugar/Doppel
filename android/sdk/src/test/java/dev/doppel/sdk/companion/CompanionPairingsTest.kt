package dev.doppel.sdk.companion

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.nio.file.Files

class CompanionPairingsTest {
    @Test fun failedCredentialFloodDoesNotBlockValidPairingOrAuthentication() {
        val directory = Files.createTempDirectory("companion-rate-test").toFile()
        try {
            var elapsed = 100L
            val pairs = CompanionPairings(directory.resolve("pairs.json"), { 1_000_000L }, { elapsed })
            val invitation = pairs.openWindow()
            val request = pairs.request(invitation.getString("pairing_id"), invitation.getString("secret"), "Legitimate PC")
            val requestId = request.getString("pairing_request_id")
            val pollToken = request.getString("poll_token")
            repeat(20) { expect(401, "authentication_required") { pairs.authenticate("wrong-bearer") } }
            repeat(5) { expect(429, "rate_limited") { pairs.authenticate("wrong-bearer") } }
            assertEquals(202, pairs.poll(requestId, pollToken).statusCode)
            pairs.decide(requestId, true, setOf("state"))
            val approved = pairs.poll(requestId, pollToken).body
            val bearer = approved.getString("bearer")
            val pairId = approved.getString("pair_id")
            assertEquals(pairId, pairs.activate(requestId, bearer).getString("pair_id"))
            assertEquals(pairId, pairs.activate(requestId, bearer).getString("pair_id"))
            assertEquals(pairId, pairs.authenticate(bearer).pairId)
            val next = pairs.openWindow()
            val nextRequest = pairs.request(next.getString("pairing_id"), next.getString("secret"), "Next legitimate PC")
            expect(429, "rate_limited") { pairs.poll(nextRequest.getString("pairing_request_id"), "wrong-poll-token") }
            elapsed += 60_000
            expect(401, "authentication_required") { pairs.poll(nextRequest.getString("pairing_request_id"), "wrong-poll-token") }
            assertEquals(pairId, pairs.authenticate(bearer).pairId)
        } finally { directory.deleteRecursively() }
    }

    @Test fun pairingLifecycleFailsClosedAndPersistsOnlyActivatedHashes() {
        val directory = Files.createTempDirectory("companion-pairing-test").toFile()
        try {
            val file = directory.resolve("pairs.json")
            var elapsed = 100L
            fun store() = CompanionPairings(file, { 1_000_000L }, { elapsed })
            val pairs = store()
            val window = pairs.openWindow()
            val request = pairs.request(window.getString("pairing_id"), window.getString("secret"), "Test PC")
            val requestId = request.getString("pairing_request_id")
            val poll = request.getString("poll_token")
            assertEquals(202, pairs.poll(requestId, poll).statusCode)
            expect(410, "pairing_result_consumed") { pairs.request(window.getString("pairing_id"), window.getString("secret"), "Again") }
            expect(422, "invalid_request") { pairs.decide(requestId, true, setOf("submit")) }
            pairs.decide(requestId, true, setOf("state", "submit"))
            val approved = pairs.poll(requestId, poll).body
            val bearer = approved.getString("bearer")
            val pairId = approved.getString("pair_id")
            assertFalse(file.exists())
            expect(401, "authentication_required") { pairs.authenticate(bearer) }
            expect(410, "pairing_result_consumed") { pairs.poll(requestId, poll) }
            expect(410, "pairing_expired") { store().activate(requestId, bearer) }
            pairs.activate(requestId, bearer)
            val stored = file.readText()
            assertFalse(stored.contains(bearer))
            assertFalse(stored.contains(poll))
            assertFalse(stored.contains(window.getString("secret")))
            val auth = pairs.authenticate(bearer)
            assertEquals(7, auth.withAuthorization(setOf("submit")) { 7 })
            var submissions = 0
            val host = object : CompanionHost {
                private fun ok() = CompanionResponse(200, JSONObject().put("fixture", true))
                override fun readSnapshot(auth: CompanionAuthContext, scopeId: String?) = ok()
                override fun readRun(auth: CompanionAuthContext, scopeId: String, runId: String) = ok()
                override fun readHistory(auth: CompanionAuthContext, scopeId: String, collection: String, cursor: String?, limit: Int) = ok()
                override fun readConversation(auth: CompanionAuthContext, scopeId: String, conversationId: String, cursor: String?, limit: Int) = ok()
                override fun readOperation(auth: CompanionAuthContext, scopeId: String, requestId: String) = ok()
                override fun submitOperation(auth: CompanionAuthContext, body: JSONObject): CompanionResponse { submissions++; return ok() }
            }
            val presence = CompanionLanPresence()
            val router = CompanionRouter(host, pairs) { context, _, present -> presence.update(context, present) }
            val authorization = "Bearer $bearer"
            val peer = java.net.InetAddress.getByName("192.168.1.3")
            val heartbeat = router.handle("POST", "/companion/v1/presence", authorization, "{}", peer)
            assertEquals(200, heartbeat.statusCode)
            assertTrue(heartbeat.body.getBoolean("present"))
            assertEquals(12_000L, heartbeat.body.getLong("lease_ms"))
            assertTrue(presence.active())
            val departed = router.handle("POST", "/companion/v1/presence", authorization, "{\"present\":false}", peer)
            assertEquals(200, departed.statusCode)
            assertFalse(departed.body.getBoolean("present"))
            assertFalse(presence.active())
            assertEquals(200, router.handle("POST", "/companion/v1/presence", authorization, "{}", peer).statusCode)
            assertEquals(200, router.handle("GET", "/companion/v1/snapshot", authorization).statusCode)
            assertEquals(400, router.handle("GET", "/companion/v1/runs/one", authorization).statusCode)
            assertEquals(400, router.handle("GET", "/companion/v1/runs/one?scope_id=a&scope_id=b", authorization).statusCode)
            assertEquals(403, router.handle("GET", "/companion/v1/runs?scope_id=a", authorization).statusCode)
            val create = JSONObject().put("kind", "create").put("request_id", "test-operation").put("scope_id", "a")
                .put("goal", "Read settings").put("mode", "ask")
            assertEquals(200, router.handle("POST", "/companion/v1/operations", authorization, create.toString()).statusCode)
            create.put("source", "schedule")
            assertEquals(422, router.handle("POST", "/companion/v1/operations", authorization, create.toString()).statusCode)
            assertEquals(1, submissions)
            assertEquals(400, router.handle("POST", "/companion/v1/operations", authorization, create.toString() + "{}").statusCode)
            expectAuth(403, "capability_denied") { auth.withAuthorization(setOf("history")) { fail("Must not execute") } }
            elapsed += 300_001
            val restarted = store()
            assertEquals(pairId, restarted.activate(requestId, bearer).getString("pair_id"))
            assertEquals(pairId, restarted.authenticate(bearer).pairId)
            pairs.revoke(pairId)
            assertFalse("Presence must recheck revocation without another request", presence.active())
            assertEquals(401, router.handle("GET", "/companion/v1/snapshot", authorization).statusCode)
            expectAuth(401, "pairing_revoked") { auth.withAuthorization(emptySet()) { fail("Revoked context executed") } }
            expect(401, "authentication_required") { store().authenticate(bearer) }
            val expiring = pairs.openWindow()
            elapsed += 300_000
            expect(410, "pairing_expired") { pairs.request(expiring.getString("pairing_id"), expiring.getString("secret"), "Late") }
            val errors = pairs.openWindow()
            repeat(5) { expect(401, "authentication_required") { pairs.request(errors.getString("pairing_id"), "wrong", "PC") } }
            expect(401, "authentication_required") { pairs.request(errors.getString("pairing_id"), errors.getString("secret"), "PC") }
            val cancelled = pairs.openWindow()
            val cancelledRequest = pairs.request(cancelled.getString("pairing_id"), cancelled.getString("secret"), "Cancel PC")
            pairs.cancelWindow()
            expect(410, "pairing_expired") { pairs.poll(cancelledRequest.getString("pairing_request_id"), cancelledRequest.getString("poll_token")) }
        } finally { directory.deleteRecursively() }
    }

    private fun expect(status: Int, code: String, action: () -> Any?) {
        try { action(); fail("Expected $code") } catch (error: CompanionProtocolException) {
            assertEquals(status, error.statusCode); assertEquals(code, error.code)
        }
    }
    private fun expectAuth(status: Int, code: String, action: () -> Any?) {
        try { action(); fail("Expected $code") } catch (error: CompanionAuthorizationException) {
            assertEquals(status, error.statusCode); assertEquals(code, error.code)
        }
    }
}

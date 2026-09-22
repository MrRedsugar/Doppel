package dev.doppel.sdk

import dev.doppel.sdk.companion.CompanionAuthContext
import dev.doppel.sdk.companion.CompanionAuthorizationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SdkCompanionHostTest {
    private class Auth(override val grantedScopes: Set<String>) : CompanionAuthContext {
        override val pairId = "test-pair"
        override val pairGeneration = 1L
        var valid = true
        override fun <T> withAuthorization(requiredScopes: Set<String>, action: () -> T): T {
            if (!valid) throw CompanionAuthorizationException(401, "pairing_revoked")
            if (!grantedScopes.containsAll(requiredScopes)) throw CompanionAuthorizationException(403, "permission_denied")
            return action()
        }
    }

    @Test fun snapshotAuthorizesAtPublicationAndRevisionsFollowFactsNotReads() {
        val auth = Auth(setOf("state"))
        var scope = "first"
        var status = "paused"
        var revokeDuringRead = false
        val host = SdkCompanionHost("installation") { includeState ->
            assertTrue(includeState)
            if (revokeDuringRead) auth.valid = false
            SdkCompanionHost.Facts(scope, true, JSONObject().put("readiness", "busy"),
                JSONObject().put("id", "run").put("status", status))
        }
        val first = host.readSnapshot(auth, null).body
        val second = host.readSnapshot(auth, "first").body
        assertEquals(first.getLong("revision"), second.getLong("revision"))
        assertEquals(first.getString("sync_epoch"), second.getString("sync_epoch"))
        status = "running"
        assertTrue(host.readSnapshot(auth, "first").body.getLong("revision") > first.getLong("revision"))
        scope = "second"
        assertEquals(409, host.readSnapshot(auth, "first").statusCode)
        assertNotEquals(first.getString("sync_epoch"), host.readSnapshot(auth, null).body.getString("sync_epoch"))
        revokeDuringRead = true
        try { host.readSnapshot(auth, null); fail("Revoked reads must not publish") }
        catch (expected: CompanionAuthorizationException) { assertEquals(401, expected.statusCode) }
    }

    @Test fun historyOnlyHandshakeNeverReadsTaskStateOrEnablesUnimplementedActions() {
        val host = SdkCompanionHost("installation") { includeState ->
            assertFalse(includeState)
            SdkCompanionHost.Facts("scope", true, null, null)
        }
        val auth = Auth(setOf("history"))
        val value = host.readSnapshot(auth, null).body
        assertEquals(setOf("api_version", "companion_device_id", "scope_id", "sync_epoch", "state_access", "capabilities"),
            value.keys().asSequence().toSet())
        assertEquals("denied", value.getString("state_access"))
        val capabilities = value.getJSONObject("capabilities")
        capabilities.keys().forEach { assertFalse(capabilities.getBoolean(it)) }
        assertEquals(501, host.submitOperation(auth, JSONObject()).statusCode)
    }
}

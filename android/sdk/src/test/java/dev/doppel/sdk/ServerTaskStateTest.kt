package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServerTaskStateTest {
    private fun body() = JSONObject().put("goal", "打开设置").put("device_id", "direct-this-phone").put("mode", "assist")
    private val owner = SplitTaskEngine.ServerOwner("account-a", "phone-a", true)

    @Test fun revisionAndOwnerAreCheckedBeforeAnyControlSideEffect() {
        var saved = ""
        var admission = 0
        val engine = SplitTaskEngine(null, { saved = it })
        val run = engine.createOwned(body(), "server-run-" + "a".repeat(64), owner)
        val id = run.getString("id")
        assertFalse(run.has("server_owner"))
        assertNull(engine.serverTask("account-b", "phone-a"))
        for ((account, revision) in listOf("account-b" to run.getLong("revision"), "account-a" to 0L)) {
            try { engine.serverControl(id, account, "phone-a", revision, "cancel") { admission++ }; fail("Stale/foreign control accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, admission)
        assertEquals("running", engine.get(id).getString("status"))
        val paused = engine.serverControl(id, "account-a", "phone-a", run.getLong("revision"), "pause") { admission++ }
        assertEquals(1, admission)
        assertTrue(paused.getLong("revision") > run.getLong("revision"))
        assertEquals("paused", JSONObject(SplitTaskEngine.readPersistedRuns(saved).getJSONObject(0).toString()).getString("status"))
        assertTrue(engine.poll().isNull("command"))
    }

    @Test fun processRestartCancelsRemoteWithoutReplayingAndKeepsLocalPaused() {
        for (remote in listOf(true, false)) {
            var saved = ""
            val engine = SplitTaskEngine(null, { saved = it })
            val run = engine.createOwned(body(), null, owner.copy(remote = remote))
            val restored = SplitTaskEngine(saved, {})
            val current = restored.get(run.getString("id"))
            assertEquals(if (remote) "cancelled" else "paused", current.getString("status"))
            assertTrue(current.getLong("revision") > run.getLong("revision"))
            assertTrue(restored.poll().isNull("command"))
            assertNull(restored.takeWork())
            assertEquals(1, restored.serverRuns("account-a", "phone-a").size)
            assertEquals(if (remote) 1 else 0, restored.serverRuns("account-a", "phone-a", true).size)
        }
    }

    @Test fun fixedOperationTaskIdentityCannotCreateTwice() {
        val engine = SplitTaskEngine(null, {})
        val id = "server-run-" + "b".repeat(64)
        engine.createOwned(body(), id, owner)
        engine.control(id, "cancel", JSONObject())
        assertEquals("cancelled", engine.createOwned(body(), id, owner).getString("status"))
        try { engine.createOwned(body().put("goal","different"), id, owner); fail("Conflicting operation content accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(1, engine.list().getJSONArray("items").length())
    }

    @Test fun wireValidationRejectsClientOwnedExecutionFieldsAndStableHashIgnoresKeyOrder() {
        val a = JSONObject().put("mode", "assist").put("goal", "打开设置")
        val b = JSONObject().put("goal", "打开设置").put("mode", "assist")
        assertEquals(ServerTaskHost.digest(ServerTaskHost.canonical(a)), ServerTaskHost.digest(ServerTaskHost.canonical(b)))
        val command = JSONObject().put("type", "command").put("session_id", "phone-a").put("operation_id", "op-1")
            .put("kind", "create_task").put("payload", a).put("expires_at_ms", 123L)
        ServerTaskHost.validate(command, "phone-a")
        a.put("server_owner", JSONObject().put("account_id", "spoof"))
        try { ServerTaskHost.validate(command, "phone-a"); fail("Client ownership field accepted") }
        catch (_: IllegalArgumentException) { }
    }
}

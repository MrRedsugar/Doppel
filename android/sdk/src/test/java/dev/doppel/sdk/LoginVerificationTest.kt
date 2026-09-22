package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LoginVerificationTest {
    private fun run() = JSONObject().put("id", "run-a").put("status", "running")

    @Test fun threeAttemptsSurviveRefillPauseAndProcessRecreation() {
        var run = run()
        assertFalse(LoginVerification.begin(run, "app.a", 1000))
        LoginVerification.recordLocalFill(run, "app.a", 1000)
        repeat(3) { index ->
            val now = 1001L + index * 10
            assertTrue(LoginVerification.begin(run, "app.a", now))
            assertEquals(index + 1, LoginVerification.summary(run, "app.a", now).getInt("attempts"))
            assertFalse("A second begin cannot silently replace a live attempt", LoginVerification.begin(run, "app.a", now + 1))
            val permit = LoginVerification.claimAction(run, "app.a", now + 2)!!
            assertTrue(LoginVerification.allows(run, "app.a", permit, now + 2))
            LoginVerification.interrupt(run)
            assertFalse(LoginVerification.allows(run, "app.a", permit, now + 2))
            run = JSONObject(run.toString())
            LoginVerification.recordLocalFill(run, "app.a", now + 3)
        }
        assertFalse(LoginVerification.begin(run, "app.a", 1100))
        assertFalse(LoginVerification.summary(run, "app.a", 1100).getBoolean("can_begin"))
    }

    @Test fun permissionIsBoundToRunAppTimeAndHostToken() {
        val run = run()
        LoginVerification.recordLocalFill(run, "app.a", 1000)
        assertTrue(LoginVerification.begin(run, "app.a", 1001))
        assertNull(LoginVerification.claimAction(run, "app.b", 1002))
        val permit = LoginVerification.claimAction(run, "app.a", 1002)!!
        assertFalse(LoginVerification.allows(run(), "app.a", permit, 1002))
        assertFalse(LoginVerification.allows(run, "app.b", permit, 1002))
        assertFalse(LoginVerification.allows(run, "app.a", "invented", 1002))
        assertFalse(LoginVerification.allows(run, "app.a", permit, 1000))
        assertFalse(LoginVerification.allows(run, "app.a", permit, 302000))
        assertFalse(LoginVerification.summary(run, "app.a", 1002).toString().contains(permit))
        run.put("status", "paused")
        assertFalse(LoginVerification.allows(run, "app.a", permit, 1002))
    }

    @Test fun eachAttemptHasBoundedActionsAndExplicitCompletion() {
        val run = run()
        LoginVerification.recordLocalFill(run, "app.a", 1000)
        assertTrue(LoginVerification.begin(run, "app.a", 1001))
        var permit = ""
        repeat(20) { permit = LoginVerification.claimAction(run, "app.a", 1002)!! }
        assertNull(LoginVerification.claimAction(run, "app.a", 1002))
        assertTrue(LoginVerification.finish(run, "app.a", false, 1002))
        assertFalse(LoginVerification.allows(run, "app.a", permit, 1002))
        assertTrue(LoginVerification.begin(run, "app.a", 1003))
        assertTrue(LoginVerification.finish(run, "app.a", true, 1004))
        assertEquals("passed", LoginVerification.summary(run, "app.a", 1004).getString("state"))
        assertEquals(2, LoginVerification.summary(run, "app.a", 1004).getInt("attempts"))
        assertFalse(LoginVerification.summary(run, "app.a", 1004).getBoolean("can_begin"))
        assertFalse(LoginVerification.begin(run, "app.a", 1005))
        LoginVerification.recordLocalFill(run, "app.a", 1006)
        assertTrue(LoginVerification.begin(run, "app.a", 1007))
        assertFalse("An expired attempt cannot claim success", LoginVerification.finish(run, "app.a", true, 302000))
    }
}

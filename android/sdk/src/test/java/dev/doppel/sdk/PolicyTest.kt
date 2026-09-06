package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class PolicyTest {
    @Test fun paymentAndPasswordAreNeverAllowed() {
        for (mode in listOf("ask", "assist", "full")) {
            assertEquals("blocked", Policy.validate("tap", "s", "s", Target("确认支付", false, true), mode))
            assertEquals("blocked", Policy.validate("tap", "s", "s", Target("Pay", false, true), mode))
            assertEquals("blocked", Policy.validate("type", "s", "s", Target("", true, true), mode))
        }
    }
    @Test fun browsingPaymentPageDoesNotAuthorizePayment() {
        assertEquals("ok", Policy.validate("scroll", "s", "s", Target("支付说明", false, true)))
        assertEquals("blocked", Policy.validate("tap", "s", "s", Target("Pay now", false, true)))
    }
    @Test fun staleAndMissingReferencesAreRejected() {
        assertEquals("stale", Policy.validate("tap", "old", "new", Target("发送", false, true)))
        assertEquals("stale", Policy.validate("tap", "s", "s", null))
        assertEquals("ok", Policy.validate("tap", "s", "s", Target("发送", false, true)))
    }
    @Test fun hashDependsOnContentNotTime() {
        assertEquals(Policy.hash("same"), Policy.hash("same"))
        assertNotEquals(Policy.hash("same"), Policy.hash("changed"))
    }
    @Test fun claimedCommandsCannotExecuteTwiceEvenAfterCrash() {
        val saved = mutableMapOf<String, String>()
        val ledger = CommandLedger({ saved[it] }, { id, value -> saved[id] = value; true })
        assertTrue(ledger.claim("one", "uncertain"))
        assertFalse(ledger.claim("one", "uncertain"))
        assertEquals("uncertain", CommandLedger({ saved[it] }, { _, _ -> true }).cached("one"))
        ledger.finish("one", "ok")
        assertEquals("ok", ledger.cached("one"))
    }
    @Test fun persistenceFailurePreventsDispatch() {
        val ledger = CommandLedger({ null }, { _, _ -> false })
        assertFalse(ledger.claim("one", "uncertain"))
    }
}

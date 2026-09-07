package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class LoginSessionTest {
    private val phone = "19900000013"
    @Test fun onlyFreshMatchingMessageCanBeConsumedOnceByBoundRunAndApp() {
        var now = 1_000_000L
        val session = LoginSession { now }
        session.begin("dev.example.shop", "run-a", phone, "Example")
        assertFalse(session.receive("sms", "sms", "dev.example.shop", "[Example] code 123456", now - 1))
        assertFalse(session.receive("untrusted", "sms", "dev.example.shop", "[Example] code 123456", now))
        assertFalse(session.receive("sms", "sms", "dev.other.app", "[Example] code 123456", now))
        assertFalse(session.receive("sms", "sms", "dev.example.shop", "[Other] code 123456", now))
        assertTrue(session.receive("sms", "sms", "dev.example.shop", "[Example] verification code: 123456", now))
        assertNull(session.consume("dev.other.app", "run-a"))
        assertNull(session.consume("dev.example.shop", "run-b"))
        assertEquals("123456", session.consume("dev.example.shop", "run-a"))
        assertNull(session.consume("dev.example.shop", "run-a"))
        assertFalse(session.receive("sms", "sms", "dev.example.shop", "[Example] verification code: 123456", now))
        assertFalse(session.redact("dev.example.shop", "Code 123456 $phone").contains("123456"))
        assertFalse(session.redact("dev.example.shop", phone).contains(phone))
        now += 300_001
        assertFalse(session.active())
    }
    @Test fun ambiguousOldAndFinancialCodesAreRejected() {
        var now = 5000L
        val session = LoginSession { now }
        session.begin("dev.example.shop", "run-a", phone, "Example")
        assertFalse(session.receive("sms", "sms", "dev.example.shop", "[Example] code 123456 or 654321", now))
        assertFalse(session.receive("sms", "sms", "dev.example.shop", "[Example] payment code 123456", now))
        assertFalse(session.receive("sms", "sms", "dev.example.shop", "[Example] balance 123456", now))
        assertFalse(session.receive("sms", "sms", "dev.example.shop", "[NotExample] code 123456", now))
        now += 300_001
        assertFalse(session.receive("sms", "sms", "dev.example.shop", "[Example] code 123456", now))
        assertNull(session.consume("dev.example.shop", "run-a"))
    }
    @Test fun changingRunOrClearingDropsPendingCode() {
        val session = LoginSession { 5000L }
        session.begin("dev.example.shop", "run-a", phone, "Example")
        assertTrue(session.receive("sms", "sms", "dev.example.shop", "[Example] code 123456", 5000L))
        session.begin("dev.example.shop", "run-b", phone, "Example")
        assertNull(session.consume("dev.example.shop", "run-b"))
        session.clear()
        assertFalse(session.active())
    }
    @Test fun expiredSecretsArePurgedWithoutAnotherObservation() {
        var now = 5000L
        val session = LoginSession { now }
        session.begin("dev.example.shop", "run-a", phone, "Example")
        assertTrue(session.receive("sms", "sms", "dev.example.shop", "[Example] code 123456", now))
        now += 600_001
        assertFalse(session.expire())
        assertNull(session.consume("dev.example.shop", "run-a"))
        assertEquals("123456", session.redact("dev.example.shop", "123456"))
    }
}

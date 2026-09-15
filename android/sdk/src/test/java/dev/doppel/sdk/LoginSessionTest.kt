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
    @Test fun filledPasswordRemainsRedactedAcrossTaskAndOtpExpiryWithoutBlockingOrdinaryScreens() {
        var now = 5000L
        val session = LoginSession { now }
        session.protectPassword("fixture-password")
        session.protectPassword(phone)
        assertFalse("Password masking is independent of the OTP screenshot barrier", session.active())
        session.begin("dev.example.shop", "run-a", phone, "Example")
        session.clear()
        now += 600_001
        assertFalse("Permanent masks must not schedule endless OTP cleanup", session.expire())
        assertTrue(session.containsPrivateValue("Displayed: fixture-password"))
        assertEquals("[private]", session.redact("dev.example.shop", phone))
        assertFalse(session.containsPrivateValue("Continue"))
        assertEquals("Displayed: [private]", session.redact("dev.other.app", "Displayed: fixture-password"))
    }
    @Test fun codeReadinessIsBoundToRunAndAppAndDoesNotConsumeOrExposeTheCode() {
        var now = 1000L
        val session = LoginSession { now }
        session.begin("dev.fixture", "run-a", phone, "Fixture")
        assertEquals("waiting", session.readiness("dev.fixture", "run-a").state)
        assertEquals("inactive", session.readiness("dev.fixture", "run-b").state)
        assertEquals("inactive", session.readiness("dev.other", "run-a").state)
        assertFalse(session.receive("sms", "sms", "dev.fixture", "[Fixture] code 135790", now - 1))
        assertFalse(session.receive("sms", "sms", "dev.fixture", "[Fixture] payment code 135790", now))
        assertTrue(session.receive("sms", "sms", "dev.fixture", "[Fixture] code 246810", now))
        repeat(2) {
            val state = session.readiness("dev.fixture", "run-a")
            assertTrue(state.codeReady)
            assertEquals(300000L, state.expiresInMs)
            assertFalse(state.toString().contains("246810"))
        }
        assertEquals("246810", session.consume("dev.fixture", "run-a"))
        assertEquals("consumed", session.readiness("dev.fixture", "run-a").state)
        assertNull(session.consume("dev.fixture", "run-a"))
        now += 600001
        assertEquals("inactive", session.readiness("dev.fixture", "run-a").state)
        assertEquals("[private]", session.redact("dev.other", "246810"))
    }
}

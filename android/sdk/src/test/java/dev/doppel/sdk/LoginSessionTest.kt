package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class LoginSessionTest {
    private val phone = "19900000013"
    @Test fun onlyFreshMatchingMessageCanBeConsumedOnceByBoundRunAndApp() {
        var now = 1_000_000L
        val session = LoginSession { now }
        session.begin("dev.example.shop", "run-a", phone)
        assertFalse(session.receive("sms", "sms", "dev.example.shop", "[Example] code 123456", now - 1))
        assertFalse(session.receive("untrusted", "sms", "dev.example.shop", "[Example] code 123456", now))
        assertFalse(session.receive("sms", "sms", "dev.other.app", "[Example] code 123456", now))
        assertTrue(session.receive("sms", "sms", "dev.example.shop", "[Other] code 123456", now))
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
        session.begin("dev.example.shop", "run-a", phone)
        assertFalse(session.receive("sms", "sms", "dev.example.shop", "[Example] code 123456 or 654321", now))
        assertFalse(session.receive("sms", "sms", "dev.example.shop", "[Example] payment code 123456", now))
        assertFalse(session.receive("sms", "sms", "dev.example.shop", "[Example] balance 123456", now))
        assertFalse(session.receive("sms", "sms", "dev.example.shop", "[Example] reset password code 123456", now))
        assertFalse(session.receive("sms", "sms", "dev.example.shop", "[Example] code ABC123456", now))
        now += 300_001
        assertFalse(session.receive("sms", "sms", "dev.example.shop", "[Example] code 123456", now))
        assertNull(session.consume("dev.example.shop", "run-a"))
    }
    @Test fun changingRunOrClearingDropsPendingCode() {
        val session = LoginSession { 5000L }
        session.begin("dev.example.shop", "run-a", phone)
        assertTrue(session.receive("sms", "sms", "dev.example.shop", "[Example] code 123456", 5000L))
        session.begin("dev.example.shop", "run-b", phone)
        assertNull(session.consume("dev.example.shop", "run-b"))
        session.clear()
        assertFalse(session.active())
    }
    @Test fun companyNameMayDifferAndCandidateContextNeverContainsCodesOrPhoneNumbers() {
        val session = LoginSession { 5000L }
        session.begin("com.tencent.tmgp.sgame", "run-a", phone)
        assertTrue(session.receive("sms", "sms", "com.tencent.tmgp.sgame", "[腾讯] 登录验证码 432187，手机号 $phone，5 分钟内有效", 5000L))
        val candidate = session.candidates("com.tencent.tmgp.sgame", "run-a").single()
        assertTrue(candidate.context.contains("腾讯"))
        assertFalse(candidate.context.any { it.isDigit() })
        assertFalse(candidate.context.contains(phone))
        assertFalse(candidate.toString().contains("432187"))
        assertTrue(session.candidates("com.tencent.tmgp.sgame", "other-run").isEmpty())
        assertEquals("432187", session.consume("com.tencent.tmgp.sgame", "run-a", candidate.id))
        session.begin("com.tencent.tmgp.sgame", "run-b", phone)
        assertTrue(session.receive("sms", "sms", "com.tencent.tmgp.sgame", "[腾讯] 登录验证码 678123", 5000L))
        assertEquals("678123", session.consume("com.tencent.tmgp.sgame", "run-b"))
    }
    @Test fun multipleMessagesRequireAnExplicitSelectionAndRepeatedNotificationsAreDeduplicated() {
        var now = 5000L
        val session = LoginSession { now }
        session.begin("dev.example.shop", "run-a", phone)
        assertTrue(session.receive("sms", "sms", "dev.example.shop", "[Shop] login code 432198", now))
        now += 1000
        assertTrue(session.receive("sms", "sms", "dev.example.shop", "[Other] login code 891234", now))
        assertTrue(session.receive("sms", "sms", "dev.example.shop", "[Other] login code 891234", now))
        assertEquals("selection_required", session.readiness("dev.example.shop", "run-a").state)
        val candidates = session.candidates("dev.example.shop", "run-a")
        assertEquals(2, candidates.size)
        assertEquals(1000L, candidates.first().ageMs)
        assertNull(session.consume("dev.example.shop", "run-a"))
        assertNull(session.consume("dev.example.shop", "run-a", "unknown"))
        assertNull(session.consume("dev.other", "run-a", candidates.last().id))
        assertEquals("891234", session.consume("dev.example.shop", "run-a", candidates.last().id))
        assertNull(session.consume("dev.example.shop", "run-a", candidates.first().id))
        assertTrue(session.candidates("dev.example.shop", "run-a").isEmpty())
    }
    @Test fun aCandidateCannotSurviveAChangedRunOrExpiration() {
        var now = 5000L
        val session = LoginSession { now }
        session.begin("dev.example.shop", "run-a", phone)
        assertTrue(session.receive("sms", "sms", "dev.example.shop", "login code 569812", now))
        val firstId = session.candidates("dev.example.shop", "run-a").single().id
        session.begin("dev.example.shop", "run-b", phone)
        assertTrue(session.receive("sms", "sms", "dev.example.shop", "login code 569812", now))
        assertNull(session.consume("dev.example.shop", "run-b", firstId))
        val secondId = session.candidates("dev.example.shop", "run-b").single().id
        now += 300_001
        assertTrue(session.candidates("dev.example.shop", "run-b").isEmpty())
        assertNull(session.consume("dev.example.shop", "run-b", secondId))
    }
    @Test fun expiredSecretsArePurgedWithoutAnotherObservation() {
        var now = 5000L
        val session = LoginSession { now }
        session.begin("dev.example.shop", "run-a", phone)
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
        session.begin("dev.example.shop", "run-a", phone)
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
        session.begin("dev.fixture", "run-a", phone)
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

    @Test fun codeExtractionDistinguishesOtpFromExpiryPhoneTailAndLinks() {
        for (message in listOf(
            "【腾讯】手机号尾号 0013，登录验证码为：246810，5分钟内有效。",
            "【腾讯】验证码 246810，手机号 $phone，1200 秒内有效，2026 年发送。",
            "【腾讯】246810，为您的登录验证码，5分钟有效。",
            "246810 is your verification code. Phone ending in 0013.",
            "Your login code is 246810; 5 minutes remaining.",
            "验证码 246810，详情 https://example.com/help?code=876543",
            "您的手机号为138****0013，验证码为246810。"
        )) assertEquals(message, "246810", LoginCodeExtractor.extract(message))
        assertEquals("A four-digit number is valid when it is actually the code", "2026", LoginCodeExtractor.extract("验证码：2026"))
        for (message in listOf(
            "验证码已发送至尾号 0013，请查收。",
            "验证码服务于 2026 年升级，客服电话 4001234567。",
            "查询验证码 https://example.com/?code=246810",
            "验证码 2026 年失效。",
            "code ABC123456",
            "验证码 123456 或 654321",
            "验证码 123456，另一个验证码 654321",
            "验证码通知：订单 123456，电话尾号 1234"
        )) assertNull(message, LoginCodeExtractor.extract(message))
    }

    @Test fun explicitCodeWithPhoneTailIsReceivedAndConsumedLocally() {
        val session = LoginSession { 5000L }
        session.begin("dev.example.shop", "run-a", phone)
        assertTrue(session.receive("sms", "sms", "dev.example.shop", "【运营公司】手机号尾号0013，验证码246810，5分钟内有效", 5000L))
        assertEquals("246810", session.consume("dev.example.shop", "run-a"))
    }

    @Test fun senderAndSupportNumbersDoNotOverrideOneLabelledCodeButCodeListsStayAmbiguous() {
        for (message in listOf(
            "10086 验证码：246810，5分钟内有效。",
            "1069 验证码246810",
            "10086 【移动】登录验证码：246810，5分钟内有效。",
            "1069 【运营公司】246810 is your verification code.",
            "【商户】验证码为246810，如有疑问请联系400-123-4567。",
            "10086 【商户】验证码246810，工单编号20260918，客服10010/10086。"
        )) {
            assertEquals(message, "246810", LoginCodeExtractor.extract(message))
            val session = LoginSession { 5000L }
            session.begin("dev.example.shop", "run-a", phone)
            assertTrue(message, session.receive("sms", "sms", "dev.example.shop", message, 5000L))
            assertEquals("246810", session.consume("dev.example.shop", "run-a"))
        }
        for (message in listOf(
            "验证码123456或654321", "Your code: 123456 or 654321", "验证码123456/654321",
            "验证码123456、654321", "验证码123456,654321", "123456/654321是您的验证码",
            "验证码123456，另一个验证码654321", "123456是您的验证码654321"
        )) assertNull(message, LoginCodeExtractor.extract(message))
    }

    @Test fun resendingOnTheSameRunDropsOldCodeAndCancellationOnlyTouchesItsOwnRequest() {
        var now = 5000L
        val session = LoginSession { now }
        val first = session.begin("dev.example.shop", "run-a", phone)
        assertTrue(session.receive("sms", "sms", "dev.example.shop", "验证码123456", now))
        val oldCandidate = session.candidates("dev.example.shop", "run-a").single().id
        now += 1000
        val second = session.begin("dev.example.shop", "run-a", "") // The phone can already be filled by the app.
        session.cancelRequest(first)
        assertEquals("waiting", session.readiness("dev.example.shop", "run-a").state)
        assertNull(session.consume("dev.example.shop", "run-a", oldCandidate))
        assertFalse(session.receive("sms", "sms", "dev.example.shop", "验证码123456", now - 1))
        assertTrue(session.receive("sms", "sms", "dev.example.shop", "验证码654321", now))
        assertEquals("正常文字", session.redact("dev.example.shop", "正常文字"))
        session.cancelRequest(second)
        assertEquals("inactive", session.readiness("dev.example.shop", "run-a").state)
        assertNull(session.consume("dev.example.shop", "run-a"))
    }
}

package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class PolicyTest {
    @Test fun longPressRequiresFreshTargetAndPreservesSensitiveGuards() {
        assertEquals("stale", Policy.validate("long_press", "old", "new", Target("Menu", false, true)))
        assertEquals("stale", Policy.validate("long_press", "s", "s", null))
        assertEquals("blocked", Policy.validate("long_press", "s", "s", Target("Pay now", false, true)))
        assertEquals("blocked", Policy.validate("long_press", "s", "s", Target("Secret", true, true)))
        assertEquals("blocked", Policy.validate("long_press", "s", "s", Target("Menu", false, false)))
        assertEquals("ok", Policy.validate("long_press", "s", "s", Target("Menu", false, true)))
    }
    @Test fun challengesRequireHumanTakeoverButOrdinarySmsLoginDoesNot() {
        for (label in listOf("请完成安全验证", "请拖动滑块完成拼图", "I'm not a robot", "Security verification", "CAPTCHA")) {
            assertTrue(label, Policy.verificationRequired(listOf(label)))
        }
        assertFalse(Policy.verificationRequired(listOf("手机号登录", "请输入短信验证码", "获取验证码")))
        assertFalse(Policy.verificationRequired(listOf("安全设置", "验证手机号", "登录")))
        assertFalse(Policy.verificationLabel("人机验证", interactive = true))
        assertFalse(Policy.verificationLabel("CAPTCHA", interactive = true))
        assertTrue(Policy.verificationLabel("请完成安全验证", interactive = true))
        assertTrue(Policy.verificationLabel("I'm not a robot", interactive = true))
        assertTrue(Policy.verificationLabel(" CAPTCHA ", interactive = false))
        assertTrue(Policy.verificationLabel("请验证您是真人", interactive = true))
        for (label in listOf("安全验证设置", "查看人机验证说明", "短信验证码登录")) {
            assertFalse(Policy.verificationLabel(label, interactive = false))
            assertFalse(Policy.verificationLabel(label, interactive = true))
        }
    }
    @Test fun loginAssistanceCannotBypassFreshnessOrSensitiveTargets() {
        for (kind in listOf("login_phone", "login_code")) {
            assertEquals("stale", Policy.validate(kind, "old", "new", Target("Phone", false, true)))
            assertEquals("blocked", Policy.validate(kind, "s", "s", Target("支付验证码", false, true)))
            assertEquals("blocked", Policy.validate(kind, "s", "s", Target("Password", true, true)))
        }
        assertEquals("ok", Policy.validate("login_code", "s", "s", Target("登录验证码", true, true)))
        assertEquals("blocked", Policy.validate("login_code", "s", "s", Target("支付验证码", true, true)))
    }
    @Test fun otpFieldDetectionDoesNotMaskOrdinarySearchOrPhoneLabels() {
        for (label in listOf("登录验证码", "动态码", "verification code", "app:id/otp_input", "one-time code")) assertTrue(Policy.codeInput(label))
        for (label in listOf("搜索", "Phone number", "登录手机号", "account name")) assertFalse(Policy.codeInput(label))
    }
    @Test fun phoneDestinationNeedsPhoneSemanticsAndRejectsGeneralSearchOrFinancialNumber() {
        for (label in listOf("登录手机号", "联系电话", "mobile", "phone", "app:id/telephone_input")) assertTrue(Policy.phoneInput(label))
        for (label in listOf("搜索关键词", "消息", "银行卡号码", "账号", "headphone search")) assertFalse(Policy.phoneInput(label))
    }
    @Test fun loginLocatorLabelsKeepMeaningButNeverEchoDigits() {
        assertEquals("登录验证码 ******", Policy.redactLoginLabel("登录验证码 673921"))
        assertEquals("手机号 +***********", Policy.redactLoginLabel("手机号 +19900000013"))
        assertEquals("verification code", Policy.redactLoginLabel("verification code"))
    }
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

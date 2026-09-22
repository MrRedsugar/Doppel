package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class PaymentPolicyTest {
    @Test fun nodeLabelsDoNotReclassifyNavigationAsPayment() {
        val labels = listOf("页面中部的“全部”订单入口（位于待付款、待收货等图标右侧）", "钱包", "查看支付说明", "待付款")
        for (mode in listOf("ask", "assist", "full")) for (label in labels)
            assertEquals("ok", Policy.validate("tap", "s", "s", Target(label, false, true), mode))
    }

    @Test fun actualPaymentNeedsFullAccessAndCurrentConsent() {
        for (mode in listOf("ask", "assist", "full", "", "invalid")) for (consent in listOf(false, true))
            assertEquals(mode == "full" && consent, Policy.canPay(mode, consent, "dev.shop"))
    }

    @Test fun temporaryWindowIdsCannotBecomeNewPaymentAttemptIdentities() {
        for (pkg in listOf("", "window:12", "window:35")) assertFalse(Policy.canPay("full", true, pkg))
        assertTrue(Policy.canPay("full", true, "dev.shop"))
    }

    @Test fun removingPageClassifiersPreservesFreshnessAndCredentialProtection() {
        assertEquals("stale", Policy.validate("tap", "old", "s", Target("", false, true)))
        assertEquals("blocked", Policy.validate("tap", "s", "s", Target("", false, false)))
        assertEquals("blocked", Policy.validate("type", "s", "s", Target("", true, true)))
        for (kind in listOf("login_code", "login_password"))
            assertEquals("blocked", Policy.validate(kind, "s", "s", Target("支付密码 / 支付验证码", true, true)))
    }
}

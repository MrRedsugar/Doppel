package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class PaymentPolicyTest {
    @Test fun quickPayIsBlockedWithoutLocalConsentInEveryMode() {
        for (mode in listOf("ask", "assist", "full")) {
            assertEquals("blocked", Policy.validate("tap", "s", "s", Target("京东快付", false, true), mode))
        }
    }

    @Test fun consentAllowsOnlyFreshEnabledPaymentTaps() {
        assertEquals("ok", Policy.validate("tap", "s", "s", Target("京东快付", false, true), paymentAuthorized = true))
        assertEquals("ok", Policy.validate("tap", "s", "s", Target("Pay now", false, true), paymentAuthorized = true))
        assertEquals("stale", Policy.validate("tap", "old", "s", Target("Pay now", false, true), paymentAuthorized = true))
        assertEquals("blocked", Policy.validate("tap", "s", "s", Target("Pay now", false, false), paymentAuthorized = true))
        for (kind in listOf("type", "long_press", "login_code")) {
            assertEquals("blocked", Policy.validate(kind, "s", "s", Target("确认支付", false, true), paymentAuthorized = true))
        }
    }

    @Test fun purchaseConsentDoesNotAuthorizeTransfersMandatesOrCredentials() {
        for (label in listOf("确认转账", "汇款", "Transfer", "开通免密支付", "授权自动扣款", "开启自动续费", "支付验证码", "Enable autopay", "Enable automatic renewal", "Disable autopay", "Authorize auto-pay", "Set up automatic payments", "Cancel automatic billing")) {
            assertEquals(label, "blocked", Policy.validate("tap", "s", "s", Target(label, false, true), paymentAuthorized = true))
        }
        assertEquals("blocked", Policy.validate("tap", "s", "s", Target("Pay now", true, true), paymentAuthorized = true))
        assertTrue(Policy.manualFinancialContext(listOf("请输入支付密码")))
        assertTrue(Policy.manualFinancialContext(listOf("转账金额")))
        assertFalse(Policy.manualFinancialContext(listOf("京东快付", "应付总额 16.26")))
        assertTrue(Policy.manualFinancialContext(listOf("收银台", "验证码"), credentialInput = true))
        assertFalse(Policy.manualFinancialContext(listOf("手机号登录", "验证码"), credentialInput = true))
        for (label in listOf("已开通免密支付", "免密支付 · 已开启", "已成功授权自动扣款", "自动续费已设置", "自动扣款已成功取消")) {
            assertFalse(label, Policy.manualFinancialContext(listOf(label, "京东快付")))
        }
        assertTrue(Policy.manualFinancialContext(listOf("已开通免密支付", "关闭免密支付")))
        assertTrue(Policy.financialCredential("PIN"))
        assertTrue(Policy.financialCredential("支付口令"))
    }

    @Test fun unlabeledAndGenericConfirmationUseCheckoutContext() {
        for (label in listOf("", "确定", "确认订单", "Continue", "Confirm")) {
            assertTrue(label, Policy.paymentTarget(label, listOf("收银台", "应付总额 16.26")))
        }
        assertFalse(Policy.paymentTarget("返回菜单", listOf("收银台")))
        assertFalse(Policy.paymentTarget("查看详情", listOf("微信支付优惠")))
        assertFalse(Policy.paymentTarget("确认", listOf("搜索菜单")))
    }

}

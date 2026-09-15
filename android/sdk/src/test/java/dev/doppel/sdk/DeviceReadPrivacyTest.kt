package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class DeviceReadPrivacyTest {
    @Test fun ordinaryCodeDiscussionAndCodeInstructionsWithoutAValueRemainReadable() {
        assertFalse(DeviceReadPrivacy.codeNotification("代码审阅", "Please review this code before merging."))
        assertFalse(DeviceReadPrivacy.codeNotification("登录", "验证码会在稍后发送，请等待。"))
        assertFalse(DeviceReadPrivacy.codeNotification("Build", "Source code compiled successfully."))
    }
    @Test fun actualOneTimeCodeNotificationsArePrivateRegardlessOfTheirAge() {
        assertTrue(DeviceReadPrivacy.codeNotification("[Fixture]", "登录验证码 246810，仅用于测试"))
        assertTrue(DeviceReadPrivacy.codeNotification("Login", "Verification code: A1B2C3"))
        assertTrue(DeviceReadPrivacy.codeNotification("Login", "Verification code: ABCDEF"))
        assertTrue(DeviceReadPrivacy.codeNotification("[Fixture]", "Your code is 135790"))
        assertTrue(DeviceReadPrivacy.codeNotification("Payment", "OTP 864209"))
    }
}

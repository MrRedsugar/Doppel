package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class LoginProfileTest {
    @Test fun migrationPreservesSingleMethodsAndDisabledStatesWithoutChoosingConflictingAuthorizations() {
        val profiles = mergeLegacyLoginProfiles(listOf(
            LoginProfile("sms", "phone"), LoginProfile("both", "kept-phone"),
            LoginProfile("password", "old-phone", false), LoginProfile("disabled-sms", "", false)
        ), mapOf("both" to true, "password" to true, "disabled-password" to false,
            "sms" to false, "password-only" to true)).associateBy { it.packageName }
        assertEquals(LoginProfile("sms", "phone"), profiles["sms"])
        assertEquals(LoginProfile("both", "kept-phone", false, ""), profiles["both"])
        assertEquals(LoginProfile("password", "old-phone", true, "password"), profiles["password"])
        assertEquals(LoginProfile("disabled-password", "", false, "password"), profiles["disabled-password"])
        assertEquals(LoginProfile("disabled-sms", "", false), profiles["disabled-sms"])
        assertEquals(LoginProfile("password-only", "", true, "password"), profiles["password-only"])
    }
}

package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class AutomaticUnlockCredentialsTest {
    @Test fun acceptsOnlySupportedCredentialsAndErasesTheReadBuffer() {
        val pin = AutomaticUnlockCredentials.Kind.PIN
        val password = AutomaticUnlockCredentials.Kind.PASSWORD
        assertTrue(AutomaticUnlockCredentials.valid(pin, "012345".toCharArray()))
        listOf("123", "12345678901234567", "12a4", "１２３４", "1234\n").forEach {
            assertFalse(AutomaticUnlockCredentials.valid(pin, it.toCharArray()))
        }
        assertTrue(AutomaticUnlockCredentials.valid(password, "Ab 12!".toCharArray()))
        listOf("abc", "a".repeat(65), "pass\nword", "🔒1234").forEach {
            assertFalse(AutomaticUnlockCredentials.valid(password, it.toCharArray()))
        }
        val credential = AutomaticUnlockCredentials.Credential(pin, "135790".toCharArray())
        assertFalse(credential.toString().contains("135790"))
        credential.close()
        assertTrue(credential.value.all { it == '\u0000' })
    }
}

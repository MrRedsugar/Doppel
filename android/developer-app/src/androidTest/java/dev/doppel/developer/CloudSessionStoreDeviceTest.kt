@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.cloud.CloudSession
import dev.doppel.sdk.cloud.CloudSessionStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class CloudSessionStoreDeviceTest {
    @Test fun encryptedRoundTripBackupRecoveryAndLogout() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.noBackupFilesDir, "cloud-session-test-${UUID.randomUUID()}")
        check(directory.mkdir())
        val isolated = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        val store = CloudSessionStore(isolated)
        val token = "synthetic-session-${UUID.randomUUID()}"
        val session = CloudSession("https://example.invalid", "test-account", "test-session", token)
        val file = File(directory, "cloud-session-v1.bin")
        val backup = File(directory, "cloud-session-v1.bin.bak")
        try {
            assertNull(store.load())
            store.save(session)
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(token))
            assertFalse(session.toString().contains(token))
            assertEquals(token, CloudSessionStore(isolated).load()!!.token)
            assertFalse(store.clearIfSession("older-session"))
            assertEquals("test-session", store.load()!!.sessionId)
            assertTrue(file.renameTo(backup))
            assertEquals("test-session", store.load()!!.sessionId)
            val ciphertext = file.readBytes()
            ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
            file.writeBytes(ciphertext)
            assertTrue(runCatching { store.load() }.isFailure)
            store.clear()
            assertNull(CloudSessionStore(isolated).load())
            assertTrue(file.renameTo(backup))
            assertNull(store.load())
        } finally {
            // Only this test's isolated ciphertext directory; never touch an installed account or key.
            directory.deleteRecursively()
        }
    }
}

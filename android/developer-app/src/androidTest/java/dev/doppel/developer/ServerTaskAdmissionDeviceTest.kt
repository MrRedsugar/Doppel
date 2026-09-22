@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.ServerTaskHost
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ServerTaskAdmissionDeviceTest {
    @Test fun expiredCommandsPersistRejectionAndNeverReenterAcceptance() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(app.noBackupFilesDir, "host-admission-${UUID.randomUUID()}").apply { check(mkdir()) }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = dir
        }
        val host = ServerTaskHost(context)
        val session = ServerTaskHost.SessionRef("fixture-account", "fixture-phone", 1)
        val input = JSONObject().put("type", "command").put("operation_id", "expired-operation").put("session_id", session.sessionId)
            .put("kind", "create_task").put("expires_at_ms", 0L)
            .put("payload", JSONObject().put("goal", "This expired request must not execute").put("mode", "assist"))
        var checks = 0
        fun accept(): JSONObject {
            val done = CountDownLatch(1)
            var receipt: JSONObject? = null
            host.accept(session, input, { checks++; false }, { false }) { receipt = it; done.countDown() }
            assertTrue("Host did not persist the rejection", done.await(5, TimeUnit.SECONDS))
            return receipt!!
        }
        val first = accept()
        assertEquals("rejected", first.getString("phase"))
        assertEquals("operation_expired", first.getString("error_code"))
        assertTrue(first.isNull("task_id"))
        assertEquals(first.toString(), accept().toString())
        assertEquals("Replayed requests cannot enter acceptance again", 1, checks)
        assertEquals(1, host.pendingReceipts(session).size)
        assertTrue(host.acknowledgeReceipt(session, "expired-operation", first.getLong("receipt_seq")))
        assertTrue(host.pendingReceipts(session).isEmpty())
        // Keep the tiny isolated ledger as device evidence; it cannot alter the app's real operation history.
    }
}

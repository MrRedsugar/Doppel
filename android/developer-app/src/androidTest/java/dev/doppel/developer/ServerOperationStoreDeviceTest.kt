@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.ServerOperationStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real SQLite/reopen checks, isolated from installed accounts and task history; no model or network. */
class ServerOperationStoreDeviceTest {
    private fun isolated(test: (Context) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.noBackupFilesDir, "server-operation-test-${UUID.randomUUID()}")
        check(directory.mkdir())
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        try { test(context) } finally { directory.deleteRecursively() }
    }

    private val hash = "a".repeat(64)
    private fun receipt(phase: String = "accepted_by_phone") = JSONObject().put("phase", phase)
        .put("task_status", if (phase == "completed") "completed" else "running").put("task_revision", 1)
    private fun sequences(store: ServerOperationStore): List<Long> =
        store.pendingReceipts("account", "session").map { it.getLong("receipt_seq") }

    @Test fun queuedAcceptanceSurvivesReopenAndKeepsOrderedStartAndCancelReceipts() = isolated { context ->
        ServerOperationStore(context).use { store ->
            assertTrue(store.claim("account", "session", "operation", "create_task", hash))
            store.attachTask("account", "session", "operation", "task")
            val accepted = store.appendReceipt("account", "session", "operation", receipt().put("task_status", "queued"))
            assertEquals("accepted_by_phone", accepted.getString("phase"))
            assertEquals("queued", accepted.getString("task_status"))
        }
        ServerOperationStore(context).use { store ->
            assertFalse(store.claim("account", "session", "operation", "create_task", hash))
            assertEquals("queued", store.find("account", "session", "operation")!!.getJSONObject("latest_receipt").getString("task_status"))
            store.appendReceipt("account", "session", "operation", receipt().put("task_revision", 2))
            store.appendReceipt("account", "session", "operation", receipt("cancelled").put("task_status", "cancelled").put("task_revision", 3))
            assertEquals(listOf("queued", "running", "cancelled"), store.pendingReceipts("account", "session").map { it.getString("task_status") })
            assertTrue(store.acknowledgeReceipt("account", "session", "operation", 1))
            assertEquals(listOf(2L, 3L), sequences(store))
        }
    }

    @Test fun reopenedClaimsDoNotReplayAndConflictsCannotChangeOwnership() = isolated { context ->
        ServerOperationStore(context).use { store ->
            assertTrue(store.claim("account", "session", "operation", "create_task", hash))
            assertTrue(File(context.noBackupFilesDir, "server-operations-v1.db").isFile)
            assertNull(store.find("account", "other-session", "operation"))
            assertTrue(store.find("account", "session", "operation")!!.isNull("latest_receipt"))
            store.attachTask("account", "session", "operation", "task")
            store.attachTask("account", "session", "operation", "task")
            assertTrue(runCatching { store.attachTask("account", "session", "operation", "different-task") }.isFailure)
            assertTrue(runCatching { store.attachTask("account", "other-session", "operation", "task") }.isFailure)
        }
        ServerOperationStore(context).use { store ->
            assertFalse(store.claim("account", "session", "operation", "create_task", hash))
            assertFalse(store.claim("account", "session", "operation", "create_task", hash.uppercase()))
            assertTrue(runCatching { store.claim("account", "session", "operation", "create_task", "b".repeat(64)) }.isFailure)
            assertTrue(runCatching { store.claim("account", "session", "operation", "control_task", hash) }.isFailure)
            assertTrue(runCatching { store.claim("account", "other-session", "operation", "create_task", hash) }.isFailure)
            assertTrue(store.claim("another-account", "session", "operation", "control_task", hash))
            val record = store.find("account", "session", "operation")!!
            assertEquals("create_task", record.getString("kind"))
            assertEquals(hash, record.getString("payload_hash"))
            assertEquals("task", record.getString("task_id"))
            assertEquals(1, store.recordsForSession("account", "session").size)
            assertEquals(0, store.recordsForSession("account", "other-session").size)
            assertEquals("control_task", store.recordsForSession("another-account", "session").single().getString("kind"))
            assertNull(store.find("missing-account", "session", "operation"))
        }
    }

    @Test fun immutableReceiptsSurviveReopenAndOutOfOrderAcknowledgementsAreExact() = isolated { context ->
        ServerOperationStore(context).use { store ->
            store.claim("account", "session", "operation", "create_task", hash)
            store.attachTask("account", "session", "operation", "task")
            val supplied = receipt().put("type", "forged").put("session_id", "forged")
                .put("operation_id", "forged").put("receipt_seq", 999)
            val first = store.appendReceipt("account", "session", "operation", supplied)
            assertEquals("receipt", first.getString("type"))
            assertEquals("session", first.getString("session_id"))
            assertEquals("operation", first.getString("operation_id"))
            assertEquals("task", first.getString("task_id"))
            assertEquals(1L, first.getLong("receipt_seq"))
            assertEquals(999, supplied.getInt("receipt_seq"))
            supplied.put("phase", "failed")
            first.put("phase", "cancelled")
            assertEquals("accepted_by_phone", store.pendingReceipts("account", "session").single().getString("phase"))
            assertEquals(2L, store.appendReceipt("account", "session", "operation", receipt()).getLong("receipt_seq"))
            assertEquals(3L, store.appendReceipt("account", "session", "operation", receipt("completed")).getLong("receipt_seq"))
            assertEquals(listOf(1L, 2L, 3L), sequences(store))
            assertFalse(store.acknowledgeReceipt("another-account", "session", "operation", 1))
            assertFalse(store.acknowledgeReceipt("account", "other-session", "operation", 1))
            assertFalse(store.acknowledgeReceipt("account", "session", "other-operation", 1))
            assertFalse(store.acknowledgeReceipt("account", "session", "operation", 4))
            assertTrue(store.acknowledgeReceipt("account", "session", "operation", 2))
            assertEquals(listOf(1L, 3L), sequences(store))
            assertTrue(store.acknowledgeReceipt("account", "session", "operation", 1))
            assertTrue(store.acknowledgeReceipt("account", "session", "operation", 1))
            assertEquals(listOf(3L), sequences(store))
        }
        ServerOperationStore(context).use { store ->
            assertEquals(listOf(3L), sequences(store))
            assertEquals(3L, store.recordsForSession("account", "session").single().getJSONObject("latest_receipt").getLong("receipt_seq"))
            assertEquals(4L, store.appendReceipt("account", "session", "operation", receipt("completed")).getLong("receipt_seq"))
            assertTrue(store.acknowledgeReceipt("account", "session", "operation", 1))
            assertEquals(listOf(3L, 4L), sequences(store))
            assertTrue(store.acknowledgeReceipt("account", "session", "operation", 4))
            assertEquals(listOf(3L), sequences(store))
            assertTrue(store.acknowledgeReceipt("account", "session", "operation", 3))
            assertEquals(emptyList<Long>(), sequences(store))
            val record = store.find("account", "session", "operation")!!
            assertEquals(4L, record.getJSONObject("latest_receipt").getLong("receipt_seq"))
            assertFalse(store.claim("account", "session", "operation", "create_task", hash))
        }
    }

    @Test fun invalidMetadataDoesNotLeakSecretsOrConsumeSequence() = isolated { context ->
        ServerOperationStore(context).use { store ->
            store.claim("account", "session", "operation", "create_task", hash)
            val invalid = listOf(
                receipt().put("token", "synthetic-secret"), receipt().put("goal", "synthetic-task-text"),
                receipt().put("password", "synthetic-password"), receipt().put("task_revision", -1),
                receipt().put("task_revision", 1.5), receipt().put("task_revision", "1"),
                receipt().put("error_code", "free form text"), receipt("unrecognized"),
                receipt().put("task_id", "unattached-task"), receipt().put("task_status", "unknown")
            )
            invalid.forEach { assertTrue(runCatching { store.appendReceipt("account", "session", "operation", it) }.isFailure) }
            assertTrue(runCatching { store.appendReceipt("account", "other-session", "operation", receipt()) }.isFailure)
            assertEquals(emptyList<JSONObject>(), store.pendingReceipts("account", "session"))
            assertEquals(1L, store.appendReceipt("account", "session", "operation", receipt("rejected")).getLong("receipt_seq"))
            assertTrue(runCatching { store.acknowledgeReceipt("account", "session", "operation", 0) }.isFailure)
        }
    }
}

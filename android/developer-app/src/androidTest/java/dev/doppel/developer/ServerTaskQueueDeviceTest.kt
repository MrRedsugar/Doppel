@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual PC command host + SQLite receipt ledger, only in a fresh disposable queueqa installation. */
class ServerTaskQueueDeviceTest {
    @Test fun remoteAdmissionReplayAndCancellationShareTheManualFifo() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        assertEquals("dev.doppel.queueqa", app.packageName)
        assertEquals("true", InstrumentationRegistry.getArguments().getString("queue_qa"))
        assertNull(DeviceWorkerService.instance)
        assertNull(DirectRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }.get(null))
        assertFalse("Fresh disposable data is required", File(app.noBackupFilesDir, "direct-runs-v1.json").exists())
        val prefs = app.getSharedPreferences("doppel", 0)
        check(prefs.edit().putBoolean("direct_mode", true).putString("device_id", DirectRuntime.DEVICE_ID)
            .putBoolean("queue_dispatch_paused", true).putBoolean("touch_pause", false).remove("active_run").commit())
        check(FirstUseConsent.accept(app)); check(FirstUseConsent.finishGuide(app))
        val providers = ModelProviders(app)
        val provider = ModelProvider("queue-host-fixture", "仅本机队列回执验证", "http://127.0.0.1:9/v1")
        providers.saveProvider(provider, "synthetic-never-sent", emptyMap())
        providers.recordVision(provider.id, "fixture", ModelVision.VERIFIED, providers.requestTarget(provider.id, "fixture").fingerprint)
        providers.saveRouting(ModelRouting(ModelSelection(provider.id, "fixture"), false, ModelSelection(provider.id, "fixture")))
        assertTrue(providers.isReady())
        val gateway = Gateway(app)
        val host = ServerTaskHost(app)
        val session = ServerTaskHost.SessionRef("queue-account", "queue-phone-session", 1)
        fun command(operation: String, kind: String, payload: JSONObject) = JSONObject().put("type", "command")
            .put("operation_id", operation).put("session_id", session.sessionId).put("kind", kind)
            .put("expires_at_ms", System.currentTimeMillis() + 120000).put("payload", payload)
        fun accept(input: JSONObject): JSONObject {
            val done = CountDownLatch(1); var receipt: JSONObject? = null
            host.accept(session, input, { true }, { true }) { receipt = it; done.countDown() }
            assertTrue("Host failed to publish a receipt", done.await(15, TimeUnit.SECONDS))
            return requireNotNull(receipt)
        }
        fun get(id: String) = gateway.request("GET", "/runs/$id")
        fun queue(): List<String> = gateway.request("GET", "/devices/${DirectRuntime.DEVICE_ID}/queue").getJSONArray("items")
            .let { rows -> (0 until rows.length()).map { rows.getJSONObject(it).getString("id") } }
        fun cancel(operation: String, id: String) = accept(command(operation, "control_task", JSONObject()
            .put("task_id", id).put("action", "cancel").put("expected_task_revision", get(id).getLong("revision"))
            .put("expected_phone_session_id", session.sessionId)))
        val a = gateway.request("POST", "/runs", JSONObject().put("device_id", DirectRuntime.DEVICE_ID)
            .put("goal", "Manual queue host fixture A").put("mode", "assist").put("defer_start", true))
        val aId = a.getString("id")
        gateway.request("POST", "/runs/$aId/start", JSONObject())
        check(prefs.edit().putString("active_run", aId).commit())
        try {
            val inputB = command("create-b", "create_task", JSONObject().put("goal", "PC queued B").put("mode", "assist"))
            val b = accept(inputB)
            assertEquals("accepted_by_phone", b.getString("phase")); assertEquals("queued", b.getString("task_status"))
            val bId = b.getString("task_id")
            assertEquals("An acknowledged retry must return its exact persisted receipt", b.toString(), accept(inputB).toString())
            val c = accept(command("create-c", "create_task", JSONObject().put("goal", "PC queued C").put("mode", "assist")))
            assertEquals("accepted_by_phone", c.getString("phase")); assertEquals("queued", c.getString("task_status"))
            val cId = c.getString("task_id")
            assertEquals(listOf(aId, bId, cId), queue())
            assertEquals(aId, prefs.getString("active_run", ""))
            val ticket = TaskControl.currentGeneration()
            val cancelled = cancel("cancel-b", bId)
            assertEquals("control_applied", cancelled.getString("phase")); assertEquals("cancelled", cancelled.getString("task_status"))
            assertEquals("PC queued cancellation must not invalidate manual A", ticket, TaskControl.currentGeneration())
            assertEquals("running", get(aId).getString("status")); assertEquals(listOf(aId, cId), queue())
            assertTrue("create_task stays offered while another task is unfinished",
                host.snapshot(session).getJSONArray("available_operations").toString().contains("create_task"))
            gateway.request("POST", "/runs/$aId/cancel", JSONObject())
            check(prefs.edit().remove("active_run").commit())
            gateway.request("POST", "/runs/$cId/start", JSONObject())
            val running = host.pendingReceipts(session).filter { it.getString("operation_id") == "create-c" }
            assertEquals(listOf("queued", "running"), running.map { it.getString("task_status") })
            assertEquals(listOf(1L, 2L), running.map { it.getLong("receipt_seq") })
            assertTrue(host.acknowledgeReceipt(session, "create-c", 1))
            assertEquals(listOf(2L), host.pendingReceipts(session).filter { it.getString("operation_id") == "create-c" }.map { it.getLong("receipt_seq") })
            assertEquals("cancelled", cancel("cancel-c", cId).getString("task_status"))
            val finished = host.pendingReceipts(session).filter { it.getString("operation_id") == "create-c" }
            assertEquals(listOf("running", "cancelled"), finished.map { it.getString("task_status") })
            assertEquals("A cancelled command cannot be replayed as a new task", "cancelled", accept(inputB).getString("task_status"))
            assertTrue(queue().isEmpty())
            assertEquals(3, gateway.request("GET", "/runs").getJSONArray("items").length())
            for (id in listOf(aId, bId, cId)) assertEquals("This admission test must never call a model", 0, get(id).optInt("calls"))
            val dir = File(app.getExternalFilesDir(null), "task-queue").apply { check(isDirectory || mkdirs()) }
            File(dir, "pc-host.json").writeText(JSONObject().put("passed", true).put("paid_requests", 0)
                .put("accepted_status", "queued").put("replay_deduplicated", true).put("manual_generation_preserved", true)
                .put("c_receipt_states", JSONArray(listOf("queued", "running", "cancelled"))).toString(2))
        } finally {
            app.stopService(Intent(app, DeviceWorkerService::class.java))
        }
    }
}

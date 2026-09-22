@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.FirstUseConsent
import dev.doppel.sdk.Gateway
import dev.doppel.sdk.TaskReviewStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Legacy review journal recovery and connection isolation; no UI, model or task. */
class TaskReviewRecoveryDeviceTest {
    @get:Rule val terminalPointer: org.junit.rules.TestRule = TerminalTaskPointerTestRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val base get() = inst.targetContext
    private fun isolated(block: (Context, Gateway, MemoryServer) -> Unit) {
        val prefix = "qa-review-${UUID.randomUUID()}-"
        val fixture = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
        MemoryServer().use { server ->
            try {
                FirstUseConsent.accept(fixture)
                val gateway = Gateway(fixture)
                check(gateway.prefs.edit().putBoolean("direct_mode", false).putString("token", "synthetic-review")
                    .putString("device_id", "review-fixture").putString("base_url", server.url).commit())
                block(fixture, gateway, server)
            } finally { listOf("doppel", "doppel_consent", "doppel_task_reviews").forEach { base.deleteSharedPreferences(prefix + it) } }
        }
    }

    @Test fun rejectedSaveRetriesOneJournalButUncertainDeliveryOnlyVerifiesWithoutAnotherPost() = isolated { context, gateway, server ->
        val store = TaskReviewStore(context)
        val item = store.add("fixture-run", "复盘验收", "先确认实际页面再操作", "failed", gateway.reviewScope())
        val id = item.getString("id")
        assertEquals(id, TaskReviewStore(context).add("fixture-run", "复盘验收", item.getString("note"), "failed", gateway.reviewScope()).getString("id"))
        assertEquals("failed", store.submit(id, gateway).getString("sync_state"))
        assertEquals(1, server.posts.get())
        assertEquals("failed", TaskReviewStore(context).get(id).getString("sync_state"))
        server.mode = "success"
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val first = executor.submit<JSONObject> { TaskReviewStore(context).submit(id, gateway) {
            started.countDown(); check(release.await(10, TimeUnit.SECONDS))
        } }
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))
            assertEquals("pending", TaskReviewStore(context).get(id).getString("sync_state"))
            assertEquals("pending", store.submit(id, gateway).getString("sync_state"))
        } finally { release.countDown(); executor.shutdown() }
        assertEquals("saved", first.get(10, TimeUnit.SECONDS).getString("sync_state"))
        assertEquals("saved", store.submit(id, gateway).getString("sync_state"))
        assertEquals(2, server.posts.get())
        assertEquals(1, store.forRun("fixture-run").size)

        val uncertain = store.add("fixture-run", "复盘验收", "先关闭弹窗再继续操作", "failed", gateway.reviewScope()).getString("id")
        server.mode = "lost-response"
        assertEquals("unknown", store.submit(uncertain, gateway).getString("sync_state"))
        assertEquals(3, server.posts.get())
        assertEquals("unknown", TaskReviewStore(context).submit(uncertain, gateway).getString("sync_state"))
        assertEquals("Never repeat a POST whose successful response was lost", 3, server.posts.get())
        assertEquals("saved", store.verify(uncertain, gateway).getString("sync_state"))
        assertEquals(3, server.posts.get())
        assertEquals(2, store.forRun("fixture-run").size)

        val prefs = context.getSharedPreferences("doppel_task_reviews", 0)
        val pending = JSONObject().put("id", "orphan").put("run_id", "old-run").put("note", "旧纠错")
            .put("scope", gateway.reviewScope()).put("sync_state", "pending").put("sync_session", "dead-process")
        check(prefs.edit().putString("items", JSONArray().put(pending).put(JSONObject().put("id", "legacy").put("run_id", "old-run").put("note", "旧格式")).toString()).commit())
        assertTrue(TaskReviewStore(context).forRun("old-run").all { it.getString("sync_state") == "unknown" })
        assertEquals("unknown", store.submit("orphan", gateway).getString("sync_state"))
        assertEquals(3, server.posts.get())
        check(prefs.edit().putString("items", "broken-fixture").commit())
        assertThrows(IllegalStateException::class.java) { store.add("new-run", "", "保留原始记录", "failed") }
        assertEquals("broken-fixture", prefs.getString("items", ""))
    }

    @Test fun legacyReviewJournalReopensAndRetriesWithoutDuplicatingLocalOrRemoteMemory() = isolated { context, gateway, server ->
        val runId = "qa-review-${UUID.randomUUID()}"
        val note = "先确认页面标题，再继续操作"
        val store = TaskReviewStore(context)
        val id = store.add(runId, "纠错失败恢复验收", note, "failed", gateway.reviewScope()).getString("id")
        assertEquals("failed", store.submit(id, gateway).getString("sync_state"))
        val reopened = TaskReviewStore(context)
        assertEquals("failed", reopened.forRun(runId, gateway.reviewScope()).single().getString("sync_state"))
        assertEquals(note, reopened.get(id).getString("note"))
        server.mode = "success"
        val saved = reopened.submit(id, gateway)
        assertEquals("saved", saved.getString("sync_state"))
        assertEquals(2, server.posts.get())
        val finalStore = TaskReviewStore(context)
        assertEquals("saved", finalStore.forRun(runId, gateway.reviewScope()).single().getString("sync_state"))
        assertEquals(saved.getString("memory_id"), finalStore.submit(id, gateway).getString("memory_id"))
        val remote = gateway.captureReviewConnection().request("GET", "/memories").getJSONArray("items")
        assertEquals(1, remote.length())
        assertEquals(saved.getString("memory_id"), remote.getJSONObject(0).getString("id"))
        assertTrue(remote.getJSONObject(0).getString("content").contains(note))
        assertEquals("Reopening saved journal entries must not repeat a POST", 2, server.posts.get())
    }

    @Test fun connectionChangedAfterClaimStillSendsOnlyToCapturedOriginAndCredential() = isolated { context, gateway, original ->
        MemoryServer().use { replacement ->
            original.mode = "success"; replacement.mode = "success"
            val store = TaskReviewStore(context)
            val scope = gateway.reviewScope()
            val item = store.add("scope-fixture", "连接切换验收", "保存到原账号的操作纠错", "failed", scope)
            val capturedRead = gateway.captureReviewConnection()
            val saved = store.submit(item.getString("id"), gateway) {
                // This is the former TOCTOU window: claim is durable, HTTP has not begun.
                check(gateway.prefs.edit().putString("base_url", replacement.url).putString("token", "synthetic-new-account")
                    .putString("device_id", "new-account-device").commit())
            }
            assertEquals("saved", saved.getString("sync_state"))
            assertEquals(scope, saved.getString("scope"))
            assertNotEquals(scope, gateway.reviewScope())
            assertEquals(1, original.posts.get())
            assertEquals("Bearer synthetic-review", original.lastAuthorization)
            assertEquals("No content or token may reach the newly selected account", 0, replacement.requests.get())
            val found = capturedRead.request("GET", "/memories").getJSONArray("items")
            assertEquals(1, found.length())
            assertEquals("Bearer synthetic-review", original.lastAuthorization)
            assertEquals(0, replacement.requests.get())
            assertEquals(0, store.forRun("scope-fixture", gateway.reviewScope()).size)
        }
    }

    private class MemoryServer : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val posts = AtomicInteger()
        val requests = AtomicInteger()
        @Volatile var lastAuthorization = ""
        @Volatile var mode = "reject"
        private val items = JSONArray()
        val url = "http://127.0.0.1:${server.localPort}"
        private val work = executor.submit {
            try { while (!server.isClosed) server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream(); val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n")) { val next = input.read(); check(next >= 0 && header.length < 16384); header.append(next.toChar()) }
                val first = header.lineSequence().first()
                requests.incrementAndGet()
                lastAuthorization = header.lineSequence().firstOrNull { it.startsWith("Authorization:", true) }
                    ?.substringAfter(':')?.trim().orEmpty()
                val size = Regex("(?im)^Content-Length: (\\d+)").find(header)?.groupValues?.get(1)?.toInt() ?: 0
                val body = ByteArray(size); var offset = 0
                while (offset < size) { val n = input.read(body, offset, size - offset); check(n > 0); offset += n }
                var code = 200
                val response = if (first.startsWith("POST /v1/memories ")) {
                    posts.incrementAndGet()
                    if (mode == "reject") { code = 401; "{}" } else {
                        val item = JSONObject(String(body, Charsets.UTF_8)).put("id", "fixture-memory-${posts.get()}")
                        items.put(item)
                        if (mode == "lost-response") "{" else item.toString()
                    }
                } else if (first.startsWith("GET /v1/memories ")) JSONObject().put("items", items).toString()
                else "{\"items\":[]}"
                val bytes = response.toByteArray(Charsets.UTF_8)
                socket.getOutputStream().apply {
                    write("HTTP/1.1 $code Fixture\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    write(bytes); flush()
                }
            } } catch (error: java.net.SocketException) { if (!server.isClosed) throw error }
        }
        override fun close() { server.close(); try { work.get(10, TimeUnit.SECONDS) } finally { executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS) } }
    }
}

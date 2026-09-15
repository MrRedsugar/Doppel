@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.ClientActivity
import dev.doppel.sdk.FirstUseConsent
import dev.doppel.sdk.Gateway
import dev.doppel.sdk.UsageBalance
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Only isolated preferences and a loopback HTTP fixture; no model, task or user credential access. */
class DelayedReplyAndBalanceDeviceTest {
    private fun isolated(block: (Gateway) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "late-response-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(ignored: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        try {
            FirstUseConsent.accept(context)
            val gateway = Gateway(context)
            check(gateway.prefs.edit().putBoolean("direct_mode", false).putString("token", "synthetic-fixture")
                .putString("device_id", "fixture-device").commit())
            block(gateway)
        } finally { base.deleteSharedPreferences(name) }
    }

    private fun usage(input: Long) = JSONObject().put("source", "billing_calls")
        .put("lifetime_input_tokens", input).put("lifetime_output_tokens", 0)

    @Test fun staleConversationOrTaskTailIsRejectedBeforeAnyContextHttpRequest() = isolated { gateway ->
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        check(gateway.prefs.edit().putString("base_url", "http://127.0.0.1:${server.localPort}").commit())
        val served = executor.submit {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream(); val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) {
                        val next = input.read(); check(next >= 0 && header.length < 16384); header.append(next.toChar())
                    }
                    requests.incrementAndGet()
                    val body = "{\"items\":[]}".toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        write(body); flush()
                    }
                }
            } catch (error: java.net.SocketException) { if (!server.isClosed) throw error }
        }
        try {
            gateway.startNewConversation()
            val original = gateway.conversationKey()
            check(gateway.prefs.edit().putString("conversation_tail", "original-task").commit())
            gateway.appendConversationReply(original, "原对话", "原回复", null)
            gateway.startNewConversation()
            val current = gateway.conversationKey()
            check(gateway.prefs.edit().putString("conversation_tail", "new-task").commit())
            val before = gateway.prefs.all.toMap()
            val changedConversation = assertThrows(IllegalStateException::class.java) {
                gateway.conversationContext(original, "original-task")
            }
            assertTrue(changedConversation.message.orEmpty().contains("重新发送"))
            val changedTail = assertThrows(IllegalStateException::class.java) {
                gateway.conversationContext(current, "original-task")
            }
            assertTrue(changedTail.message.orEmpty().contains("重新发送"))
            assertEquals("Reject before requesting either old or new task history", 0, requests.get())
            assertEquals("Rejected submissions cannot migrate or modify another conversation", before, gateway.prefs.all)
            assertEquals(0, gateway.conversationContext(current, "new-task").length())
            assertEquals("The fixture proves valid context requests still reach HTTP", 1, requests.get())
        } finally {
            server.close()
            try { served.get(10, TimeUnit.SECONDS) }
            finally { executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS) }
        }
    }

    @Test fun lateRepliesPersistToCapturedConversationWithoutChangingSelectionOrNewerDraft() = isolated { gateway ->
        val original = gateway.conversationKey()
        gateway.appendConversationReply(original, "原问题", "原回复", "原对话")
        gateway.startNewConversation()
        val selected = gateway.conversationKey()
        check(gateway.prefs.edit().putString("draft_goal", "新对话尚未发送的草稿").commit())
        lateinit var activity: ClientActivity
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Detached screen has no UI to receive the result, just as after destruction.
            activity = ClientActivity()
            ClientActivity::class.java.getDeclaredField("gateway").apply { isAccessible = true }.set(activity, gateway)
        }
        val persist = ClientActivity::class.java.getDeclaredMethod("persistConversationReply", JSONObject::class.java,
            String::class.java, String::class.java, String::class.java).apply { isAccessible = true }
        assertEquals(true, persist.invoke(activity, JSONObject().put("intent", "conversation").put("reply", "付费请求完成后的回复"),
            "离开页面前的问题", original, "captured-task"))
        assertEquals(selected, gateway.conversationKey())
        assertEquals(0, gateway.conversationMessages().length())
        assertEquals("新对话尚未发送的草稿", gateway.prefs.getString("draft_goal", null))
        val old = JSONObject(gateway.prefs.getString("conversation_chat_$original", null)!!).getJSONArray("messages")
        assertEquals(4, old.length())
        assertEquals("付费请求完成后的回复", old.getJSONObject(3).getString("content"))
        assertEquals("captured-task", old.getJSONObject(3).getString("after_run_id"))

        val beforeTask = gateway.prefs.all.toMap()
        assertEquals(false, persist.invoke(activity, JSONObject().put("intent", "task").put("confidence", 1.0)
            .put("task_goal", "打开计算器"), "打开计算器", original, "captured-task"))
        assertEquals("A late task must not create or associate a run", beforeTask, gateway.prefs.all)

        assertEquals(true, persist.invoke(activity, JSONObject().put("intent", "uncertain").put("question", "请选择要操作的应用"),
            "信息不足的问题", original, "captured-task"))
        val finalMessages = JSONObject(gateway.prefs.getString("conversation_chat_$original", null)!!).getJSONArray("messages")
        assertEquals("请选择要操作的应用", finalMessages.getJSONObject(5).getString("content"))
        assertEquals(selected, gateway.conversationKey())
    }

    @Test fun concurrentRefreshAndRechargeKeepTheDepositAndDebitNewUsageOnlyOnce() = isolated { gateway ->
        gateway.updateUsageBalance(usage(1_000_000), "baseline") { _, _, editor -> editor.putString("balance", "100.0") }
        val executor = Executors.newFixedThreadPool(2)
        val refreshRead = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val rechargeAttempted = CountDownLatch(1)
        val rechargeEntered = CountDownLatch(1)
        try {
            val refresh = executor.submit {
                gateway.updateUsageBalance(usage(2_000_000), "baseline") { before, now, editor ->
                    val remaining = gateway.prefs.getString("balance", "")!!.toDouble() - UsageBalance.cost(before, now, 2.0, 2.0)
                    refreshRead.countDown()
                    check(releaseRefresh.await(10, TimeUnit.SECONDS))
                    editor.putString("balance", remaining.toString())
                }
            }
            assertTrue(refreshRead.await(10, TimeUnit.SECONDS))
            val recharge = executor.submit {
                rechargeAttempted.countDown()
                gateway.updateUsageBalance(usage(2_000_000), "baseline") { before, now, editor ->
                    rechargeEntered.countDown()
                    val remaining = gateway.prefs.getString("balance", "")!!.toDouble() - UsageBalance.cost(before, now, 2.0, 2.0)
                    editor.putString("balance", (remaining + 10.0).toString())
                }
            }
            assertTrue(rechargeAttempted.await(10, TimeUnit.SECONDS))
            assertFalse("A recharge cannot read a partly committed refresh", rechargeEntered.await(250, TimeUnit.MILLISECONDS))
            releaseRefresh.countDown()
            refresh.get(10, TimeUnit.SECONDS); recharge.get(10, TimeUnit.SECONDS)
            assertEquals(108.0, gateway.prefs.getString("balance", "")!!.toDouble(), 0.0)
            // A delayed pre-refresh response must not lower the baseline and charge those tokens again.
            gateway.updateUsageBalance(usage(1_000_000), "baseline") { before, now, editor ->
                editor.putString("balance", (gateway.prefs.getString("balance", "")!!.toDouble() - UsageBalance.cost(before, now, 2.0, 2.0)).toString())
            }
            assertEquals(2_000_000L, JSONObject(gateway.prefs.getString("baseline", null)!!).getLong("input"))
            assertEquals(108.0, gateway.prefs.getString("balance", "")!!.toDouble(), 0.0)
        } finally {
            releaseRefresh.countDown(); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Test fun usageResponseCannotBeRelabeledAfterConnectionChangesDuringOrAfterTheRequest() = isolated { gateway ->
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 10000 }
        val executor = Executors.newFixedThreadPool(2)
        val requested = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        check(gateway.prefs.edit().putString("base_url", "http://127.0.0.1:${server.localPort}").commit())
        try {
            val served = executor.submit {
                repeat(2) { index -> server.accept().use { socket ->
                    socket.soTimeout = 10000
                    val header = StringBuilder(); val input = socket.getInputStream()
                    while (!header.endsWith("\r\n\r\n")) {
                        val next = input.read(); check(next >= 0 && header.length < 16384); header.append(next.toChar())
                    }
                    assertTrue(header.startsWith("GET /v1/usage "))
                    if (index == 0) { requested.countDown(); check(releaseResponse.await(10, TimeUnit.SECONDS)) }
                    val body = usage(1_000_000).toString().toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        write(body); flush()
                    }
                } }
            }
            val first = executor.submit<Boolean> { runCatching { gateway.readUsage() }.isFailure }
            assertTrue(requested.await(10, TimeUnit.SECONDS))
            check(gateway.prefs.edit().putString("token", "synthetic-other-account").commit())
            releaseResponse.countDown()
            assertTrue("Reject an old connection's HTTP response", first.get(10, TimeUnit.SECONDS))

            val response = gateway.readUsage()
            served.get(10, TimeUnit.SECONDS)
            check(gateway.prefs.edit().putString("token", "synthetic-third-account").commit())
            val before = gateway.prefs.all.toMap()
            assertThrows(IllegalStateException::class.java) {
                gateway.updateUsageBalance(response, "baseline") { _, _, editor -> editor.putString("balance", "0") }
            }
            assertEquals("A connection change before commit must preserve all balance data", before, gateway.prefs.all)
        } finally {
            releaseResponse.countDown(); server.close(); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }
}

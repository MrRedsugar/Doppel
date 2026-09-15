@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.Gateway
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real Android persistence with isolated preferences. No requests, runtime, worker or model configuration. */
class ConversationStorageDeviceTest {
    @Test fun taskCreationKeepsAReplySavedWhileTheTaskRequestIsInFlight() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "conversation-race-${UUID.randomUUID()}-"; val names = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                names += prefix + name
                return base.getSharedPreferences(prefix + name, mode)
            }
        }
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).apply { soTimeout = 10000 }
        val requested = java.util.concurrent.CountDownLatch(1); val replySaved = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            assertTrue(dev.doppel.sdk.FirstUseConsent.accept(context))
            val gateway = Gateway(context)
            assertTrue(gateway.prefs.edit().putBoolean("direct_mode", false).putString("base_url", "http://127.0.0.1:${server.localPort}")
                .putString("device_id", "fixture-device").putString("token", "synthetic-fixture-token").commit())
            val key = gateway.conversationKey()
            gateway.appendConversationReply(key, "先讨论", "可以", "并发保存回归")
            val serverResult = executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 10000
                    val input = socket.getInputStream(); val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) {
                        val next = input.read(); check(next >= 0 && header.length < 16384); header.append(next.toChar())
                    }
                    assertTrue(header.startsWith("POST /v1/runs "))
                    val length = Regex("(?im)^Content-Length: (\\d+)").find(header)?.groupValues?.get(1)?.toInt() ?: 0
                    repeat(length) { check(input.read() >= 0) }
                    requested.countDown()
                    check(replySaved.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    val body = "{\"id\":\"fixture-run-concurrent\",\"title\":\"任务标题\"}".toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        write(body); flush()
                    }
                }
            }
            val created = executor.submit<JSONObject> {
                gateway.createConversationRun(JSONObject().put("device_id", "fixture-device").put("goal", "仅创建合成任务").put("mode", "assist"))
            }
            assertTrue("Wait until the request is actually in flight", requested.await(10, java.util.concurrent.TimeUnit.SECONDS))
            gateway.appendConversationReply(key, "请求期间补充", "补充已保存", null)
            replySaved.countDown()
            val run = created.get(10, java.util.concurrent.TimeUnit.SECONDS)
            serverResult.get(10, java.util.concurrent.TimeUnit.SECONDS)
            val reopened = Gateway(context)
            assertEquals(4, reopened.conversationMessages().length())
            assertEquals("补充已保存", reopened.conversationMessages().getJSONObject(3).getString("content"))
            assertEquals("并发保存回归", reopened.conversationTitle())
            assertEquals(run.getString("id"), reopened.selectedConversationRun())
            assertEquals(4, reopened.conversationMessagesForRun(run.getString("id")).length())
        } finally {
            replySaved.countDown(); server.close(); executor.shutdownNow()
            executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
            names.forEach { base.deleteSharedPreferences(it) }
        }
    }

    @Test fun longHistoryIsPreservedAndFullStorageRejectsNewReplyWithoutDeletingAnything() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "conversation-capacity-${UUID.randomUUID()}-"
        val names = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                names += prefix + name
                return base.getSharedPreferences(prefix + name, mode)
            }
        }
        try {
            val gateway = Gateway(context)
            assertTrue(gateway.prefs.edit().putBoolean("direct_mode", false).putString("draft_goal", "新草稿仍须保留").commit())
            val key = gateway.conversationKey()
            repeat(25) { gateway.appendConversationReply(key, "用户 $it", "回复 $it", "完整历史") }
            assertEquals(50, Gateway(context).conversationMessages().length())
            assertEquals("用户 0", Gateway(context).conversationMessages().getJSONObject(0).getString("content"))
            assertEquals(12, gateway.conversationContext().length())
            var rejected = false
            repeat(100) {
                if (!rejected) {
                    val before = gateway.conversationMessages().toString()
                    try { gateway.appendConversationReply(key, "用".repeat(12000), "回".repeat(12000), null) }
                    catch (error: IllegalStateException) {
                        assertTrue(error.message.orEmpty().contains("容量上限"))
                        assertEquals(before, Gateway(context).conversationMessages().toString())
                        rejected = true
                    }
                }
            }
            assertTrue("Bound storage explicitly rather than silently deleting history", rejected)
            assertEquals("新草稿仍须保留", gateway.prefs.getString("draft_goal", ""))
        } finally { names.forEach { base.deleteSharedPreferences(it) } }
    }

    @Test fun completedReplyClearsOnlyItsOwnDraftWhileANewerDraftSurvives() {
        val inst = InstrumentationRegistry.getInstrumentation(); val base = inst.targetContext
        val name = "conversation-draft-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(ignored: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        val gateway = Gateway(context)
        var failure: Throwable? = null
        try {
            inst.runOnMainSync {
                try {
                    // Call the production cleanup on a detached activity; no host lifecycle/settings are touched.
                    val activity = dev.doppel.sdk.ClientActivity()
                    val editor = android.widget.EditText(base)
                    dev.doppel.sdk.ClientActivity::class.java.getDeclaredField("gateway").apply { isAccessible = true }.set(activity, gateway)
                    dev.doppel.sdk.ClientActivity::class.java.getDeclaredField("goal").apply { isAccessible = true }.set(activity, editor)
                    val cleanup = dev.doppel.sdk.ClientActivity::class.java.getDeclaredMethod("clearSubmittedChatDraft", String::class.java).apply { isAccessible = true }
                    editor.setText("下一条输入")
                    assertTrue(gateway.prefs.edit().putString("draft_goal", "下一条输入").commit())
                    cleanup.invoke(activity, "上一条已发送内容")
                    assertEquals("下一条输入", editor.text.toString()); assertEquals("下一条输入", gateway.prefs.getString("draft_goal", ""))
                    editor.setText("  已发送内容  ")
                    assertTrue(gateway.prefs.edit().putString("draft_goal", "  已发送内容  ").commit())
                    cleanup.invoke(activity, "已发送内容")
                    assertEquals("", editor.text.toString()); assertEquals("", gateway.prefs.getString("draft_goal", "not-cleared"))
                } catch (error: Throwable) { failure = error }
            }
            failure?.let { throw it }
        } finally { base.deleteSharedPreferences(name) }
    }

    @Test fun discussionsSurviveNewConversationAndGatewayRecreationWithoutCrossingConnectionScopes() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "conversation-storage-${UUID.randomUUID()}-"
        val names = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                names += prefix + name
                return base.getSharedPreferences(prefix + name, mode)
            }
        }
        val realPrefsBefore = base.getSharedPreferences("doppel", 0).all.toMap()
        val prefs = context.getSharedPreferences("doppel", 0)
        try {
            assertTrue(prefs.edit().putBoolean("direct_mode", false).putString("base_url", "https://fixture.invalid")
                .putString("device_id", "fixture-device").putString("token", "fixture-account-one").commit())
            var gateway = Gateway(context)
            val firstKey = gateway.conversationKey()
            gateway.appendConversationReply(firstKey, "第一段用户消息", "第一段回复", "第一段标题")
            assertEquals(2, gateway.conversationMessages().length())
            assertEquals("第一段标题", gateway.conversationTitle())

            gateway.startNewConversation()
            val secondKey = gateway.conversationKey()
            assertNotEquals(firstKey, secondKey)
            assertEquals(0, gateway.conversationMessages().length())
            gateway.appendConversationReply(secondKey, "第二段用户消息", "第二段回复", "第二段标题")
            gateway = Gateway(context)
            assertEquals(secondKey, gateway.conversationKey())
            assertEquals("第二段用户消息", gateway.conversationMessages().getJSONObject(0).getString("content"))
            assertEquals(2, gateway.savedConversations().length())

            gateway.selectConversation(firstKey)
            assertEquals("第一段标题", gateway.conversationTitle())
            assertEquals("第一段用户消息", gateway.conversationMessages().getJSONObject(0).getString("content"))
            val beforeStaleReply = prefs.all.toMap()
            assertThrows(IllegalStateException::class.java) { gateway.appendConversationReply(secondKey, "过期问题", "过期回复", null) }
            assertTrue("A stale model reply cannot enter either conversation", beforeStaleReply == prefs.all)
            gateway.conversationMessages().getJSONObject(0).put("content", "caller mutation")
            assertEquals("第一段用户消息", Gateway(context).conversationMessages().getJSONObject(0).getString("content"))

            assertTrue(prefs.edit().putString("token", "fixture-account-two").commit())
            val otherAccount = Gateway(context)
            assertEquals(0, otherAccount.savedConversations().length())
            assertEquals(0, otherAccount.conversationMessages().length())
            assertThrows(IllegalArgumentException::class.java) { otherAccount.selectConversation(firstKey) }
            assertTrue(prefs.edit().putString("token", "fixture-account-one").commit())
            assertEquals("第一段用户消息", Gateway(context).conversationMessages().getJSONObject(0).getString("content"))

            // Migrate the former single chat buffer once, then preserve it when starting a new conversation.
            assertTrue(prefs.edit().clear().putBoolean("direct_mode", false).putString("base_url", "https://legacy.invalid")
                .putString("chat_title", "迁移的标题").putString("chat_messages", JSONArray()
                    .put(JSONObject().put("role", "user").put("content", "迁移的消息")).toString()).commit())
            gateway = Gateway(context)
            val legacyKey = gateway.conversationKey()
            gateway.startNewConversation()
            assertFalse(prefs.contains("chat_messages"))
            assertFalse(prefs.contains("chat_title"))
            assertEquals(0, gateway.conversationMessages().length())
            gateway.selectConversation(legacyKey)
            assertEquals("迁移的标题", gateway.conversationTitle())
            assertEquals("迁移的消息", gateway.conversationMessages().getJSONObject(0).getString("content"))
        } finally {
            names.forEach { base.deleteSharedPreferences(it) }
            assertTrue("Never change the user's task, model or conversation settings", realPrefsBefore == base.getSharedPreferences("doppel", 0).all)
        }
    }
}

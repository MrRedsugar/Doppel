@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Process
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Run seed, externally force-stop the host, then verify in a different PID, then cleanup.
 * Real SharedPreferences, Keystore, AtomicFile and engine; synthetic model output, no network/gestures.
 * It deliberately does not exercise the real host's worker or overwrite any user settings/history.
 */
class PersistentStateRestartDeviceTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "qa-persistent-restart-v1-"
    private val root get() = File(base.noBackupFilesDir, "qa-persistent-restart-v1")
    private val fixture get() = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName() = base.packageName + ".qa.persistent.restart"
        override fun getApplicationInfo() = ApplicationInfo(base.applicationInfo).apply { flags = flags and ApplicationInfo.FLAG_DEBUGGABLE.inv() }
        override fun getNoBackupFilesDir() = root
        override fun getFilesDir() = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences(prefix + name, mode)
    }
    private fun metadata() = fixture.getSharedPreferences("metadata", 0)
    private fun engine(file: File): SplitTaskEngine = SplitTaskEngine(if (file.exists()) AtomicFile(file).openRead().bufferedReader().use { it.readText() } else null, { value ->
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try { output.write(value.toByteArray()); atomic.finishWrite(output) } catch (error: Throwable) { atomic.failWrite(output); throw error }
    }, enhancementEnabled = { false })
    private fun create(engine: SplitTaskEngine, goal: String) = engine.create(JSONObject().put("device_id", "direct-this-phone").put("mode", "full").put("goal", goal)).getString("id")

    @Test fun seed() {
        assertTrue("Run cleanup before starting a new staged test", metadata().all.isEmpty())
        assertTrue(root.isDirectory || root.mkdirs())
        val context = fixture
        val prefs = context.getSharedPreferences("doppel", 0)
        assertTrue(prefs.edit().putBoolean("direct_mode", false).putString("base_url", "https://fixture.invalid")
            .putString("device_id", "restart-device").putString("token", "synthetic-restart-token")
            .putString("draft_goal", "尚未发送的新草稿").putInt("screenshot_retention_days", 30)
            .putBoolean("touch_pause", true).commit())
        val gateway = Gateway(context)
        val first = gateway.conversationKey()
        repeat(25) { gateway.appendConversationReply(first, "用户第 $it 条", "回复第 $it 条", "保留全部历史") }
        assertEquals("Regression: old code discarded turns beyond 40 messages", 50, gateway.conversationMessages().length())
        gateway.startNewConversation()
        val second = gateway.conversationKey()
        gateway.appendConversationReply(second, "另一个会话", "独立回复", "第二个标题")
        gateway.selectConversation(first)
        assertTrue(context.getSharedPreferences("doppel_ui", 0).edit().putString("appearance", "DARK").commit())
        assertTrue(FirstUseConsent.accept(context))
        val providers = ModelProviders(context)
        val selected = ModelSelection("restart-fixture", "fixture-vision")
        providers.saveProvider(ModelProvider(selected.providerId, "重启测试平台", "https://fixture.invalid/v1"), "sk-synthetic-restart-only", emptyMap())
        providers.saveRouting(ModelRouting(selected, false, selected))
        assertFalse("Synthetic auth must never be written as clear text", File(root, "model-providers-v1.bin").readText().contains("sk-synthetic-restart-only"))

        val running: SplitTaskEngine = engine(File(root, "direct-runs-v1.json"))
        val cancelled = create(running, "已经结束的测试任务")
        running.control(cancelled, "cancel", JSONObject())
        val active = create(running, "等待设备回执的测试任务")
        val screenshot = running.poll().getJSONObject("command")
        assertTrue(running.result(JSONObject().put("run_id", active).put("command_id", screenshot.getString("id")).put("status", "ok")
            .put("observation", JSONObject().put("package_name", "fixture.page").put("screen_id", "fixture-screen"))
            .put("data", JSONObject().put("image_base64", "cGl4ZWxz").put("visual_frame", JSONObject().put("capture_id", "fixture-capture")
                .put("display_width", 1000).put("display_height", 1000)))).getBoolean("accepted"))
        val wire = JSONObject().put("decision", JSONObject().put("kind", "back").put("target", "返回上一页")
            .put("expected", "上一页显示").put("screen_context", "测试页面")).put("state", JSONObject.NULL)
        running.accept(requireNotNull(running.takeWork()), JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("finish_reason", "stop").put("message", JSONObject().put("content", wire.toString())))))
        val delivered = running.poll().getJSONObject("command")
        assertEquals("back", delivered.getString("kind"))
        assertEquals("running", running.get(active).getString("status"))
        val pausedEngine: SplitTaskEngine = engine(File(root, "paused-runs.json"))
        val paused = create(pausedEngine, "已暂停的测试任务")
        pausedEngine.control(paused, "pause", JSONObject())
        TaskReviewStore(context).add(active, "测试任务", "先查看实际页面再继续", "paused")
        ModelUsageLedger(root).record(JSONObject().put("prompt_tokens", 19).put("completion_tokens", 3))
        val usage = ModelUsageLedger(root).snapshot()
        assertTrue(prefs.edit().putString("active_run", active).putString("balance_amount", "12.50")
            .putString("balance_usage_baseline", UsageBalance.snapshot(usage).toString()).commit())
        assertTrue(metadata().edit().putInt("seed_pid", Process.myPid()).putString("first", first).putString("second", second)
            .putString("active", active).putString("cancelled", cancelled).putString("paused", paused)
            .putString("old_command", delivered.getString("id")).putString("usage", usage.toString()).commit())
        report("seed", JSONObject().put("seed_pid", Process.myPid()).put("messages", 50).put("network_requests", 0).put("device_gestures", 0))
    }

    @Test fun verify() {
        val meta = metadata()
        val seedPid = meta.getInt("seed_pid", -1)
        assertTrue("seed must finish before the external force-stop", seedPid > 0)
        assertNotEquals("Activity recreation is insufficient; force-stop the host between stages", seedPid, Process.myPid())
        val context = fixture; val gateway = Gateway(context); val prefs = gateway.prefs
        assertEquals(meta.getString("first", ""), gateway.conversationKey())
        assertEquals("保留全部历史", gateway.conversationTitle())
        assertEquals(50, gateway.conversationMessages().length())
        assertEquals("用户第 0 条", gateway.conversationMessages().getJSONObject(0).getString("content"))
        assertEquals("回复第 24 条", gateway.conversationMessages().getJSONObject(49).getString("content"))
        assertEquals(12, gateway.conversationContext().length())
        assertEquals(2, gateway.savedConversations().length())
        gateway.selectConversation(meta.getString("second", "")!!)
        assertEquals("第二个标题", gateway.conversationTitle()); assertEquals(2, gateway.conversationMessages().length())
        gateway.selectConversation(meta.getString("first", "")!!)
        assertEquals("尚未发送的新草稿", prefs.getString("draft_goal", ""))
        assertEquals(30, prefs.getInt("screenshot_retention_days", 0))
        assertEquals("DARK", context.getSharedPreferences("doppel_ui", 0).getString("appearance", ""))
        assertTrue(FirstUseConsent.isAccepted(context))
        val providers = ModelProviders(context); val routing = providers.routing()
        assertEquals("restart-fixture", routing.primary.providerId); assertEquals("fixture-vision", routing.primary.model)
        assertFalse(routing.enhancementEnabled); assertTrue(providers.hasCredentials("restart-fixture"))
        assertEquals("重启测试平台", providers.list().single { it.id == "restart-fixture" }.name)
        val active = meta.getString("active", "")!!; val cancelled = meta.getString("cancelled", "")!!
        val restored: SplitTaskEngine = engine(File(root, "direct-runs-v1.json"))
        assertEquals("paused", restored.get(active).getString("status"))
        assertEquals("cancelled", restored.get(cancelled).getString("status"))
        repeat(3) { assertTrue("Cold start must not replay the in-flight back command", restored.poll().isNull("command")); assertNull(restored.takeWork()) }
        assertFalse(restored.get(active).has("pending_command")); assertFalse(restored.get(active).has("pending_request"))
        restored.control(active, "resume", JSONObject())
        val fresh = restored.poll().getJSONObject("command")
        assertEquals("screenshot", fresh.getString("kind")); assertNotEquals(meta.getString("old_command", ""), fresh.getString("id"))
        restored.control(active, "pause", JSONObject())
        val pausedEngine: SplitTaskEngine = engine(File(root, "paused-runs.json"))
        assertEquals("paused", pausedEngine.get(meta.getString("paused", "")!!).getString("status")); assertTrue(pausedEngine.poll().isNull("command"))
        assertEquals(1, TaskReviewStore(context).forRun(active).size)
        assertEquals("12.50", prefs.getString("balance_amount", ""))
        val usage = ModelUsageLedger(root).snapshot()
        assertEquals(JSONObject(meta.getString("usage", "")!!).getLong("lifetime_input_tokens"), usage.getLong("lifetime_input_tokens"))
        assertEquals(0.0, UsageBalance.cost(JSONObject(prefs.getString("balance_usage_baseline", "")!!), UsageBalance.snapshot(usage), 2.0, 4.0), 0.0)
        report("verify", JSONObject().put("seed_pid", seedPid).put("verify_pid", Process.myPid()).put("pid_changed", true)
            .put("messages", 50).put("restored_status", "paused").put("old_action_replayed", false).put("first_resumed_command", "screenshot"))
    }

    @Test fun cleanup() {
        ModelProviders(fixture).reset()
        for (name in listOf("metadata", "doppel", "doppel_ui", "doppel_consent", "doppel_task_reviews")) base.deleteSharedPreferences(prefix + name)
        val checked = root.canonicalFile
        assertEquals(File(base.noBackupFilesDir.canonicalFile, "qa-persistent-restart-v1"), checked)
        assertTrue(!checked.exists() || checked.deleteRecursively())
    }

    private fun report(stage: String, body: JSONObject) {
        val folder = File(base.getExternalFilesDir(null), "full-feature/persistent-state").apply { mkdirs() }
        File(folder, "$stage.json").writeText(body.put("status", "passed").put("isolated_namespace", prefix).toString(2))
    }
}

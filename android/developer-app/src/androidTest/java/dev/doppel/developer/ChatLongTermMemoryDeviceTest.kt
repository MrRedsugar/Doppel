@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")

package dev.doppel.developer

import android.app.Activity
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.doppel.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Fresh disposable queueqa installation per method, -e queue_qa true. Only loopback model traffic. */
class ChatLongTermMemoryDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val ui get() = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val gateway by lazy { Gateway(context) }
    private val runtime get() = DirectRuntime.get(context)
    private var automationFlags: Int? = null
    private val activities = mutableListOf<Activity>()

    @Test fun chatCorrectionSettingsEditAndNextTaskShareDurableMemory() {
        guard()
        LocalModel().use { model ->
            configure(model.port)
            launch()
            send(QUESTION)
            await("Ordinary chat reply must be saved") { gateway.conversationMessages().length() == 2 }
            assertEquals(0, memories().length())
            assertEquals(0, runs().length())
            assertNull("A discussion must not start the worker", DeviceWorkerService.instance)
            assertEquals(1, model.calls.get())

            send(CORRECTION)
            await("The composer correction must save a real memory") { memories().length() == 1 && gateway.conversationMessages().length() == 4 }
            val memoryId = memories().getJSONObject(0).getString("id")
            assertEquals(LESSON, memories().getJSONObject(0).getString("content"))
            assertEquals(0, runs().length())
            assertEquals("One A request must both answer and extract each correction", 2, model.calls.get())
            assertFalse("No separate review button is needed", visibleText().contains("复盘这次任务"))

            openMemories()
            screenshot("settings-before-edit.png")
            tap("编辑记忆 1")
            setText("记忆内容", EDITED)
            tap("保存")
            await("Settings edit must reach durable storage") { memories().getJSONObject(0).optString("content") == EDITED }
            assertEquals(memoryId, memories().getJSONObject(0).getString("id"))
            val reopened = TaskReviewMemory(File(context.noBackupFilesDir, "task-review-memory-v1.json"))
            assertEquals(EDITED, reopened.list().getJSONArray("items").getJSONObject(0).getString("content"))
            assertEquals("Editing memory must never call the model", 2, model.calls.get())
            screenshot("settings-edited.png")
            tap("返回")
            tap("导航菜单"); tap("当前任务")
            tap("新任务")
            send(NEXT_TASK)
            await("The next real UI task must finish", 30000) {
                gateway.selectedConversationRun()?.takeIf(String::isNotBlank)?.let { gateway.runStatus(it) == "completed" } == true
            }
            await("The task's planner request must be recorded") { model.planners.size == 1 }
            val planner = JSONObject(model.planners.single())
            assertEquals("The router's shortened interpretation cannot replace user constraints", NEXT_TASK, planner.getString("task"))
            assertEquals(RESOLVED_TASK, planner.getString("task_context"))
            assertTrue("The next task must actually receive the edited memory", planner.getJSONObject("review_memory").toString().contains(EDITED))
            assertFalse(planner.getJSONObject("review_memory").toString().contains(LESSON))
            val run = runtime.request("GET", "/runs/${gateway.selectedConversationRun()}", null)
            assertEquals(NEXT_TASK, run.getString("goal"))
            assertEquals(NEXT_TASK, run.getJSONObject("task_state").getString("goal"))
            assertEquals(NEXT_TASK, run.getJSONArray("conversation_messages").getJSONObject(0).getString("text"))
            assertEquals("Memory text cannot change execution mode", "assist", run.getString("mode"))
            assertEquals("Two chats, one task route and one planner request", 4, model.calls.get())
            assertEquals(1, runs().length())
            await("The completed task must present its result") { visibleText().contains("关闭结果") }
            tap("关闭结果")
            await("The result overlay must close before editing settings") { !visibleText().contains("关闭结果") }
            launch()
            openMemories()
            tap("编辑记忆 1"); tap("删除")
            await("Memory deletion must ask about the selected record") { visibleText().contains("删除这条记忆？") }
            tap("删除")
            await("Settings delete must persist") { memories().length() == 0 }
            assertEquals(0, reopened.list().getJSONArray("items").length())
            assertEquals(4, model.calls.get())
            model.assertHealthy()
            evidence("chat-settings-next-task", JSONObject().put("passed", true).put("model_requests", model.calls.get())
                .put("created_tasks", 1).put("memory_edited_and_deleted", true).put("planner_received_edited_memory", true))
        }
    }

    @Test fun retryAndLateReplyRemainBoundToTheOriginalConversation() {
        guard()
        LocalModel().use { model ->
            configure(model.port)
            val request = JSONObject().put("message", CORRECTION).put("history", JSONArray()).put("device_available", true)
                .put("run_id", "").put("conversation_id", gateway.conversationKey()).put("request_id", UUID.randomUUID().toString())
            val first = runtime.request("POST", "/conversation/intent", request)
            assertEquals("conversation", first.getString("intent"))
            assertEquals(1, memories().length())
            val saved = memories().toString()
            runtime.request("POST", "/conversation/intent", JSONObject(request.toString()))
            assertEquals("Retry must reuse the persisted A result", 1, model.calls.get())
            assertEquals(saved, memories().toString())
            val item = memories().getJSONObject(0)
            runtime.request("PATCH", "/memories/${item.getString("id")}", JSONObject().put("content", EDITED)
                .put("expected_revision", item.getLong("revision")))
            runtime.request("POST", "/conversation/intent", JSONObject(request.toString()))
            assertEquals("An old retry cannot overwrite a settings edit", EDITED, memories().getJSONObject(0).getString("content"))
            runtime.request("DELETE", "/memories/${item.getString("id")}", null)
            runtime.request("POST", "/conversation/intent", JSONObject(request.toString()))
            assertEquals("An old retry cannot resurrect a deleted memory", 0, memories().length())
            assertEquals(1, model.calls.get())

            // A terminal associated task provides a real source without executing any device action.
            val source = gateway.createConversationRun(JSONObject().put("device_id", DirectRuntime.DEVICE_ID)
                .put("goal", "仅关联迟到聊天的历史任务").put("mode", "assist"))
            val sourceId = source.getString("id")
            runtime.request("POST", "/runs/$sourceId/cancel", JSONObject())
            val originalKey = gateway.conversationKey()
            val old = launch()
            send(LATE_CORRECTION)
            assertTrue("The real composer request must reach the local model", model.blocked.await(15, TimeUnit.SECONDS))
            inst.runOnMainSync { old.finish() }
            gateway.startNewConversation()
            val selected = gateway.conversationKey()
            check(gateway.prefs.edit().putString("draft_goal", "新会话草稿必须保留").commit())
            model.release.countDown()
            await("A destroyed Activity must still save its reply to the original conversation") {
                JSONObject(gateway.prefs.getString("conversation_chat_$originalKey", "{}").orEmpty())
                    .optJSONArray("messages")?.length() == 2
            }
            val history = JSONObject(gateway.prefs.getString("conversation_chat_$originalKey", null)!!).getJSONArray("messages")
            assertEquals(LATE_CORRECTION, history.getJSONObject(0).getString("content"))
            assertEquals(sourceId, history.getJSONObject(1).getString("after_run_id"))
            assertEquals(selected, gateway.conversationKey())
            assertEquals(0, gateway.conversationMessages().length())
            assertEquals("新会话草稿必须保留", gateway.prefs.getString("draft_goal", null))
            val late = (0 until memories().length()).map { memories().getJSONObject(it) }.single { it.optString("content") == LATE_LESSON }
            assertEquals("A late memory must retain the captured task", sourceId, late.getString("run_id"))
            assertEquals(2, model.calls.get())

            val conversations = gateway.savedConversations().toString()
            val automatic = gateway.createAutomaticRun(JSONObject().put("device_id", DirectRuntime.DEVICE_ID)
                .put("goal", "仅验证自动任务不建立聊天").put("mode", "assist").put("source", "trigger").put("defer_start", true))
            assertEquals(selected, gateway.conversationKey())
            assertEquals(conversations, gateway.savedConversations().toString())
            assertEquals(0, runtime.request("GET", "/runs/${automatic.getString("id")}/conversation", null).getJSONArray("items").length())
            runtime.request("POST", "/runs/${automatic.getString("id")}/cancel", JSONObject())
            assertEquals(2, model.calls.get())
            assertNull(DeviceWorkerService.instance)
            model.assertHealthy()
            evidence("retry-late-reply", JSONObject().put("passed", true).put("model_requests", model.calls.get())
                .put("retry_reused_response", true).put("late_reply_kept_original_task", true).put("automatic_task_created_chat", false))
        }
    }

    private fun guard() {
        assertEquals("Disposable package required", "dev.doppel.queueqa", context.packageName)
        assertEquals("true", InstrumentationRegistry.getArguments().getString("queue_qa"))
        assertNull("Host must force-stop between methods", DeviceWorkerService.instance)
        assertNull(DirectRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }.get(null))
        assertFalse("Host must clear the disposable app before each method", File(context.noBackupFilesDir, "direct-runs-v1.json").exists())
        assertFalse(File(context.noBackupFilesDir, "task-review-memory-v1.json").exists())
        assertFalse(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        assertTrue(Settings.canDrawOverlays(context))
        check(FirstUseConsent.accept(context)); check(FirstUseConsent.finishGuide(context))
        check(gateway.prefs.edit().putBoolean("direct_mode", true).putString("device_id", DirectRuntime.DEVICE_ID)
            .putBoolean("queue_dispatch_paused", false).putBoolean("touch_pause", false).putBoolean("completion_speech", false)
            .putInt("mode_index", 1).commit())
        check(context.getSharedPreferences("doppel_gui_grounding", 0).edit().putBoolean("enabled", false).commit())
        AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        automationFlags = ui.serviceInfo.flags
        ui.serviceInfo = ui.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    }

    private fun configure(port: Int) {
        val providers = ModelProviders(context)
        val provider = ModelProvider("chat-memory-local", "聊天记忆隔离验证", "http://127.0.0.1:$port/v1")
        providers.saveProvider(provider, "synthetic-local-only", emptyMap())
        providers.recordVision(provider.id, "fixture-primary", ModelVision.VERIFIED, providers.requestTarget(provider.id, "fixture-primary").fingerprint)
        val selection = ModelSelection(provider.id, "fixture-primary")
        providers.saveRouting(ModelRouting(selection, false, selection))
        assertTrue(providers.isReady())
    }

    private fun launch(): Activity {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        var found: Activity? = null
        await("The main screen must be resumed") {
            inst.runOnMainSync { found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { it is MainActivity } }
            found != null
        }
        return requireNotNull(found).also { if (it !in activities) activities.add(it) }
    }

    private fun send(message: String) { setText("任务输入", message); tap("开始任务") }
    private fun openMemories() { tap("导航菜单"); tap("设置"); tap("长期记忆", scroll = true); await("Memory list must load") { visibleText().contains("编辑记忆 1") } }
    private fun memories() = runtime.request("GET", "/memories", null).getJSONArray("items")
    private fun runs() = runtime.request("GET", "/runs", null).getJSONArray("items")

    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(root) + (0 until root.childCount).flatMap { index -> root.getChild(index)?.let(::nodes).orEmpty() }
    private fun <T> withNodes(block: (List<AccessibilityNodeInfo>) -> T): T {
        val windows = ui.windows.sortedByDescending { it.layer }
        val all = windows.flatMap { window -> window.root?.let { root ->
            if (root.refresh()) nodes(root) else { root.recycle(); emptyList() }
        }.orEmpty() }
        return try { block(all.filter { it.packageName?.toString() == context.packageName && it.isVisibleToUser }) }
        finally { all.forEach { it.recycle() }; windows.forEach { it.recycle() } }
    }
    private fun visibleText() = withNodes { all -> all.joinToString("\n") { "${it.text?.toString().orEmpty()} ${it.contentDescription?.toString().orEmpty()}" } }
    private fun setText(label: String, value: String) = await("Editable $label must be available") {
        withNodes { all -> all.firstOrNull { it.isEditable && it.contentDescription?.toString() == label }?.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }) == true }
    }
    private fun tap(label: String, scroll: Boolean = false) {
        inst.waitForIdleSync()
        val display = Point().also { context.getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(it) }
        val screen = Rect(0, 0, display.x, display.y)
        var bounds: Rect? = null
        var lastTarget = ""
        var stableSince = 0L
        var targetWindow = -1
        await("Control $label must have a stable active window") {
            var currentWindow = -1
            var currentFrame = ""
            val current = withNodes { all ->
                val found = all.firstOrNull { it.isEnabled && (it.text?.toString() == label || it.contentDescription?.toString() == label) }
                    ?.takeIf { it.refresh() && it.isVisibleToUser && it.isEnabled }
                if (found == null) {
                    if (scroll) all.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    null
                } else found.window?.let { window ->
                    try {
                        val resultClose = label == "关闭结果" && window.type == AccessibilityWindowInfo.TYPE_SYSTEM
                        if (!window.isActive && !window.isFocused && !resultClose) null else {
                            val frame = Rect().also(window::getBoundsInScreen)
                            if (!screen.contains(frame)) null else Rect().also(found::getBoundsInScreen)
                                .takeIf { it.intersect(frame) && !it.isEmpty && screen.contains(it.centerX(), it.centerY()) }
                                ?.also { currentWindow = window.id; currentFrame = frame.toShortString() }
                        }
                    } finally { window.recycle() }
                }
            }
            val signature = if (current == null) "" else "$currentWindow:$currentFrame:${current.toShortString()}"
            if (signature != lastTarget) { lastTarget = signature; stableSince = SystemClock.elapsedRealtime() }
            bounds = current; targetWindow = currentWindow
            current != null && SystemClock.elapsedRealtime() - stableSince >= 300
        }
        val area = requireNotNull(bounds)
        val observed = JSONObject().put("control", label).put("window_id", targetWindow).put("bounds", area.toShortString())
            .put("screen_bounds", screen.toShortString())
        val windows = ui.windows
        try {
            observed.put("windows", JSONArray(windows.map { window -> JSONObject().put("id", window.id).put("type", window.type)
                .put("active", window.isActive).put("focused", window.isFocused).put("layer", window.layer)
                .put("bounds", Rect().also(window::getBoundsInScreen).toShortString()) }))
        } finally { windows.forEach { it.recycle() } }
        File(folder(), "last-ui-target.json").writeText(observed.toString(2))
        val down = SystemClock.uptimeMillis()
        var delivered = true
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            if (action == MotionEvent.ACTION_UP) SystemClock.sleep(60)
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, area.exactCenterX(), area.exactCenterY(), 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                val accepted = ui.injectInputEvent(event, true)
                observed.put(if (action == MotionEvent.ACTION_DOWN) "down_delivered" else "up_delivered", accepted)
                delivered = accepted && delivered
            } finally { event.recycle() }
        }
        File(folder(), "last-ui-target.json").writeText(observed.toString(2))
        if (!delivered) runCatching { screenshot("rejected-ui-tap.png") }
        assertTrue("Real pointer delivery rejected for $label in window $targetWindow at ${area.toShortString()}", delivered)
        inst.waitForIdleSync()
    }
    private fun await(message: String, ms: Long = 12000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + ms
        do { if (condition()) return; SystemClock.sleep(100) } while (SystemClock.elapsedRealtime() < until)
        assertTrue(message, condition())
    }
    private fun folder() = File(context.getExternalFilesDir(null), "chat-long-term-memory").apply { check(isDirectory || mkdirs()) }
    private fun screenshot(name: String) { val image = requireNotNull(ui.takeScreenshot()); try { File(folder(), name).outputStream().use { check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } } finally { image.recycle() } }
    private fun evidence(name: String, result: JSONObject) { File(folder(), "$name.json").writeText(result.put("paid_requests", 0).toString(2)) }

    @After fun finishIsolatedUi() {
        if (context.packageName != "dev.doppel.queueqa") return
        inst.runOnMainSync {
            DeviceWorkerService.instance?.suspendLocally()
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList().forEach { if (it.packageName == context.packageName) it.finish() }
            activities.forEach { if (!it.isDestroyed) it.finish() }
        }
        automationFlags?.let { saved -> ui.serviceInfo = ui.serviceInfo.apply { flags = saved } }
    }

    private class LocalModel : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val calls = AtomicInteger()
        val blocked = CountDownLatch(1); val release = CountDownLatch(1)
        val planners = Collections.synchronizedList(mutableListOf<String>())
        private val executor = Executors.newSingleThreadExecutor()
        @Volatile private var failure: Throwable? = null
        private val work = executor.submit {
            try { while (!server.isClosed) server.accept().use { socket ->
                socket.soTimeout = 10000
                val input = BufferedInputStream(socket.getInputStream())
                fun line() = buildString { while (length < 16384) { val c = input.read(); check(c >= 0); if (c == 10) break; if (c != 13) append(c.toChar()) } }
                check(line().startsWith("POST /v1/chat/completions "))
                var length = 0
                while (true) { val header = line(); if (header.isEmpty()) break; if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt() }
                check(length in 1..20 * 1024 * 1024)
                val bytes = ByteArray(length); var offset = 0
                while (offset < length) { val n = input.read(bytes, offset, length - offset); check(n > 0); offset += n }
                val request = JSONObject(String(bytes, Charsets.UTF_8)); calls.incrementAndGet()
                check(request.getString("model") == "fixture-primary")
                val messages = request.getJSONArray("messages")
                val lastContent = messages.getJSONObject(messages.length() - 1).get("content")
                val text = if (lastContent is JSONArray) (0 until lastContent.length()).mapNotNull { lastContent.optJSONObject(it)?.optString("text") }.first() else lastContent.toString()
                val data = JSONObject(text)
                val decision = if (data.has("task")) {
                    planners.add(data.toString())
                    JSONObject().put("decision", JSONObject().put("kind", "finish").put("status", "completed").put("message", "隔离任务已核对记忆，无设备操作"))
                        .put("state", JSONObject.NULL)
                } else {
                    val rows = data.getJSONArray("messages")
                    val user = (rows.length() - 1 downTo 0).map { rows.getJSONObject(it) }.first { it.optString("role") == "user" }.getString("content")
                    if (user == LATE_CORRECTION) { blocked.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
                    check(user in setOf(QUESTION, CORRECTION, LATE_CORRECTION, NEXT_TASK)) { "Unexpected fixture turn" }
                    val changes = JSONArray()
                    if (user == CORRECTION || user == LATE_CORRECTION) changes.put(JSONObject().put("op", "upsert")
                        .put("content", if (user == CORRECTION) LESSON else LATE_LESSON).put("scope", "global"))
                    JSONObject().put("intent", if (user == NEXT_TASK) "task" else "conversation").put("confidence", 1.0)
                        .put("task_goal", if (user == NEXT_TASK) RESOLVED_TASK else "").put("title", "聊天记忆隔离验证").put("question", "")
                        .put("reply", if (user == NEXT_TASK) "" else "这条消息的语义已处理。").put("memory_changes", changes)
                }
                val reply = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                    .put("message", JSONObject().put("role", "assistant").put("content", decision.toString()))))
                    .put("usage", JSONObject().put("prompt_tokens", 0).put("completion_tokens", 0)).toString().toByteArray(Charsets.UTF_8)
                socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${reply.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)); write(reply); flush() }
            } } catch (error: Throwable) { if (!server.isClosed) failure = error }
        }
        fun assertHealthy() { failure?.let { throw AssertionError("Local model fixture failed", it) } }
        override fun close() { release.countDown(); server.close(); executor.shutdownNow(); runCatching { work.get(3, TimeUnit.SECONDS) }; executor.awaitTermination(3, TimeUnit.SECONDS) }
    }

    companion object {
        private const val QUESTION = "请解释为什么操作前要先确认当前页面，我现在只想讨论。"
        private const val CORRECTION = "刚才你把导航入口当成保存按钮了。以后要先核对页面标题，再判断入口用途。"
        private const val LESSON = "操作前先核对页面标题，再判断入口用途。"
        private const val EDITED = "先确认当前页面所属功能，再辨认提交按钮，旧页面坐标不能直接复用。"
        private const val NEXT_TASK = "只观察当前屏幕，核对我刚才给出的操作建议是否适用，完成后简短说明。不要下单或修改系统权限。"
        private const val RESOLVED_TASK = "观察当前屏幕并核对操作建议。"
        private const val LATE_CORRECTION = "还有一点：弹窗挡住内容时先理解弹窗用途，不要立即重复之前的操作。"
        private const val LATE_LESSON = "弹窗遮挡内容时先理解弹窗用途，再决定后续操作。"
    }
}

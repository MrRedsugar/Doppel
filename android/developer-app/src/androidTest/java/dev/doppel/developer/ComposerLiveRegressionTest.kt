@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.doppel.developer

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real composer, configured provider and worker. No model replies or device actions are stubbed. */
class ComposerLiveRegressionTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val ui by lazy { inst.getUiAutomation(1).apply {
        serviceInfo = serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    } }
    private val gateway get() = Gateway(context)
    private var activity: Activity? = null
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun <T> main(block: () -> T): T { var result: T? = null; inst.runOnMainSync { result = block() }; @Suppress("UNCHECKED_CAST") return result as T }
    private fun await(message: String, timeout: Long = 30000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return; Thread.sleep(100) }
        assertTrue(message, condition())
    }
    private fun open() {
        activity = inst.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        inst.waitForIdleSync()
    }
    private fun submit(text: String) = main {
        val all = views(activity!!.window.decorView)
        all.filterIsInstance<EditText>().single { it.contentDescription == "任务输入" }.setText(text)
        assertTrue(all.single { it.contentDescription == "开始任务" }.performClick())
    }
    private fun runs(): List<JSONObject> = gateway.request("GET", "/runs").getJSONArray("items").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }
    }
    @Test fun actualChatThenTaskThenContextualFollowupSurvivesReopening() {
        assertEquals("This test sends paid requests using the existing model configuration", "true",
            InstrumentationRegistry.getArguments().getString("composer_live"))
        assertTrue(gateway.isConnected())
        assertFalse("Finish existing work before live QA", DirectRuntime.get(context).hasUnfinishedRun())
        assertNull(DeviceWorkerService.instance)
        val baseline = runs().map { it.getString("id") }.toSet()
        val report = JSONObject().put("status", "running").put("stages", JSONArray())
        val providers = ModelProviders(context)
        val originalRouting = providers.routing()
        val enhancement = InstrumentationRegistry.getArguments().getString("composer_enhancement")
        if (enhancement != null) {
            require(enhancement in setOf("true", "false"))
            providers.saveRouting(originalRouting.copy(enhancementEnabled = enhancement.toBoolean()))
        }
        report.put("visual_enhancement", providers.routing().enhancementEnabled)
        val folder = File(context.getExternalFilesDir(null), "full-feature/composer-live").apply { mkdirs() }
        fun save(stage: String) {
            report.getJSONArray("stages").put(stage)
            File(folder, "report.json").writeText(report.toString(2))
            ui.takeScreenshot()?.let { b -> File(folder, "$stage.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
        }
        var ownRun: String? = null
        try {
            ui
            AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
            open()
            val oldConversation = gateway.conversationKey()
            main { assertTrue(views(activity!!.window.decorView).first { it.contentDescription == "新任务" }.performClick()) }
            await("New conversation must open") {
                // The existing draft dialog can appear after the asynchronous run-status check.
                ui.windows.flatMap { it.root?.let(::nodes).orEmpty() }.firstOrNull {
                    it.isClickable && it.className == "android.widget.Button" && it.text?.toString() == "新任务"
                }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                gateway.selectedConversationRun() == "" && gateway.conversationKey() != oldConversation
            }
            val greeting = "这次测试的代号是蓝鲸七号，请记住并只回复这个代号，不要操作手机。"
            submit(greeting)
            await("The actual chat answer must appear", 60000) { main {
                views(activity!!.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.text.toString().contains("蓝鲸七号") && it.text.toString() != greeting && it !is EditText }
            } }
            assertEquals("Chat must not create a task", baseline, runs().map { it.getString("id") }.toSet())
            save("01-chat")
            val taskGoal = InstrumentationRegistry.getArguments().getString("composer_goal")
                ?: "打开系统设置，进入关于手机，告诉我手机型号。完成后停止。"
            report.put("task_goal", taskGoal)
            submit(taskGoal)
            await("Composer must create a real device task", 60000) {
                ownRun = runs().firstOrNull { it.getString("id") !in baseline }?.getString("id"); ownRun != null
            }
            val started = SystemClock.elapsedRealtime()
            var run = JSONObject()
            await("Real task must reach completion within three minutes", 180000) {
                run = gateway.request("GET", "/runs/$ownRun")
                report.put("run", run); File(folder, "report.json").writeText(report.toString(2))
                assertTrue("Bound real paid calls", run.optInt("calls") <= 18)
                TaskPresentation.terminal(run.optString("status")) || run.optString("status") == "paused"
            }
            report.put("task_elapsed_ms", SystemClock.elapsedRealtime() - started)
            assertEquals(run.optString("message"), "completed", run.optString("status"))
            InstrumentationRegistry.getArguments().getString("composer_expected_launch")?.let { expectedPackage ->
                val stored = dev.doppel.sdk.SplitTaskEngine.readPersistedRuns(File(context.noBackupFilesDir, "direct-runs-v1.json").readText())
                val own = (0 until stored.length()).map { stored.getJSONObject(it) }.single { it.getString("id") == ownRun }
                val steps = own.optJSONArray("recent_steps") ?: JSONArray()
                val launches = (0 until steps.length()).map { steps.getJSONObject(it).getJSONObject("receipt") }
                    .filter { it.optString("action") == "launch" && it.optString("package_name") == expectedPackage }
                assertTrue("The real planner must launch the discovered package through the native executor", launches.any {
                    it.optString("status") == "ok" && it.optBoolean("launch_verified")
                })
                assertEquals("Opening an app alone must not require B", 0,
                    run.optJSONObject("model_metrics")?.optJSONObject("grounding")?.optInt("calls") ?: 0)
                report.put("verified_native_launch", expectedPackage).put("launch_receipts", JSONArray(launches))
            }
            save("02-task-completed")
            open()
            val previousText = main { views(activity!!.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toSet() }
            submit("刚才测试的代号是什么？刚才手机任务的结果是什么？只回答，不操作手机。")
            await("Follow-up must be visible after a device task", 60000) { main {
                views(activity!!.window.decorView).filterIsInstance<TextView>().any { it.isShown && it !is EditText &&
                    it.text.toString().contains("蓝鲸七号") && it.text.toString() !in previousText && it.text.toString() != greeting }
            } }
            assertEquals(baseline.size + 1, runs().size)
            save("03-followup")
            open()
            await("Reopening must retain the follow-up and task") { main {
                views(activity!!.window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("蓝鲸七号") && it !is EditText }
            } }
            report.put("status", "passed"); save("04-reopened")
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failure_class", failure.javaClass.simpleName)
                .put("failure", failure.message.orEmpty().take(600))
            throw failure
        } finally {
            ownRun?.let { id -> if (!TaskPresentation.terminal(gateway.request("GET", "/runs/$id").optString("status")))
                gateway.request("POST", "/runs/$id/cancel") }
            main { activity?.finish(); context.stopService(Intent(context, DeviceWorkerService::class.java)) }
            if (enhancement != null) providers.saveRouting(originalRouting)
            report.put("finished_at", System.currentTimeMillis()); File(folder, "report.json").writeText(report.toString(2))
        }
    }
    private fun nodes(n: android.view.accessibility.AccessibilityNodeInfo): List<android.view.accessibility.AccessibilityNodeInfo> =
        listOf(n) + (0 until n.childCount).flatMap { n.getChild(it)?.let(::nodes).orEmpty() }
}

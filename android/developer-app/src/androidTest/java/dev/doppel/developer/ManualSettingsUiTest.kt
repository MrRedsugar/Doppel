package dev.doppel.developer

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in real Activity acceptance. Model replies are never injected or replaced. */
class ManualSettingsUiTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val automation by lazy { inst.getUiAutomation(1).apply {
        serviceInfo = serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    } }
    private var activity: Activity? = null
    private val folder by lazy {
        val label = args.getString("manual_ui_label", "latest").orEmpty()
        require(label.matches(Regex("[A-Za-z0-9_-]{1,60}")))
        File(context.getExternalFilesDir(null), "manual-settings-ui/$label").apply { check(mkdirs() || isDirectory) }
    }

    private fun await(message: String, timeout: Long = 12000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) { if (condition()) return; Thread.sleep(150) }
        assertTrue(message, condition())
    }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(root) + (0 until root.childCount).flatMap { root.getChild(it)?.let(::nodes).orEmpty() }
    private fun visibleNodes() = automation.windows.flatMap { it.root?.let(::nodes).orEmpty() }.filter { it.isVisibleToUser }
    private fun mainText(label: String): TextView? {
        var value: TextView? = null
        inst.runOnMainSync { value = activity?.window?.decorView?.let(::views)?.filterIsInstance<TextView>()?.firstOrNull { it.text.toString() == label } }
        return value
    }
    private fun show(view: View) {
        inst.runOnMainSync { view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true) }
        inst.waitForIdleSync(); Thread.sleep(180)
    }
    private fun click(label: String) {
        mainText(label)?.let(::show)
        await("Visible clickable control: $label") { visibleNodes().any { it.text?.toString() == label } }
        var node = visibleNodes().first { it.text?.toString() == label }
        while (!node.isClickable) node = requireNotNull(node.parent) { "No clickable parent: $label" }
        assertTrue("Actual UI action: $label", node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); inst.waitForIdleSync()
    }
    private fun field(hint: String): EditText {
        var value: EditText? = null
        inst.runOnMainSync { value = activity?.window?.decorView?.let(::views)?.filterIsInstance<EditText>()?.firstOrNull { it.hint?.toString() == hint } }
        return requireNotNull(value) { "Editable field: $hint" }
    }
    private fun set(view: EditText, text: String) { show(view); inst.runOnMainSync { view.setText(text) }; inst.waitForIdleSync() }
    private fun open(type: Class<out Activity>) {
        activity?.let { old -> inst.runOnMainSync { old.finish() } }
        activity = inst.startActivitySync(Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        inst.waitForIdleSync()
    }
    private fun shot(name: String) {
        inst.waitForIdleSync(); Thread.sleep(250)
        val bitmap = requireNotNull(automation.takeScreenshot()) { "Screenshot unavailable: $name" }
        File(folder, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }
    private fun report(name: String, value: JSONObject) { File(folder, "$name.json").writeText(value.toString(2)) }
    private fun close() { activity?.let { current -> inst.runOnMainSync { current.finish() } }; activity = null }
    private fun noActiveTask() {
        if(args.getString("manual_ui_consent")=="true") {check(FirstUseConsent.accept(context));FirstUseConsent.finishGuide(context)}
        FirstUseConsent.requireAccepted(context)
        if(args.getString("manual_ui_cleanup_split")=="true") {
            // Explicit test-only cleanup of the exact opt-in run; no unrelated task is ended.
            val marker=File(context.filesDir,"split-live-run-id.txt")
            val evidence=File(context.filesDir,"split-live-latest.json")
            if(marker.isFile && evidence.isFile) {
                val report=JSONObject(evidence.readText());val id=marker.readText().trim()
                check(report.optString("control")=="production SplitTaskEngine A/B only")
                check(report.getJSONObject("run").getString("id")==id)
                val gateway=Gateway(context);val run=gateway.request("GET","/runs/$id")
                check(run.getString("goal")==report.getString("goal"))
                if(run.optString("status") !in setOf("completed","failed","cancelled")) {
                    gateway.request("POST","/runs/$id/cancel",JSONObject())
                }
            }
        }
        assertFalse("Finish existing tasks before this UI test", DirectRuntime.get(context).hasUnfinishedRun())
        assertFalse("Do not interrupt an executing worker", DeviceWorkerService.instance?.isPaused == false)
        if(DoppelAccessibilityService.instance==null) {
            val own=android.content.ComponentName(context,DoppelAccessibilityService::class.java)
            val others=Settings.Secure.getString(context.contentResolver,"enabled_accessibility_services").orEmpty().split(':')
                .filter {it.isNotBlank() && it!="null" && android.content.ComponentName.unflattenFromString(it)!=own}
            fun shell(cmd:String) {android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(cmd)).use {it.readBytes()}}
            if(others.isEmpty()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services ${others.joinToString(":")}")
            shell("settings put secure enabled_accessibility_services ${(others+own.flattenToString()).joinToString(":")}")
            shell("settings put secure accessibility_enabled 1")
            await("QA accessibility binding after instrumentation restart") {DoppelAccessibilityService.instance!=null}
        }
    }

    @Test fun actualModelSettingsShowsConfiguredRoutingWithoutChangingIt() {
        assumeTrue(args.getString("manual_ui") == "true")
        noActiveTask()
        val providers = ModelProviders(context); val before = providers.routing()
        val result = JSONObject().put("ok", false).put("real_api_calls", 0)
        try {
            open(ModelSettingsActivity::class.java)
            assertEquals(ModelSettingsActivity::class.java, activity!!.javaClass)
            assertNotNull(mainText("默认模型")); assertNotNull(mainText("独立视觉增强"))
            assertNotNull(mainText(before.primary.model))
            var secure = false
            inst.runOnMainSync { secure = activity!!.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 }
            assertTrue("Model settings must retain screenshot protection", secure)
            // FLAG_SECURE is preserved. This evidence may be redacted by Android; no bypass is used.
            result.put("screenshot_saved", runCatching { shot("model-settings-protected") }.isSuccess)
            click("选择默认模型")
            await("Real model picker opened") { visibleNodes().any { it.text?.toString() == "保存选择" } }
            click("取消")
            assertEquals(before, providers.routing())
            result.put("ok", true).put("primary_model", before.primary.model).put("independent_visual", before.enhancementEnabled)
                .put("screenshot_protected", true).put("settings_unchanged", true)
        } finally { report("model-settings", result); close() }
    }

    @Test fun actualScheduleEditorPersistsAndOffersChoiceWhilePhoneIsInUse() {
        assumeTrue(args.getString("manual_ui") == "true" && args.getString("manual_ui_schedule") == "true")
        noActiveTask()
        assertTrue("Use an already configured local connection", DirectMode.isEnabled(context))
        assertNotNull("Root must enable accessibility before testing", DoppelAccessibilityService.instance)
        assertTrue(Settings.canDrawOverlays(context))
        val power = context.getSystemService(PowerManager::class.java); val lock = context.getSystemService(KeyguardManager::class.java)
        assertTrue("Device must already be bright", power.isInteractive)
        assertFalse("Device must already be unlocked", lock.isDeviceLocked || lock.isKeyguardLocked)
        val manager = ScheduleManager.get(context); val existing = manager.request("GET", "/schedules").getJSONArray("items")
        repeat(existing.length()) { assertFalse("Preserve existing enabled plans; test in an idle fixture", existing.getJSONObject(it).getBoolean("enabled")) }
        val originalRuns = Gateway(context).request("GET", "/runs").getJSONArray("items").length()
        val title = "UI手工计划-${UUID.randomUUID().toString().take(8)}：查看设置首页"
        val choice = args.getString("manual_ui_schedule_choice", "skip")
        require(choice in setOf("skip", "postpone", "locked"))
        val result = JSONObject().put("ok", false).put("bright", true).put("unlocked", true).put("choice", choice)
        var id: String? = null
        var slept = false
        try {
            open(ScheduleActivity::class.java)
            var guidance = false
            inst.runOnMainSync { guidance = views(activity!!.window.decorView).filterIsInstance<TextView>().any { it.text.contains("请保留系统锁屏密码") && it.text.contains("15 秒") } }
            assertTrue("Actual readiness guidance is visible in the editor", guidance); shot("schedule-readiness-guidance")
            click("新建计划")
            set(field("希望替你完成什么？"), title)
            val now = System.currentTimeMillis(); var due = ((now / 60000) + 1) * 60000
            if (due - now < 25000) due += 60000
            val zone = ZoneId.systemDefault()
            set(field("时间"), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(due).atZone(zone)))
            set(field("时区，例如 Asia/Shanghai"), zone.id)
            click("保存计划")
            await("UI-created schedule must be persisted") {
                val items = manager.request("GET", "/schedules").getJSONArray("items")
                id = (0 until items.length()).map { items.getJSONObject(it) }.firstOrNull { it.optString("goal") == title }?.getString("id")
                id != null
            }
            shot("schedule-created")
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("Real Settings must be in the foreground") { automation.rootInActiveWindow?.packageName?.toString() == "com.android.settings" }
            if(choice == "locked") {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input keyevent 223")).use {it.readBytes()}
                slept = true
                await("Real display is asleep") {!power.isInteractive}
            }
            await("The saved wall-clock schedule becomes due", 100000) { System.currentTimeMillis() >= due }
            val done = CountDownLatch(1); manager.tick { done.countDown() }; assertTrue(done.await(15, TimeUnit.SECONDS))
            if(choice == "locked") {
                assertEquals("device_locked",manager.request("GET","/schedules/$id").getString("waiting_reason"))
                assertEquals(originalRuns,Gateway(context).request("GET","/runs").getJSONArray("items").length())
                result.put("ok",true).put("waiting_reason","device_locked").put("actual_display_asleep",true).put("no_run_created",true)
                return
            }
            await("Actual user-active preannouncement must be shown") { visibleNodes().any { it.text?.toString() == "定时任务到时间了" } }
            assertEquals("schedule_countdown", manager.request("GET", "/schedules/$id").getString("waiting_reason"))
            for (label in listOf("执行", "推迟 10 分钟", "跳过")) assertTrue(visibleNodes().any { it.text?.toString() == label })
            val bounds = Rect(); visibleNodes().first { it.text?.toString() == "定时任务到时间了" }.getBoundsInScreen(bounds)
            assertTrue("Prompt stays in the lower half", bounds.top > context.resources.displayMetrics.heightPixels / 2)
            shot("schedule-user-active-prompt")
            click(if (choice == "skip") "跳过" else "推迟 10 分钟")
            await("Manual occurrence choice must persist") {
                val job = manager.request("GET", "/schedules/$id")
                if (choice == "skip") job.getJSONArray("history").length() == 1 else job.optLong("next_due_ms") > due + 300000
            }
            val after = manager.request("GET", "/schedules/$id")
            if (choice == "skip") assertEquals("user_skipped", after.getJSONArray("history").getJSONObject(0).getString("reason"))
            val disk = JSONObject(File(context.noBackupFilesDir, "schedules-v1.json").readText()).getJSONArray("items")
            assertTrue((0 until disk.length()).any { disk.getJSONObject(it).getString("id") == id })
            val checked = CountDownLatch(1); manager.tick { checked.countDown() }; assertTrue(checked.await(15, TimeUnit.SECONDS))
            assertEquals("No phone task may be created by this choice test", originalRuns, Gateway(context).request("GET", "/runs").getJSONArray("items").length())
            result.put("ok", true).put("schedule_id", id).put("manual_create_persisted", true).put("waiting_reason", "schedule_countdown").put("no_run_created", true)
        } catch (error: Throwable) { result.put("failure_type", error.javaClass.simpleName); runCatching { shot("schedule-failure") }; throw error }
        finally {
            val items = manager.request("GET", "/schedules").getJSONArray("items")
            repeat(items.length()) { i -> val item = items.getJSONObject(i); if (item.optString("goal") == title) manager.request("DELETE", "/schedules/${item.getString("id")}") }
            if(slept) {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input keyevent 224")).use {it.readBytes()}
                android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input keyevent 82")).use {it.readBytes()}
            }
            manager.tick {}; report("schedule-ui", result); close()
        }
    }
}

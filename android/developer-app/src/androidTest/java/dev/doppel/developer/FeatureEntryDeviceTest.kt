@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.app.Activity
import android.app.Dialog
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Real installed configuration and navigation. No task submissions, model calls, passwords or setting changes. */
class FeatureEntryDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val automation by lazy { inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }
    private val entries = JSONArray()
    private val folder by lazy { File(context.getExternalFilesDir(null), "full-feature/entries").apply { mkdirs() } }

    @Test fun realConfiguredFeaturePagesLoadThroughTheirVisibleNavigation() {
        assertTrue("Run UI regression only in a disposable APK", context.packageName in setOf("dev.doppel.loginqa", "dev.doppel.queueqa"))
        assertTrue("Finish onboarding before this read-only UI regression", FirstUseConsent.isAccepted(context) && !FirstUseConsent.needsGuide(context))
        assertTrue("Use the user's existing local model configuration", DirectMode.isEnabled(context) && Gateway(context).isConnected())
        assertNull("Stop the worker before viewing protected settings; retain paused tasks", DeviceWorkerService.instance)
        assertFalse("Unlock the emulator before UI regression", context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        val runsFile = File(context.noBackupFilesDir, "direct-runs-v1.json")
        val persisted = if (runsFile.exists()) dev.doppel.sdk.SplitTaskEngine.readPersistedRuns(runsFile.readText()) else JSONArray()
        repeat(persisted.length()) {
            assertTrue("Preserve tasks: do not run this test while execution is active", persisted.getJSONObject(it).optString("status") in setOf("paused", "completed", "failed", "cancelled"))
        }
        val prefs = context.getSharedPreferences("doppel", 0)
        val connectionKeys = setOf("direct_mode", "base_url", "token", "device_id", "server_device_id", "active_run", "balance_amount", "cost_per_million", "balance_alert_threshold")
        val connectionBefore = prefs.all.filterKeys { it in connectionKeys }
        val protectedPrefs = listOf("doppel_ui", "doppel_consent", "doppel_credential_vault", "doppel_automatic_unlock_state", "doppel_auto_triggers", "doppel_skill_switches", "doppel_login")
        val prefsBefore = protectedPrefs.associateWith { context.getSharedPreferences(it, 0).all.toMap() }
        val protectedFiles = listOf("model-providers-v1.bin", "credential-vault-v1.bin", "automatic-unlock-v1.bin", "payment-active-grant")
        val filesBefore = protectedFiles.associateWith { hash(File(context.noBackupFilesDir, it)) }
        val gateway = Gateway(context)
        val runsBefore = runState(gateway)
        val opened = mutableListOf<Activity>()
        var launched: Activity? = null
        var succeeded = false
        try {
            val main = inst.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).also { launched = it }
            inst.waitForIdleSync()
            requireDescription(main, "导航菜单", "任务输入", "开始任务")
            capture("01-task", main)

            navigate(main, "记录")
            requireText(main, "记录", "最近任务")
            requireDescription(main, "刷新记录")
            val first = persisted.optJSONObject(0)
            if (first == null) await("The empty history must load") { hasText(main, "暂无任务记录") }
            else await("An existing task must be loaded in history") {
                hasText(main, first.optString("title").ifBlank { first.optString("goal") }.ifBlank { "未命名任务" })
            }
            capture("02-history", main)

            navigate(main, "用量")
            requireText(main, "开发用量", "输入 Token", "输出 Token", "请求次数", "截图数量")
            await("Usage must finish loading from the configured local runtime") {
                texts(main).any { it == "本机请求用量累计 · 旧用量仅含升级时保留的任务" || it.startsWith("余额即将用尽") }
            }
            ui { assertTrue("The usage page must contain a laid-out curve view", all(main.window.decorView).any { it is UsageChartView && it.width > 0 && it.height > 0 }) }
            capture("03-usage", main)

            navigate(main, "设置")
            requireText(main, "系统权限", "通知与麦克风权限")
            requireDescription(main, "打开无障碍服务系统设置", "打开悬浮窗系统设置")
            capture("04-permissions", main)
            click(main.window.decorView, "打开无障碍服务系统设置", description = true)
            await("The permission shortcut must open Android Settings") { automation.rootInActiveWindow?.packageName?.toString() == "com.android.settings" }
            capture("05-system-accessibility", null)
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Back must return to Doppel settings") { ui { main.hasWindowFocus() } }

            fun page(label: String, type: Class<out Activity>, file: String, vararg expected: String): Activity {
                val activity = open(main, label, type).also { opened += it }
                requireText(activity, *expected)
                requireDescription(activity, "返回")
                capture(file, activity)
                return activity
            }
            fun back(activity: Activity) {
                click(activity.window.decorView, "返回", description = true)
                await("Return from ${activity.javaClass.simpleName}") { ui { activity.isFinishing && main.hasWindowFocus() } }
            }

            val appearance = page("显示模式", AppearanceActivity::class.java, "06-appearance", "外观", "跟随系统", "浅色", "深色", "预览")
            back(appearance)
            val models = page("模型设置", ModelSettingsActivity::class.java, "07-models", "模型连接", "默认模型", "选择默认模型", "验证默认模型的视觉能力")
            requireText(models, ModelProviders(context).routing().primary.model)
            back(models)
            val speech = page("离线中文语音识别", SpeechSettingsActivity::class.java, "08-speech", "语音识别", "离线中文识别", "麦克风权限", "已内置 · 约 78 MiB")
            back(speech)
            assertFalse("Login and password management must share one settings entry", hasText(main, "密码管理"))
            val login = page("登录设置", LoginSettingsActivity::class.java, "09-login-pin", "登录设置",
                if (CredentialVault(context).hasPin()) "解锁登录设置" else "设置 4 位 PIN")
            assertFalse("Login details must remain behind the PIN page", hasText(login, "常用手机号") || hasText(login, "已设置应用"))
            back(login)
            val payment = page("支付授权", PaymentSettingsActivity::class.java, "11-payment", "支付授权", "允许代为支付")
            requireDescription(payment, "payment_toggle")
            back(payment)
            val extensions = page("扩展服务", ExtensionSettingsActivity::class.java, "12-extensions", "扩展", "MCP 服务需要网关连接")
            assertFalse("Retired Skills must have no settings entry", hasText(extensions, "Skills"))
            back(extensions)
            val triggers = page("自动触发", AutoTriggerSettingsActivity::class.java, "13-triggers", "自动触发", "创建规则", "从当前应用选择控件", "自动解锁设置")
            back(triggers)
            val schedules = page("定时任务", ScheduleActivity::class.java, "14-schedules", "定时任务", "让事情按时发生", "新建计划", "自动解锁设置")
            await("Schedules must finish loading") { texts(schedules).any { it.startsWith("还没有计划。") || it.endsWith("个计划 · 当前设备") } }
            capture("14-schedules-loaded", schedules)
            val unlock = open(schedules, "自动解锁设置", AutomaticUnlockSettingsActivity::class.java).also { opened += it }
            requireText(unlock, "自动任务解锁")
            val unlockStatus = when {
                AutomaticUnlockCredentials.isEnabled(context) -> "已开启 · 更新密码必须重新验证设备身份"
                AutomaticUnlockCredentials.hasSaved(context) -> "已停用，等待用户检查"
                else -> "未设置 · 默认关闭 · 不需要关闭系统锁屏密码"
            }
            requireText(unlock, unlockStatus)
            capture("15-automatic-unlock", unlock)
            click(unlock.window.decorView, "返回", description = true)
            await("Return to the schedule list") { ui { unlock.isFinishing && schedules.hasWindowFocus() } }
            back(schedules)
            succeeded = true
        } finally {
            ui { opened.asReversed().forEach { if (!it.isFinishing) it.finish() }; launched?.finish() }
            inst.waitForIdleSync()
            val unchanged = connectionBefore == prefs.all.filterKeys { it in connectionKeys } &&
                protectedPrefs.all { prefsBefore[it] == context.getSharedPreferences(it, 0).all } &&
                protectedFiles.all { filesBefore[it] == hash(File(context.noBackupFilesDir, it)) } && runsBefore == runState(gateway)
            File(folder, "report.json").writeText(JSONObject().put("passed", succeeded && unchanged).put("configuration_and_tasks_preserved", unchanged)
                .put("model_calls_requested", 0).put("entries", entries).toString(2))
            assertTrue("Viewing feature pages must preserve user configuration and task status/call counts", unchanged)
        }
    }

    private fun navigate(main: Activity, section: String) {
        click(main.window.decorView, "导航菜单", description = true)
        val dialog = ui { ClientActivity::class.java.getDeclaredField("navigationDialog").apply { isAccessible = true }.get(main) as Dialog }
        click(dialog.window!!.decorView, section)
        inst.waitForIdleSync()
    }

    private fun open(owner: Activity, label: String, type: Class<out Activity>): Activity {
        val monitor = inst.addMonitor(type.name, null, false)
        return try {
            click(owner.window.decorView, label)
            inst.waitForMonitorWithTimeout(monitor, 8000).also { assertNotNull("$label must open ${type.simpleName}", it) }
        } finally { inst.removeMonitor(monitor); inst.waitForIdleSync() }
    }

    private fun click(root: View, label: String, description: Boolean = false) {
        ui {
            val target = all(root).firstOrNull { it.isShown && if (description) it.contentDescription == label else it is TextView && it.text.toString() == label }
            assertNotNull("Missing visible control: $label", target)
            target!!.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true)
        }
        inst.waitForIdleSync()
        ui {
            val target = all(root).first { it.isShown && if (description) it.contentDescription == label else it is TextView && it.text.toString() == label }
            assertTrue("Control must be on screen: $label", target.getGlobalVisibleRect(Rect()))
            var button = target
            while (!button.isClickable && button.parent is View) button = button.parent as View
            assertTrue("Control must be enabled: $label", button.isEnabled)
            assertTrue("Click must be handled: $label", button.performClick())
        }
        inst.waitForIdleSync()
    }

    private fun requireText(activity: Activity, vararg labels: String) = await("${activity.javaClass.simpleName} must show ${labels.joinToString()}") {
        val shown = texts(activity); labels.all { it in shown }
    }
    private fun requireDescription(activity: Activity, vararg labels: String) = await("${activity.javaClass.simpleName} controls: ${labels.joinToString()}") {
        ui { val shown = all(activity.window.decorView).filter { it.isShown }.map { it.contentDescription?.toString() }; labels.all { it in shown } }
    }
    private fun hasText(activity: Activity, label: String) = label in texts(activity)
    private fun texts(activity: Activity) = ui { all(activity.window.decorView).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() } }
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun <T> ui(work: () -> T): T { var result: Result<T>? = null; inst.runOnMainSync { result = runCatching(work) }; return result!!.getOrThrow() }
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 8000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(80)
        assertTrue(message, condition())
    }
    private fun capture(name: String, activity: Activity?) {
        if (activity != null) await("Capture the foreground ${activity.javaClass.simpleName}") {
            ui { activity.hasWindowFocus() && activity.window.decorView.width > 0 }
        }
        // Assertions inspect real Views; evidence must also wait for the system's transition to draw them.
        inst.waitForIdleSync()
        SystemClock.sleep(450)
        val secure = activity?.let { ui { it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 } } ?: false
        val screenshot = automation.takeScreenshot()
        if (screenshot != null) {
            File(folder, "$name.png").outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            screenshot.recycle()
        }
        entries.put(JSONObject().put("name", name).put("secure_window", secure).put("screenshot_saved", screenshot != null))
    }
    private fun runState(gateway: Gateway): Map<String, Pair<String, Int>> {
        val list = gateway.request("GET", "/runs").getJSONArray("items")
        return (0 until list.length()).associate { list.getJSONObject(it).let { item -> item.getString("id") to (item.optString("status") to item.optInt("calls")) } }
    }
    private fun hash(file: File): String? = if (!file.exists()) null else MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}

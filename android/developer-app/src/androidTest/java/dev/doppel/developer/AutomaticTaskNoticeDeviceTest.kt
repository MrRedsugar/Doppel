package dev.doppel.developer

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DoppelAccessibilityService
import dev.doppel.sdk.AutoTriggerStore
import dev.doppel.sdk.ScheduleActivity
import dev.doppel.sdk.AutomaticUnlockSettingsActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/** Actual production countdown on LDPlayer; no model call or phone task is submitted. */
class AutomaticTaskNoticeDeviceTest {
    @get:org.junit.Rule val terminalTaskPointer: org.junit.rules.TestRule = TerminalTaskPointerTestRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val automation get() = inst.getUiAutomation(1)
    private val type = Class.forName("dev.doppel.sdk.AutomaticTaskNotice")
    private val notice = type.getField("INSTANCE").get(null)
    private val evidence get() = File(context.getExternalFilesDir(null), "automatic-task-notice-verification").apply { mkdirs() }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .use { String(it.readBytes(), Charsets.UTF_8) }
    private fun await(message: String, timeout: Long = 6000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < until) Thread.sleep(50)
        assertTrue(message, condition())
    }
    private fun blocked(): String? = type.getMethod("localBlockReason", Context::class.java, Boolean::class.javaPrimitiveType)
        .invoke(notice, context, false) as String?
    private fun dismiss(key: String) = inst.runOnMainSync { type.getMethod("dismiss", String::class.java).invoke(notice, key) }
    private fun approved(key: String) = type.getMethod("approved", String::class.java).invoke(notice, key) as Boolean
    private fun show(key: String, execute: () -> Unit, skip: () -> Unit = {}, cancelled: () -> Unit = {}, onShown: () -> Unit = {}): Boolean {
        var shown = false
        val valid: () -> Boolean = { blocked() == null }
        inst.runOnMainSync {
            shown = type.declaredMethods.single { it.name == "show" && it.parameterCount == 10 }
                .invoke(notice, context, key, "自动任务预告验证", "本项只验证预告，不会操作应用", valid, execute, skip, null, cancelled, onShown) as Boolean
        }
        return shown
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val found = ArrayList<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (found.size >= 1000) return
            found.add(node)
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        automation.windows.forEach { it.root?.let(::visit) }
        return found
    }
    private fun shot(name: String) {
        val bitmap = requireNotNull(automation.takeScreenshot())
        try { File(evidence, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun currentNotice(): Any? = (type.getDeclaredField("current").apply { isAccessible = true }.get(notice)
        as java.util.concurrent.atomic.AtomicReference<*>).get()
    private fun visibleSince(): Long = currentNotice()?.let { owner ->
        owner.javaClass.getDeclaredField("shownAt").apply { isAccessible = true }.getLong(owner)
    } ?: 0L
    private fun noticeDiagnostic(): JSONObject {
        val result = JSONObject().put("block_reason", blocked() ?: JSONObject.NULL)
            .put("interactive", context.getSystemService(PowerManager::class.java).isInteractive)
            .put("device_locked", context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
            .put("keyguard_locked", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        fun state() {
            val owner = currentNotice()
            result.put("notice_present", owner != null)
            if (owner != null) {
                val view = owner.javaClass.getDeclaredField("view").apply { isAccessible = true }.get(owner) as? View
                val generation = owner.javaClass.getDeclaredField("generation").apply { isAccessible = true }.getLong(owner)
                result.put("attached", view?.isAttachedToWindow == true).put("shown", view?.isShown == true)
                    .put("window_visibility", view?.windowVisibility).put("generation_current", dev.doppel.sdk.TaskControl.isCurrent(generation))
                    .put("visible_since", visibleSince())
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) state() else inst.runOnMainSync { state() }
        result.put("windows", org.json.JSONArray(automation.windows.map { window ->
            val root = window.root
            try { JSONObject().put("id", window.id).put("type", window.type).put("focused", window.isFocused)
                .put("active", window.isActive).put("package", root?.packageName?.toString()) }
            finally { root?.recycle(); window.recycle() }
        }))
        return result
    }

    @Test fun shownCallbackRequiresAValidAttachedNotice() {
        assertNull(DeviceWorkerService.instance)
        assertTrue(context.getSharedPreferences("doppel", 0).getString("active_run", "").isNullOrBlank())
        assertTrue(AutoTriggerStore(context).list().none { it.enabled })
        if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        assertNull(blocked())
        val key = "notice-admission-${UUID.randomUUID()}"
        val valid = AtomicBoolean(true)
        val shown = AtomicInteger()
        val cancelled = AtomicInteger()
        val validity: () -> Boolean = { valid.get() }
        val noOp: () -> Unit = {}
        val onCancelled: () -> Unit = { cancelled.incrementAndGet() }
        val onShown: () -> Unit = { shown.incrementAndGet() }
        val method = type.declaredMethods.single { it.name == "show" && it.parameterCount == 10 }
        fun request(id: String): Boolean = method.invoke(notice, context, id, "预告接收验证", "只检查真实显示，不执行任务",
            validity, noOp, noOp, null, onCancelled, onShown) as Boolean
        val originalAutomationFlags = automation.serviceInfo.flags
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            inst.runOnMainSync {
                assertTrue(request(key))
                valid.set(false) // Invalid before the queued window creation is allowed to run.
            }
            await("Invalid queued notice must cancel") { cancelled.get() == 1 }
            assertEquals("Queue acceptance is not visible admission", 0, shown.get())
            valid.set(true)
            inst.runOnMainSync { assertTrue(request(key)) }
            await("Attached notice receives one shown callback") { shown.get() == 1 }
            await("Attached notice is published to accessibility") { nodes().any { it.text?.toString() == "预告接收验证" } }
            inst.runOnMainSync { assertFalse(request("$key-other")) }
            assertEquals("An occupied notice cannot report admission", 1, shown.get())
            dismiss(key)
            assertEquals("Dismiss cannot fire shown again", 1, shown.get())
        } finally {
            try { dismiss(key); dismiss("$key-other") }
            finally { automation.serviceInfo = automation.serviceInfo.apply { flags = originalAutomationFlags } }
        }
    }

    @Test fun realCountdownRemainsVisibleOnHomeAndCancelsWhenScreenTurnsOff() {
        val prefs = context.getSharedPreferences("doppel", Context.MODE_PRIVATE)
        assertNull("Leave an existing execution session untouched", DeviceWorkerService.instance)
        assertTrue("Leave any existing task untouched", prefs.getString("active_run", "").isNullOrBlank())
        assertFalse("Do not migrate a legacy connection as part of this test", prefs.getBoolean("artemis_mode", false))
        assertTrue("Do not run existing rules during the test", AutoTriggerStore(context).list().none { it.enabled })
        val power = context.getSystemService(PowerManager::class.java)
        val lock = context.getSystemService(KeyguardManager::class.java)
        assertTrue("Test only on an already unlocked emulator", power.isInteractive && !lock.isKeyguardLocked && !lock.isDeviceLocked)
        assertFalse("This test never changes or enters system credentials", lock.isDeviceSecure)
        val feedbackBefore = if (prefs.contains("action_feedback")) prefs.getBoolean("action_feedback", true) else null
        assertFalse("Rebinding must not change a disabled feedback preference", feedbackBefore == false)
        val runBefore = prefs.getString("active_run", "")
        val key = "notice-verification-${UUID.randomUUID()}"
        val executions = AtomicInteger()
        val executionAt = AtomicLong()
        val shownAt = AtomicLong()
        val firstCancellation = java.util.concurrent.atomic.AtomicReference<JSONObject?>()
        val report = JSONObject().put("ok", false).put("model_requests", 0).put("submitted_tasks", 0)
        var asleep = false
        var passed = false
        var stage = "rebind"
        var primaryFailure: Throwable? = null
        val oldInfo = automation.serviceInfo
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
            assertNotNull(DoppelAccessibilityService.instance)
            assertNull(blocked())
            stage = "notice_attachment"
            assertTrue(show(key, { executions.incrementAndGet(); executionAt.compareAndSet(0, SystemClock.elapsedRealtime()) },
                cancelled = { firstCancellation.compareAndSet(null, noticeDiagnostic()) },
                onShown = { shownAt.compareAndSet(0L, visibleSince()) }))
            assertFalse("Another source must not replace an existing preannouncement", show("$key-other", {}))
            // Queue admission is not an attached window. HOME should exercise an already-visible notice.
            await("Notice must be attached and exposed before navigating Home") {
                shownAt.get() > 0 && nodes().any { it.text?.toString() == "自动任务预告验证" }
            }
            val started = shownAt.get()
            stage = "countdown"
            shell("input keyevent 3")
            await("Notice remains over the launcher") { nodes().any { it.text?.toString() == "自动任务预告验证" } }
            shot("01-home-countdown")
            Thread.sleep((started + 3000 - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            val bounds = android.graphics.Rect()
            nodes().first { it.text?.toString() == "自动任务预告验证" }.getBoundsInScreen(bounds)
            shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
            Thread.sleep((started + 12000 - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            assertTrue("The early-countdown observation must occur before the 15-second deadline", SystemClock.elapsedRealtime() < started + 15000)
            assertEquals("Never execute before the full visible countdown", 0, executions.get())
            assertFalse(approved(key))
            await("The real 15-second countdown must finish without a model", 7000) { executions.get() == 1 }
            val elapsed = executionAt.get() - started
            assertTrue("15 seconds must elapse", elapsed >= 15000)
            assertTrue("Touch must not restart the countdown", elapsed < 18000)
            assertTrue(approved(key))
            Thread.sleep(1200)
            assertEquals("Completion must be delivered once", 1, executions.get())
            report.put("countdown_ms", elapsed).put("home_kept_notice", true).put("touch_did_not_restart", true)
            dismiss(key)

            stage = "skip"
            val skipped = AtomicInteger()
            assertTrue(show(key, { executions.incrementAndGet() }, { skipped.incrementAndGet() }))
            await("Skip button is visible") { nodes().any { it.text?.toString() == "跳过" } }
            val skip = nodes().first { it.text?.toString() == "跳过" }
            assertTrue(skip.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Skip callback must run") { skipped.get() == 1 }
            assertEquals(1, executions.get())
            Thread.sleep(15500)
            assertEquals("Skipped callback must not leave a timer that later executes", 1, executions.get())
            dismiss(key)

            stage = "screen_off"
            val cancelled = AtomicInteger()
            assertTrue(show(key, { executions.incrementAndGet() }, cancelled = { cancelled.incrementAndGet() }))
            asleep = true; shell("input keyevent 223")
            await("Actual emulator display must turn off") { !power.isInteractive }
            await("Screen off cancels the preannouncement") { cancelled.get() == 1 }
            assertFalse(approved(key))
            assertEquals(1, executions.get())
            assertEquals("device_locked", blocked())
            report.put("skip_prevented_execution", true).put("screen_off_cancelled", true)
            report.put("active_task_unchanged", runBefore == prefs.getString("active_run", ""))
            assertTrue(report.getBoolean("active_task_unchanged"))
            passed = true
        } catch (error: Throwable) {
            primaryFailure = error
            report.put("failure_type", error.javaClass.simpleName).put("failure_stage", stage)
                .put("failure_assertion", (error as? AssertionError)?.message?.take(400) ?: JSONObject.NULL)
            report.put("shown_at", shownAt.get()).put("executions", executions.get())
                .put("first_cancellation", firstCancellation.get() ?: JSONObject.NULL)
            runCatching { noticeDiagnostic() }.getOrNull()?.let { report.put("notice_diagnostic", it) }
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(work: () -> Unit) {
                try { work() } catch (error: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
                }
            }
            cleanup { dismiss(key) }
            cleanup { dismiss("$key-other") }
            cleanup { automation.serviceInfo = oldInfo }
            cleanup {
                if (asleep) {
                    shell("input keyevent 224")
                    await("Restore the display after the screen-off probe") { power.isInteractive }
                    shell("wm dismiss-keyguard")
                    await("Restore the original unlocked state") { !lock.isDeviceLocked && !lock.isKeyguardLocked }
                }
            }
            cleanup {
                check(prefs.edit().apply {
                    if (feedbackBefore == null) remove("action_feedback") else putBoolean("action_feedback", feedbackBefore)
                }.commit())
                assertTrue("Restore the feedback preference including its original absence", if (feedbackBefore == null)
                    !prefs.contains("action_feedback") else prefs.getBoolean("action_feedback", !feedbackBefore) == feedbackBefore)
            }
            report.put("ok", passed && cleanupFailure == null).put("stage", stage)
                .put("device_unlocked_after_cleanup", power.isInteractive && !lock.isDeviceLocked && !lock.isKeyguardLocked)
            cleanupFailure?.let {
                report.put("cleanup_failure_type", it.javaClass.simpleName)
                    .put("cleanup_failure_assertion", (it as? AssertionError)?.message?.take(400) ?: JSONObject.NULL)
            }
            cleanup { File(evidence, "result.json").writeText(report.toString(2)) }
            cleanupFailure?.let { if (primaryFailure != null) primaryFailure!!.addSuppressed(it) else throw it }
        }
    }

    @Test fun settingsKeepSystemPasswordAndNoLongerOfferDisablingIt() {
        val activity = inst.startActivitySync(Intent(context, ScheduleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            inst.runOnMainSync {
                val text = all(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(text.any { it.contains("请保留系统锁屏密码") && it.contains("15 秒") })
                assertFalse(text.any { it.contains("设置无锁屏密码") })
            }
            shot("02-schedule-settings")
        } finally { inst.runOnMainSync { activity.finish() } }
    }

    @Test fun automaticUnlockSettingsExplainRisksAndCannotEnableWithoutSystemPassword() {
        val prefs = context.getSharedPreferences("doppel", Context.MODE_PRIVATE)
        assertNull("Opening settings must not pause an existing worker", DeviceWorkerService.instance)
        assertTrue("Leave an existing task untouched", prefs.getString("active_run", "").isNullOrBlank())
        assertFalse("This case requires the original password-free emulator", context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        val credentialType = Class.forName("dev.doppel.sdk.AutomaticUnlockCredentials")
        val credentials = credentialType.getField("INSTANCE").get(null)
        fun enabled() = credentialType.getMethod("isEnabled", Context::class.java).invoke(credentials, context) as Boolean
        assertFalse("Retain any existing automatic unlock configuration", enabled())
        val activity = inst.startActivitySync(Intent(context, AutomaticUnlockSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            inst.runOnMainSync {
                val views = all(activity.window.decorView)
                val text = views.filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(text.any { it.contains("默认关闭") })
                assertTrue(text.any { it.contains("高风险") && it.contains("不能替代系统锁屏") && it.contains("强停应用") && it.contains("盗用风险") })
                assertTrue(text.any { it.contains("后台可读取该密码") })
                assertTrue(text.any { it.contains("当前设备未设置安全锁屏") })
                assertFalse("No credential fields without secure keyguard", views.any { it is EditText })
                assertFalse("No enable action without secure keyguard", text.any { it == "验证身份并开启" })
                assertTrue("The credential settings page must remain capture-protected", activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
            assertFalse("Viewing the risk notice must leave automatic unlock disabled", enabled())
        } finally { inst.runOnMainSync { activity.finish() } }
    }
}

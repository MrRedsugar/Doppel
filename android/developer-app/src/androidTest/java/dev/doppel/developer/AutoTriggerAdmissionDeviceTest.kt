package dev.doppel.developer

import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.AutoTriggerRule
import dev.doppel.sdk.AutoTriggerStore
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DoppelAccessibilityService
import dev.doppel.sdk.FirstUseConsent
import dev.doppel.sdk.Gateway
import dev.doppel.sdk.ScheduleManager
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real service and notice windows; all requests are skipped, with an unreachable loopback-only gateway. */
class AutoTriggerAdmissionDeviceTest {
    @get:org.junit.Rule val terminalTaskPointer: org.junit.rules.TestRule = TerminalTaskPointerTestRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val automation get() = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun singleton(name: String): Any = Class.forName("dev.doppel.sdk.$name").getField("INSTANCE").get(null)
    private fun call(owner: Any, name: String, vararg args: Any?): Any? = owner.javaClass.declaredMethods.single {
        it.name == name && it.parameterCount == args.size && !java.lang.reflect.Modifier.isStatic(it.modifiers)
    }.invoke(owner, *args)
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (root == null) emptyList() else
        listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    private fun visibleNodes() = automation.windows.flatMap { nodes(it.root) }.filter { it.isVisibleToUser }
    private fun has(text: String) = visibleNodes().any { it.text?.toString() == text }
    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 6_000
        do { if (condition()) return; SystemClock.sleep(80) } while (SystemClock.elapsedRealtime() < end)
        assertTrue(label, condition())
    }
    private fun click(text: String) {
        await("Missing $text") { has(text) }
        var node: AccessibilityNodeInfo? = visibleNodes().first { it.text?.toString() == text }
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node.parent
        }
        error("Cannot click $text")
    }
    private fun lastSettledAttempt(id: String): Long? {
        var stamp: Long? = null
        inst.runOnMainSync {
            val service = requireNotNull(DoppelAccessibilityService.instance)
            val engine = (service.javaClass.getDeclaredField("autoTriggers\$delegate").apply { isAccessible = true }.get(service) as Lazy<*>).value!!
            val appearances = engine.javaClass.getDeclaredField("appearances").apply { isAccessible = true }.get(engine) as Map<*, *>
            appearances[id]?.let { occurrence ->
                val pending = occurrence.javaClass.getDeclaredField("pending").apply { isAccessible = true }.get(occurrence)
                if (pending == null) stamp = occurrence.javaClass.getDeclaredField("lastAttempt").apply { isAccessible = true }.get(occurrence) as Long?
            }
        }
        return stamp
    }

    @Test fun asynchronousRejectionAndOccupiedNoticeDoNotConsumeTheControlAppearance() {
        val prefs = Gateway(context).prefs
        val consent = context.getSharedPreferences("doppel_consent", Context.MODE_PRIVATE)
        val savedPrefs = prefs.all.toMap()
        val savedConsent = consent.all.toMap()
        assertNull("Leave running sessions untouched", DeviceWorkerService.instance)
        assertTrue(prefs.getString("active_run", "").isNullOrBlank())
        val store = AutoTriggerStore(context)
        val oldRules = store.list()
        assertTrue("Test does not modify existing active rules", oldRules.none { it.enabled })
        val jobs = ScheduleManager.get(context).request("GET", "/schedules").getJSONArray("items")
        assertTrue((0 until jobs.length()).none { jobs.getJSONObject(it).optBoolean("enabled") })
        val rulePrefs = context.getSharedPreferences("doppel_auto_triggers", 0)
        val oldRulesJson = rulePrefs.getString("rules", null)
        val notice = singleton("AutomaticTaskNotice")
        val blocker = "admission-blocker-${UUID.randomUUID()}"
        val goal = "控件接收验证 ${UUID.randomUUID()}"
        val rule = AutoTriggerRule(packageName = "dev.doppel.testapp", matchResourceId = "android:id/button1",
            action = "task", taskGoal = goal, cooldownMs = 500)
        val originalAutomationFlags = automation.serviceInfo.flags
        var ownsRule = false
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            check(prefs.edit().putBoolean("direct_mode", false).putString("base_url", "http://127.0.0.1:9")
                .putString("device_id", "trigger-admission-fixture").remove("token").commit())
            check(FirstUseConsent.accept(context))
            if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
            assertNull(call(notice, "localBlockReason", context, false))
            val launcher = singleton("AutoTriggerTaskLauncher")
            val callbacks = AtomicInteger()
            val throwAfterReject: (Boolean) -> Unit = { accepted ->
                assertFalse(accepted); callbacks.incrementAndGet(); error("Fixture callback failure")
            }
            assertEquals(true, call(launcher, "launch", context, goal, throwAfterReject))
            await("Callback failure cannot prevent request cleanup") {
                callbacks.get() == 1 && !(launcher.javaClass.getDeclaredField("inFlight").apply { isAccessible = true }.get(launcher)
                    as java.util.concurrent.atomic.AtomicBoolean).get()
            }
            val synchronous = mutableListOf<Boolean>()
            val rejected: (Boolean) -> Unit = { synchronous += it }
            assertEquals(false, call(launcher, "launch", context, "", rejected))
            assertEquals(listOf(false), synchronous)
            context.startActivity(Intent().setClassName("dev.doppel.testapp", "dev.doppel.testapp.InteractionFixtureActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            click("控件触发验证")
            click("切换目标显示")
            await("Fixture target hidden") { !has("触发目标") }
            store.save(rule); ownsRule = true
            click("切换目标显示")
            await("Disconnected request settles without consuming appearance") { lastSettledAttempt(rule.id) != null }
            assertFalse(has(goal))
            check(prefs.edit().putString("token", "local-fixture-no-external-access").commit())
            SystemClock.sleep(600)
            click("刷新页面事件")
            await("Same still-visible control retries after asynchronous rejection") { has(goal) }
            click("跳过")
            await("Skipped notice removed") { !has(goal) }
            SystemClock.sleep(600)
            click("刷新页面事件")
            SystemClock.sleep(300)
            assertFalse("Skip consumes the admitted appearance", has(goal))

            click("切换目标显示")
            await("Control absent before new appearance") { !has("触发目标") }
            SystemClock.sleep(300)
            val shown = AtomicInteger()
            val noOp: () -> Unit = {}
            val valid: () -> Boolean = { true }
            val onShown: () -> Unit = { shown.incrementAndGet() }
            inst.runOnMainSync { assertEquals(true, call(notice, "show", context, blocker, "另一条预告", "本地占用验证", valid, noOp, noOp, null, noOp, onShown)) }
            await("Competing notice really attached") { shown.get() == 1 }
            val previous = lastSettledAttempt(rule.id)!!
            click("切换目标显示")
            await("Rejected occupied notice resolves") { (lastSettledAttempt(rule.id) ?: 0) > previous }
            assertFalse("Target notice cannot replace competing notice", has(goal))
            inst.runOnMainSync { call(notice, "dismiss", blocker) }
            SystemClock.sleep(600)
            click("刷新页面事件")
            await("Occupied-notice failure must leave same appearance retryable") { has(goal) }
            click("跳过")
            await("Final notice removed") { !has(goal) }
            assertNull(DeviceWorkerService.instance)
            assertTrue(prefs.getString("active_run", "").isNullOrBlank())
        } finally {
            try {
                if (ownsRule) store.remove(rule.id)
                inst.runOnMainSync { call(notice, "dismiss", blocker) }
                // Any still-open fixture notice becomes invalid when its rule is removed.
                await("Fixture notice cleaned") { !has(goal) }
                assertEquals(oldRules, store.list())
                val rulesEdit = rulePrefs.edit()
                if (oldRulesJson == null) rulesEdit.remove("rules") else rulesEdit.putString("rules", oldRulesJson)
                check(rulesEdit.commit())
            } finally {
                try { restore(prefs, savedPrefs) }
                finally {
                    try {
                        restore(consent, savedConsent)
                        context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } finally {
                        automation.serviceInfo = automation.serviceInfo.apply { flags = originalAutomationFlags }
                    }
                }
            }
        }
        assertTrue("Gateway restored without printing credentials", savedPrefs == prefs.all)
        assertTrue(savedConsent == consent.all)
    }

    private fun restore(prefs: SharedPreferences, values: Map<String, *>) {
        val edit = prefs.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        check(edit.commit())
    }
}

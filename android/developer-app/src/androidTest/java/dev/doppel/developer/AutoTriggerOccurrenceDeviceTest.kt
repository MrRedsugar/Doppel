package dev.doppel.developer

import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.AutoTriggerRule
import dev.doppel.sdk.AutoTriggerStore
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DoppelAccessibilityService
import org.junit.Assert.*
import org.junit.Test

/** External, network-free fixture. Tests the real accessibility event engine, not injected matches. */
class AutoTriggerOccurrenceDeviceTest {
    @get:org.junit.Rule val terminalTaskPointer: org.junit.rules.TestRule = TerminalTaskPointerTestRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val automation get() = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (root == null) emptyList() else
        listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    private fun visibleNodes() = nodes(automation.rootInActiveWindow).filter { it.isVisibleToUser }
    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 5_000
        do { if (condition()) return; SystemClock.sleep(80) } while (SystemClock.elapsedRealtime() < end)
        assertTrue(label, condition())
    }
    private fun has(text: String) = visibleNodes().any { it.text?.toString() == text }
    private fun click(text: String) {
        await("Missing $text") { has(text) }
        assertTrue(visibleNodes().single { it.text?.toString() == text }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun hide() { click("切换目标显示"); await("Target must disappear") { !has("触发目标") }; SystemClock.sleep(300) }
    private fun show() { click("切换目标显示"); await("Target must appear") { has("触发目标") } }

    @Test fun sameAppearanceThirdBurstAndBusyTaskCannotRepeatedlyOperate() {
        assertTrue(context.getSharedPreferences("doppel", 0).getString("active_run", "").isNullOrBlank())
        assertTrue(DeviceWorkerService.instance == null)
        if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        assertNotNull(DoppelAccessibilityService.instance)
        val prefs = context.getSharedPreferences("doppel", 0)
        val activeRun = prefs.getString("active_run", null)
        val store = AutoTriggerStore(context)
        val rulePrefs = context.getSharedPreferences("doppel_auto_triggers", 0)
        val oldRulesJson = rulePrefs.getString("rules", null)
        val oldRules = store.list()
        val fixtureIds = mutableListOf<String>()
        val fixturePackage = "dev.doppel.testapp"
        assertTrue("Existing fixture rules would interfere with this isolated check", oldRules.none { it.enabled && it.packageName == fixturePackage })
        try {
            val previousWindow = automation.rootInActiveWindow?.windowId
            context.startActivity(Intent().setClassName(fixturePackage, "$fixturePackage.InteractionFixtureActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("The replacement fixture window must be published before clicking") {
                automation.rootInActiveWindow?.let {
                    it.windowId != previousWindow && it.packageName?.toString() == fixturePackage && it.refresh()
                } == true && has("控件触发验证")
            }
            click("控件触发验证")
            hide()
            val firstRule = AutoTriggerRule(packageName = fixturePackage, matchResourceId = "android:id/button1", matchText = "触发目标", cooldownMs = 500)
            store.save(firstRule); fixtureIds += firstRule.id
            show()
            await("First appearance should click once") { has("触发次数 1") }
            SystemClock.sleep(650)
            click("刷新页面事件")
            SystemClock.sleep(300)
            assertTrue("Repeated content event must not click same appearance", has("触发次数 1"))
            hide(); show()
            await("Second appearance should re-arm") { has("触发次数 2") }
            SystemClock.sleep(650)
            hide(); show()
            await("Third occurrence is accepted before pausing") { has("触发次数 3") }
            SystemClock.sleep(650)
            hide(); show()
            SystemClock.sleep(500)
            assertTrue("Fourth occurrence must be paused", has("触发次数 3"))

            // A fresh rule has no burst history. Busy state must still block direct actions.
            store.remove(firstRule.id)
            hide()
            check(prefs.edit().putString("active_run", "trigger-fixture-busy").commit())
            val busyRule = AutoTriggerRule(packageName = fixturePackage, matchResourceId = "android:id/button1", cooldownMs = 500)
            store.save(busyRule); fixtureIds += busyRule.id
            show(); SystemClock.sleep(500)
            assertTrue("Existing task owns device", has("触发次数 3"))
            if (activeRun == null) prefs.edit().remove("active_run").commit() else prefs.edit().putString("active_run", activeRun).commit()
            click("刷新页面事件")
            await("New rule can run when current task ends") { has("触发次数 4") }
        } finally {
            fixtureIds.forEach { store.remove(it) }
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val restoreRun = prefs.edit()
            if (activeRun == null) restoreRun.remove("active_run") else restoreRun.putString("active_run", activeRun)
            check(restoreRun.commit())
            assertEquals(oldRules, store.list())
            val restoreRules = rulePrefs.edit()
            if (oldRulesJson == null) restoreRules.remove("rules") else restoreRules.putString("rules", oldRulesJson)
            check(restoreRules.commit())
            assertEquals(activeRun, prefs.getString("active_run", null))
        }
    }
}

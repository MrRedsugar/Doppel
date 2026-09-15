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
import java.util.ArrayDeque

/** Real tree and content-change events, with duplicate IDs and a description-only target. */
class AutoTriggerScanDeviceTest {
    @get:org.junit.Rule val terminalTaskPointer: org.junit.rules.TestRule = TerminalTaskPointerTestRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val automation get() = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun nodes(root: AccessibilityNodeInfo?, depth: Int = 0): List<Pair<AccessibilityNodeInfo, Int>> =
        if (root == null) emptyList() else listOf(root to depth) +
            (0 until root.childCount).flatMap { nodes(root.getChild(it), depth + 1) }
    private fun visibleNodes() = nodes(automation.rootInActiveWindow).filter { it.first.isVisibleToUser }
    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 5000
        do { if (condition()) return; SystemClock.sleep(80) } while (SystemClock.elapsedRealtime() < end)
        assertTrue(label, condition())
    }
    private fun has(text: String) = visibleNodes().any { it.first.text?.toString() == text }
    private fun click(text: String) {
        await("Missing $text") { has(text) }
        assertTrue(visibleNodes().single { it.first.text?.toString() == text && it.first.isClickable }
            .first.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test fun singleScanRetainsBfsOrderDuplicateIdsAndDescriptionOnlyTargets() {
        assertTrue(context.getSharedPreferences("doppel", 0).getString("active_run", "").isNullOrBlank())
        assertNull(DeviceWorkerService.instance)
        if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        assertNotNull(DoppelAccessibilityService.instance)
        val store = AutoTriggerStore(context)
        val rulePrefs = context.getSharedPreferences("doppel_auto_triggers", 0)
        val oldRaw = rulePrefs.getString("rules", null)
        val oldRules = store.list()
        assertTrue("Preserve existing rules without running them", oldRules.none { it.enabled })
        val ownedRules = mutableListOf<String>()
        val pkg = "dev.doppel.testapp"
        try {
            context.startActivity(Intent().setClassName(pkg, "$pkg.InteractionFixtureActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            click("控件扫描验证")
            await("Scan fixture must be ready") { has("浅层 0 · 深层 0 · 描述 0") }
            val tree = visibleNodes()
            val shallow = tree.single { it.first.text?.toString() == "浅层同ID目标" }
            val deep = tree.single { it.first.text?.toString() == "深层同ID目标" }
            assertEquals("android:id/button1", shallow.first.viewIdResourceName)
            assertEquals(shallow.first.viewIdResourceName, deep.first.viewIdResourceName)
            assertTrue("Fixture must expose different actual accessibility depths", shallow.second < deep.second)
            val description = tree.single { it.first.contentDescription?.toString() == "无ID描述目标" }.first
            assertTrue(description.viewIdResourceName.isNullOrBlank())
            assertTrue(description.text.isNullOrBlank())
            val selectors = listOf("" to "", "android:id/button1" to "", "android:id/button1" to "深层同ID目标", "" to "描述目标")
            val expected = listOf("浅层 1 · 深层 0 · 描述 0", "浅层 2 · 深层 0 · 描述 0",
                "浅层 2 · 深层 1 · 描述 0", "浅层 2 · 深层 1 · 描述 1")
            for ((index, selector) in selectors.withIndex()) {
                val rule = AutoTriggerRule(packageName = pkg, matchResourceId = "android:id/button1",
                    targetResourceId = selector.first, targetText = selector.second, cooldownMs = 500)
                store.save(rule); ownedRules += rule.id
                click("刷新扫描页面")
                await("Correct control must receive exactly one action: ${expected[index]}") { has(expected[index]) }
                store.remove(rule.id)
            }
            SystemClock.sleep(600)
            click("刷新扫描页面")
            SystemClock.sleep(250)
            assertTrue("No enabled rules must leave all controls unchanged", has(expected.last()))
        } finally {
            ownedRules.forEach(store::remove)
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertEquals(oldRules, store.list())
            val restore = rulePrefs.edit()
            if (oldRaw == null) restore.remove("rules") else restore.putString("rules", oldRaw)
            assertTrue(restore.commit())
        }
    }

    @Test fun configuredControlsBeyondFourHundredNodesRetainBfsAndTargetMatching() {
        assertTrue(context.getSharedPreferences("doppel", 0).getString("active_run", "").isNullOrBlank())
        assertNull(DeviceWorkerService.instance)
        if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        assertNotNull(DoppelAccessibilityService.instance)
        val store = AutoTriggerStore(context)
        val rulePrefs = context.getSharedPreferences("doppel_auto_triggers", 0)
        val oldRaw = rulePrefs.getString("rules", null)
        val oldRules = store.list()
        assertTrue("Preserve existing rules without running them", oldRules.none { it.enabled })
        val ownedRules = mutableListOf<String>()
        val pkg = "dev.doppel.testapp"
        try {
            context.startActivity(Intent().setClassName(pkg, "$pkg.LargeControlTreeFixtureActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("Large tree fixture must be ready") { has("浅层 0 · 深层 0 · 目标 0 · 描述 0") }
            val bfs = mutableListOf<AccessibilityNodeInfo>()
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(automation.rootInActiveWindow) }
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst(); bfs += node
                for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
            }
            assertTrue("All fixture nodes must fit the same 2,000-node picker/runtime contract", bfs.size in 451..2000)
            assertTrue("The early trigger must fit the original fast path",
                bfs.indexOfFirst { it.viewIdResourceName == "android:id/checkbox" } in 0..399)
            for (label in listOf("末尾浅层目标", "末尾独立目标", "深层同ID目标")) {
                val index = bfs.indexOfFirst { it.text?.toString() == label }
                assertTrue("$label must actually occur beyond the previous runtime cutoff", index >= 400)
                assertTrue(bfs[index].isVisibleToUser)
            }
            val tree = visibleNodes()
            assertTrue(tree.single { it.first.text?.toString() == "末尾浅层目标" }.second <
                tree.single { it.first.text?.toString() == "深层同ID目标" }.second)
            val cases = listOf(
                Triple("android:id/button1", "", ""),
                Triple("android:id/checkbox", "android:id/button2", ""),
                Triple("android:id/button1", "android:id/button1", "深层同ID目标"),
                Triple("android:id/checkbox", "", "末尾描述目标")
            )
            val expected = listOf("浅层 1 · 深层 0 · 目标 0 · 描述 0", "浅层 1 · 深层 0 · 目标 1 · 描述 0",
                "浅层 1 · 深层 1 · 目标 1 · 描述 0", "浅层 1 · 深层 1 · 目标 1 · 描述 1")
            for ((index, selector) in cases.withIndex()) {
                val rule = AutoTriggerRule(packageName = pkg, matchResourceId = selector.first,
                    targetResourceId = selector.second, targetText = selector.third, cooldownMs = 500)
                store.save(rule); ownedRules += rule.id
                click("刷新大树页面")
                await("The configured late control must be clicked exactly once: ${expected[index]}") { has(expected[index]) }
                store.remove(rule.id)
            }
            SystemClock.sleep(600)
            click("刷新大树页面")
            SystemClock.sleep(250)
            assertTrue("Removing every test rule must stop all fixture actions", has(expected.last()))
        } finally {
            ownedRules.forEach(store::remove)
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertEquals(oldRules, store.list())
            val restore = rulePrefs.edit()
            if (oldRaw == null) restore.remove("rules") else restore.putString("rules", oldRaw)
            assertTrue(restore.commit())
        }
    }
}

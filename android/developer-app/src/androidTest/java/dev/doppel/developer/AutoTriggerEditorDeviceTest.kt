package dev.doppel.developer

import android.app.Activity
import android.app.UiAutomation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.AutoTriggerRule
import dev.doppel.sdk.AutoTriggerSettingsActivity
import dev.doppel.sdk.AutoTriggerStore
import org.junit.Assert.*
import org.junit.Test

/** Real editor round-trip: picker text survives save; changing action cannot save an ignored task. */
class AutoTriggerEditorDeviceTest {
    @get:org.junit.Rule val terminalTaskPointer: org.junit.rules.TestRule = TerminalTaskPointerTestRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (root == null) emptyList() else
        listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }

    private fun waitFor(label: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        do {
            nodes(automation.rootInActiveWindow).firstOrNull(predicate)?.let { return it }
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Missing control: $label")
    }

    private fun click(text: String) {
        var node: AccessibilityNodeInfo? = waitFor(text) { it.text?.toString() == text }
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node.parent
        }
        error("Control could not be clicked: $text")
    }

    @Test fun pickerMetadataAndActionEditingRoundTrip() {
        val store = AutoTriggerStore(context)
        val prefs = context.getSharedPreferences("doppel_auto_triggers", 0)
        val originalJson = prefs.getString("rules", null)
        val original = store.list()
        val originalIds = original.map { it.id }.toSet()
        var activity: Activity? = null
        try {
            assertTrue("Cannot replace rule store while a task is active",
                context.getSharedPreferences("doppel", 0).getString("active_run", "").isNullOrBlank())
            activity = instrumentation.startActivitySync(Intent(context, AutoTriggerSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("picker_package", "dev.doppel.fixture")
                .putExtra("picker_resource_id", "dev.doppel.fixture:id/skip")
                .putExtra("picker_text", "跳过"))
            waitFor("picker friendly name") { it.text?.toString() == "dev.doppel.fixture · 跳过" }
            assertFalse("Technical ID should be collapsed", nodes(automation.rootInActiveWindow)
                .any { it.isVisibleToUser && it.text?.toString() == "dev.doppel.fixture:id/skip" })
            val goal = waitFor("task content") { it.isEditable && it.hintText?.toString() == "触发后执行的任务内容" }
            assertTrue(goal.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "打开收藏列表")
            }))
            click("保存")
            waitFor("friendly saved rule") { it.text?.toString() == "dev.doppel.fixture · 跳过 → 打开收藏列表" }
            val created = store.list().single { it.id !in originalIds }
            assertEquals("跳过", created.matchText)
            assertEquals("dev.doppel.fixture:id/skip", created.matchResourceId)
            assertEquals("打开收藏列表", created.taskGoal)

            // Exercise non-default values, which used to be silently reset on edit.
            store.save(created.copy(enabled = false, cooldownMs = 7_000, burstLimit = 2, burstWindowMs = 20_000))
            instrumentation.runOnMainSync { activity?.finish() }
            activity = instrumentation.startActivitySync(Intent(context, AutoTriggerSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            click("dev.doppel.fixture · 跳过 → 打开收藏列表")
            click("执行任务")
            click("点击")
            waitFor("click target") { it.isEditable && it.hintText?.toString() == "要点击的文字（可留空则点触发控件）" }
            assertFalse("Task content must disappear for click action", nodes(automation.rootInActiveWindow)
                .any { it.isVisibleToUser && it.isEditable && it.hintText?.toString() == "触发后执行的任务内容" })
            click("保存")
            waitFor("saved click rule") { it.text?.toString() == "dev.doppel.fixture · 跳过 → 点击该控件" }
            val edited: AutoTriggerRule = store.list().single { it.id == created.id }
            assertEquals("click", edited.action)
            assertEquals("", edited.taskGoal)
            assertEquals(created.matchText, edited.matchText)
            assertFalse(edited.enabled)
            assertEquals(7_000L, edited.cooldownMs)
            assertEquals(2, edited.burstLimit)
            assertEquals(20_000L, edited.burstWindowMs)
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            store.list().filter { it.id !in originalIds && it.packageName == "dev.doppel.fixture" }.forEach { store.remove(it.id) }
            assertEquals(original, store.list())
            val restore = prefs.edit()
            if (originalJson == null) restore.remove("rules") else restore.putString("rules", originalJson)
            check(restore.commit())
        }
    }
}

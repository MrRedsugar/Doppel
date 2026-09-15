package dev.doppel.developer

import android.app.Activity
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.FirstUseConsent
import dev.doppel.sdk.Gateway
import dev.doppel.sdk.ScheduleActivity
import dev.doppel.sdk.ScheduleManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Actual calendar editor plus Android persistence. Disabled fixture; no worker, model or external request. */
class ScheduleEditorDeviceTest {
    @get:org.junit.Rule val terminalTaskPointer: org.junit.rules.TestRule = TerminalTaskPointerTestRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val automation get() = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (root == null) emptyList() else
        listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }

    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 6_000
        do { if (condition()) return; SystemClock.sleep(80) } while (SystemClock.elapsedRealtime() < end)
        assertTrue(label, condition())
    }
    private fun view(activity: Activity, label: String, predicate: (View) -> Boolean): View {
        var result: View? = null
        await(label) { inst.runOnMainSync { result = views(activity.window.decorView).firstOrNull(predicate) }; result != null }
        return requireNotNull(result)
    }
    private fun click(activity: Activity, label: String) {
        val target = view(activity, label) { (it as? TextView)?.text?.toString() == label || it.contentDescription?.toString() == label }
        inst.runOnMainSync {
            target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true)
            assertTrue("Clickable UI control: $label", target.performClick())
        }
    }
    private fun openEditor(activity: Activity, goal: String) {
        val goalView = view(activity, "Saved fixture card") { it !is EditText && (it as? TextView)?.text?.toString() == goal }
        inst.runOnMainSync {
            val card = goalView.parent as ViewGroup
            val edit = views(card).single { it.contentDescription?.toString() == "编辑计划" }
            edit.requestRectangleOnScreen(Rect(0, 0, edit.width, edit.height), true)
            assertTrue(edit.performClick())
        }
        view(activity, "Task goal field") { it is EditText && it.hint?.toString() == "希望替你完成什么？" }
    }
    private fun assertNaturalEditor(activity: Activity, frequency: String) {
        inst.runOnMainSync {
            val all = views(activity.window.decorView)
            assertEquals(frequency, (all.single { it.contentDescription?.toString() == "执行频率" } as TextView).text.toString())
            assertEquals("Daily/weekly editor should only need the task text field", 1, all.filterIsInstance<EditText>().size)
            assertFalse("No developer expression controls", all.filterIsInstance<TextView>().any {
                "${it.text} ${it.hint?.toString().orEmpty()} ${it.contentDescription?.toString().orEmpty()}".let { label ->
                    label.contains("cron", ignoreCase = true) || label.contains("表达式")
                }
            })
            assertTrue("Native time picker must retain the original 09:30", all.filterIsInstance<TextView>().any { it.text.toString() == "时间 · 09:30" })
        }
    }
    private fun chooseWeekly(activity: Activity) {
        click(activity, "执行频率")
        var option: AccessibilityNodeInfo? = null
        await("Weekly frequency option") {
            option = nodes(automation.rootInActiveWindow).firstOrNull { it.text?.toString() == "每周" && it.isVisibleToUser }
            option != null
        }
        var node = option
        var clicked = false
        while (node != null && !clicked) {
            if (node.isClickable) clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node = node.parent
        }
        assertTrue("Weekly option selectable", clicked)
        view(activity, "Weekday choices") { it is CheckBox && it.text.toString() == "周一" }
        inst.runOnMainSync {
            val desired = setOf("周一", "周三")
            views(activity.window.decorView).filterIsInstance<CheckBox>().forEach {
                val checked = it.text.toString() in desired
                if (it.isChecked != checked) it.performClick()
                assertEquals("Weekday selection must change", checked, it.isChecked)
            }
        }
    }

    @Test fun disabledDailyEditAndNaturalWeeklyControlsRoundTrip() {
        val gateway = Gateway(context)
        val manager = ScheduleManager.get(context)
        val originalJobs = manager.request("GET", "/schedules").getJSONArray("items")
        val originalIds = (0 until originalJobs.length()).map { originalJobs.getJSONObject(it).getString("id") }.toSet()
        assertNull("Never stop an existing worker for this test", DeviceWorkerService.instance)
        assertTrue(gateway.prefs.getString("active_run", "").isNullOrBlank())
        for (index in 0 until originalJobs.length()) assertFalse("Existing enabled schedules must be left alone", originalJobs.getJSONObject(index).getBoolean("enabled"))
        val prefs = gateway.prefs
        val consent = context.getSharedPreferences("doppel_consent", Context.MODE_PRIVATE)
        val beforePrefs = prefs.all.toMap()
        val beforeConsent = consent.all.toMap()
        val gateClass = Class.forName("dev.doppel.sdk.TaskSubmissionGate")
        val gateOwner = gateClass.getField("INSTANCE").get(null)
        val gate = gateClass.declaredMethods.single { it.name.startsWith("getCreating") && it.parameterCount == 0 }
            .invoke(gateOwner) as AtomicBoolean
        assertTrue("Do not disturb another submission", gate.compareAndSet(false, true))
        val marker = "计划编辑验证 ${UUID.randomUUID()}"
        var activity: Activity? = null
        var id = ""
        try {
            check(prefs.edit().putBoolean("direct_mode", false).putString("base_url", "http://127.0.0.1:9")
                .putString("token", "schedule-editor-fixture-no-network").putString("device_id", "schedule-editor-fixture").commit())
            check(FirstUseConsent.accept(context))
            val created = manager.request("POST", "/schedules", JSONObject().put("device_id", "schedule-editor-fixture")
                .put("goal", marker).put("mode", "ask").put("enabled", false)
                .put("rule", JSONObject().put("kind", "cron").put("timezone", "Asia/Shanghai").put("expression", "30 9 * * *")))
            id = created.getString("id")
            val originalRule = created.getJSONObject("rule").toString()
            assertFalse(created.getBoolean("enabled"))
            val owner = inst.startActivitySync(Intent(context, ScheduleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = owner
            openEditor(owner, marker)
            assertNaturalEditor(owner, "每天")
            val goalField = view(owner, "Task field") { it is EditText && it.hint?.toString() == "希望替你完成什么？" } as EditText
            val editedGoal = "$marker · 已修改"
            inst.runOnMainSync { goalField.setText(editedGoal) }
            click(owner, "保存计划")
            await("Local plan edit persisted") { manager.request("GET", "/schedules/$id").getString("goal") == editedGoal }
            val daily = manager.request("GET", "/schedules/$id")
            assertFalse("Editing goal must preserve disabled state", daily.getBoolean("enabled"))
            assertEquals("Editing goal must preserve exact recurrence", originalRule, daily.getJSONObject("rule").toString())

            openEditor(owner, editedGoal)
            assertNaturalEditor(owner, "每天")
            chooseWeekly(owner)
            assertNaturalEditor(owner, "每周")
            click(owner, "保存计划")
            await("Weekly selection persisted") { manager.request("GET", "/schedules/$id").getJSONObject("rule").optString("expression") == "30 9 * * 1,3" }
            val weekly = manager.request("GET", "/schedules/$id")
            assertFalse("Changing recurrence must preserve disabled state", weekly.getBoolean("enabled"))
            assertEquals("Asia/Shanghai", weekly.getJSONObject("rule").getString("timezone"))
            assertEquals(0, weekly.getJSONArray("history").length())
            openEditor(owner, editedGoal)
            assertNaturalEditor(owner, "每周")
            inst.runOnMainSync {
                val checked = views(owner.window.decorView).filterIsInstance<CheckBox>().filter { it.isChecked }.map { it.text.toString() }.toSet()
                assertEquals(setOf("周一", "周三"), checked)
            }
            assertNull("UI editing never starts a worker", DeviceWorkerService.instance)
        } finally {
            try {
                try {
                    activity?.let { owner ->
                        // Wait for editor I/O before restoring the gateway so it cannot race cleanup.
                        val executor = ScheduleActivity::class.java.getDeclaredField("io").apply { isAccessible = true }.get(owner) as ExecutorService
                        try { if (!executor.isShutdown) executor.submit {}.get(6, TimeUnit.SECONDS) }
                        finally { inst.runOnMainSync { owner.finish() } }
                    }
                } finally {
                    val current = manager.request("GET", "/schedules").getJSONArray("items")
                    (0 until current.length()).map { current.getJSONObject(it) }.filter {
                        it.getString("id") !in originalIds && (it.getString("id") == id || it.optString("goal").startsWith(marker)) &&
                            it.optString("device_id") == "schedule-editor-fixture" && it.getJSONArray("history").length() == 0
                    }.forEach { manager.request("DELETE", "/schedules/${it.getString("id")}") }
                }
            } finally {
                try { restore(prefs, beforePrefs) }
                finally { try { restore(consent, beforeConsent) } finally { gate.set(false); manager.arm() } }
            }
        }
        assertTrue("Gateway configuration restored without printing credentials", beforePrefs == prefs.all)
        assertTrue("Consent restored", beforeConsent == consent.all)
        val remaining = manager.request("GET", "/schedules").getJSONArray("items")
        assertEquals(originalIds, (0 until remaining.length()).map { remaining.getJSONObject(it).getString("id") }.toSet())
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

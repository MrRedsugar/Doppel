@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.AutoTriggerStore
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DoppelAccessibilityService
import dev.doppel.sdk.ScreenNotReadyException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real window events and Split gestures; no synthetic events, model calls or serviceInfo overrides. */
class WindowSubscriptionDeviceTest {
    @get:org.junit.Rule val terminalTaskPointer: org.junit.rules.TestRule = TerminalTaskPointerTestRule()
    @Test fun realWindowChangesUpdateNavigationButOwnOverlaysAllowTapAndDoubleTap() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        val automation = ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        assertNull("Leave running sessions untouched", DeviceWorkerService.instance)
        assertTrue(context.getSharedPreferences("doppel", 0).getString("active_run", "").isNullOrBlank())
        assertTrue("Existing rules must not operate the fixture", AutoTriggerStore(context).list().none { it.enabled })
        if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(ins)
        val service = requireNotNull(DoppelAccessibilityService.instance)
        assertTrue("Production XML must subscribe to window changes",
            service.serviceInfo.eventTypes and AccessibilityEvent.TYPE_WINDOWS_CHANGED != 0)
        assertFalse("Leave existing touch protection untouched", service.guardVisible)
        val generationField = service.javaClass.getDeclaredField("navigationGeneration").apply { isAccessible = true }
        val signatureField = service.javaClass.getDeclaredField("windowSignature").apply { isAccessible = true }
        fun navigation(): Pair<Int, String> {
            var value: Pair<Int, String>? = null
            ins.runOnMainSync { value = generationField.getInt(service) to signatureField.get(service) as String }
            return requireNotNull(value)
        }
        fun await(label: String, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 6000
            do {
                // A real app transition can temporarily have no accessibility root.
                if (try { condition() } catch (_: ScreenNotReadyException) { false }) return
                SystemClock.sleep(80)
            } while (SystemClock.elapsedRealtime() < end)
            assertTrue(label, condition())
        }
        fun has(label: String): Boolean {
            val nodes = service.observe().getJSONArray("nodes")
            return (0 until nodes.length()).any { nodes.getJSONObject(it).optString("text") == label }
        }
        fun settle() {
            // WindowManager attaches before accessibility publishes the corresponding event.
            SystemClock.sleep(250)
            automation.waitForIdle(200, 5000)
        }
        fun command(kind: String) = JSONObject().put("id", UUID.randomUUID().toString())
            .put("run_id", "window-subscription-regression").put("kind", kind).put("split_agent", true).put("mode", "full")
        fun gesture(kind: String, vararg labels: String): JSONObject {
            var shot = service.execute(command("screenshot"))
            repeat(3) {
                if (shot.optString("status") == "stale") {
                    SystemClock.sleep(200)
                    shot = service.execute(command("screenshot"))
                }
            }
            assertEquals(shot.optString("message"), "ok", shot.optString("status"))
            val frame = shot.getJSONObject("data").getJSONObject("visual_frame")
            val nodes = shot.getJSONObject("observation").getJSONArray("nodes")
            val points = JSONArray()
            for (label in labels) {
                val node = (0 until nodes.length()).map { nodes.getJSONObject(it) }
                    .first { it.optString("text") == label && it.optBoolean("clickable") }
                val bounds = node.getJSONArray("bounds")
                val x = (bounds.getDouble(0) + bounds.getDouble(2)) * 500 / frame.getDouble("display_width")
                val y = (bounds.getDouble(1) + bounds.getDouble(3)) * 500 / frame.getDouble("display_height")
                points.put(JSONArray(listOf(x, y)))
            }
            // Never retry an action: a false stale/cancelled result must fail this regression.
            return service.execute(command("split_action").put("source", frame).put("action", JSONObject()
                .put("status", "located").put("action", kind).put("target", labels.joinToString())
                .put("points", points)))
        }
        fun home() = context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            service.stopActionFeedback()
            home()
            await("Home must publish a real window signature") {
                service.observe().optString("package_name") != "dev.doppel.testapp" && navigation().second.isNotEmpty()
            }
            settle()
            val homeNavigation = navigation()
            context.startActivity(Intent().setClassName("dev.doppel.testapp", "dev.doppel.testapp.InteractionFixtureActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("Fixture must publish a different real window signature") {
                has("控件触发验证") && navigation().second.isNotEmpty() && navigation().second != homeNavigation.second
            }
            settle()
            assertTrue("Real app navigation must invalidate earlier frames", navigation().first > homeNavigation.first)
            // Fixture setup uses its real node action; the assertions below use coordinate injection.
            val entry = automation.rootInActiveWindow.findAccessibilityNodeInfosByText("控件触发验证")
                .first { it.isClickable && it.text?.toString() == "控件触发验证" }
            assertTrue(entry.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Fixture counter must be ready") { has("触发次数 0") }
            settle()
            val baseline = navigation()
            service.setTouchGuard(true)
            await("Touch guard must attach") { service.guardVisible }
            settle()
            assertEquals("Attaching our own guard must not change app navigation", baseline, navigation())
            val cases = listOf("tap" to listOf("触发目标"), "double_tap" to listOf("触发目标"),
                "double_tap" to listOf("触发目标", "刷新页面事件"))
            for ((index, case) in cases.withIndex()) {
                val (kind, labels) = case
                val receipt = gesture(kind, *labels.toTypedArray())
                assertEquals(receipt.toString(), "ok", receipt.optString("status"))
                val data = receipt.getJSONObject("data")
                assertEquals(receipt.toString(), "accepted", data.getString("action_state"))
                assertEquals(if (kind == "double_tap") 2 else 1, data.getInt("completed_strokes"))
                await("Guard must return after $kind") { service.guardVisible }
                val count = listOf(1, 3, 4)[index]
                await("Both strokes must reach the real controls: expected $count") { has("触发次数 $count") }
                if (index == 2) await("Second point must reach refresh control") { has("页面已刷新") }
                settle()
                assertEquals("Guard handoff and action feedback must preserve navigation after $kind", baseline, navigation())
            }
            service.stopActionFeedback()
            service.setTouchGuard(false)
            await("All own overlays must detach") { !service.guardVisible && !service.feedbackVisible }
            settle()
            assertEquals("Removing own overlays must preserve navigation", baseline, navigation())
        } finally {
            service.stopActionFeedback()
            service.setTouchGuard(false)
            ins.waitForIdleSync()
            home()
        }
    }
}

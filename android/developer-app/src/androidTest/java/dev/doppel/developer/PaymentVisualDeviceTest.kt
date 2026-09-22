@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")
package dev.doppel.developer

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Scripted A/B verdicts, production Android executor, real receiver MotionEvents. No model/payment network. */
class PaymentVisualDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val ui by lazy { inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }
    private val fixture = "dev.doppel.testapp"
    private val session = UUID.randomUUID().toString()
    private val run = "payment-visual-$session"
    private lateinit var service: DoppelAccessibilityService
    private lateinit var folder: File
    private val rows = JSONArray()
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 8000
        do { if (condition()) return; SystemClock.sleep(80) } while (SystemClock.elapsedRealtime() < end)
        assertTrue(message, condition())
    }
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else
        listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun stateOrNull(): JSONObject? {
        val all = nodes(ui.rootInActiveWindow)
        return try { all.firstOrNull { it.packageName?.toString() == fixture && it.contentDescription?.startsWith("payment-fixture:") == true }
            ?.contentDescription?.toString()?.substringAfter("payment-fixture:")?.let(::JSONObject) }
        finally { all.forEach { it.recycle() } }
    }
    private fun state() = requireNotNull(stateOrNull()).also { assertEquals(session, it.getString("session")) }
    private fun command(kind: String, runId: String = run) = JSONObject().put("id", UUID.randomUUID().toString())
        .put("run_id", runId).put("kind", kind).put("split_agent", true).put("mode", "full")
    private fun capture(): JSONObject {
        var shot = JSONObject()
        repeat(5) {
            if (shot.optString("status") != "ok") {
                SystemClock.sleep(350)
                shot = service.execute(command("screenshot"))
            }
        }
        assertEquals(shot.toString(), "ok", shot.optString("status"))
        assertEquals(fixture, shot.getJSONObject("observation").getString("package_name"))
        return shot.getJSONObject("data").getJSONObject("visual_frame")
    }
    private fun action(pay: Boolean, mode: String = "full", grant: String? = null, runId: String = run): JSONObject {
        val frame = capture()
        val bounds = state().getJSONArray(if (pay) "payment_bounds" else "history_bounds")
        val target = if (pay) "确认模拟付款" else "页面中部的“全部”订单入口（位于待付款、待收货等图标右侧）"
        return command("split_action", runId).put("mode", mode).put("source", frame)
            .put("semantic_intent", JSONObject().put("action", if (pay) "pay" else "tap").put("target", target))
            .put("action", JSONObject().put("status", "located").put("action", "tap").put("target", target)
                .put("duration_ms", 90).put("points", JSONArray().put(JSONArray(listOf(
                    (bounds.getDouble(0) + bounds.getDouble(2)) * 500 / frame.getDouble("display_width"),
                    (bounds.getDouble(1) + bounds.getDouble(3)) * 500 / frame.getDouble("display_height"))))))
            .apply { if (grant != null) put("payment_consent_id", grant) }
    }
    private fun execute(name: String, request: JSONObject, status: String, navigation: Int = 1, payments: Int = 0): JSONObject {
        val result = service.execute(request)
        await("Actual fixture effects for $name must match") {
            stateOrNull()?.let { it.optInt("navigation_clicks") == navigation && it.optInt("payment_clicks") == payments } == true
        }
        rows.put(JSONObject().put("case", name).put("request", request).put("receipt", result).put("actual", state()))
        assertEquals(result.toString(), status, result.optString("status"))
        if (status == "blocked") assertEquals(result.toString(), "payment", result.getJSONObject("data").getString("human_takeover"))
        return result
    }
    private fun enable(consent: PaymentConsent): String {
        // Isolated test-only setup of the same persisted gate; the separate consent UI test covers real settings interaction.
        val owner = Any()
        PaymentConsent.enterSettings(owner)
        try {
            val flow = PaymentConsentFlow().apply { begin(0) }
            for (now in listOf(5000L, 10000L, 15000L)) assertTrue(flow.advance(now, true))
            assertTrue(consent.enable(flow))
        } finally { PaymentConsent.leaveSettings(owner) }
        return requireNotNull(consent.currentId())
    }

    @Test fun semanticNavigationAndAuthorizedCoordinatePaymentUseActualTouches() {
        assertEquals("Only the disposable QA package may change test consent", "dev.doppel.queueqa", context.packageName)
        assertEquals("true", InstrumentationRegistry.getArguments().getString("queue_qa"))
        assertNull("No user task may be replaced", DeviceWorkerService.instance)
        assertFalse(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        ui
        AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        service = requireNotNull(DoppelAccessibilityService.instance)
        assertFalse(service.guardVisible)
        val consent = PaymentConsent(context)
        assertFalse("Fresh disposable package required", consent.isEnabledForSettings())
        folder = File(context.getExternalFilesDir(null), "payment-visual").apply { mkdirs() }
        val report = JSONObject().put("passed", false).put("model_calls", 0).put("cases", rows)
            .put("scope", "scripted planner decisions, real Android gestures, no live purchase")
        try {
            context.startActivity(Intent().setClassName(fixture, "$fixture.PaymentNavigationFixtureActivity")
                .putExtra("session", session).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("Fixture must be visible") { stateOrNull()?.optString("session") == session }
            val all = nodes(ui.rootInActiveWindow)
            try {
                assertTrue("Payment-like labels must really exist in the Android node tree", all.any { it.text?.contains("待付款") == true })
                assertFalse("Canvas targets must have no accessible target nodes", all.any {
                    it.text?.toString() in setOf("全部订单", "确认模拟付款") ||
                        it.contentDescription?.toString() in setOf("全部订单", "确认模拟付款")
                })
            } finally { all.forEach { it.recycle() } }
            execute("navigation-with-payment-reference", action(false, "assist"), "ok")
            execute("payment-switch-off", action(true), "blocked")
            val grant = enable(consent)
            execute("navigation-cannot-carry-payment-grant", action(false, grant = grant), "blocked")
            for (mode in listOf("ask", "assist")) execute("payment-$mode", action(true, mode, grant), "blocked")
            val first = execute("full-payment-without-target-node", action(true, grant = grant), "ok", payments = 1)
            assertTrue(first.getJSONObject("data").getBoolean("payment_attempted"))
            val repeat = execute("duplicate-new-capture", action(true, grant = grant), "blocked", payments = 1)
            assertEquals("duplicate", repeat.getJSONObject("data").getString("payment_guard"))
            val pending = action(true, grant = grant, runId = "$run-revoked")
            assertTrue(consent.disable())
            execute("revoked-after-command-created", pending, "blocked", payments = 1)
            val renewed = enable(consent)
            assertNotEquals(grant, renewed)
            execute("reenable-does-not-clear-attempt", action(true, grant = renewed), "blocked", payments = 1)
            ui.takeScreenshot()?.let { bitmap ->
                try { File(folder, "actual-one-payment.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
            report.put("passed", true)
        } finally {
            consent.disable()
            service.stopActionFeedback()
            File(folder, "report.json").writeText(report.toString(2))
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

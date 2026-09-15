package dev.doppel.developer

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DoppelAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real Split executor; synthetic verification screen, no model or network. */
class SplitVerificationDeviceTest {
    @Test fun clickableHeadingOpensButChallengeBlocksCoordinateAction() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        ins.getUiAutomation(1)
        assertNull(DeviceWorkerService.instance)
        assertTrue(context.getSharedPreferences("doppel", 0).getString("active_run", "").isNullOrBlank())
        if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(ins)
        val service = requireNotNull(DoppelAccessibilityService.instance)
        fun command(kind: String) = JSONObject().put("id", UUID.randomUUID().toString())
            .put("run_id", "verification-regression").put("kind", kind).put("split_agent", true).put("mode", "full")
        fun node(label: String): JSONObject {
            val nodes = service.observe().getJSONArray("nodes")
            return (0 until nodes.length()).map { nodes.getJSONObject(it) }.first { it.optString("text") == label }
        }
        fun tap(label: String): JSONObject {
            var shot = service.execute(command("screenshot"))
            // A fresh activity/overlay can still deliver its final navigation event during capture.
            repeat(3) {
                if (shot.optString("status") == "stale") {
                    Thread.sleep(200)
                    shot = service.execute(command("screenshot"))
                }
            }
            assertEquals(shot.optString("message"), "ok", shot.optString("status"))
            val frame = shot.getJSONObject("data").getJSONObject("visual_frame")
            val bounds = node(label).getJSONArray("bounds")
            val x = (bounds.getDouble(0) + bounds.getDouble(2)) * 500 / frame.getDouble("display_width")
            val y = (bounds.getDouble(1) + bounds.getDouble(3)) * 500 / frame.getDouble("display_height")
            return service.execute(command("split_action").put("source", frame).put("action", JSONObject()
                .put("status", "located").put("action", "tap").put("target", label)
                .put("points", JSONArray().put(JSONArray(listOf(x, y))))))
        }
        context.startActivity(Intent().setClassName("dev.doppel.testapp", "dev.doppel.testapp.InteractionFixtureActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (runCatching { service.observe().toString().contains("CAPTCHA") }.getOrDefault(false).not() &&
            SystemClock.elapsedRealtime() < deadline) Thread.sleep(80)
        ins.getUiAutomation(1).waitForIdle(200, 5000)
        try {
            assertTrue(node("CAPTCHA").getBoolean("clickable"))
            val opened = tap("CAPTCHA")
            assertEquals(opened.toString(), "ok", opened.optString("status"))
            assertTrue(service.observe().toString().contains("请完成安全验证"))
            service.setTouchGuard(true)
            val blocked = tap("受保护操作")
            assertEquals(blocked.toString(), "blocked", blocked.optString("status"))
            assertEquals("verification", blocked.getJSONObject("data").getString("human_takeover"))
            ins.waitForIdleSync()
            assertFalse(service.guardVisible)
            assertFalse(service.observe().toString().contains("错误：验证前触发了操作"))
        } finally {
            service.stopActionFeedback(); service.setTouchGuard(false)
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

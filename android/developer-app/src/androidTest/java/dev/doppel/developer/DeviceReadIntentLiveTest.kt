package dev.doppel.developer

import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DirectRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Four real model routing requests; no device data is read and no task is created. */
class DeviceReadIntentLiveTest {
    @Test fun currentDeviceDataRequestsRouteToTasksWhileDiscussionRemainsChat() {
        val inst = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("device_read_intent_live") == "true")
        val context = inst.targetContext
        val runtime = DirectRuntime.get(context)
        assertFalse("Retain all unfinished tasks", runtime.hasUnfinishedRun())
        assertNull(DeviceWorkerService.instance)
        val file = File(context.noBackupFilesDir, "direct-runs-v1.json")
        val before = file.takeIf { it.exists() }?.readBytes()
        val cases = listOf(
            "帮我看看明天手机日历里有什么安排，只汇总已有日程。" to "task",
            "把当前剪贴板里的文字读给我。" to "task",
            "告诉我手机现在有哪些通知。" to "task",
            "解释日历、通知和剪贴板的区别，不要读取手机内容。" to "conversation"
        )
        val rows = JSONArray()
        val report = JSONObject().put("status", "running").put("rows", rows)
            .put("real_model_requests", cases.size).put("device_reads", 0).put("created_tasks", 0)
        val folder = File(context.getExternalFilesDir(null), "full-feature/device-read-intent").apply { mkdirs() }
        try {
            for ((message, expected) in cases) {
                val started = android.os.SystemClock.elapsedRealtime()
                val result = runtime.request("POST", "/conversation/intent", JSONObject()
                    .put("message", message).put("history", JSONArray()).put("device_available", true))
                rows.put(JSONObject().put("message", message).put("expected", expected).put("result", result)
                    .put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - started))
                assertEquals("Route the actual purpose, not matching words", expected, result.getString("intent"))
                if (expected == "task") {
                    assertTrue(result.getDouble("confidence") >= 0.72)
                    assertTrue(result.getString("task_goal").isNotBlank())
                }
            }
            report.put("status", "passed")
        } finally {
            val after = file.takeIf { it.exists() }?.readBytes()
            val unchanged = before?.contentEquals(after) ?: (after == null)
            report.put("task_history_unchanged", unchanged)
            if (report.optString("status") != "passed" || !unchanged) report.put("status", "failed")
            File(folder, "report.json").writeText(report.toString(2))
            assertTrue("Routing must not create or modify a task", unchanged)
            assertNull(DeviceWorkerService.instance)
        }
    }
}

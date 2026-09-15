package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectRecoveryTest {
    @Test fun repeatedPausePreservesTheOriginalFailureAndEvents() {
        val state = savedRun("paused", "已达到本机任务调用或时间上限，请结束后创建新任务")
        val engine = DirectTaskEngine(JSONArray().put(state).toString(), {}, { 2000L })
        val before = engine.get("run-a").toString()
        val events = engine.events("run-a").toString()
        repeat(2) { engine.control("run-a", "pause", JSONObject()) }
        assertEquals(before, engine.get("run-a").toString())
        assertEquals(events, engine.events("run-a").toString())
        assertTrue(engine.poll().isNull("command"))
    }

    @Test fun reopeningAPausedDeviceFailurePreservesItsReasonDiagnosticsAndEvents() {
        var persisted = ""
        var now = 1000L
        val engine = DirectTaskEngine(null, { persisted = it }, { now })
        val id = engine.create(JSONObject().put("device_id", DirectRuntime.DEVICE_ID)
            .put("goal", "读取游戏界面").put("mode", "full")).getString("id")
        val command = engine.poll().getJSONObject("command")
        now = 1500L
        val reason = "游戏窗口尚未提供可读取的根节点"
        engine.result(JSONObject().put("run_id", id).put("command_id", command.getString("id"))
            .put("status", "error").put("message", reason)
            .put("data", JSONObject().put("read_diagnostic", JSONObject()
                .put("error_class", "ScreenNotReadyException").put("source_file", "DoppelAccessibilityService.kt").put("source_line", 155))))
        val original = engine.get(id).toString()
        val events = engine.events(id).toString()
        repeat(2) {
            now += 1000
            val restored = DirectTaskEngine(persisted, { persisted = it }, { now })
            assertEquals(original, restored.get(id).toString())
            assertEquals(reason, restored.get(id).getString("message"))
            assertEquals(events, restored.events(id).toString())
            assertTrue(restored.poll().isNull("command")); assertNull(restored.takeWork())
        }
    }

    @Test fun pausedTakeoverKeepsReasonButOldRequestAndActionCannotBeApprovedOrReplayed() {
        val state = savedRun("paused", "需要用户手动完成安全验证")
        var persisted = ""
        val restored = DirectTaskEngine(JSONArray().put(state).toString(), { persisted = it }, { 2000L })
        val run = restored.get("run-a")
        assertEquals("paused", run.getString("status"))
        assertEquals("需要用户手动完成安全验证", run.getString("message"))
        assertEquals(1234L, run.getLong("updated_at"))
        assertEquals(state.getJSONArray("events").toString(), restored.events("run-a").getJSONArray("items").toString())
        val saved = JSONArray(persisted).getJSONObject(0)
        assertFalse(saved.has("pending_request")); assertFalse(saved.has("pending_command"))
        assertThrows(IllegalStateException::class.java) {
            restored.control("run-a", "answer", JSONObject().put("request_id", "old-approval").put("approve", true))
        }
        assertTrue(restored.poll().isNull("command")); assertNull(restored.takeWork())
        restored.control("run-a", "resume", JSONObject())
        val fresh = restored.poll().getJSONObject("command")
        assertEquals("observe", fresh.getString("kind"))
        assertNotEquals("old-action", fresh.getString("id"))
    }

    @Test fun uncertainRunningApprovalAndInputStatesStillPauseAndDiscardPendingWork() {
        for (status in listOf("running", "awaiting_approval", "awaiting_input")) {
            val state = savedRun(status, "先前执行状态")
            var persisted = ""
            val restored = DirectTaskEngine(JSONArray().put(state).toString(), { persisted = it }, { 2000L })
            val run = restored.get("run-a")
            assertEquals("paused", run.getString("status"))
            assertTrue(run.getString("message").contains("未重放旧操作"))
            assertEquals(state.getJSONArray("events").toString(), restored.events("run-a").getJSONArray("items").toString())
            val saved = JSONArray(persisted).getJSONObject(0)
            assertFalse(saved.has("pending_request")); assertFalse(saved.has("pending_command"))
            assertTrue(restored.poll().isNull("command")); assertNull(restored.takeWork())
            restored.control("run-a", "resume", JSONObject())
            assertEquals("observe", restored.poll().getJSONObject("command").getString("kind"))
        }
    }

    @Test fun completedFailedAndCancelledHistoryRetainsFinalStatusAndExplanation() {
        for (status in listOf("completed", "failed", "cancelled")) {
            val state = savedRun(status, "终态具体说明")
            var persisted = ""
            val restored = DirectTaskEngine(JSONArray().put(state).toString(), { persisted = it }, { 2000L })
            val run = restored.get("run-a")
            assertEquals(status, run.getString("status")); assertEquals("终态具体说明", run.getString("message"))
            assertEquals(1234L, run.getLong("updated_at")); assertFalse(restored.hasUnfinished())
            assertEquals(state.getJSONArray("events").toString(), restored.events("run-a").getJSONArray("items").toString())
            val saved = JSONArray(persisted).getJSONObject(0)
            assertFalse(saved.has("pending_request")); assertFalse(saved.has("pending_command"))
            assertTrue(restored.poll().isNull("command")); assertNull(restored.takeWork())
        }
    }

    private fun savedRun(status: String, message: String) = JSONObject()
        .put("id", "run-a").put("status", status).put("message", message).put("updated_at", 1234L)
        .put("device_id", DirectRuntime.DEVICE_ID).put("goal", "测试恢复").put("mode", "full")
        .put("events", JSONArray().put(JSONObject().put("message", message).put("created_at", 1234L)))
        .put("pending_request", JSONObject().put("id", "old-approval").put("kind", "approval"))
        .put("pending_command", JSONObject().put("id", "old-action").put("kind", "tap").put("target", "obsolete-node"))
}

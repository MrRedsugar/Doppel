package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskEventOutboxTest {
    private fun body(source: String = "user", mode: String = "full") = JSONObject().put("goal", "查看设置")
        .put("title", "检查设备").put("device_id", "direct-this-phone").put("source", source)
        .put("conversation_enabled", source == "user").put("mode", mode)
    private fun rows(events: JSONArray) = (0 until events.length()).map(events::getJSONObject)
    private fun observe(engine: SplitTaskEngine) {
        val capture = engine.poll().getJSONObject("command")
        engine.result(JSONObject().put("run_id", capture.getString("run_id")).put("command_id", capture.getString("id"))
            .put("status", "ok").put("observation", JSONObject().put("screen_id", "screen").put("package_name", "dev.fixture"))
            .put("data", JSONObject().put("image_base64", "cGl4ZWxz").put("visual_frame", JSONObject()
                .put("capture_id", "frame").put("display_width", 1080).put("display_height", 1920))))
    }

    @Test fun shortTransitionsAndTerminalEventsSurviveClearedActiveAndReconnectWithoutDuplicates() {
        var saved = ""
        var writes = 0
        val engine = SplitTaskEngine(null, { saved = it; writes++ }, { 1000L })
        val id = engine.create(body("schedule")).getString("id")
        engine.start(id)
        engine.control(id, "pause", JSONObject())
        engine.control(id, "resume", JSONObject())
        engine.control(id, "cancel", JSONObject())
        assertNull(engine.companionState())
        val events = engine.companionTaskEvents()
        assertEquals(listOf("queued", "running", "paused", "running", "cancelled"), rows(events).map { it.getString("status") })
        assertEquals(5, rows(events).map { it.getString("event_id") }.toSet().size)
        assertTrue(rows(events).all { it.getString("task_id") == id })
        val before = writes
        engine.companionTaskEvents().getJSONObject(0).put("reason", "caller mutation")
        assertEquals(events.toString(), engine.companionTaskEvents().toString())
        assertEquals(before, writes)
        val restored = SplitTaskEngine(saved, { saved = it }, { 2000L })
        assertEquals(events.toString(), restored.companionTaskEvents().toString())
        restored.checkpoint()
        assertEquals(events.toString(), restored.companionTaskEvents().toString())
        assertFalse(restored.get(id).has("task_events"))
    }

    @Test fun manualInputApprovalAndBothFinishResultsPublishTheirDistinctCategories() {
        val decisions = listOf(
            Triple(JSONObject().put("kind", "manual_takeover").put("message", "请手动处理"), "paused", "manual_takeover"),
            Triple(JSONObject().put("kind", "ask_user").put("message", "请选择账户"), "awaiting_input", "input"),
            Triple(JSONObject().put("kind", "execute").put("action", "tap").put("target", "设置入口")
                .put("expected", "打开设置"), "awaiting_approval", "approval"),
            Triple(JSONObject().put("kind", "finish").put("status", "completed").put("message", "已完成"), "completed", ""),
            Triple(JSONObject().put("kind", "finish").put("status", "failed").put("message", "无法完成"), "failed", "")
        )
        for ((decision, status, category) in decisions) {
            val engine = SplitTaskEngine(null, {}, { 1000L })
            engine.create(body(mode = if (status == "awaiting_approval") "ask" else "full"))
            observe(engine)
            assertEquals("Normal screenshot progress is not another task event", 1, engine.companionTaskEvents().length())
            engine.accept(engine.takeWork()!!, SplitTestReply.response(decision))
            val events = engine.companionTaskEvents()
            assertEquals(2, events.length())
            val event = events.getJSONObject(1)
            assertEquals(status, event.getString("status")); assertEquals(category, event.getString("pause_category"))
            assertEquals(setOf("event_id", "task_id", "status", "revision", "title", "reason", "pause_category", "occurred_at_ms"),
                event.keys().asSequence().toSet())
            assertFalse(event.has("goal")); assertFalse(event.has("pending_request"))
        }
    }

    @Test fun saveFailureCannotPublishUncommittedEventAndRestartRecoveryIsRecordedOnlyOnce() {
        var saved = ""
        var failing = false
        val engine = SplitTaskEngine(null, { if (failing) error("storage unavailable") else saved = it }, { 1000L })
        val id = engine.create(body()).getString("id")
        val before = engine.companionTaskEvents().toString()
        failing = true
        assertThrows(IllegalStateException::class.java) { engine.control(id, "pause", JSONObject()) }
        assertEquals(before, engine.companionTaskEvents().toString())
        failing = false
        engine.checkpoint()
        assertEquals(2, engine.companionTaskEvents().length())
        val committed = engine.companionTaskEvents().toString()
        val restored = SplitTaskEngine(saved, { saved = it }, { 2000L })
        assertEquals(committed, restored.companionTaskEvents().toString())
        restored.control(id, "resume", JSONObject())
        val afterCrash = SplitTaskEngine(saved, { saved = it }, { 3000L })
        assertEquals(listOf("running", "paused", "running", "paused"), rows(afterCrash.companionTaskEvents()).map { it.getString("status") })
        val recovered = afterCrash.companionTaskEvents().toString()
        assertEquals(recovered, SplitTaskEngine(saved, {}, { 4000L }).companionTaskEvents().toString())
    }

    @Test fun accountAndSessionScopesExcludeOldOwnersWhileUnownedDeviceTasksStayOnLan() {
        val engine = SplitTaskEngine(null, {}, { 1000L })
        val owners = listOf(null, SplitTaskEngine.ServerOwner("a", "one", false),
            SplitTaskEngine.ServerOwner("a", "two", false), SplitTaskEngine.ServerOwner("b", "one", true))
        val ids = owners.map { owner -> engine.createOwned(body("trigger"), null, owner).getString("id").also {
            engine.control(it, "cancel", JSONObject())
        } }
        assertEquals(setOf(ids[0]), rows(engine.companionTaskEvents()).map { it.getString("task_id") }.toSet())
        assertEquals(setOf(ids[0], ids[1]), rows(engine.companionTaskEvents("a", "one")).map { it.getString("task_id") }.toSet())
        assertEquals(setOf(ids[1]), rows(engine.serverTaskEvents("a", "one")).map { it.getString("task_id") }.toSet())
        assertEquals(0, engine.serverTaskEvents("a", "missing").length())
    }

    @Test fun eventRetentionIsFiftyTotalAndDurableRowsHaveStableIdentity() {
        var saved = ""
        val engine = SplitTaskEngine(null, { saved = it }, { 1000L })
        repeat(30) { val id = engine.create(body()).getString("id"); engine.control(id, "cancel", JSONObject()) }
        val events = engine.companionTaskEvents()
        assertEquals(50, events.length())
        val runs = SplitTaskEngine.readPersistedRuns(saved)
        assertEquals(50, (0 until runs.length()).sumOf { runs.getJSONObject(it).getJSONArray("task_events").length() })
        assertEquals(events.toString(), SplitTaskEngine(saved, {}, { 2000L }).companionTaskEvents().toString())
    }

    @Test fun upgradeDoesNotAnnounceHistoricalTerminalRunsButReportsInterruptedCurrentTask() {
        var saved = ""
        val engine = SplitTaskEngine(null, { saved = it }, { 1000L })
        repeat(3) { val id = engine.create(body()).getString("id"); engine.control(id, "cancel", JSONObject()) }
        val currentId = engine.create(body()).getString("id")
        val legacy = SplitTaskEngine.readPersistedRuns(saved)
        repeat(legacy.length()) { legacy.getJSONObject(it).apply { remove("task_events"); remove("task_event_key") } }
        val restored = SplitTaskEngine(legacy.toString(), { saved = it }, { 2000L })
        val events = restored.companionTaskEvents()
        assertEquals(1, events.length())
        assertEquals(currentId, events.getJSONObject(0).getString("task_id"))
        assertEquals("paused", events.getJSONObject(0).getString("status"))
        assertEquals(4, restored.list().getJSONArray("items").length())
        assertEquals(events.toString(), SplitTaskEngine(saved, {}, { 3000L }).companionTaskEvents().toString())
    }
}

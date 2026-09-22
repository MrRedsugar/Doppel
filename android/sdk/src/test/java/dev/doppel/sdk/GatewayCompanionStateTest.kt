package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GatewayCompanionStateTest {
    private fun run(status: String = "running", id: String = "run") = JSONObject().put("id", id)
        .put("status", status).put("title", "设置检查").put("message", "等待设备操作")
        .put("goal", "任务细节").put("device_id", "phone").put("internal_secret", "must-not-publish")

    @Test fun gatewayEventsPersistTransientStatesAndExcludeHistoryReadsFromTheCaptureBoundary() {
        var saved = ""
        var writes = 0
        val history = GatewayTaskEventHistory(null) { saved = it; writes++ }
        for (status in listOf("queued", "running", "paused", "running", "completed")) history.observe("scope", run(status), 1000)
        val events = history.events("scope")
        assertEquals(listOf("queued", "running", "paused", "running", "completed"), (0 until events.length()).map { events.getJSONObject(it).getString("status") })
        history.observe("scope", run("completed").put("message", "轮询文案变化").put("revision", 100), 2000)
        assertEquals(5, writes)
        assertEquals(events.toString(), GatewayTaskEventHistory(saved) {}.events("scope").toString())
        for (path in listOf("/runs", "/runs/run", "/runs/run/events", "/runs/run/conversation"))
            assertFalse(GatewayTaskEvents.isTaskMutation("GET", path))
        assertFalse(GatewayTaskEvents.isTaskMutation("POST", "/runs/run/review"))
        for (path in listOf("/runs", "/runs/run/start", "/runs/run/pause", "/runs/run/resume", "/runs/run/cancel", "/runs/run/answer"))
            assertTrue(GatewayTaskEvents.isTaskMutation("POST", path))
        assertFalse(events.toString().contains("must-not-publish")); assertFalse(events.toString().contains("任务细节"))
    }

    @Test fun pendingRequestIdentityIsSemanticAndReadCopiesCannotChangeStoredHistory() {
        val history = GatewayTaskEventHistory(null) {}
        val manual = run("paused").put("pending_request", JSONObject().put("id", "one")
            .put("kind", "input").put("manual_only", true).put("message", "手动处理"))
        history.observe("scope", manual, 1000)
        history.observe("scope", manual, 1100)
        manual.getJSONObject("pending_request").put("id", "two")
        history.observe("scope", manual, 1200)
        val events = history.events("scope")
        assertEquals(2, events.length())
        assertEquals("manual_takeover", events.getJSONObject(1).getString("pause_category"))
        events.getJSONObject(1).put("reason", "caller mutation")
        assertEquals("手动处理", history.events("scope").getJSONObject(1).getString("reason"))
    }

    @Test fun saveFailureAndConnectionChangeCannotExposeAnotherOwnersEvents() {
        var failure = false
        val history = GatewayTaskEventHistory(null) { if (failure) error("storage unavailable") }
        history.observe("old", run(), 1000)
        val original = history.events("old").toString()
        failure = true
        assertThrows(IllegalStateException::class.java) { history.observe("new", run("paused"), 2000) }
        assertEquals(original, history.events("old").toString()); assertEquals(0, history.events("new").length())
        failure = false
        history.observe("new", run("paused"), 2000)
        assertEquals(0, history.events("old").length()); assertEquals(1, history.events("new").length())
        repeat(60) { history.observe("new", run("completed", "run-$it"), 3000L + it) }
        assertEquals(50, history.events("new").length())
    }

    @Test fun gatewayManualInputWaitIsReportedAsTakeoverRatherThanTextInput() {
        val history = GatewayTaskEventHistory(null) {}
        history.observe("scope", run("awaiting_input").put("pending_request", JSONObject().put("id", "manual")
            .put("kind", "input").put("manual_only", true)), 1000)
        assertEquals("manual_takeover", history.events("scope").getJSONObject(0).getString("pause_category"))
        history.observe("scope", run("awaiting_input").put("pending_request", JSONObject().put("id", "text")
            .put("kind", "input").put("manual_only", false)), 1100)
        assertEquals("input", history.events("scope").getJSONObject(1).getString("pause_category"))
    }

    @Test fun workerSnapshotRequiresExactConnectionActiveRunAndLiveWorker() {
        val state = WorkerCompanionState()
        state.update("scope", run("paused"))
        assertNotNull(state.read("scope", "run", true, true))
        assertNull(state.read("other", "run", true, true))
        assertNull(state.read("scope", "new-run", true, true))
        assertNull(state.read("scope", "", true, true))
        assertNull(state.read("scope", "run", false, true))
        assertNull("Resume immediately invalidates a previously paused snapshot", state.read("scope", "run", true, false))
        val exposed = state.read("scope", "run", true, true)!!
        assertFalse(exposed.has("internal_secret"))
        exposed.put("status", "running")
        assertEquals("paused", state.read("scope", "run", true, true)!!.getString("status"))
    }

    @Test fun localStopIsVisibleAndCompletedOrReplacedTasksCannotRemainControllable() {
        val state = WorkerCompanionState()
        state.update("scope", run())
        assertEquals("paused", state.read("scope", "run", true, true)!!.getString("status"))
        assertEquals("running", state.read("scope", "run", true, false)!!.getString("status"))
        for (status in listOf("awaiting_input", "awaiting_approval")) {
            state.update("scope", run(status))
            assertEquals(status, state.read("scope", "run", true, true)!!.getString("status"))
        }
        state.update("scope", run("completed"))
        assertNull(state.read("scope", "run", true, true))
    }
}

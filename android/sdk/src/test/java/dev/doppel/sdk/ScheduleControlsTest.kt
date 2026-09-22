package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScheduleControlsTest {
    private fun body() = JSONObject("""{"device_id":"fixture","goal":"打开设置","rule":{"kind":"once","at_ms":101000}}""")
    private class Port : SchedulePort {
        var calls = 0
        var reason: String? = "user_active"
        override fun readiness(job: JSONObject): String? = if (job.optLong("manual_execute_due_ms") == job.optLong("next_due_ms")) null else reason
        override fun create(job: JSONObject): String { calls++; return "run-$calls" }
        override fun start(runId: String) {}
        override fun status(runId: String) = "running"
    }
    @Test fun postponeSurvivesRestartAndDoesNotReplayOriginalOccurrence() {
        var now = 100000L; var disk: String? = null
        val engine = ScheduleEngine(null, { disk = it }, { now }, { "fixture" }); val id = engine.create(body()).getString("id")
        now = 101000L; engine.control(id, "postpone", 60000)
        val restored = ScheduleEngine(disk, { disk = it }, { now }, { "fixture" }); val port = Port().apply { reason = null }
        restored.tick(port); assertEquals(0, port.calls)
        now += 60000; restored.tick(port); restored.tick(port); assertEquals(1, port.calls)
    }
    @Test fun skipClaimsOccurrenceAndExecuteCannotReviveIt() {
        var now = 100000L
        val engine = ScheduleEngine(null, {}, { now }, { "fixture" }); val id = engine.create(body()).getString("id"); now = 101000L
        engine.control(id, "skip")
        assertThrows(IllegalStateException::class.java) { engine.control(id, "execute") }
        val port = Port(); engine.tick(port); assertEquals(0, port.calls)
        assertEquals("user_skipped", engine.get(id).getJSONArray("history").getJSONObject(0).getString("reason"))
    }
    @Test fun missedOccurrencePreservesReadinessReason() {
        var now = 100000L; val port = Port()
        val engine = ScheduleEngine(null, {}, { now }, { "fixture" }); val id = engine.create(body()).getString("id")
        now = 101000L; engine.tick(port); now += ScheduleEngine.GRACE_MS + 1; engine.tick(port)
        assertEquals("user_active", engine.get(id).getJSONArray("history").getJSONObject(0).getString("reason"))
    }
    @Test fun immediateConflictChoiceDispatchesOnlyTheSelectedSchedule() {
        var now = 100000L
        val engine = ScheduleEngine(null, {}, { now }, { "fixture" })
        val first = engine.create(body()).getString("id")
        val selected = engine.create(body().put("goal", "选中的自动任务")).getString("id")
        now = 101000L
        engine.control(selected, "execute")
        val port = Port()
        engine.tick(port, onlyId = selected)
        assertEquals(1, port.calls)
        assertEquals(0, engine.get(first).getJSONArray("history").length())
        assertEquals("queued", engine.get(selected).getJSONArray("history").getJSONObject(0).getString("status"))
    }
    @Test fun failedImmediateChoiceClearsApprovalWithoutSkippingTheOccurrence() {
        var now = 100000L
        val engine = ScheduleEngine(null, {}, { now }, { "fixture" })
        val id = engine.create(body()).getString("id")
        now = 101000L
        engine.control(id, "execute")
        engine.clearManualDecision(id, now + 1)
        assertTrue(engine.get(id).has("manual_execute_due_ms"))
        engine.clearManualDecision(id, now)
        val port = Port()
        engine.tick(port, onlyId = id)
        assertEquals(0, port.calls)
        assertFalse(engine.get(id).has("manual_execute_due_ms"))
        assertTrue(engine.get(id).getBoolean("enabled"))
        assertEquals(now, engine.get(id).getLong("next_due_ms"))
        assertEquals(0, engine.get(id).getJSONArray("history").length())
    }
}

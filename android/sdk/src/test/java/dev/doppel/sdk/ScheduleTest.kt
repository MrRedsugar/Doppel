package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.time.Instant

class ScheduleTest {
    private fun ms(value: String) = Instant.parse(value).toEpochMilli()
    private fun rule(value: String) = ScheduleRule.parse(JSONObject(value))
    @Test fun fixedAnchorAndTimeZone() {
        val interval = rule("""{"kind":"interval","every_ms":60000,"anchor_ms":1000}""")
        assertEquals(121000L, interval.nextAfter(120999L))
        assertEquals(181000L, interval.nextAfter(121000L))
        val daily = rule("""{"kind":"cron","expression":"30 9 * * *","timezone":"Asia/Shanghai"}""")
        assertEquals(ms("2026-09-08T01:30:00Z"), daily.nextAfter(ms("2026-09-08T00:00:00Z")))
    }
    @Test fun dstGapSkippedAndRepeatedTimeFiresOnce() {
        val spring = rule("""{"kind":"cron","expression":"30 2 * * *","timezone":"America/New_York"}""")
        assertEquals(ms("2026-03-09T06:30:00Z"), spring.nextAfter(ms("2026-03-08T05:00:00Z")))
        val fall = rule("""{"kind":"cron","expression":"30 1 * * *","timezone":"America/New_York"}""")
        val first = ms("2026-11-01T05:30:00Z")
        assertEquals(first, fall.nextAfter(first - 1))
        assertEquals(ms("2026-11-02T06:30:00Z"), fall.nextAfter(first))
    }
    @Test fun invalidRulesRejectedAndDomDowUsesOr() {
        for (json in listOf("""{"kind":"once","at_ms":true}""", """{"kind":"interval","every_ms":500,"anchor_ms":0}""",
                """{"kind":"cron","expression":"61 * * * *"}""", """{"kind":"cron","expression":"* * * * *","timezone":"Wrong/Zone"}""")) {
            assertThrows(IllegalArgumentException::class.java) { rule(json) }
        }
        val cron = rule("""{"kind":"cron","expression":"0 9 15 * 1","timezone":"UTC"}""")
        assertEquals(ms("2026-09-14T09:00:00Z"), cron.nextAfter(ms("2026-09-13T12:00:00Z")))
    }
    private class Port : SchedulePort {
        var busy: String? = null
        var calls = 0
        var status: String? = "running"
        val statusCalls = mutableListOf<String>()
        var statusFailure: Exception? = null
        var onStatus: () -> Unit = {}
        var fail = false
        override fun readiness(job: JSONObject) = busy
        override fun create(job: JSONObject): String { calls++; if (fail) error("secret provider detail"); return "run-$calls" }
        override fun start(runId: String) {}
        override fun status(runId: String): String? {
            statusCalls += runId; onStatus(); statusFailure?.let { throw it }; return status
        }
    }
    private fun body(at: Long) = JSONObject().put("device_id", "fixture").put("goal", "检查模拟测试界面").put("mode", "ask")
        .put("rule", JSONObject().put("kind", "once").put("at_ms", at))
    @Test fun dispatchOnceAndTrackTerminalRun() {
        var now = 100000L; var disk: String? = null; val port = Port()
        val engine = ScheduleEngine(null, { disk = it }, { now }, { "fixture" })
        val job = engine.create(body(now + 1000)); now += 1000
        engine.tick(port); engine.tick(port)
        assertEquals(1, port.calls)
        assertFalse(engine.get(job.getString("id")).getBoolean("enabled"))
        port.status = "completed"; engine.tick(port)
        assertEquals("completed", engine.get(job.getString("id")).getJSONArray("history").getJSONObject(0).getString("status"))
        assertNotNull(disk)
    }
    @Test fun missingRunEndsOnlyItsHistoryLookupAndSurvivesRestart() {
        var now = 100000L; var disk: String? = null; val port = Port()
        val engine = ScheduleEngine(null, { disk = it }, { now }, { "fixture" })
        val due = now + 1000
        val id = engine.create(body(due).put("rule", JSONObject().put("kind", "interval")
            .put("every_ms", 60000).put("anchor_ms", due))).getString("id")
        now = due; engine.tick(port)
        now += 1000; port.status = "missing"; engine.tick(port)
        val job = engine.get(id); val entry = job.getJSONArray("history").getJSONObject(0)
        assertEquals("unavailable", entry.getString("status"))
        assertEquals("run_missing", entry.getString("reason"))
        assertEquals(now, entry.getLong("checked_at_ms"))
        assertEquals("run-1", entry.getString("run_id"))
        assertEquals(due, entry.getLong("scheduled_at_ms"))
        assertFalse(entry.has("finished_at_ms"))
        assertTrue(job.getBoolean("enabled")); assertEquals(due + 60000, job.getLong("next_due_ms"))
        port.status = "completed"; now += 1000; engine.tick(port)
        val restored = ScheduleEngine(disk, { disk = it }, { now }, { "fixture" })
        restored.tick(port)
        assertEquals(listOf("run-1"), port.statusCalls); assertEquals(1, port.calls)
        assertEquals(entry.toString(), restored.get(id).getJSONArray("history").getJSONObject(0).toString())
        now = due + 60000; restored.tick(port)
        assertEquals(2, port.calls); assertEquals(listOf("run-1"), port.statusCalls)
        assertEquals("unavailable", restored.get(id).getJSONArray("history").getJSONObject(0).getString("status"))
        assertEquals("started", restored.get(id).getJSONArray("history").getJSONObject(1).getString("status"))
    }
    @Test fun unavailableStatusAndTransientErrorsRemainRetryable() {
        for (failure in listOf(null, GatewayHttpException(401, "unauthorized"),
                GatewayHttpException(403, "forbidden"), GatewayHttpException(500, "server error"),
                SocketTimeoutException("status timed out"))) {
            var now = 100000L; var disk: String? = null; val port = Port()
            val engine = ScheduleEngine(null, { disk = it }, { now }, { "fixture" })
            val id = engine.create(body(now + 1000)).getString("id")
            now += 1000; engine.tick(port)
            val started = engine.get(id).getJSONArray("history").getJSONObject(0).toString()
            port.status = null; port.statusFailure = failure; now += 1000; engine.tick(port)
            assertEquals(started, engine.get(id).getJSONArray("history").getJSONObject(0).toString())
            val restored = ScheduleEngine(disk, { disk = it }, { now }, { "fixture" })
            restored.tick(port)
            assertEquals(started, restored.get(id).getJSONArray("history").getJSONObject(0).toString())
            assertEquals(listOf("run-1", "run-1"), port.statusCalls)
            port.statusFailure = null; port.status = "completed"; now += 1000; restored.tick(port)
            val recovered = restored.get(id).getJSONArray("history").getJSONObject(0)
            assertEquals("completed", recovered.getString("status"))
            assertEquals(now, recovered.getLong("finished_at_ms")); assertFalse(recovered.has("reason"))
            assertEquals(1, port.calls); assertEquals(3, port.statusCalls.size)
        }
    }
    @Test fun connectionChangeDuringStatusIgnoresResponseAndStopsRemainingLookups() {
        for (response in listOf("missing", "completed", "failed", "cancelled")) {
            var now = 100000L; var binding = "server-a"; var disk: String? = null; val port = Port()
            val engine = ScheduleEngine(null, { disk = it }, { now }, { binding })
            val id = engine.create(body(now + 1000).put("rule", JSONObject().put("kind", "interval")
                .put("every_ms", 60000).put("anchor_ms", now + 1000))).getString("id")
            now += 1000; engine.tick(port); now += 60000; engine.tick(port)
            val before = engine.get(id).getJSONArray("history").toString(); val savedBefore = disk
            assertEquals(2, engine.get(id).getJSONArray("history").length())
            port.statusCalls.clear(); port.status = response; port.onStatus = { binding = "server-b" }
            now += 1000; engine.tick(port)
            assertEquals(listOf("run-1"), port.statusCalls)
            assertEquals(before, engine.get(id).getJSONArray("history").toString())
            repeat(2) { index ->
                assertEquals("started", engine.get(id).getJSONArray("history").getJSONObject(index).getString("status"))
            }
            assertEquals(savedBefore, disk)
            assertEquals(2, port.calls)
        }
    }
    @Test fun pausedTaskWaitsThenExpiresWithoutPreemption() {
        var now = 100000L; val port = Port().apply { busy = "device_busy" }
        val engine = ScheduleEngine(null, {}, { now }, { "fixture" })
        val id = engine.create(body(now + 1000)).getString("id"); now += 1000
        engine.tick(port); assertEquals("device_busy", engine.get(id).getString("waiting_reason"))
        now += 300001; engine.tick(port)
        assertEquals(0, port.calls)
        assertEquals("missed", engine.get(id).getJSONArray("history").getJSONObject(0).getString("status"))
    }
    @Test fun disableDeleteAndRestartDoNotReplay() {
        var now = 100000L; var disk: String? = null; val port = Port()
        val engine = ScheduleEngine(null, { disk = it }, { now }, { "fixture" })
        val id = engine.create(body(now + 1000)).getString("id")
        engine.update(id, JSONObject().put("enabled", false)); now += 1000; engine.tick(port)
        assertEquals(0, port.calls)
        engine.delete(id); assertEquals(0, engine.list().getJSONArray("items").length())
        val recurring = body(now + 1).put("rule", JSONObject().put("kind", "interval").put("every_ms", 60000).put("anchor_ms", now + 1000))
        val secondId = engine.create(recurring).getString("id"); now += 86400000
        val restored = ScheduleEngine(disk, { disk = it }, { now }, { "fixture" }); restored.tick(port)
        assertTrue(restored.get(secondId).getLong("next_due_ms") > now)
        assertEquals(0, port.calls)
    }
    @Test fun failureDisablesAndNeverCopiesRawError() {
        var now = 100000L; val port = Port().apply { fail = true }
        val engine = ScheduleEngine(null, {}, { now }, { "fixture" })
        val id = engine.create(body(now + 1000)).getString("id"); now += 1000
        engine.tick(port); engine.tick(port)
        assertEquals(1, port.calls)
        val job = engine.get(id)
        assertFalse(job.getBoolean("enabled")); assertFalse(job.toString().contains("secret"))
        assertEquals("uncertain", job.getJSONArray("history").getJSONObject(0).getString("status"))
    }
    @Test fun saveFailureAfterCreateQuarantinesWithoutRestartAndReleasesGate() {
        var now = 100000L; var writes = 0; var storageFails = true; var released = 0; var creates = 0
        val port = object : SchedulePort {
            override fun readiness(job: JSONObject): String? = null
            override fun create(job: JSONObject): String { creates++; return "run-$creates" }
            override fun start(runId: String) {}
            override fun status(runId: String): String? = null
            override fun finishDispatch() { released++ }
        }
        val engine = ScheduleEngine(null, { writes++; if (storageFails && writes > 2) error("disk unavailable") }, { now }, { "fixture" })
        val id = engine.create(body(now + 1).put("rule", JSONObject().put("kind", "interval").put("every_ms", 60000).put("anchor_ms", now + 1000))).getString("id")
        now += 1000
        assertThrows(IllegalStateException::class.java) { engine.tick(port) }
        assertEquals(1, creates); assertEquals(1, released)
        storageFails = false; now += 60000; engine.tick(port)
        assertEquals(1, creates); assertFalse(engine.get(id).getBoolean("enabled"))
    }
    @Test fun dispatchFinishesOnceOnSuccessCreateFailureAndStartFailure() {
        for (failure in listOf("", "create", "start")) {
            var now = 100000L; var releases = 0; var starts = 0
            val port = object : SchedulePort {
                override fun readiness(job: JSONObject): String? = null
                override fun create(job: JSONObject): String { check(failure != "create"); return "fixture-run" }
                override fun start(runId: String) { starts++; check(failure != "start") }
                override fun status(runId: String): String? = null
                override fun finishDispatch() { releases++ }
            }
            val engine = ScheduleEngine(null, {}, { now }, { "fixture" })
            val id = engine.create(body(now + 1000)).getString("id")
            now += 1000; engine.tick(port); engine.tick(port)
            assertEquals("Release exactly the gate owned by this dispatch: $failure", 1, releases)
            assertEquals(if (failure == "create") 0 else 1, starts)
            assertEquals(if (failure.isEmpty()) "started" else "uncertain",
                engine.get(id).getJSONArray("history").getJSONObject(0).getString("status"))
        }
    }
    @Test fun connectionChangeDoesNotReadOldRunOnNewServer() {
        var now = 100000L; var binding = "server-a"; var lookups = 0
        val port = object : SchedulePort {
            override fun readiness(job: JSONObject): String? = null
            override fun create(job: JSONObject) = "private-run-on-a"
            override fun start(runId: String) {}
            override fun status(runId: String): String? { lookups++; return "running" }
        }
        val engine = ScheduleEngine(null, {}, { now }, { binding })
        engine.create(body(now + 1000)); now += 1000; engine.tick(port)
        binding = "server-b"; engine.tick(port)
        assertEquals(0, lookups)
    }
}

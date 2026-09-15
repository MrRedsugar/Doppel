package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScheduleWakeupStatusTest {
    @Test fun systemPermissionFailureCannotUndoSavedCreateOrDuplicateThePlan() {
        var planDisk: String? = null
        var statusDisk: String? = null
        val engine = ScheduleEngine(null, { planDisk = it }, { 100000L }, { "fixture" })
        val wakeup = ScheduleWakeupStatus(null, { statusDisk = it }, { 100000L })
        val saved = engine.create(JSONObject().put("device_id", "fixture").put("goal", "Synthetic persisted plan")
            .put("rule", JSONObject().put("kind", "once").put("at_ms", 101000L)))
        saved.put("background_wakeup", wakeup.refresh { throw SecurityException("private OS diagnostics") })
        assertTrue(saved.getString("id").isNotBlank())
        assertEquals("waiting", saved.getJSONObject("background_wakeup").getString("status"))
        assertEquals("permission_missing", saved.getJSONObject("background_wakeup").getString("reason"))
        val restarted = ScheduleEngine(planDisk, { planDisk = it }, { 100000L }, { "fixture" })
        assertEquals(1, restarted.list().getJSONArray("items").length())
        assertEquals(saved.getString("id"), restarted.list().getJSONArray("items").getJSONObject(0).getString("id"))
        assertEquals("permission_missing", ScheduleWakeupStatus(statusDisk, {}).current().getString("reason"))
        assertFalse(statusDisk!!.contains("private"))
    }

    @Test fun declinedAndBrokenSystemCallsStayVisibleUntilSuccessfulRecovery() {
        var disk: String? = null
        val wakeup = ScheduleWakeupStatus(null, { disk = it }, { 1234L })
        assertEquals("system_declined", wakeup.refresh { "declined" }.getString("reason"))
        assertEquals("system_unavailable", wakeup.refresh { throw IllegalStateException("private detail") }.getString("reason"))
        val recovered = wakeup.refresh { "scheduled" }
        assertEquals("scheduled", recovered.getString("status")); assertTrue(recovered.isNull("reason"))
        assertTrue(recovered.getBoolean("status_persisted"))
        assertEquals("scheduled", ScheduleWakeupStatus(disk, {}).current().getString("status"))
        assertEquals("idle", wakeup.refresh { "idle" }.getString("status"))
    }

    @Test fun diagnosticStorageFailureDoesNotEscapeAfterPlanCommit() {
        val wakeup = ScheduleWakeupStatus(null, { error("status disk failed") }, { 1234L })
        val waiting = wakeup.refresh { throw SecurityException("system detail") }
        assertEquals("permission_missing", waiting.getString("reason"))
        assertFalse(waiting.getBoolean("status_persisted"))
        assertEquals("waiting", wakeup.current().getString("status"))
        assertFalse(waiting.toString().contains("detail"))
    }

    @Test fun identicalStatusAvoidsWritesButStillChecksTheSystemAndRetriesFailedStorage() {
        var now = 1234L
        var writes = 0
        var checks = 0
        var failSave = false
        val wakeup = ScheduleWakeupStatus(null, { writes++; if (failSave) error("disk unavailable") }, { now })
        val initial = wakeup.refresh { checks++; "scheduled" }.toString()
        repeat(5) { now++; assertEquals(initial, wakeup.refresh { checks++; "scheduled" }.toString()) }
        assertEquals(6, checks)
        assertEquals(1, writes)
        failSave = true
        assertFalse(wakeup.refresh { "declined" }.getBoolean("status_persisted"))
        assertEquals(2, writes)
        failSave = false
        assertTrue(wakeup.refresh { "declined" }.getBoolean("status_persisted"))
        assertEquals(3, writes)
        repeat(3) { wakeup.refresh { "declined" } }
        assertEquals(3, writes)
        wakeup.refresh { "idle" }
        assertEquals(4, writes)
    }
}

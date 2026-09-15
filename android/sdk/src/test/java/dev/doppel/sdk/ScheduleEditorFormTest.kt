package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class ScheduleEditorFormTest {
    private val now = Instant.parse("2026-09-15T01:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun job(expression: String, enabled: Boolean = true) = JSONObject().put("enabled", enabled)
        .put("rule", JSONObject().put("kind", "cron").put("timezone", zone.id).put("expression", expression))

    @Test fun editingDisabledSchedulePreservesStateAndDueDateWhileNewScheduleStartsEnabled() {
        val engine = ScheduleEngine(null, {}, { now }, { "fixture" })
        val existing = engine.create(job("30 9 * * *", false).put("device_id", "fixture").put("goal", "原内容"))
        val form = ScheduleEditorForm(existing, now, zone)
        val saved = engine.update(existing.getString("id"), form.saveFields("修改后的内容", "ask", now + 60000))
        assertFalse(saved.getBoolean("enabled"))
        assertTrue(saved.isNull("next_due_ms"))
        assertEquals("修改后的内容", saved.getString("goal"))
        assertTrue(ScheduleEditorForm(null, now, zone).saveFields("新任务", "ask", now).getBoolean("enabled"))
    }

    @Test fun dailyAndWeeklyReopenWithSameClockAndWeekdays() {
        for ((expression, frequency, days) in listOf(
            Triple("30 9 * * *", ScheduleEditorForm.Frequency.DAILY, null),
            Triple("5 18 * * 0,2,4", ScheduleEditorForm.Frequency.WEEKLY, setOf(7, 2, 4)),
            Triple("5 18 * * 1-5", ScheduleEditorForm.Frequency.WEEKLY, setOf(1, 2, 3, 4, 5)),
        )) {
            val existing = job(expression)
            val form = ScheduleEditorForm(existing, now, zone)
            assertEquals(frequency, form.values.frequency)
            assertEquals(if (frequency == ScheduleEditorForm.Frequency.DAILY) 9 else 18, form.values.localTime.hour)
            assertEquals(if (frequency == ScheduleEditorForm.Frequency.DAILY) 30 else 5, form.values.localTime.minute)
            if (days != null) assertEquals(days, form.values.weekdays)
            assertEquals(existing.getJSONObject("rule").toString(), form.saveFields("内容", "ask", now).getJSONObject("rule").toString())
            form.values = form.values.copy(localTime = form.values.localTime.plusMinutes(1))
            val reopened = ScheduleEditorForm(form.saveFields("内容", "ask", now), now, zone)
            assertEquals(form.values, reopened.values)
        }
    }

    @Test fun intervalKeepsAnchorOnTextEditAndUsesSelectedUnitsOnlyAfterChange() {
        val rule = JSONObject().put("kind", "interval").put("timezone", zone.id).put("every_ms", 7200000L).put("anchor_ms", now)
        val form = ScheduleEditorForm(JSONObject().put("rule", rule), now, zone)
        assertEquals(ScheduleEditorForm.IntervalUnit.HOUR, form.values.intervalUnit)
        assertEquals(2L, form.values.intervalAmount)
        assertEquals(now, form.saveFields("内容", "ask", now + 60000).getJSONObject("rule").getLong("anchor_ms"))
        form.values = form.values.copy(intervalAmount = 3, intervalUnit = ScheduleEditorForm.IntervalUnit.DAY)
        val changed = form.saveFields("内容", "ask", now).getJSONObject("rule")
        assertEquals(3 * 86400000L, changed.getLong("every_ms"))
        assertEquals(now + 3 * 86400000L, changed.getLong("anchor_ms"))
    }

    @Test fun legacyRecurrenceIsUnchangedUntilUserSelectsReplacement() {
        val existing = job("0 9 1 * *", false)
        val form = ScheduleEditorForm(existing, now, ZoneId.of("UTC"))
        assertEquals(ScheduleEditorForm.Frequency.LEGACY, form.values.frequency)
        assertEquals(zone, form.values.zone)
        assertEquals(existing.getJSONObject("rule").toString(), form.saveFields("修改描述", "assist", now).getJSONObject("rule").toString())
        form.values = form.values.copy(frequency = ScheduleEditorForm.Frequency.DAILY)
        val saved = form.saveFields("修改描述", "assist", now)
        assertFalse(saved.getBoolean("enabled"))
        assertEquals("0 10 * * *", saved.getJSONObject("rule").getString("expression"))
    }

    @Test fun emptyWeekAndInvalidIntervalsCannotBeSaved() {
        val form = ScheduleEditorForm(null, now, zone)
        form.values = form.values.copy(frequency = ScheduleEditorForm.Frequency.WEEKLY, weekdays = emptySet())
        assertThrows(IllegalArgumentException::class.java) { form.saveFields("内容", "ask", now) }
        form.values = form.values.copy(weekdays = setOf(0))
        assertThrows(IllegalArgumentException::class.java) { form.saveFields("内容", "ask", now) }
        for (amount in listOf(0L, -1L, Long.MAX_VALUE)) {
            form.values = form.values.copy(frequency = ScheduleEditorForm.Frequency.INTERVAL, intervalAmount = amount)
            assertThrows(IllegalArgumentException::class.java) { form.saveFields("内容", "ask", now) }
        }
    }

    @Test fun onceUsesChosenLocalCalendarTimeAndRejectsDstGap() {
        val form = ScheduleEditorForm(null, now, zone)
        form.values = form.values.copy(localTime = LocalDateTime.of(2026, 9, 16, 14, 45))
        assertEquals(Instant.parse("2026-09-16T06:45:00Z").toEpochMilli(), form.saveFields("内容", "ask", now).getJSONObject("rule").getLong("at_ms"))
        form.values = form.values.copy(zone = ZoneId.of("America/New_York"), localTime = LocalDateTime.of(2027, 3, 14, 2, 30))
        assertThrows(IllegalArgumentException::class.java) { form.saveFields("内容", "ask", now) }
    }
}

package dev.doppel.sdk

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Native picker values; existing rules are preserved until the recurrence actually changes. */
internal class ScheduleEditorForm(private val existing: JSONObject?, now: Long, deviceZone: ZoneId) {
    enum class Frequency { ONCE, DAILY, WEEKLY, INTERVAL, LEGACY }
    enum class IntervalUnit(val millis: Long, val label: String) {
        MINUTE(60000, "分钟"), HOUR(3600000, "小时"), DAY(86400000, "天")
    }
    data class Values(
        val frequency: Frequency,
        val zone: ZoneId,
        val localTime: LocalDateTime,
        val weekdays: Set<Int>,
        val intervalAmount: Long = 1,
        val intervalUnit: IntervalUnit = IntervalUnit.HOUR,
    )
    private val originalRule = existing?.optJSONObject("rule")?.let { JSONObject(it.toString()) }
    private val initial = read(originalRule, now, deviceZone)
    var values = initial

    fun saveFields(goal: String, mode: String, now: Long): JSONObject = JSONObject()
        .put("goal", goal).put("mode", mode).put("enabled", existing?.optBoolean("enabled", true) ?: true)
        .put("rule", if (originalRule != null && values == initial) JSONObject(originalRule.toString()) else buildRule(now))

    private fun buildRule(now: Long): JSONObject {
        val rule = JSONObject().put("timezone", values.zone.id)
        val local = values.localTime.withSecond(0).withNano(0)
        val clock = "${local.minute} ${local.hour}"
        when (values.frequency) {
            Frequency.ONCE -> {
                val offsets = values.zone.rules.getValidOffsets(local)
                require(offsets.isNotEmpty()) { "该时区不存在这个时间，请重新选择" }
                rule.put("kind", "once").put("at_ms", local.toInstant(offsets.first()).toEpochMilli())
            }
            Frequency.DAILY -> rule.put("kind", "cron").put("expression", "$clock * * *")
            Frequency.WEEKLY -> {
                require(values.weekdays.isNotEmpty() && values.weekdays.all { it in 1..7 }) { "请至少选择一个星期日期" }
                val days = values.weekdays.map { it % 7 }.sorted().joinToString(",")
                rule.put("kind", "cron").put("expression", "$clock * * $days")
            }
            Frequency.INTERVAL -> {
                require(values.intervalAmount in 1..(31536000000L / values.intervalUnit.millis)) { "间隔至少 1 分钟，最长 365 天" }
                val duration = values.intervalAmount * values.intervalUnit.millis
                rule.put("kind", "interval").put("every_ms", duration).put("anchor_ms", now + duration)
            }
            Frequency.LEGACY -> error("请先选择新的执行频率，才可替换旧版自定义周期")
        }
        return ScheduleRule.parse(rule).json()
    }

    private fun read(rule: JSONObject?, now: Long, deviceZone: ZoneId): Values {
        val zone = rule?.let { ZoneId.of(it.optString("timezone", "UTC")) } ?: deviceZone
        val local = Instant.ofEpochMilli(now + 3600000).atZone(zone).toLocalDateTime().withSecond(0).withNano(0)
        val defaults = Values(Frequency.ONCE, zone, local, setOf(local.dayOfWeek.value))
        if (rule == null) return defaults
        ScheduleRule.parse(rule)
        return when (rule.getString("kind")) {
            "once" -> defaults.copy(localTime = Instant.ofEpochMilli(rule.getLong("at_ms")).atZone(zone).toLocalDateTime())
            "interval" -> {
                val duration = rule.getLong("every_ms")
                val unit = IntervalUnit.entries.reversed().firstOrNull { duration % it.millis == 0L }
                if (unit == null) defaults.copy(frequency = Frequency.LEGACY)
                else defaults.copy(frequency = Frequency.INTERVAL, intervalAmount = duration / unit.millis, intervalUnit = unit)
            }
            else -> {
                val fields = rule.getString("expression").trim().split(Regex("\\s+"))
                val minute = fields[0].toIntOrNull(); val hour = fields[1].toIntOrNull()
                if (minute == null || hour == null || fields[2] != "*" || fields[3] != "*") {
                    defaults.copy(frequency = Frequency.LEGACY)
                } else {
                    val time = local.withHour(hour).withMinute(minute)
                    if (fields[4] == "*") defaults.copy(frequency = Frequency.DAILY, localTime = time)
                    else {
                        // Calendar pickers support selected weekdays, including legacy numeric ranges.
                        val days = runCatching {
                            fields[4].split(',').flatMap { item ->
                                require(Regex("\\d(?:-\\d)?").matches(item))
                                val ends = item.split('-').map(String::toInt)
                                (ends.first()..ends.last()).toList()
                            }.map { if (it == 0) 7 else it }.toSet()
                        }.getOrNull()
                        if (days.isNullOrEmpty()) defaults.copy(frequency = Frequency.LEGACY)
                        else defaults.copy(frequency = Frequency.WEEKLY, localTime = time, weekdays = days)
                    }
                }
            }
        }
    }
}

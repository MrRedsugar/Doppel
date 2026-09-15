package dev.doppel.sdk

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Restricted five-field cron and fixed UTC intervals; never executes expression text. */
class ScheduleRule private constructor(private val data: JSONObject) {
    companion object {
        const val MAX_MS = 253402214400000L
        fun parse(value: JSONObject): ScheduleRule {
            val copy = JSONObject(value.toString())
            require(copy.keys().asSequence().all { it in setOf("kind", "timezone", "at_ms", "every_ms", "anchor_ms", "expression") }) { "未知的定时规则字段" }
            return ScheduleRule(copy).apply { validate() }
        }
        private fun number(value: JSONObject, key: String, low: Long, high: Long): Long {
            val raw = value.opt(key)
            require(raw is Long || raw is Int) { "时间必须为整数：$key" }
            val result = (raw as Number).toLong()
            require(result in low..high) { "时间超出范围：$key" }
            return result
        }
        private fun field(text: String, low: Int, high: Int): Set<Int> {
            val values = sortedSetOf<Int>()
            for (item in text.split(',')) {
                val match = Regex("(\\*|\\d+(?:-\\d+)?)(?:/(\\d+))?").matchEntire(item)
                require(match != null) { "Cron 只支持数字、列表、范围和步长" }
                val base = match.groupValues[1]; val stride = match.groupValues[2]
                val step = if (stride.isEmpty()) 1 else stride.toIntOrNull() ?: 0
                require(step in 1..(high - low + 1)) { "Cron 步长无效" }
                val ends = base.split('-')
                val first = if (base == "*") low else ends.first().toIntOrNull() ?: -1
                val last = when { base == "*" -> high; ends.size == 2 -> ends.last().toIntOrNull() ?: -1; stride.isNotEmpty() -> high; else -> first }
                require(first in low..high && last in first..high) { "Cron 数字范围无效" }
                values.addAll((first..last step step).toList())
            }
            return values
        }
    }
    val kind: String get() = data.getString("kind")
    private val zone: ZoneId get() = try { ZoneId.of(data.optString("timezone", "UTC")) } catch (_: Exception) { throw IllegalArgumentException("IANA 时区无效") }
    fun json() = JSONObject(data.toString()).put("timezone", zone.id)
    private fun validate() {
        require(data.opt("kind") is String && kind in setOf("once", "interval", "cron")) { "规则应为 once、interval 或 cron" }
        require(!data.has("timezone") || data.opt("timezone") is String) { "时区必须为文字" }; zone
        val expected = when (kind) {
            "once" -> { number(data, "at_ms", 1, MAX_MS); setOf("kind", "timezone", "at_ms") }
            "interval" -> { number(data, "every_ms", 60000, 31536000000); number(data, "anchor_ms", 0, MAX_MS); setOf("kind", "timezone", "every_ms", "anchor_ms") }
            else -> { cron(); setOf("kind", "timezone", "expression") }
        }
        require(data.keys().asSequence().all { it in expected }) { "规则包含不适用的字段" }
    }
    private fun cron(): Pair<List<String>, List<Set<Int>>> {
        require(data.opt("expression") is String) { "缺少 Cron 表达式" }
        val expression = data.getString("expression")
        require(expression.length <= 120) { "Cron 表达式过长" }
        val parts = expression.trim().split(Regex("\\s+"))
        require(parts.size == 5) { "Cron 需要五段：分钟 小时 日 月 星期" }
        return parts to listOf(field(parts[0], 0, 59), field(parts[1], 0, 23), field(parts[2], 1, 31), field(parts[3], 1, 12), field(parts[4], 0, 7).map { it % 7 }.toSet())
    }
    fun nextAfter(afterMs: Long): Long? {
        require(afterMs in 0 until MAX_MS) { "参考时间无效" }
        if (kind == "once") return data.getLong("at_ms").takeIf { it > afterMs }
        if (kind == "interval") {
            val every = data.getLong("every_ms"); val anchor = data.getLong("anchor_ms")
            return (if (afterMs < anchor) anchor else anchor + ((afterMs - anchor) / every + 1) * every).takeIf { it < MAX_MS }
        }
        val (parts, fields) = cron(); val zone = zone
        val date = Instant.ofEpochMilli(afterMs).atZone(zone).toLocalDate()
        for (offset in 0L..(366L * 8)) {
            val day = date.plusDays(offset)
            if (day.monthValue !in fields[3]) continue
            val dom = day.dayOfMonth in fields[2]; val dow = day.dayOfWeek.value % 7 in fields[4]
            if (!(if (parts[2] != "*" && parts[4] != "*") dom || dow else dom && dow)) continue
            for (hour in fields[1].sorted()) for (minute in fields[0].sorted()) {
                val local = LocalDateTime.of(day.year, day.monthValue, day.dayOfMonth, hour, minute)
                val offsets = zone.rules.getValidOffsets(local)
                if (offsets.isEmpty()) continue // No invented spring-gap time.
                val stamp = local.toInstant(offsets.first()).toEpochMilli() // First overlap only.
                if (stamp > afterMs) return stamp
            }
        }
        throw IllegalArgumentException("此 Cron 在未来八年内没有执行时间")
    }
}

package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

/** Shared interpretation of usage responses; rolling charts are never treated as lifetime counters. */
object UsageBalance {
    fun daily(response: JSONObject): JSONArray = response.optJSONArray("daily") ?: JSONArray().apply {
        response.optJSONArray("days")?.let { dates -> repeat(dates.length()) { index ->
            put(JSONObject().put("date", dates.optString(index)).apply {
                for (key in listOf("input_tokens", "output_tokens", "requests", "screenshots"))
                    put(key, response.optJSONArray(key)?.optLong(index)?.coerceAtLeast(0) ?: 0)
            })
        } }
    }
    fun total(response: JSONObject, key: String): Long = (response.opt(key) as? Number)?.toLong()?.coerceAtLeast(0)
        ?: daily(response).let { rows -> (0 until rows.length()).sumOf { rows.getJSONObject(it).optLong(key).coerceAtLeast(0) } }

    fun snapshot(response: JSONObject): JSONObject = JSONObject().put("source", response.optString("source"))
        .put("observed_day", java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())).apply {
        if (response.opt("lifetime_input_tokens") is Number && response.opt("lifetime_output_tokens") is Number) {
            put("kind", "lifetime").put("input", response.getLong("lifetime_input_tokens")).put("output", response.getLong("lifetime_output_tokens"))
        } else {
            put("kind", "daily").put("days", daily(response))
        }
    }

    fun cost(previous: JSONObject?, current: JSONObject, inputPrice: Double, outputPrice: Double): Double {
        require(inputPrice.isFinite() && outputPrice.isFinite() && inputPrice >= 0 && outputPrice >= 0)
        if (previous == null || previous.optString("source") != current.optString("source") || previous.optString("kind") != current.optString("kind") || previous.optString("scope") != current.optString("scope")) return 0.0
        val counts = if (current.getString("kind") == "lifetime") {
            (current.getLong("input") - previous.getLong("input")).coerceAtLeast(0) to
                (current.getLong("output") - previous.getLong("output")).coerceAtLeast(0)
        } else {
            val oldRows = previous.getJSONArray("days"); val newRows = current.getJSONArray("days")
            val old = (0 until oldRows.length()).map { oldRows.getJSONObject(it) }.associateBy { it.getString("date") }
            val lastDate = old.keys.maxOrNull()
            fun delta(key: String) = (0 until newRows.length()).sumOf { index ->
                val row = newRows.getJSONObject(index); val date = row.getString("date"); val before = old[date]
                if (before != null || lastDate != null && date > lastDate || lastDate == null && date >= previous.getString("observed_day"))
                    (row.optLong(key) - (before?.optLong(key) ?: 0)).coerceAtLeast(0) else 0L
            }
            delta("input_tokens") to delta("output_tokens")
        }
        return counts.first / 1_000_000.0 * inputPrice + counts.second / 1_000_000.0 * outputPrice
    }

    fun advance(previous: JSONObject?, current: JSONObject): JSONObject {
        if (previous != null && previous.optString("kind") == "lifetime" && current.optString("kind") == "lifetime" &&
            previous.optString("source") == current.optString("source") && previous.optString("scope") == current.optString("scope")) {
            // A delayed older page response must not roll a committed lifetime baseline backwards.
            for (key in listOf("input", "output")) current.put(key, maxOf(previous.getLong(key), current.getLong(key)))
        }
        return current
    }
}

package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

/** Single I/O owner. Gateway cursors advance; direct-mode snapshots replace the recent window. */
internal class TaskEventFeed {
    private var currentRun = ""
    private var cursor = 0L
    private var sequenced = false
    private var recent = emptyList<JSONObject>()

    fun refresh(runId: String, fetch: (Long) -> JSONArray?): JSONArray {
        if (currentRun != runId) { currentRun = runId; cursor = 0; sequenced = false; recent = emptyList() }
        repeat(4) {
            val page = runCatching { fetch(cursor) }.getOrNull() ?: return JSONArray(recent)
            val rows = (0 until page.length()).mapNotNull { page.optJSONObject(it) }
            if (rows.any { it.has("sequence") }) sequenced = true
            if (!sequenced) { recent = rows.takeLast(80); return JSONArray(recent) }
            val additions = rows.filter { it.optLong("sequence") > cursor }
            if (additions.isEmpty()) return JSONArray(recent)
            recent = (recent + additions).takeLast(80)
            cursor = additions.maxOf { it.optLong("sequence") }
            if (rows.size < 500) return JSONArray(recent)
        }
        return JSONArray(recent)
    }
}

package dev.doppel.sdk

import org.json.JSONObject
import java.util.UUID

/** Compact semantic transitions shared by the local engine and observed gateway tasks. */
internal object TaskEventRecord {
    fun key(run: JSONObject): String {
        val category = category(run)
        return "${run.getString("status")}/$category/" + if (category in setOf("manual_takeover", "approval", "input"))
            run.optJSONObject("pending_request")?.optString("id").orEmpty() else ""
    }
    private fun category(run: JSONObject): String = when {
        run.optString("status") in setOf("paused", "awaiting_input") && run.optJSONObject("pending_request")?.optBoolean("manual_only") == true -> "manual_takeover"
        run.optString("status") == "awaiting_approval" -> "approval"
        run.optString("status") == "awaiting_input" -> "input"
        run.optString("status") == "paused" -> "paused"
        else -> ""
    }
    fun append(run: JSONObject, now: Long, emit: Boolean = true): Boolean {
        val key = key(run)
        if (run.optString("task_event_key") == key) return false
        run.put("task_event_key", key)
        if (!emit) return true
        val category = category(run)
        val item = JSONObject().put("event_id", UUID.randomUUID().toString()).put("task_id", run.getString("id"))
            .put("status", run.getString("status")).put("revision", run.optLong("revision"))
            .put("title", run.optString("title").take(120))
            .put("reason", (run.optJSONObject("pending_request")?.optString("message")?.takeIf { category.isNotEmpty() && it.isNotBlank() }
                ?: run.optString("message")).take(120))
            .put("pause_category", category).put("occurred_at_ms", now)
        SplitTaskState.append(run, "task_events", item, 50)
        return true
    }
}

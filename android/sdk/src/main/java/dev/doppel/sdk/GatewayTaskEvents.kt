package dev.doppel.sdk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Gateway responses already observed by the app; never fetches history, runs or models. */
internal object GatewayTaskEvents {
    private fun prefs(context: Context) = context.getSharedPreferences("doppel_gateway_task_events", Context.MODE_PRIVATE)
    fun observe(context: Context, scope: String, run: JSONObject) {
        val prefs = prefs(context)
        synchronized(prefs) {
            val previous = prefs.getString("history", null)
            GatewayTaskEventHistory(previous) { value ->
                if (!prefs.edit().putString("history", value).commit()) {
                    prefs.edit().putString("history", previous).commit()
                    error("任务消息尚未保存")
                }
            }.observe(scope, run)
        }
    }
    fun isTaskMutation(method: String, path: String): Boolean {
        val route = path.substringBefore('?').trim('/').split('/')
        return method == "POST" && route.firstOrNull() == "runs" &&
            (route.size == 1 || route.size == 3 && route[2] in setOf("start", "pause", "resume", "cancel", "answer"))
    }
    fun read(context: Context, scope: String): JSONArray = prefs(context).let { prefs -> synchronized(prefs) {
        GatewayTaskEventHistory(prefs.getString("history", null)) {}.events(scope)
    } }
}

internal class GatewayTaskEventHistory(persisted: String?, private val save: (String) -> Unit) {
    private var state = persisted?.let(::JSONObject) ?: JSONObject()
    fun events(scope: String): JSONArray = if (state.optString("scope") == scope)
        JSONArray(state.optJSONArray("events")?.toString() ?: "[]") else JSONArray()

    fun observe(scope: String, run: JSONObject, now: Long = System.currentTimeMillis()) {
        if (run.optString("id").isBlank() || run.optString("status") !in setOf("queued", "running", "paused", "awaiting_approval", "awaiting_input", "completed", "failed", "cancelled")) return
        val next = if (state.optString("scope") == scope) JSONObject(state.toString()) else JSONObject().put("scope", scope)
        val rows = next.optJSONArray("rows") ?: JSONArray().also { next.put("rows", it) }
        val existing = (0 until rows.length()).map(rows::getJSONObject).firstOrNull { it.optString("id") == run.getString("id") }
        val key = TaskEventRecord.key(run)
        if (existing?.optString("key") == key) return
        val eventRun = JSONObject(run.toString())
        eventRun.remove("task_event_key"); eventRun.remove("task_events")
        TaskEventRecord.append(eventRun, now)
        val events = next.optJSONArray("events") ?: JSONArray().also { next.put("events", it) }
        events.put(eventRun.getJSONArray("task_events").getJSONObject(0))
        while (events.length() > 50) events.remove(0)
        if (existing != null) existing.put("key", key) else rows.put(JSONObject().put("id", run.getString("id")).put("key", key))
        while (rows.length() > 50) rows.remove(0)
        save(next.toString())
        state = next
    }
}

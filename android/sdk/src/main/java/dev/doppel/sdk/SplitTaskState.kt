package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

/** Durable semantic memory, separated from ephemeral screenshots and model work tickets. */
internal object SplitTaskState {
    fun merge(run: JSONObject, update: JSONObject?) {
        if (update?.has("progress") == true && !update.isNull("progress")) {
            val progress = update.optJSONObject("progress") ?: throw IllegalArgumentException("Invalid task progress")
            TaskProgress.merge(run, progress)
        }
        TaskProgress.initial(run)
        val state=run.optJSONObject("task_state") ?: JSONObject().also { run.put("task_state",it) }
        state.put("goal",run.getString("goal"))
        if(update==null) return
        for(key in listOf("facts","completed_steps","failed_routes")) update.optJSONArray(key)?.let { input ->
            val old=state.optJSONArray(key) ?: JSONArray()
            val values=(0 until old.length()).map { old.optString(it) }.toMutableList()
            repeat(minOf(input.length(),30)) { val s=input.optString(it).trim().take(700);if(s.isNotBlank() && s !in values) values.add(s) }
            state.put(key,JSONArray(values.takeLast(60)))
        }
        update.optJSONArray("remaining_steps")?.let { input -> state.put("remaining_steps",JSONArray((0 until minOf(30,input.length())).map { input.optString(it).take(700) })) }
        (update.opt("phase") as? String)?.let { state.put("phase",it.take(500)) }
    }
    fun append(run: JSONObject, key: String, value: JSONObject, limit: Int = 16) {
        val items=run.optJSONArray(key) ?: JSONArray().also { run.put(key,it) }
        items.put(value);while(items.length()>limit) items.remove(0)
    }
}

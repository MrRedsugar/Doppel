package dev.doppel.sdk

import android.content.Context
import org.json.JSONObject

/** One bounded device-local stop receipt; never reused for another run or a resumed run. */
internal object PauseDetails {
    private const val KEY = "local_pause_detail"
    private fun prefs(context: Context) = context.getSharedPreferences("doppel", Context.MODE_PRIVATE)
    fun cached(context: Context, id: String): JSONObject? = runCatching {
        JSONObject(prefs(context).getString(KEY, "{}").orEmpty())
    }.getOrNull()?.takeIf { id.isNotBlank() && it.optString("id") == id && it.optString("status") == "paused" }
    fun remember(context: Context, run: JSONObject) {
        if (run.optString("status") != "paused" || run.optString("id").isBlank()) return
        prefs(context).edit().putString(KEY, receipt(run).toString()).apply()
    }
    internal fun receipt(run: JSONObject): JSONObject = JSONObject().put("id", run.getString("id")).put("status", "paused")
            .put("message", TaskPresentation.message(run.optString("message")).take(4000))
            .put("updated_at", run.optLong("updated_at")).apply {
                run.optJSONObject("pending_request")?.let { pending ->
                    put("pending_request", JSONObject().apply {
                        for (key in listOf("id", "kind", "manual_only", "message", "reason", "package_name"))
                            if (pending.has(key)) put(key, pending.get(key))
                    })
                }
            }
    fun clear(context: Context, id: String? = null) {
        val prefs = prefs(context)
        val saved = runCatching { JSONObject(prefs.getString(KEY, "{}").orEmpty()) }.getOrDefault(JSONObject())
        if (id == null || saved.optString("id") == id) prefs.edit().remove(KEY).apply()
    }
    fun resolve(context: Context, run: JSONObject, locallyPaused: Boolean): JSONObject = merge(run,
        runCatching { JSONObject(prefs(context).getString(KEY, "{}").orEmpty()) }.getOrDefault(JSONObject()), locallyPaused)

    fun merge(run: JSONObject, saved: JSONObject, locallyPaused: Boolean): JSONObject {
        val status = run.optString("status")
        if (run.optString("id").isBlank() || saved.optString("id") != run.optString("id")) return run
        val localStop = status == "running" && locallyPaused
        val currentMessage = TaskPresentation.message(run.optString("message"))
        val replaceable = PausePresentation.generic(currentMessage) || PausePresentation.manual(currentMessage)
        if (!localStop && !(status == "paused" && replaceable)) return run
        val currentRequest = run.optJSONObject("pending_request")?.optString("id").orEmpty()
        val savedRequest = saved.optJSONObject("pending_request")?.optString("id").orEmpty()
        if (currentRequest.isNotBlank() && savedRequest.isNotBlank() && currentRequest != savedRequest) return run
        return JSONObject(run.toString()).put("status", "paused").put("message", saved.optString("message"))
            .put("updated_at", saved.optLong("updated_at")).apply {
                saved.optJSONObject("pending_request")?.let { savedPending ->
                    // Old local receipts contain only a reason; retain the runtime request identity.
                    val pending = JSONObject(run.optJSONObject("pending_request")?.toString() ?: "{}")
                    savedPending.keys().forEach { key -> pending.put(key, savedPending.get(key)) }
                    put("pending_request", pending)
                }
            }
    }
}

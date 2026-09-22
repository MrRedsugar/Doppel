package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

/** Last observed gateway run, bound to its connection; reads never poll or advance work. */
internal class WorkerCompanionState {
    private var sourceScope = ""
    private var observed: JSONObject? = null
    @Synchronized fun update(scope: String, run: JSONObject) {
        sourceScope = scope
        observed = JSONObject(run.toString())
    }
    @Synchronized fun scopeFor(runId: String): String? = sourceScope.takeIf { observed?.optString("id") == runId }
    @Synchronized fun read(scope: String, activeRun: String, alive: Boolean, locallyPaused: Boolean): JSONObject? {
        val run = observed ?: return null
        if (!alive || scope != sourceScope || activeRun.isBlank() || run.optString("id") != activeRun ||
            TaskPresentation.terminal(run.optString("status")) || !locallyPaused && run.optString("status") == "paused") return null
        val progress = TaskProgress.presentation(run)
        return JSONObject().apply {
            for (key in listOf("id", "title", "goal", "status", "mode", "source", "message"))
                if (run.has(key)) put(key, run.get(key))
            if (locallyPaused && run.optString("status") == "running") put("status", "paused")
            put("phase", if (locallyPaused || run.optString("status") != "running") "idle" else "unknown")
            put("current_step", progress.current)
            put("progress", JSONObject().put("plan", JSONArray(progress.plan)).put("completed", progress.completed).put("total_known", progress.known))
            put("pending_request", run.optJSONObject("pending_request")?.let { pending -> JSONObject().apply {
                for (key in listOf("id", "kind", "manual_only", "message")) if (pending.has(key)) put(key, pending.get(key))
            } } ?: JSONObject.NULL)
        }
    }
}

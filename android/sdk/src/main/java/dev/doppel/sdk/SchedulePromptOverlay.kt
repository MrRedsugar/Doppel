package dev.doppel.sdk

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/** A schedule occurrence shares the local countdown with control-triggered tasks. */
internal object SchedulePromptOverlay {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var key = ""
    fun key(job: JSONObject) = "schedule:${job.optString("id")}:${job.optLong("next_due_ms")}:" +
        "${job.optString("goal")}:${job.optString("mode")}:${job.optJSONArray("allowed_packages")}:${job.optString("binding")}"
    fun announced(job: JSONObject) = AutomaticTaskNotice.approved(key(job))
    fun generation(job: JSONObject) = AutomaticTaskNotice.generation(key(job))
    fun update(context: Context, job: JSONObject?) { handler.post {
        val desired = job?.takeIf { it.optString("waiting_reason") in setOf("user_active", "schedule_countdown") }?.let(::key).orEmpty()
        if (key != desired) {
            AutomaticTaskNotice.dismiss(key)
            key = ""
        }
        if (desired.isBlank() || AutomaticTaskNotice.generation(desired) != null) return@post
        val gateway = Gateway(context)
        val connection = AutomaticTaskNotice.connectionStamp(context)
        val previousActive = gateway.prefs.getString("active_run", "").orEmpty()
        val goal = job!!.getString("goal")
        val id = job.getString("id")
        if (AutomaticTaskNotice.show(context, desired, "定时任务到时间了", goal,
                valid = { gateway.prefs.getString("active_run", "").orEmpty() == previousActive &&
                    AutomaticTaskNotice.connectionStamp(context) == connection },
                onExecute = { ScheduleManager.get(context).tick {} },
                onSkip = { ScheduleManager.get(context).control(id, "skip") },
                onPostpone = { ScheduleManager.get(context).control(id, "postpone") })) key = desired
    } }
}

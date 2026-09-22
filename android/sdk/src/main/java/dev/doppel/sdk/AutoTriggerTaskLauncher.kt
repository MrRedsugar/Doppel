package dev.doppel.sdk

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A control occurrence admits one durable task. Device ownership is decided only by the FIFO. */
internal object AutoTriggerTaskLauncher {
    private val io = Executors.newSingleThreadExecutor { task -> Thread(task, "auto-trigger-task").apply { isDaemon = true } }
    private const val PREFS = "doppel_trigger_occurrences"

    /** A real disappearance re-arms this rule, including after process recreation. */
    fun absent(context: Context, ruleId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(prefs) { if (prefs.contains(ruleId)) check(prefs.edit().remove(ruleId).commit()) { "控件出现状态保存失败" } }
    }

    @JvmOverloads
    fun launch(context: Context, goal: String, onAdmission: (Boolean) -> Unit = {}, sourceLabel: String = "",
               sourceRuleId: String = "", sourceRuleVersion: String = "", immediate: Boolean = false): Boolean {
        val app = context.applicationContext
        val gateway = Gateway(app)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val connection = AutomaticTaskNotice.connectionStamp(app)
        val admissionSent = AtomicBoolean(false)
        fun admission(accepted: Boolean) { if (admissionSent.compareAndSet(false, true)) runCatching { onAdmission(accepted) } }
        if (goal.isBlank() || !FirstUseConsent.isAccepted(app) || !ReleaseIntegrity.isTrusted(app)) {
            admission(false); return false
        }
        if (sourceRuleId.isNotBlank() && AutoTriggerStore(app).list().none {
                it.id == sourceRuleId && it.enabled && it.action == "task" && it.taskGoal == goal && it.json().toString() == sourceRuleVersion
            }) { admission(false); return false }
        val occurrence = try {
            synchronized(prefs) {
                val old = if (sourceRuleId.isBlank()) null else prefs.getString(sourceRuleId, null)?.let(::JSONObject)
                if (old != null && old.optString("version") == sourceRuleVersion && old.optString("connection") == connection) old
                else {
                    val mode = listOf("ask", "assist", "full")[gateway.prefs.getInt("mode_index", 1).coerceIn(0, 2)]
                    val body = JSONObject().put("device_id", gateway.prefs.getString("device_id", ""))
                        .put("goal", goal).put("mode", mode).put("request_id", TaskSubmissionKey.create())
                        .put("source_metadata", JSONObject().put("rule_id", sourceRuleId).put("label", sourceLabel))
                    JSONObject().put("version", sourceRuleVersion).put("connection", connection).put("body", body).also {
                        if (sourceRuleId.isNotBlank()) check(prefs.edit().putString(sourceRuleId, it.toString()).commit()) { "控件触发记录保存失败" }
                    }
                }
            }
        } catch (_: Exception) { admission(false); return false }
        if (occurrence.optBoolean("accepted")) { admission(true); return true }
        io.execute {
            try {
                check(connection == AutomaticTaskNotice.connectionStamp(app) && gateway.isConnected()) { "任务连接已变化" }
                val body = occurrence.getJSONObject("body")
                check(body.optString("device_id").isNotBlank()) { "设备尚未连接" }
                val run = gateway.createAutomaticRun(body)
                check(run.optString("id").isNotBlank()) { "任务入队未返回编号" }
                synchronized(prefs) {
                    val current = if (sourceRuleId.isBlank()) null else prefs.getString(sourceRuleId, null)?.let(::JSONObject)
                    // A disappearance/new appearance may have replaced this claim while create was in flight.
                    if (current?.optJSONObject("body")?.optString("request_id") == body.getString("request_id")) {
                        current.put("accepted", true)
                        check(prefs.edit().putString(sourceRuleId, current.toString()).commit()) { "控件触发结果保存失败" }
                    }
                }
                admission(true)
                if (connection == AutomaticTaskNotice.connectionStamp(app)) runCatching { TaskControl.wakeQueue(app) }
            } catch (_: Exception) {
                // The persisted request_id is retried on the next scan, never as a second task.
                admission(false)
                android.util.Log.w("DoppelTrigger", "Task admission unconfirmed; retained the occurrence key")
            }
        }
        return true
    }
}

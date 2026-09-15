package dev.doppel.sdk

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A control match announces the task, or uses the explicitly enabled local unlock session. */
internal object AutoTriggerTaskLauncher {
    private val io = Executors.newSingleThreadExecutor { task -> Thread(task, "auto-trigger-task").apply { isDaemon = true } }
    private val inFlight = AtomicBoolean(false)

    @JvmOverloads
    fun launch(context: Context, goal: String, onAdmission: (Boolean) -> Unit = {}, sourceLabel: String = "",
               sourceRuleId: String = "", sourceRuleVersion: String = "", immediate: Boolean = false): Boolean {
        val admissionSent = AtomicBoolean(false)
        fun admission(accepted: Boolean): Boolean {
            if (admissionSent.compareAndSet(false, true)) runCatching { onAdmission(accepted) }
                .onFailure { android.util.Log.w("DoppelTrigger", "admission_callback_failed") }
            return accepted
        }
        var claimed = false
        try {
        val app = context.applicationContext
        val gateway = Gateway(app)
        val store = AutoTriggerStore(app)
        fun sourceUnchanged() = sourceRuleId.isBlank() || store.list().any {
            it.id == sourceRuleId && it.enabled && it.action == "task" && it.taskGoal == goal && it.json().toString() == sourceRuleVersion
        }
        if (goal.isBlank() || !sourceUnchanged()) return admission(false)
        val activeRun = gateway.prefs.getString("active_run", "").orEmpty()
        if (activeRun.isNotBlank()) return admission(!immediate &&
            AutomaticTaskConflict.offerTrigger(app, activeRun, goal, sourceLabel, sourceRuleId, sourceRuleVersion))
        val block = AutomaticTaskNotice.localBlockReason(app)
        if (AutomaticUnlockSession.active ||
            (block != null && !(block == "device_locked" && !immediate && AutomaticUnlockCredentials.isEnabled(app))) ||
            !gateway.prefs.getString("active_run", "").isNullOrBlank() || !inFlight.compareAndSet(false, true)) return admission(false)
        claimed = true
        val ticket = TaskControl.currentGeneration()
        val connection = AutomaticTaskNotice.connectionStamp(app)
        val device = gateway.prefs.getString("device_id", "").orEmpty()
        val modeIndex = gateway.prefs.getInt("mode_index", 1).coerceIn(0, 2)
        val key = "trigger:${java.util.UUID.randomUUID()}"
        val feedback = android.os.Handler(android.os.Looper.getMainLooper())
        fun failedToStart() {
            if (immediate) feedback.post { android.widget.Toast.makeText(app, "设备或连接状态已变化，自动任务未启动；请查看任务记录", android.widget.Toast.LENGTH_LONG).show() }
        }
        // This remains true while our session owns the gate and operates the real keyguard.
        fun contextUnchanged(): Boolean = TaskControl.isCurrent(ticket) &&
            sourceUnchanged() &&
            connection == AutomaticTaskNotice.connectionStamp(app) &&
            gateway.prefs.getString("active_run", "").isNullOrBlank() &&
            gateway.prefs.getInt("mode_index", 1).coerceIn(0, 2) == modeIndex
        fun available(ownGate: Boolean = false): Boolean = contextUnchanged() &&
            AutomaticTaskNotice.localBlockReason(app, ownGate) == null &&
            (!AutomaticUnlockSession.active || AutomaticUnlockSession.matches(key))
        fun create() {
            io.execute {
                var ownsGate = false
                var runId = ""
                var started = false
                try {
                    if (AutomaticUnlockSession.matches(key) && !AutomaticUnlockSession.approved(key)) return@execute
                    if (!available() || !TaskSubmissionGate.creating.compareAndSet(false, true)) return@execute
                    ownsGate = true
                    if (!available(true) || !gateway.isConnected()) return@execute
                    val runs = gateway.request("GET", "/runs").getJSONArray("items")
                    if ((0 until runs.length()).any {
                        val run = runs.getJSONObject(it)
                        run.optString("device_id") == device && run.optString("status") !in setOf("completed", "failed", "cancelled")
                    }) return@execute
                    if (!available(true)) return@execute
                    // An idle poller must not execute a new run before its local reference is saved.
                    DeviceWorkerService.instance?.suspendLocally()
                    if (!available(true)) return@execute
                    AutomaticUnlockSession.dispatching(key)
                    val run = gateway.createAutomaticRun(JSONObject().put("device_id", device).put("goal", goal)
                        .put("mode", listOf("ask", "assist", "full")[modeIndex]).put("source", "trigger"))
                    runId = run.getString("id"); check(runId.isNotBlank()) { "任务创建未返回编号" }
                    AutomaticUnlockSession.bindRun(key, runId)
                    if (connection != AutomaticTaskNotice.connectionStamp(app)) return@execute
                    synchronized(gateway.prefs) {
                        check(gateway.prefs.getString("active_run", "").isNullOrBlank()) { "已有任务正在执行" }
                        check(gateway.prefs.edit().putString("active_run", runId).commit()) { "任务状态保存失败" }
                    }
                    if (!TaskControl.isCurrent(ticket) || !sourceUnchanged() ||
                        gateway.prefs.getInt("mode_index", 1).coerceIn(0, 2) != modeIndex ||
                        AutomaticTaskNotice.localBlockReason(app, true) != null) {
                        gateway.request("POST", "/runs/$runId/pause")
                        return@execute
                    }
                    check(TaskControl.startWorker(app, ticket)) { "任务已被接管" }
                    started = true
                } catch (_: Exception) {
                    if (runId.isNotBlank() && connection == AutomaticTaskNotice.connectionStamp(app))
                        runCatching { gateway.request("POST", "/runs/$runId/pause") }
                    android.util.Log.w("DoppelTrigger", "Automatic task start was not confirmed; inspect task records")
                } finally {
                    // Release only a gate acquired by this launcher; never clear another submitter's gate.
                    if (ownsGate) TaskSubmissionGate.creating.set(false)
                    if (!started) AutomaticUnlockSession.dispatchFailed(key)
                    AutomaticTaskNotice.dismiss(key)
                    inFlight.set(false)
                    admission(started)
                    if (!started) failedToStart()
                }
            }
        }
        fun showNotice() {
            val shown = AutomaticTaskNotice.show(app, key, "控件触发了自动任务", goal,
                valid = { available() }, onExecute = { admission(true); create() },
                onSkip = { inFlight.set(false); admission(true) },
                onCancelled = { inFlight.set(false); admission(false) }, onShown = { admission(true) })
            if (!shown) { inFlight.set(false); admission(false) }
        }
        io.execute {
            try {
                val localBlock = AutomaticTaskNotice.localBlockReason(app)
                if (device.isBlank() || !contextUnchanged() || AutomaticUnlockSession.active ||
                    (localBlock != null && !(localBlock == "device_locked" && !immediate && AutomaticUnlockCredentials.isEnabled(app))) || !gateway.isConnected() ||
                    (gateway.isDirectMode() && DirectRuntime.get(app).hasUnfinishedRun())) {
                    inFlight.set(false); admission(false); failedToStart(); return@execute
                }
                if (immediate) { create(); return@execute }
                if (AutomaticUnlockSession.locked(app)) {
                    // Check remote task ownership before unlocking; a blank local pointer is not proof of idleness.
                    val runs = gateway.request("GET", "/runs").getJSONArray("items")
                    if (!contextUnchanged() || (0 until runs.length()).any {
                        val run = runs.getJSONObject(it)
                        run.optString("device_id") == device && run.optString("status") !in setOf("completed", "failed", "cancelled")
                    }) { inFlight.set(false); admission(false); return@execute }
                    if (AutomaticUnlockSession.locked(app)) {
                        val prepared = AutomaticUnlockSession.prepare(app, key, valid = ::contextUnchanged, ready = ::create,
                            onAlreadyUnlocked = {
                                // Queue behind this setup so releasing the unlock claim cannot clear the notice's claim.
                                io.execute {
                                    try { if (available() && inFlight.compareAndSet(false, true)) showNotice() else admission(false) }
                                    catch (_: Exception) { inFlight.set(false); admission(false) }
                                }
                            })
                        if (!prepared && !AutomaticUnlockSession.locked(app) && available()) { showNotice(); return@execute }
                        // The session now excludes other triggers, including when unlock fails before ready().
                        inFlight.set(false)
                        admission(prepared)
                        return@execute
                    }
                }
                showNotice()
            } catch (_: Exception) { inFlight.set(false); admission(false); failedToStart() }
        }
        return true
        } catch (_: Exception) {
            if (claimed) inFlight.set(false)
            return admission(false)
        }
    }
}

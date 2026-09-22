package dev.doppel.sdk

import android.content.Context
import org.json.JSONObject

/** Screen-side preparation for the single automatic FIFO head; admission itself never touches the device. */
internal object AutomaticQueuedTaskAdmission {
    private class Pending(val context: Context, val run: JSONObject, val valid: () -> Boolean,
                          val onReady: () -> Unit, val onSkip: () -> Unit, val onBlocked: (String) -> Unit) {
        val id = run.getString("id")
        val key = "queued:$id"
        val generation = TaskControl.currentGeneration()
        val connection = AutomaticTaskNotice.connectionStamp(context)
        @Volatile var stage = "preparing"
    }
    @Volatile private var pending: Pending? = null

    /** True means this head owns an in-progress preparation, not that it has started execution. */
    @Synchronized
    fun prepare(context: Context, run: JSONObject, valid: () -> Boolean, onReady: () -> Unit,
                onSkip: () -> Unit, onBlocked: (String) -> Unit): Boolean {
        val existing = pending
        if (existing != null) {
            if (!live(existing)) complete(existing.id, false)
            else if (existing.id != run.optString("id")) return false
            else if (existing.stage == "unlocking" && !AutomaticUnlockSession.matches(existing.key)) {
                complete(existing.id, false); onBlocked("device_locked"); return false
            } else return true
        }
        if (run.optString("status") != "queued" || run.optString("source") !in setOf("schedule", "trigger") || !valid()) return false
        if (AutomaticUnlockSession.active) { onBlocked("device_busy"); return false }
        val block = AutomaticTaskNotice.localBlockReason(context)
        val mayUnlock = block == "device_locked" && AutomaticUnlockCredentials.isEnabled(context)
        if (block != null && !mayUnlock) { onBlocked(block); return false }
        val owner = Pending(context.applicationContext, JSONObject(run.toString()), valid, onReady, onSkip, onBlocked)
        pending = owner
        if (AutomaticUnlockSession.locked(context)) {
            owner.stage = "unlocking"
            val prepared = AutomaticUnlockSession.prepare(owner.context, owner.key, { live(owner) },
                ready = { ready(owner) }, onAlreadyUnlocked = { showNotice(owner) })
            if (!prepared) {
                if (!AutomaticUnlockSession.locked(context) && live(owner)) showNotice(owner)
                else blocked(owner, "device_locked")
            }
        } else showNotice(owner)
        return pending === owner
    }

    private fun live(owner: Pending): Boolean = pending === owner && TaskControl.isCurrent(owner.generation) &&
        owner.connection == AutomaticTaskNotice.connectionStamp(owner.context) && runCatching(owner.valid).getOrDefault(false)

    private fun showNotice(owner: Pending) {
        if (!live(owner)) { blocked(owner, "device_busy"); return }
        owner.stage = "notice"
        val title = if (owner.run.optString("source") == "schedule") "定时任务即将执行" else "控件触发任务即将执行"
        if (!AutomaticTaskNotice.show(owner.context, owner.key, title, owner.run.optString("goal"), valid = { live(owner) },
                onExecute = { ready(owner) },
                onSkip = { if (live(owner)) { complete(owner.id, false); owner.onSkip() } },
                onCancelled = { blocked(owner, "device_busy") })) blocked(owner, "device_busy")
    }

    private fun ready(owner: Pending) {
        if (!live(owner) || AutomaticTaskNotice.localBlockReason(owner.context) != null) {
            blocked(owner, "device_busy"); return
        }
        owner.stage = "ready"
        // The task already exists, so its protected session can be bound before /start.
        AutomaticUnlockSession.dispatching(owner.key)
        AutomaticUnlockSession.bindRun(owner.key, owner.id)
        try { owner.onReady() } catch (_: Exception) { blocked(owner, "queue_start_failed") }
    }

    private fun blocked(owner: Pending, reason: String) {
        if (pending !== owner) return
        complete(owner.id, false)
        owner.onBlocked(reason)
    }

    /** Caller invokes after /start and active ownership publication, including every failure path. */
    @Synchronized fun complete(runId: String, started: Boolean) {
        val owner = pending?.takeIf { it.id == runId } ?: return
        pending = null
        AutomaticTaskNotice.dismiss(owner.key)
        if (!started) AutomaticUnlockSession.dispatchFailed(owner.key)
    }
}

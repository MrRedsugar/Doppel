package dev.doppel.sdk

import android.app.Activity
import android.widget.Toast

/** Starting a new goal never silently becomes an answer to the existing task. */
internal object NewTaskEntry {
    fun open(activity: Activity, gateway: Gateway, draft: String, onKeep: () -> Unit = {}, onReady: () -> Unit) {
        if (TaskSubmissionGate.creating.get()) {
            Toast.makeText(activity, "正在创建任务，请稍候", Toast.LENGTH_SHORT).show(); return
        }
        val id = gateway.prefs.getString("active_run", "").orEmpty()
        if (id.isBlank()) { onReady(); return }
        if (draft.isNotBlank()) gateway.prefs.edit().putString("draft_goal", draft).apply()
        val io = java.util.concurrent.Executors.newSingleThreadExecutor()
        io.execute {
            val state = runCatching { gateway.request("GET", "/runs/$id") }
            activity.runOnUiThread {
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                val current = gateway.prefs.getString("active_run", "").orEmpty()
                if (current.isBlank()) { onReady(); return@runOnUiThread }
                if (current != id) {
                    Toast.makeText(activity, "已有另一项执行，输入内容已保留", Toast.LENGTH_LONG).show(); return@runOnUiThread
                }
                if (state.isFailure) {
                    Toast.makeText(activity, "暂时无法确认当前执行状态，输入内容已保留", Toast.LENGTH_LONG).show()
                } else if (TaskPresentation.terminal(state.getOrThrow().optString("status"))) {
                    gateway.prefs.edit().remove("active_run").remove("voice_pending_worker_run").remove("voice_pending_worker_generation").apply()
                    onReady()
                } else chooseUnfinished(activity, gateway, id, draft, onKeep, onReady)
            }
            io.shutdown()
        }
    }
    private fun chooseUnfinished(activity: Activity, gateway: Gateway, id: String, draft: String, onKeep: () -> Unit, onReady: () -> Unit) {
        UiDialog.Builder(activity).setTitle("开始一个新任务？")
            .setMessage("当前任务尚未结束。你可以保留它，或停止后开始新任务。${if (draft.isNotBlank()) "\n新任务内容已保存为草稿，不会作为补充发送。" else ""}")
            .setNegativeButton("保留当前任务") { _, _ -> onKeep() }
            .setPositiveButton("停止并新建") { _, _ ->
                if (gateway.prefs.getString("active_run", "") != id) {
                    Toast.makeText(activity, "当前任务已改变，请重新打开", Toast.LENGTH_LONG).show(); return@setPositiveButton
                }
                TaskControl.request(activity, id, "cancel") { run, error ->
                    if (run == null || !TaskPresentation.terminal(run.optString("status"))) {
                        if (!activity.isDestroyed) Toast.makeText(activity, error ?: "停止尚未确认，请查看当前任务", Toast.LENGTH_LONG).show()
                        return@request
                    }
                    val mayOpen = synchronized(gateway.prefs) {
                        val active = gateway.prefs.getString("active_run", "").orEmpty()
                        if (active.isNotBlank() && active != id) false else {
                            val edit = gateway.prefs.edit().remove("active_run").remove("companion_draft")
                            if (gateway.prefs.getString("voice_pending_worker_run", "") == id) edit.remove("voice_pending_worker_run").remove("voice_pending_worker_generation")
                            edit.commit()
                        }
                    }
                    if (mayOpen && !activity.isFinishing && !activity.isDestroyed) onReady()
                }
            }.show()
    }
}

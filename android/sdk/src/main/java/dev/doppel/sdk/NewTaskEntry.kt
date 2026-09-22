package dev.doppel.sdk

import android.app.Activity
import android.widget.Toast

/** A new goal is queued independently; it never answers or replaces the current task. */
internal object NewTaskEntry {
    fun open(activity: Activity, gateway: Gateway, draft: String, onReady: () -> Unit) {
        if (TaskSubmissionGate.creating.get()) {
            Toast.makeText(activity, "正在创建任务，请稍候", Toast.LENGTH_SHORT).show(); return
        }
        if (draft.isNotBlank()) gateway.prefs.edit().putString("draft_goal", draft).apply()
        onReady()
    }
}

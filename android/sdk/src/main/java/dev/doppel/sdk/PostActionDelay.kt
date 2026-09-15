package dev.doppel.sdk

import org.json.JSONObject

/** Short, cancellable settling time starts after the device confirms the whole action. */
internal object PostActionDelay {
    fun apply(result: JSONObject, now: () -> Long, sleep: (Long) -> Unit, cancelled: () -> Boolean) {
        val data=result.optJSONObject("data") ?: return
        if(result.optString("status")!="ok" || data.optString("action_state")!="accepted") return
        val completedAt=now()
        data.put("action_completed_at_elapsed_ms",completedAt)
        while(now()-completedAt<500L && !cancelled()) {
            sleep(minOf(50L,500L-(now()-completedAt)).coerceAtLeast(1L))
        }
        data.put("post_action_delay_ms",(now()-completedAt).coerceAtLeast(0L))
        if(cancelled()) result.put("status","cancelled").put("message","动作已经执行；等待页面期间任务被中断，未继续截图或重放动作")
    }
}

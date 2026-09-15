package dev.doppel.sdk

import org.json.JSONObject

/** Fixed vocabulary and relative timings only: no view labels, packages, coordinates or user data. */
internal class TouchHandoffDiagnostic(private val now:()->Long,private val timeoutMs:Long=800) {
    private val startedAt=now()
    private val timings=linkedMapOf("queued" to 0L)
    private var stage="queued"
    private var outcome="pending"
    private var failureStage:String?=null
    private var finishedAt:Long?=null
    @Synchronized fun mark(value:String) {
        require(value in STAGES)
        stage=value;timings.putIfAbsent(value,(now()-startedAt).coerceIn(0,60000))
    }
    @Synchronized fun finish(value:String) {
        require(value in OUTCOMES)
        if(outcome!="pending") return
        outcome=value;finishedAt=(now()-startedAt).coerceIn(0,60000)
        if(value!="ready") failureStage=stage
    }
    @Synchronized fun snapshot()=JSONObject().put("outcome",outcome).put("stage",stage)
        .put("elapsed_ms",finishedAt ?: (now()-startedAt).coerceIn(0,60000))
        .put("timeout_ms",timeoutMs).put("ack_delay_ms",80).put("timings_ms",JSONObject(timings as Map<*,*>))
        .apply {failureStage?.let {put("failure_stage",it)}}
    private companion object {
        val STAGES=setOf("queued","main_entered","host_checked","ticket_acquired","layout_started","layout_applied",
            "ack_queued","ack_entered","window_traversed","window_absent","main_acknowledged","compositor_wait_started","compositor_wait_finished",
            "ready","release_queued","released")
        val OUTCOMES=setOf("ready","main_thread","stale_host","user_touch","voice_input","editor_visible",
            "ticket_busy","layout_failed","timeout","interrupted","ack_expired","queue_rejected")
    }
}

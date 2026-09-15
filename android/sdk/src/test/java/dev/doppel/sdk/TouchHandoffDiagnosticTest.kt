package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class TouchHandoffDiagnosticTest {
    @Test fun recordsQueueLayoutAndAcknowledgementSeparatelyWithoutPayloadData() {
        var now=1000L
        val d=TouchHandoffDiagnostic({now})
        now+=20;d.mark("main_entered");d.mark("host_checked");d.mark("ticket_acquired");d.mark("layout_started")
        now+=40;d.mark("layout_applied");d.mark("ack_queued")
        now+=90;d.mark("ack_entered");d.mark("ready");d.finish("ready")
        val result=d.snapshot();val times=result.getJSONObject("timings_ms")
        assertEquals(20L,times.getLong("main_entered"))
        assertEquals(40L,times.getLong("layout_applied")-times.getLong("layout_started"))
        assertEquals(90L,times.getLong("ack_entered")-times.getLong("ack_queued"))
        assertEquals(150L,result.getLong("elapsed_ms"));assertEquals("ready",result.getString("outcome"))
        assertFalse(result.has("failure_stage"));assertFalse(result.has("target"));assertFalse(result.has("coordinates"))
    }
    @Test fun lateCallbacksCannotOverwriteTheOriginalFailureOrElapsedTime() {
        var now=0L
        val d=TouchHandoffDiagnostic({now})
        now=800;d.finish("timeout")
        now=900;d.mark("main_entered");d.finish("stale_host")
        val result=d.snapshot()
        assertEquals("timeout",result.getString("outcome"));assertEquals("queued",result.getString("failure_stage"))
        assertEquals(800L,result.getLong("elapsed_ms"));assertEquals(900L,result.getJSONObject("timings_ms").getLong("main_entered"))
    }
    @Test fun repeatedReleaseKeepsFirstTimingAndCallerCannotInjectArbitraryDiagnosticContent() {
        var now=0L
        val d=TouchHandoffDiagnostic({now})
        now=100;d.mark("released");now=200;d.mark("released")
        assertEquals(100L,d.snapshot().getJSONObject("timings_ms").getLong("released"))
        assertThrows(IllegalArgumentException::class.java) {d.mark("private content")}
        assertThrows(IllegalArgumentException::class.java) {d.finish("private content")}
    }
    @Test fun feedbackCleanupKeepsItsOwnOneSecondBudgetInTheDiagnostic() {
        val d=TouchHandoffDiagnostic({0L},1000)
        assertEquals(1000L,d.snapshot().getLong("timeout_ms"))
        assertEquals(80L,d.snapshot().getLong("ack_delay_ms"))
    }
}

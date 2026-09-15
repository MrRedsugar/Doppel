package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PostActionDelayTest {
    private var clock=1000L
    private fun result(status:String="ok",state:String="accepted")=JSONObject().put("status",status)
        .put("data",JSONObject().put("action_state",state).put("completed_strokes",2))
    @Test fun completedActionWaitsBeforeResultBecomesAvailableForNextScreenshot() {
        val r=result()
        PostActionDelay.apply(r,{clock},{clock+=it},{false})
        assertEquals(1500L,clock)
        assertEquals(1000L,r.getJSONObject("data").getLong("action_completed_at_elapsed_ms"))
        assertEquals(500L,r.getJSONObject("data").getLong("post_action_delay_ms"))
        assertEquals("ok",r.getString("status"))
    }
    @Test fun cancellationDuringDelayPreservesExecutionReceiptWithoutReplay() {
        val r=result()
        PostActionDelay.apply(r,{clock},{clock+=it},{clock>=1150})
        assertEquals(1150L,clock);assertEquals("cancelled",r.getString("status"))
        assertEquals("accepted",r.getJSONObject("data").getString("action_state"))
        assertEquals(2,r.getJSONObject("data").getInt("completed_strokes"))
    }
    @Test fun staleFailedAndUnconfirmedActionsDoNotAddDelay() {
        for((status,state) in listOf("stale" to "not_dispatched","error" to "unconfirmed","cancelled" to "partial")) {
            val r=result(status,state)
            PostActionDelay.apply(r,{clock},{clock+=it},{false})
            assertEquals(1000L,clock);assertEquals(status,r.getString("status"))
        }
    }
    @Test fun interruptionOnLastWaitSliceStillCancelsBeforeScreenshot() {
        val r=result()
        PostActionDelay.apply(r,{clock},{clock+=it},{clock>=1500})
        assertEquals("cancelled",r.getString("status"))
    }
}

package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

internal data class PlannedStroke(val points:List<FeedbackPoint>,val durationMs:Long,val startHoldMs:Long=0) {
    val totalMs:Long get()=startHoldMs+durationMs
}
internal data class GestureSequencePlan(val kind:String,val strokes:List<PlannedStroke>,val intervalMs:Long) {
    val totalMs:Long get()=strokes.sumOf {it.totalMs}+intervalMs*(strokes.size-1).coerceAtLeast(0)
    companion object {
        fun from(input:JSONObject,width:Int,height:Int,random:Random=Random.Default):GestureSequencePlan {
            require(width>0 && height>0)
            val a=SplitAgentProtocol.grounding(input,input.getString("action"));require(a.getString("status")=="located")
            fun mapped(points:JSONArray)=(0 until points.length()).map {i ->
                val p=points.getJSONArray(i)
                FeedbackPoint((p.getDouble(0)*width/1000).toFloat().coerceIn(0f,(width-1).toFloat()),
                    (p.getDouble(1)*height/1000).toFloat().coerceIn(0f,(height-1).toFloat()))
            }
            val kind=a.getString("action")
            val strokes=when(kind) {
                "tap","double_tap","long_press" -> {
                    val points=mapped(a.getJSONArray("points"));val times=if(kind=="double_tap") 2 else 1
                    List(times){i ->
                        val requested=a.getLong("duration_ms")
                        // Vary only DOWN-to-UP time. Long presses and the gap between taps keep their semantics.
                        val duration=if(kind=="long_press") requested else random.nextLong(
                            (requested*7/10).coerceAtLeast(40L),(requested*13/10).coerceAtMost(180L)+1)
                        PlannedStroke(listOf(points[minOf(i,points.lastIndex)]),duration)
                    }
                }
                "swipe","swipe_sequence" -> {
                    val list=a.getJSONArray("strokes")
                    (0 until list.length()).map {i -> val s=list.getJSONObject(i);PlannedStroke(mapped(s.getJSONArray("points")),s.getLong("duration_ms"),s.optLong("start_hold_ms",0))}
                }
                else -> emptyList()
            }
            return GestureSequencePlan(kind,strokes,a.optLong("interval_ms",0))
        }
    }
}

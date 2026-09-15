package dev.doppel.sdk
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random
class GestureSequencePlanTest {
    @Test fun onlyTapContactDurationVariesWhileCoordinatesAndDoubleTapGapStayExact() {
        val random=Random(41)
        val durations=mutableSetOf<Long>()
        repeat(32) {
            val plan=GestureSequencePlan.from(JSONObject("""{"status":"located","action":"double_tap","points":[[250,500],[750,500]],"duration_ms":100,"interval_ms":120}"""),1080,1920,random)
            assertEquals(120L,plan.intervalMs)
            assertEquals(listOf(270f,810f),plan.strokes.map {it.points.single().x})
            plan.strokes.forEach { stroke -> assertTrue(stroke.durationMs in 70..130);durations.add(stroke.durationMs) }
        }
        assertTrue("Repeated taps must not keep one fixed contact duration",durations.size>1)
        for(duration in listOf(40,180)) repeat(10) {
            val plan=GestureSequencePlan.from(JSONObject("""{"status":"located","action":"tap","points":[[500,500]],"duration_ms":$duration}"""),1080,1920,random)
            assertTrue(plan.strokes.single().durationMs in 40..180)
        }
        val hold=GestureSequencePlan.from(JSONObject("""{"status":"located","action":"long_press","points":[[500,500]],"duration_ms":800}"""),1080,1920,random)
        assertEquals(800L,hold.strokes.single().durationMs)
    }
    @Test fun edgesStayInsidePhysicalScreenAndDoubleTapHasTwoStrokes() {
        val p=GestureSequencePlan.from(JSONObject("""{"status":"located","action":"double_tap","points":[[1000,0]],"interval_ms":100}"""),1440,3200)
        assertEquals(2,p.strokes.size);assertEquals(1439f,p.strokes[0].points[0].x,0f)
        assertEquals(p.strokes[0].points,p.strokes[1].points)
    }
    @Test fun separateSwipePathsStaySeparate() {
        val p=GestureSequencePlan.from(JSONObject("""{"status":"located","action":"swipe_sequence","strokes":[{"points":[[500,900],[500,200]],"duration_ms":300},{"points":[[300,800],[600,800]],"duration_ms":400}]}"""),1440,3200)
        assertEquals(2,p.strokes.size);assertEquals(300L,p.strokes[0].durationMs);assertEquals(400L,p.strokes[1].durationMs)
    }
}

package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class FeedbackMotionTest {
    @Test fun perimeterStripsCoverEveryOuterEdgeAndNeverOverlapOrCoverTheCentre() {
        for((w,h,d) in listOf(Triple(3200,1440,2.5f),Triple(1440,3200,2.5f),Triple(1080,2400,3f))) {
            val regions=FeedbackMotion.edgeRegions(w,h,d)
            assertEquals(4,regions.size)
            fun covers(x:Int,y:Int)=regions.count {x>=it[0] && x<it[2] && y>=it[1] && y<it[3]}
            for(x in 0 until w) {assertEquals(1,covers(x,0));assertEquals(1,covers(x,h-1))}
            for(y in 0 until h) {assertEquals(1,covers(0,y));assertEquals(1,covers(w-1,y))}
            assertEquals(0,covers(w/2,h/2))
            for(a in regions.indices) for(b in a+1 until regions.size) {
                val first=regions[a];val second=regions[b]
                assertFalse(minOf(first[2],second[2])>maxOf(first[0],second[0]) && minOf(first[3],second[3])>maxOf(first[1],second[1]))
            }
            assertTrue(regions.sumOf {(it[2]-it[0])*(it[3]-it[1])} < w*h/4)
            assertTrue(regions.first()[3] >= 22*d+FeedbackMotion.edgeWidth(d,minOf(w,h))*0.66f+4.6f*d)
        }
    }
    @Test fun highResolutionAndDensityHaveVisibleMinimums() {
        assertTrue(FeedbackMotion.edgeWidth(1f, 1440) >= 7f)
        assertTrue(FeedbackMotion.edgeWidth(3.5f, 1440) >= 17f)
        assertTrue(FeedbackMotion.radius(1f, 1440) > 30f)
    }
    @Test fun pressCompressesThenReleasesAndSwipeMovesContinuously() {
        assertTrue(FeedbackMotion.pressScale(120, false) < FeedbackMotion.pressScale(0, false))
        assertTrue(FeedbackMotion.pressScale(450, false) > FeedbackMotion.pressScale(120, false))
        assertEquals(0.7f, FeedbackMotion.pressScale(500, true), 0.001f)
        assertEquals(0f, FeedbackMotion.travel(0), 0f)
        assertEquals(1f, FeedbackMotion.travel(900), 0f)
        assertTrue(FeedbackMotion.travel(220) < FeedbackMotion.travel(440))
    }
}

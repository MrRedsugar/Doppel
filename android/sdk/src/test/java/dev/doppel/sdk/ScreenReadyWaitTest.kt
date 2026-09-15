package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class ScreenReadyWaitTest {
    @Test fun transientMissingRootOnlyRepeatsObservationUntilItExists() {
        var now = 0L; var reads = 0
        val wait = ScreenReadyWait({ now }, { now += it }, { false })
        val value = wait.read { reads++; if (reads < 3) throw ScreenNotReadyException(); "fresh screen" }
        assertEquals("fresh screen", value); assertEquals(3, reads); assertEquals(200L, now)
    }
    @Test fun unavailableWindowHasFiniteFiveSecondBudget() {
        var now = 0L; var reads = 0
        val wait = ScreenReadyWait({ now }, { now += it }, { false })
        assertThrows(ScreenNotReadyException::class.java) { wait.read { reads++; throw ScreenNotReadyException() } }
        assertEquals(51, reads); assertEquals(5000L, now)
    }
    @Test fun permissionAndProgrammingErrorsAreNeverRetried() {
        for (error in listOf(SecurityException(), IllegalArgumentException(), NullPointerException())) {
            var reads = 0; var sleeps = 0
            val wait = ScreenReadyWait({ 0L }, { sleeps++ }, { false })
            val actual = assertThrows(error.javaClass) { wait.read { reads++; throw error } }
            assertSame(error, actual); assertEquals(1, reads); assertEquals(0, sleeps)
        }
    }
    @Test fun pauseDuringWaitStopsBeforeNextObservation() {
        var now = 0L; var reads = 0
        val wait = ScreenReadyWait({ now }, { now += it }, { now >= 100 })
        assertThrows(ScreenReadInterruptedException::class.java) { wait.read { reads++; throw ScreenNotReadyException() } }
        assertEquals(1, reads); assertEquals(100L, now)
    }
    @Test fun pauseDuringObservationDiscardsItsResult() {
        var paused = false
        val wait = ScreenReadyWait({ 0L }, {}, { paused })
        assertThrows(ScreenReadInterruptedException::class.java) { wait.read { paused = true; "obsolete observation" } }
    }
    @Test fun aFaultyClockCannotCauseAnInfiniteObservationLoop() {
        var reads = 0
        val wait = ScreenReadyWait({ 0L }, {}, { false }, 200)
        assertThrows(ScreenNotReadyException::class.java) { wait.read { reads++; throw ScreenNotReadyException() } }
        assertEquals(3, reads)
    }
    @Test fun nestedReadsShareOneReadinessBudget() {
        var now = 0L; var firstReads = 0; var secondReads = 0
        val wait = ScreenReadyWait({ now }, { now += it }, { false }, 300)
        assertEquals("ready", wait.read { firstReads++; if (firstReads < 3) throw ScreenNotReadyException(); "ready" })
        assertThrows(ScreenNotReadyException::class.java) { wait.read { secondReads++; throw ScreenNotReadyException() } }
        assertEquals(300L, now); assertEquals(2, secondReads)
    }
    @Test fun blackFramesWaitFourHundredMillisecondsUntilVisiblePixelsArrive() {
        var now = 0L
        val reads = mutableListOf<Long>()
        val wait = ScreenReadyWait({ now }, { now += it }, { false })
        val visible = wait.read {
            reads.add(now)
            val pixels = if (reads.size < 3) intArrayOf(0xff000000.toInt()) else intArrayOf(0xff000001.toInt())
            if (!ScreenPixelContent.hasVisibleRgb(pixels)) throw ScreenNotReadyException(ScreenReadinessReason.EMPTY_FRAME)
            pixels
        }
        assertArrayEquals(intArrayOf(0xff000001.toInt()), visible)
        assertEquals(listOf(0L, 400L, 800L), reads)
    }
    @Test fun rootAndNestedBlackCaptureUseTheSameFiveSecondBudget() {
        var now = 0L; var rootReads = 0
        val frameReads = mutableListOf<Long>()
        val wait = ScreenReadyWait({ now }, { now += it }, { false })
        assertEquals("root ready", wait.read { rootReads++; if (rootReads < 3) throw ScreenNotReadyException(); "root ready" })
        val error = assertThrows(ScreenNotReadyException::class.java) {
            wait.read { frameReads.add(now); throw ScreenNotReadyException(ScreenReadinessReason.EMPTY_FRAME) }
        }
        assertEquals(ScreenReadinessReason.EMPTY_FRAME, error.reason)
        assertEquals(5000L, now); assertEquals(13, frameReads.size)
        assertEquals(200L, frameReads.first()); assertEquals(5000L, frameReads.last())
        assertTrue(frameReads.zipWithNext().all { (first, second) -> second - first == 400L })
    }
    @Test fun aPartialFinalBudgetNeverRequestsAnEarlyBlackFrameRetry() {
        var now = 0L
        val frameReads = mutableListOf<Long>()
        val wait = ScreenReadyWait({ now }, { now += it }, { false })
        assertThrows(ScreenNotReadyException::class.java) {
            wait.read { frameReads.add(now); throw ScreenNotReadyException(ScreenReadinessReason.EMPTY_FRAME) }
        }
        assertEquals(5000L, now); assertEquals(13, frameReads.size); assertEquals(4800L, frameReads.last())
        assertTrue(frameReads.zipWithNext().all { (first, second) -> second - first == 400L })
    }
    @Test fun aBlackFrameWaitStillHonorsPauseAfterOnlyOneHundredMilliseconds() {
        var now = 0L; var reads = 0
        val sleeps = mutableListOf<Long>()
        val wait = ScreenReadyWait({ now }, { sleeps.add(it); now += it }, { now >= 100 })
        assertThrows(ScreenReadInterruptedException::class.java) {
            wait.read { reads++; throw ScreenNotReadyException(ScreenReadinessReason.EMPTY_FRAME) }
        }
        assertEquals(1, reads); assertEquals(100L, now); assertEquals(listOf(100L), sleeps)
    }
    @Test fun pauseAlreadyRaisedByAFailedReadPreventsAnyAdditionalSleep() {
        for (reason in ScreenReadinessReason.entries) {
            var paused = false; var sleeps = 0
            val wait = ScreenReadyWait({ 0L }, { sleeps++ }, { paused })
            assertThrows(ScreenReadInterruptedException::class.java) {
                wait.read { paused = true; throw ScreenNotReadyException(reason) }
            }
            assertEquals(0, sleeps)
        }
    }
    @Test fun aFrozenClockCannotKeepRetryingEmptyFrames() {
        var reads = 0; var sleepMs = 0L
        val wait = ScreenReadyWait({ 0L }, { sleepMs += it }, { false }, 800)
        assertThrows(ScreenNotReadyException::class.java) {
            wait.read { reads++; throw ScreenNotReadyException(ScreenReadinessReason.EMPTY_FRAME) }
        }
        assertEquals(3, reads); assertEquals(800L, sleepMs)
    }
    @Test fun theLatestReadinessReasonControlsTheNextInterval() {
        var now = 0L; var reads = 0
        val wait = ScreenReadyWait({ now }, { now += it }, { false })
        assertEquals("ready", wait.read {
            reads++
            when (reads) {
                1 -> throw ScreenNotReadyException(ScreenReadinessReason.EMPTY_FRAME)
                2 -> throw ScreenNotReadyException()
                else -> "ready"
            }
        })
        assertEquals(3, reads); assertEquals(500L, now)
    }
}

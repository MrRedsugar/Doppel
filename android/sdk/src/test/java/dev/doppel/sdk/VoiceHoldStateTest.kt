package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class VoiceHoldStateTest {
    @Test fun releaseBeforeForegroundNeverStartsOrSubmits() {
        val state = VoiceHoldState()
        state.release()
        assertFalse(state.startRecording())
        state.finalText("too late")
        assertNull(state.takeResult())
    }

    @Test fun cancelledRecordingIgnoresLateFinalAndRelease() {
        val state = VoiceHoldState()
        assertTrue(state.startRecording())
        state.cancel()
        state.finalText("do not submit")
        state.release()
        assertNull(state.takeResult())
        assertEquals(VoiceHoldState.Phase.CANCELLED, state.phase)
    }

    @Test fun releaseThenFinalDeliversOnlyOnce() {
        val state = VoiceHoldState()
        state.startRecording()
        state.release()
        state.finalText("  open calendar  ")
        assertEquals("open calendar", state.takeResult())
        state.release()
        state.finalText("duplicate")
        assertNull(state.takeResult())
    }

    @Test fun finalBeforeReleaseWaitsForTheOriginalFinger() {
        val state = VoiceHoldState()
        state.startRecording()
        state.finalText("open calendar")
        assertNull(state.takeResult())
        state.release()
        assertEquals("open calendar", state.takeResult())
    }

    @Test fun cancellationAfterFinalDiscardsBufferedWords() {
        val state = VoiceHoldState()
        state.startRecording()
        state.finalText("do not submit")
        state.cancel()
        assertNull(state.takeResult())
    }

    @Test fun emptyFinalCannotProduceATask() {
        val state = VoiceHoldState()
        state.startRecording()
        state.release()
        state.finalText("   ")
        assertNull(state.takeResult())
        assertTrue(state.hasFinal)
    }

    @Test fun failureOrTeardownCannotRestartTheSameHold() {
        val state = VoiceHoldState()
        assertTrue(state.startRecording())
        assertFalse(state.startRecording())
        state.cancel()
        assertFalse(state.startRecording())
        state.finalText("late callback")
        assertNull(state.takeResult())
    }
}

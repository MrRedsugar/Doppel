package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class AssistantVisualStateTest {
    @Test fun localPauseWinsOverAnOldRunningResponse() {
        val state = AssistantVisualState.resolve("running", "\u6b63\u5728\u70b9\u51fb", true)
        assertEquals(AssistantVisualState.PAUSED, state)
        assertFalse(state.animated)
    }

    @Test fun disconnectedWorkerCannotKeepShowingExecution() {
        val state = AssistantVisualState.resolve("running", "\u8fde\u63a5\u4e2d\u65ad\uff0c\u7b49\u5f85\u91cd\u8fde", false)
        assertEquals(AssistantVisualState.OFFLINE, state)
        assertFalse(state.animated)
    }

    @Test fun approvalAndInputWaitsDoNotSuggestContinuedExecution() {
        for (status in listOf("awaiting_approval", "awaiting_input")) {
            val state = AssistantVisualState.resolve(status, "\u6b63\u5728\u6267\u884c", false)
            assertEquals(status, AssistantVisualState.WAITING, state)
            assertFalse(status, state.animated)
        }
    }

    @Test fun stoppedAndTerminalTasksDoNotAnimateEvenWhenWorkerIsBusy() {
        val cases = listOf(
            "paused" to AssistantVisualState.PAUSED,
            "cancelled" to AssistantVisualState.PAUSED,
            "completed" to AssistantVisualState.COMPLETE,
            "failed" to AssistantVisualState.FAILED,
        )
        for ((status, expected) in cases) {
            val state = AssistantVisualState.resolve(status, "\u6b63\u5728\u6267\u884c", false)
            assertEquals(status, expected, state)
            assertFalse(status, state.animated)
        }
    }

    @Test fun explicitLocalStopWinsOverARunningServerSnapshot() {
        assertEquals(AssistantVisualState.PAUSED,
            AssistantVisualState.resolve("running", "\u5df2\u505c\u6b62", false))
    }

    @Test fun reconnectionAndResumeRestoreMotionOnlyForActiveStates() {
        assertEquals(AssistantVisualState.RUNNING,
            AssistantVisualState.resolve("running", "\u7b49\u5f85\u4efb\u52a1", false))
        assertTrue(AssistantVisualState.resolve("running", "\u7b49\u5f85\u4efb\u52a1", false).animated)
        assertEquals(AssistantVisualState.QUEUED,
            AssistantVisualState.resolve("queued", "\u7b49\u5f85\u4efb\u52a1", false))
        assertEquals(AssistantVisualState.IDLE, AssistantVisualState.resolve("", "", true))
    }

    @Test fun modelDecisionPhaseHasItsOwnVisualState() {
        val state = AssistantVisualState.resolve("running", "正在思考", false)
        assertEquals(AssistantVisualState.THINKING, state)
        assertEquals("思考中", state.label)
        assertTrue(state.animated)
    }

    @Test fun terminalAndPausedStatesOverrideThinkingMessage() {
        assertEquals(AssistantVisualState.PAUSED,
            AssistantVisualState.resolve("paused", "正在思考", false))
        assertEquals(AssistantVisualState.COMPLETE,
            AssistantVisualState.resolve("completed", "正在思考", false))
    }
}

package dev.doppel.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class CheckableTapTest {
    @Test fun checkableControlsRequireExplicitIntent() {
        for (checked in listOf(false, true)) {
            assertEquals(CheckableTap.Decision.REQUIRE_STATE, CheckableTap.decide(true, checked, null))
        }
    }

    @Test fun repeatedDesiredStateNeverTogglesBack() {
        for (desired in listOf(false, true)) {
            assertEquals(CheckableTap.Decision.CLICK, CheckableTap.decide(true, !desired, desired))
            assertEquals(CheckableTap.Decision.ALREADY_SET, CheckableTap.decide(true, desired, desired))
        }
    }

    @Test fun ordinaryButtonsKeepTapSemanticsAndRejectInventedState() {
        assertEquals(CheckableTap.Decision.CLICK, CheckableTap.decide(false, false, null))
        assertEquals(CheckableTap.Decision.NOT_CHECKABLE, CheckableTap.decide(false, false, true))
        assertEquals(CheckableTap.Decision.NOT_CHECKABLE, CheckableTap.decide(false, false, false))
    }
}

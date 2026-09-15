package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class AutomaticScreenUnlockerTest {
    @Test fun matchesDigitIdentityWithoutFixedCoordinatesOrCountdownNumbers() {
        assertEquals('2', AutomaticScreenUnlocker.digitLabel("", "2 ABC", ""))
        assertEquals('0', AutomaticScreenUnlocker.digitLabel("0", "", ""))
        assertEquals('9', AutomaticScreenUnlocker.digitLabel("", "", "com.android.systemui:id/key9"))
        assertNull(AutomaticScreenUnlocker.digitLabel("12", "", ""))
        assertNull(AutomaticScreenUnlocker.digitLabel("请等待2秒", "", ""))
        assertNull(AutomaticScreenUnlocker.digitLabel("", "", "app:id/key12"))
    }
}

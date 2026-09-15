package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class InterruptionPolicyTest {
    @Test fun ordinaryTaskLabelsDoNotMasqueradeAsCallsOrRingingAlarms() {
        assertNull(InterruptionPolicy.reason("com.example.notes", listOf("接听电话", "闹钟")))
        assertNull(InterruptionPolicy.reason("com.android.deskclock", listOf("闹钟", "07:00", "已开启")))
    }
    @Test fun visibleCallAndRingingClockRequireUserChoice() {
        assertEquals("call", InterruptionPolicy.reason("com.android.incallui", listOf("接听", "拒接")))
        assertEquals("alarm", InterruptionPolicy.reason("com.android.deskclock", listOf("稍后提醒", "停止")))
        assertEquals("call", InterruptionPolicy.reason("com.android.systemui", listOf("来电", "接听", "拒接")))
        assertEquals("alarm", InterruptionPolicy.reason("com.android.systemui", listOf("闹钟", "稍后提醒")))
        assertNull(InterruptionPolicy.reason("com.android.systemui", listOf("闹钟已设定", "07:00")))
    }
}

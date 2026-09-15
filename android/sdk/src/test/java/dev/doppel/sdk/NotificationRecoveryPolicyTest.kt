package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class NotificationRecoveryPolicyTest {
    @Test fun `ordinary heads up with system dismissal can be recovered`() {
        assertTrue(NotificationRecoveryPolicy.canDismiss("com.android.systemui", "heads_up", listOf("测试消息"), 80, 480, 3200, true))
    }
    @Test fun `calls alarms security prompts and app dialogs never use notification dismissal`() {
        for (label in listOf("来电 接听 拒接", "闹钟 稍后提醒", "请输入验证码", "支付确认"))
            assertFalse(NotificationRecoveryPolicy.canDismiss("com.android.systemui", "heads_up", listOf(label), 80, 480, 3200, true))
        assertFalse(NotificationRecoveryPolicy.canDismiss("dev.other", "heads_up", listOf("消息"), 80, 480, 3200, true))
        assertFalse(NotificationRecoveryPolicy.canDismiss("com.android.systemui", "notification", listOf("消息"), 80, 3000, 3200, true))
        assertFalse(NotificationRecoveryPolicy.canDismiss("com.android.systemui", "heads_up", listOf("消息"), 80, 480, 3200, false))
    }
}

package dev.doppel.sdk

internal object InterruptionPolicy {
    fun reason(packageName: String, labels: Collection<String>): String? {
        val text = labels.joinToString(" ").take(24000).lowercase()
        val systemUi = packageName == "com.android.systemui"
        val callUi = packageName.contains("incall", true) || packageName.contains("dialer", true) || packageName.contains("telecom", true)
        if (callUi && listOf("接听", "挂断", "来电", "正在通话", "answer", "decline", "incoming call", "end call").any(text::contains)) return "call"
        if (systemUi && (text.contains("接听") && listOf("来电", "拒接", "挂断").any(text::contains) || text.contains("answer") && listOf("decline", "incoming call").any(text::contains))) return "call"
        val clockUi = packageName.contains("deskclock", true) || packageName.contains("alarm", true) || packageName.contains("clock", true)
        if (clockUi && listOf("稍后提醒", "再响", "停止响铃", "snooze", "dismiss alarm").any(text::contains)) return "alarm"
        if (systemUi && (text.contains("闹钟") && listOf("稍后提醒", "再响", "停止").any(text::contains) || text.contains("alarm") && text.contains("snooze"))) return "alarm"
        return null
    }
    fun message(reason: String) = when (reason) {
        "call" -> "电话需要你处理，任务已暂停"
        "alarm" -> "闹钟正在响铃，任务已暂停"
        else -> "屏幕出现中断，请处理后继续"
    }
}

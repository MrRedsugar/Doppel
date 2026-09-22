package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

/** Only translates presentation; the host's run state and event messages remain authoritative. */
internal object TaskPresentation {
    private val actions = mapOf("tap" to "点击", "long_press" to "长按", "scroll" to "滑动", "swipe" to "滑动",
        "type" to "输入文字", "type_text" to "输入文字", "set_text" to "输入文字", "launch" to "打开应用", "back" to "返回",
        "home" to "回到桌面", "wait" to "等待页面稳定", "observe" to "读取界面", "screenshot" to "读取画面", "set_checked" to "更新选项")

    fun terminal(status: String) = status in setOf("completed", "failed", "cancelled")

    fun withSourceLabel(run: JSONObject?, text: String): String =
        if (run?.optString("source") in setOf("schedule", "trigger"))
            if (text.isBlank()) "自动任务" else "自动任务 · $text"
        else text

    fun title(status: String, locallyPaused: Boolean = false): String = when {
        status == "running" && locallyPaused -> "已暂停"
        else -> when (status) {
            "running" -> "执行中"; "paused" -> "已暂停"; "queued" -> "排队中"
            "awaiting_approval" -> "需要你批准"; "awaiting_input" -> "需要你处理"
            "completed" -> "已完成"; "failed" -> "未完成"; "cancelled" -> "已停止"
            else -> "正在读取任务"
        }
    }

    fun message(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty() || value.startsWith("{") || value.startsWith("[") || value.startsWith("```")) return ""
        if (value.startsWith("正在执行：")) {
            val action = value.substringAfter("：").trim()
            return actions[action]?.let { "正在$it" } ?: "正在操作手机"
        }
        actions[value]?.let { return "正在$it" }
        val separator = value.indexOf('：')
        if (separator > 0) actions[value.substring(0, separator)]?.let { return "$it：${value.substring(separator + 1)}" }
        return value.take(12000)
    }

    fun detail(run: JSONObject, locallyPaused: Boolean): String {
        val state = run.optString("status")
        if (state == "queued") return queueLabel(run) + "，前面的任务结束后自动执行"
        PausePresentation.from(run)?.let { return it.detail }
        if (state == "running" && locallyPaused) return "本机执行已停止，但未收到具体原因。\n\n下一步：核对当前屏幕和任务记录后选择继续或结束任务。"
        return message(run.optString("message")).ifBlank {
            when (state) { "running" -> "正在处理任务"; "queued" -> "等待开始执行"; "paused" -> "任务已保留，点击继续执行"; else -> title(state) }
        }
    }

    fun queueLabel(run: JSONObject): String = run.optInt("queue_position").takeIf { it > 0 }
        ?.let { "排队第 $it 位" } ?: "排队中"

    fun process(events: JSONArray?, currentMessage: String = ""): List<String> {
        if (events == null) return emptyList()
        val current = message(currentMessage)
        val result = mutableListOf<String>()
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            if (event.optString("kind") == "created" || event.optString("kind") == "command") continue
            val text = message(event.optString("message"))
            if (text.isBlank() || text == current || text == "当前界面已更新" || text == result.lastOrNull()) continue
            result.add(text.take(240))
        }
        return result.takeLast(5)
    }
}

/** A completion announcement is timed once per run/outcome, independent of polling cadence. */
internal class CompanionNotice {
    private var terminalKey = ""
    private var firstShown = 0L
    fun collapsed(runId: String, status: String, now: Long): Boolean {
        if (!TaskPresentation.terminal(status)) { terminalKey = ""; return false }
        val key = "$runId:$status"
        if (key != terminalKey) { terminalKey = key; firstShown = now }
        return now - firstShown >= 4000
    }
}

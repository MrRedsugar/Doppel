package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

/** Coarse milestones are display/memory, never executable actions or a success signal. */
internal object TaskProgress {
    const val PROMPT = """
        首次正常决策时，在state.progress给出简短粗计划，不另发规划请求。progress={plan:[阶段目标],completed:已被当前画面证实完成的连续阶段数,total_known:是否已知完整阶段范围}。
        plan最多5项，每项最多60字，只写目标，例如“打开应用”“找到目标商品”“完成购买”；禁止把“点击我的”“进入详情页”等按钮、页面路径、坐标或手势写成计划。简单任务可只有1项。
        尚不能判断完整路线时total_known=false，可保留已知的部分阶段；不要猜百分比或用点击次数当进度。计划只是暂定目标，以当前画面为准；意外或路线变化时可替换计划、调整已完成数，不要为了旧计划重复无效操作。
        阶段有变化才更新progress，否则填null保留旧值。不要把预计执行的动作当作已完成；任务最终成功仍须验证实际结果后finish。completed_steps等操作记忆照常记录，与粗阶段计数独立。
    """

    data class Presentation(val plan: List<String>, val completed: Int, val known: Boolean,
        val current: String, val label: String, val running: Boolean)

    private fun state(run: JSONObject) = run.optJSONObject("task_state")
        ?: JSONObject().also { run.put("task_state", it) }

    fun initial(run: JSONObject) {
        val state = state(run)
        if (state.optJSONObject("progress") == null) state.put("progress",
            JSONObject().put("plan", JSONArray()).put("completed", 0).put("total_known", false))
    }

    /** Validate before replacing: malformed model/persisted data must not destroy the last plan. */
    fun merge(run: JSONObject, update: JSONObject?) {
        if (update == null) return
        val normalized = validated(update)
        state(run).put("progress", normalized)
    }

    private fun validated(value: JSONObject): JSONObject {
        require(value.keys().asSequence().toSet() == setOf("plan", "completed", "total_known")) { "Invalid progress fields" }
        val plan = value.optJSONArray("plan") ?: throw IllegalArgumentException("progress.plan must be an array")
        require(plan.length() <= 5) { "progress.plan has too many stages" }
        val stages = (0 until plan.length()).map { i ->
            val stage = (plan.opt(i) as? String)?.trim()
            require(!stage.isNullOrEmpty() && stage.length <= 60) { "Invalid progress stage" }
            stage
        }
        val count = value.opt("completed") as? Number ?: throw IllegalArgumentException("Invalid completed count")
        require(count.toDouble().isFinite() && count.toDouble() == count.toInt().toDouble() && count.toInt() in 0..stages.size) { "Invalid completed count" }
        val known = value.opt("total_known") as? Boolean ?: throw IllegalArgumentException("Invalid progress range")
        require(!known || stages.isNotEmpty()) { "Known progress requires stages" }
        return JSONObject().put("plan", JSONArray(stages)).put("completed", count.toInt()).put("total_known", known)
    }

    /** Called only after the engine's existing completion checks have succeeded. */
    fun complete(run: JSONObject) {
        initial(run)
        val progress = run.getJSONObject("task_state").getJSONObject("progress")
        val normalized = runCatching { validated(progress) }.getOrNull() ?: return
        val size = normalized.getJSONArray("plan").length()
        normalized.put("completed", size).put("total_known", size > 0)
        state(run).put("progress", normalized)
    }

    fun presentation(run: JSONObject?): Presentation {
        val saved = run?.optJSONObject("task_state")?.optJSONObject("progress")
        val progress = saved?.let { runCatching { validated(it) }.getOrNull() }
        val stages = progress?.getJSONArray("plan")?.let { p -> (0 until p.length()).map(p::getString) }.orEmpty()
        val status = run?.optString("status").orEmpty()
        val done = status == "completed"
        val running = status == "running"
        val known = stages.isNotEmpty() && (done || progress?.optBoolean("total_known") == true)
        val reported = progress?.optInt("completed") ?: 0
        // A model can claim every stage early. Keep the final stage pending until verified finish.
        val completed = if (done) stages.size else if (known) reported.coerceAtMost((stages.size - 1).coerceAtLeast(0)) else reported
        val current = when {
            done -> "任务已完成"
            status == "queued" -> "前面的任务结束后自动执行"
            stages.isEmpty() -> if (running) "正在确定任务阶段" else "暂无阶段计划"
            reported >= stages.size -> if (known) "正在确认任务结果" else "正在确定后续阶段"
            else -> stages[reported]
        }
        val label = when {
            done -> if (stages.isEmpty()) "任务已完成" else "任务已完成 · ${stages.size}/${stages.size} 阶段"
            status == "queued" -> TaskPresentation.queueLabel(requireNotNull(run))
            known -> "已完成 $completed/${stages.size} 阶段"
            else -> "进度待确定"
        }
        return Presentation(stages, completed, known, current, label, running)
    }
}

package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only assistant for the task review screen.
 *
 * This is intentionally separate from SplitTaskEngine: a review is a
 * diagnostic request and can never enqueue a screenshot, command, or task.
 * The model receives a compact, redacted evidence package and returns a
 * bounded semantic answer suitable for rendering in the app.
 */
internal class TaskReviewAssistant(
    private val model: ModelApi,
    private val reviews: TaskReviewMemory,
) {
    companion object {
        private const val MAX_QUESTION = 2000
        private const val MAX_EVENT_ROWS = 36
        private const val MAX_ERRORS = 12
        private const val MAX_FIELD = 1800
        private val secret = Regex(
            "(?i)(password|passwd|密码|验证码|verification\\s*code|otp|token|api[_ -]?key|secret)\\s*[:：=]?\\s*[^，,；;\\s]{1,120}"
        )

        private fun safe(value: String?, limit: Int = MAX_FIELD): String = value.orEmpty()
            .replace(secret, "[已隐藏]")
            .replace(Regex("(?i)(?:x|y|坐标|points?)\\s*[:：=]\\s*[-+]?\\d+(?:\\.\\d+)?"), "[位置已隐藏]")
            .replace(Regex("\\s+"), " ").trim().take(limit)

        private fun safeArray(value: JSONArray?, max: Int, limit: Int = 1000): JSONArray = JSONArray().apply {
            if (value == null) return@apply
            repeat(minOf(value.length(), max)) {
                val item = value.opt(it)
                if (item is String && item.isNotBlank()) put(safe(item, limit))
            }
        }

        internal fun compact(review: JSONObject): JSONObject = JSONObject().apply {
            put("run_id", safe(review.optString("run_id"), 120))
            put("goal", safe(review.optString("goal"), 1500))
            put("status", safe(review.optString("status"), 40))
            put("errors", JSONArray().also { out ->
                val source = review.optJSONArray("errors") ?: JSONArray()
                repeat(minOf(source.length(), MAX_ERRORS)) { index ->
                    val event = source.optJSONObject(index) ?: return@repeat
                    out.put(JSONObject().put("message", safe(event.optString("message"), 700))
                        .apply { event.optJSONObject("diagnostic")?.let { put("diagnostic", safe(it.toString(), 500)) } })
                }
            })
            put("timeline", JSONArray().also { out ->
                val source = review.optJSONArray("timeline") ?: JSONArray()
                val start = (source.length() - MAX_EVENT_ROWS).coerceAtLeast(0)
                for (index in start until source.length()) {
                    val event = source.optJSONObject(index) ?: continue
                    out.put(JSONObject().put("message", safe(event.optString("message"), 700))
                        .apply { if (event.has("created_at")) put("created_at", event.optLong("created_at"))
                            event.optJSONObject("diagnostic")?.let { put("diagnostic", safe(it.toString(), 500)) } })
                }
            })
            put("task_state", review.optJSONObject("task_state")?.let { safe(it.toString(), 5000) } ?: "{}")
            review.optJSONObject("metrics")?.let { put("metrics", safe(it.toString(), 600)) }
        }

        /** Shared chat uses the same redacted evidence, with a smaller per-message budget. */
        internal fun chatEvidence(review: JSONObject): JSONObject = compact(review).apply {
            remove("errors") // The timeline already includes these events.
            remove("metrics")
            val events = getJSONArray("timeline")
            while (events.length() > 12) events.remove(0)
            put("task_state", optString("task_state").take(1600))
            while (toString().length > 6000 && events.length() > 0) events.remove(0)
        }

        private fun parsed(content: String): JSONObject {
            val text = content.trim().let {
                if (it.startsWith("``")) it.substringAfter('\n', it).substringBeforeLast("```").trim() else it
            }
            return StrictModelJson.objectValue(text)
        }

        private fun answer(value: JSONObject): JSONObject = JSONObject().apply {
            // Keep a stable shape even when a provider omits an optional field.
            put("summary", safe(value.optString("summary"), 2200))
            put("failure_causes", safeArray(value.optJSONArray("failure_causes"), 8))
            put("key_steps", safeArray(value.optJSONArray("key_steps"), 12))
            put("improvements", safeArray(value.optJSONArray("improvements"), 10))
            put("confidence", safe(value.optString("confidence"), 40))
            value.optString("follow_up").takeIf { it.isNotBlank() }?.let { put("follow_up", safe(it, 1000)) }
        }
    }

    fun analyze(run: JSONObject, question: String?): JSONObject {
        val asked = question.orEmpty().trim()
        require(asked.length <= MAX_QUESTION) { "复盘问题过长" }
        val evidence = compact(reviews.review(run))
        val prompt = """
            你是 Doppel 的任务复盘助手，只能分析已经结束或暂停的手机任务记录。
            这是只读诊断：不要提出点击、滑动、输入、返回等可执行动作，不要创建或修改任务，不要声称你看到了未提供的截图。
            根据 evidence 判断失败原因、关键步骤和下次可采用的改进。保留不确定性，不把模型猜测写成事实。
            只输出一个严格 JSON 对象（不要 Markdown、不要额外文字），字段必须是：
            summary（字符串）、failure_causes（字符串数组）、key_steps（字符串数组）、
            improvements（字符串数组）、confidence（high/medium/low）、follow_up（可选字符串）。
            坐标、密码、验证码、Token、API Key 等敏感信息不要输出。
            用户追问：${safe(asked, MAX_QUESTION).ifBlank { "请概括这次任务的结果、原因和改进建议" }}
            evidence：$evidence
        """.trimIndent()
        val started = System.nanoTime()
        val response = model.complete(JSONObject().put("_doppel_role", "primary").put("stream", false)
            .put("max_completion_tokens", 1200)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", prompt))))
        val choice = response.optJSONArray("choices")?.optJSONObject(0)
            ?: throw IllegalStateException("复盘模型未返回结果")
        val content = choice.optJSONObject("message")?.optString("content").orEmpty()
        require(content.isNotBlank()) { "复盘模型返回内容为空" }
        val analysis = runCatching { answer(parsed(content)) }.getOrElse {
            // Some compatible providers ignore the JSON instruction. Preserve
            // the useful text in a bounded summary instead of making the
            // history screen unusable; it remains read-only and redacted.
            answer(JSONObject().put("summary", safe(content, 2200)).put("confidence", "low"))
        }
        // Keep both a structured object and a short answer for older clients
        // that render a single assistant bubble.
        val result = JSONObject(analysis.toString()).put("analysis", analysis)
            .put("answer", analysis.optString("summary"))
            .put("run_id", run.optString("id")).put("status", "ok")
            .put("elapsed_ms", (System.nanoTime() - started) / 1_000_000)
        response.optJSONObject("usage")?.let { usage ->
            result.put("usage", JSONObject().put("prompt_tokens", usage.optLong("prompt_tokens"))
                .put("completion_tokens", usage.optLong("completion_tokens")))
        }
        return result
    }
}

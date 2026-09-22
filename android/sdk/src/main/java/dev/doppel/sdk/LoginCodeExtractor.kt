package dev.doppel.sdk

/** Extract a numeric login code locally; unrelated digits are never sent to a model to decode. */
internal object LoginCodeExtractor {
    private const val label = "(?:验证码|校验码|动态码|动态密码|一次性密码|一次性口令|\\b(?:(?:verification|security|login|one[ -]?time)\\s+)?code\\b|\\bOTP\\b)"
    private const val digits = "(?<![A-Za-z0-9])([0-9]{4,8})(?![A-Za-z0-9])"
    private const val gap = "[\\s:：=,，\\[\\]【】()（）\"'“”‘’]*"
    private val afterLabel = Regex("$label$gap(?:(?:为|是|is)\\s*)?$gap$digits", RegexOption.IGNORE_CASE)
    private val beforeLabel = Regex("$digits$gap(?:(?<relation>is\\s+(?:your\\s+|the\\s+)?|为您(?:的)?|是您(?:的)?|为|是|您(?:的)?)(?:登录|login\\s+)?)?$label", RegexOption.IGNORE_CASE)
    private val alternatives = Regex("$digits$gap(?:或(?:者)?|or|and|/|、|,|，)$gap$digits", RegexOption.IGNORE_CASE)
    private val urls = Regex("(?i)(?:https?://|www\\.)[^\\s<>，。；]+")
    private val incidentalPrefix = Regex("(?i)(?:尾号|后\\s*[四4]\\s*位|ending\\s+(?:in\\s+)?|last\\s+(?:four|4)\\s+digits|[*•]{2,})\\s*[:：]?\\s*$")
    private val incidentalSuffix = Regex("(?i)^\\s*(?:年|月|日|分钟|秒|小时|天|years?\\b|minutes?\\b|seconds?\\b|hours?\\b|[-/]\\d{1,2}[-/]\\d{1,2}\\b)")

    fun extract(message: String): String? {
        if (message.length > 4096) return null
        val text = urls.replace(message, " ")
        fun incidental(match: MatchGroup): Boolean =
            incidentalPrefix.containsMatchIn(text.substring(0, match.range.first).takeLast(40)) ||
                incidentalSuffix.containsMatchIn(text.substring(match.range.last + 1).take(40))
        val after = afterLabel.findAll(text).toList()
        // "10086 验证码123456" has a numeric sender before the same label. Prefer its
        // labelled value; an explicit "123456 is your code" relation still counts.
        val before = beforeLabel.findAll(text).filterNot { candidate -> candidate.groups["relation"] == null &&
            after.any { it.range.first <= candidate.range.last && candidate.range.first <= it.range.last } }
        val explicit = (after.asSequence() + before).mapNotNull { it.groups[1] }
            .filterNot(::incidental).toList()
        val code = explicit.map { it.value }.toSet().singleOrNull() ?: return null
        // Sender IDs and customer-service numbers do not compete with a labelled code.
        // A list attached to that code still needs a human choice; never take its first number.
        if (alternatives.findAll(text).any { pair ->
            val first = pair.groups[1]!!; val second = pair.groups[2]!!
            first.value != second.value && !incidental(first) && !incidental(second) &&
                explicit.any { it.range == first.range || it.range == second.range }
        }) return null
        return code
    }
}

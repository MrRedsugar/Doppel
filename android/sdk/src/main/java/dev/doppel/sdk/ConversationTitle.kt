package dev.doppel.sdk

/**
 * Creates a short, stable title without an extra model request.  Keeping this
 * local makes title generation instant and keeps task token usage unchanged.
 */
internal object ConversationTitle {
    // Put the longer phrase first; Regex alternation otherwise consumes “请”
    // from “请帮我 …” and leaves “帮我” in the title.
    private val prefixes = Regex("^(请帮我|请|帮我|麻烦|可以|能否)\\s*")

    fun fromGoal(goal: String): String {
        val normalized = goal.replace(Regex("\\s+"), " ").trim().replace(prefixes, "")
        if (normalized.isBlank()) return "新任务"
        return normalized.take(36).let { if (normalized.length > 36) "$it…" else it }
    }
}

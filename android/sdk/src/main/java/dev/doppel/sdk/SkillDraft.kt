package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

/** A draft is advice grounded in supplied evidence, never a replayable coordinate program. */
object SkillDraft {
    private val coordinate = Regex("(?i)(坐标|coordinate|\\b[xy]\\s*[:=])|[（(]\\s*\\d+(?:\\.\\d+)?\\s*[,，]\\s*\\d+(?:\\.\\d+)?\\s*[)）]")
    private fun text(value: JSONObject, key: String, max: Int): String {
        val valueText = value.opt(key) as? String ?: throw IllegalArgumentException("草稿缺少$key")
        require(valueText.isNotBlank() && valueText.length <= max) { "草稿${key}长度无效" }
        return valueText.trim()
    }
    fun validate(value: JSONObject, evidenceIds: Set<String>): JSONObject {
        val result = JSONObject().put("title", text(value, "title", 120)).put("description", text(value, "description", 500)).put("verified", false)
        val steps = value.optJSONArray("steps") ?: throw IllegalArgumentException("草稿缺少操作说明")
        require(steps.length() in 1..24) { "草稿应包含 1 至 24 段操作说明" }
        result.put("steps", JSONArray().apply {
            repeat(steps.length()) { index ->
                val step = steps.getJSONObject(index)
                require(step.keys().asSequence().all { it in setOf("instruction", "expected", "evidence_ids") }) { "草稿含固定动作参数" }
                val instruction = text(step, "instruction", 2000); val expected = text(step, "expected", 1000)
                require(!coordinate.containsMatchIn(instruction) && !coordinate.containsMatchIn(expected)) { "请用界面目标描述步骤，不要保存固定坐标" }
                val ids = step.optJSONArray("evidence_ids") ?: throw IllegalArgumentException("步骤缺少来源")
                require(ids.length() in 1..30 && (0 until ids.length()).all { ids.opt(it) is String && ids.getString(it) in evidenceIds }) { "草稿引用了不存在的观察证据" }
                put(JSONObject().put("instruction", instruction).put("expected", expected).put("evidence_ids", JSONArray(ids.toString())))
            }
        })
        val limitations = value.optJSONArray("limitations") ?: throw IllegalArgumentException("草稿缺少观察限制")
        require(limitations.length() in 1..16 && (0 until limitations.length()).all { limitations.opt(it) is String && limitations.getString(it).isNotBlank() && limitations.getString(it).length <= 1000 })
        return result.put("limitations", JSONArray(limitations.toString()))
    }
    fun evidenceIds(evidence: JSONObject): Set<String> = evidence.optJSONArray("events")?.let { events ->
        (0 until events.length()).map { events.getJSONObject(it).getString("id") }.toSet()
    }.orEmpty()
    fun markdown(name: String, draft: JSONObject): String = buildString {
        append("---\nname: $name\ndescription: ${JSONObject.quote(draft.getString("description"))}\n---\n\n# ${draft.getString("title")}\n\n")
        append("用户复核过的操作建议；尚未验证为通用成功路线。每次根据当前截图重新定位，不继承示教中的坐标、账号或授权。\n\n## 操作与核验\n\n")
        val steps = draft.getJSONArray("steps")
        repeat(steps.length()) { i -> val step = steps.getJSONObject(i); append("${i + 1}. ${step.getString("instruction")}\n   预期：${step.getString("expected")}\n") }
        append("\n## 限制\n\n")
        val limits = draft.getJSONArray("limitations"); repeat(limits.length()) { append("- ${limits.getString(it)}\n") }
        append("\n## 来源\n\n按需读取 references/evidence.json 查看用户目标、观察事件和截图摘要。缺失操作不可推断为已完成。\n")
    }
}

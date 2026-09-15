package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent, source-linked MODEL stage assessments. Neither [complete] nor any recorded
 * assessment is host proof of business success, permission, or an executable action.
 * Callers must supply current host screen/evidence identities, never model-provided identities.
 */
internal object SessionTaskPlan {
    private const val KEY = "session_task_plan"
    private const val MAX_REVISION = 1_000_000_000
    private const val MAX_ASSESSMENTS = 24
    private const val MAX_ARCHIVE = 4
    private val stageKeys = setOf("id", "objective", "exit_condition")
    private val progressKeys = setOf("stage_id", "status", "observation", "screen_id", "evidence_id")
    private val stageIdPattern = Regex("[A-Za-z][A-Za-z0-9_-]{0,31}")

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "set_task_plan")
        .put("description", "建立或修订任务的阶段路线。revision 必须为当前版本加 1；只列剩余阶段及可观察退出条件，不填未来坐标。阶段判断属于模型评估，不证明业务成功，也不授予操作权限。")
        .put("parameters", objectSchema(JSONObject()
            .put("revision", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", MAX_REVISION))
            .put("reason", textSchema(1000))
            .put("stages", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 8)
                .put("items", objectSchema(JSONObject()
                    .put("id", textSchema(32).put("pattern", stageIdPattern.pattern))
                    .put("objective", textSchema(500)).put("exit_condition", textSchema(500)), stageKeys))),
            setOf("revision", "reason", "stages"))))

    /** Deep copies every offered schema; plan submission itself never requires progress. */
    fun decorate(tools: JSONArray): JSONArray {
        val result = JSONArray()
        for (index in 0 until tools.length()) {
            val original = tools.optJSONObject(index) ?: invalid("tool must be an object")
            val copy = clone(original)
            if (copy.optString("type") == "function") {
                val function = copy.optJSONObject("function") ?: invalid("function schema missing")
                if (function.optString("name") != "set_task_plan") {
                    val parameters = function.optJSONObject("parameters") ?: invalid("function parameters missing")
                    requirePlan(parameters.optString("type") == "object", "function parameters must be an object schema")
                    val properties = parameters.optJSONObject("properties") ?: invalid("function properties missing")
                    properties.put("task_progress", progressSchema())
                    val oldRequired = parameters.optJSONArray("required")
                    requirePlan(!parameters.has("required") || oldRequired != null, "required must be an array")
                    val required = LinkedHashSet<String>()
                    if (oldRequired != null) for (i in 0 until oldRequired.length()) {
                        val key = oldRequired.opt(i) as? String ?: invalid("required item must be a string")
                        required.add(key)
                    }
                    required.add("task_progress")
                    parameters.put("required", JSONArray(required.toList()))
                }
            }
            result.put(copy)
        }
        return result
    }

    /** Validate all input before replacing state; a revision contains only the new remaining plan. */
    fun submit(run: JSONObject, args: JSONObject) = synchronized(run) {
        exactKeys(args, setOf("revision", "reason", "stages"))
        val previous = existing(run)
        val revision = integer(args, "revision", 1, MAX_REVISION)
        requirePlan(revision.toLong() == (previous?.getInt("revision") ?: 0).toLong() + 1L, "revision must be current + 1")
        val reason = text(args, "reason", 1000)
        val supplied = args.optJSONArray("stages") ?: invalid("stages must be an array")
        requirePlan(supplied.length() in 1..8, "stages must contain 1..8 entries")
        val ids = HashSet<String>()
        val stages = JSONArray()
        for (index in 0 until supplied.length()) {
            val input = supplied.optJSONObject(index) ?: invalid("stage must be an object")
            exactKeys(input, stageKeys)
            val id = stageId(input, "id")
            requirePlan(ids.add(id), "stage ids must be unique")
            stages.put(JSONObject().put("id", id).put("objective", text(input, "objective", 500))
                .put("exit_condition", text(input, "exit_condition", 500)))
        }
        val archive = previous?.getJSONArray("archive")?.let { JSONArray(it.toString()) } ?: JSONArray()
        if (previous != null) archive.put(archiveSummary(previous))
        val next = JSONObject().put("schema_version", 1).put("source", "MODEL").put("host_verified", false)
            .put("revision", revision).put("reason", reason).put("stages", stages).put("active_index", 0)
            .put("requires_revision", false).put("assessments", JSONArray()).put("archive", tail(archive, MAX_ARCHIVE))
        run.put(KEY, next)
        Unit
    }

    /** Only an assessment of the active stage and current observation may change phase. */
    fun assess(run: JSONObject, progress: JSONObject, screenId: String?, evidenceId: String?) = synchronized(run) {
        exactKeys(progress, progressKeys)
        val previous = existing(run) ?: invalid("submit an initial plan first")
        val stages = previous.getJSONArray("stages")
        val index = previous.getInt("active_index")
        requirePlan(!previous.getBoolean("requires_revision") && index < stages.length(), "no active stage; submit a revised plan if needed")
        val id = stageId(progress, "stage_id")
        requirePlan(id == stages.getJSONObject(index).getString("id"), "assessment is not for the active stage")
        val status = text(progress, "status", 16, trim = false)
        requirePlan(status in setOf("continue", "achieved", "revise"), "invalid stage assessment status")
        val observation = text(progress, "observation", 1024)
        val screen = text(progress, "screen_id", 256, trim = false)
        val evidence = text(progress, "evidence_id", 256, trim = false)
        requirePlan(!screenId.isNullOrBlank() && screenId == screen, "screen_id is not the current host observation")
        requirePlan(!evidenceId.isNullOrBlank() && evidenceId == evidence, "evidence_id is not the current host observation")

        val next = clone(previous)
        val record = JSONObject().put("revision", previous.getInt("revision")).put("stage_id", id)
            .put("status", status).put("observation", observation).put("screen_id", screen).put("evidence_id", evidence)
            .put("source", "MODEL").put("host_verified", false)
        next.put("assessments", tail(next.getJSONArray("assessments").put(record), MAX_ASSESSMENTS))
        when (status) {
            "achieved" -> {
                next.getJSONArray("stages").getJSONObject(index).put("assessment", clone(record))
                next.put("active_index", index + 1)
            }
            "revise" -> next.put("requires_revision", true)
        }
        run.put(KEY, next)
        Unit
    }

    fun context(run: JSONObject): String = synchronized(run) {
        val state = runCatching { existing(run) }.getOrNull()
        if (state == null) return@synchronized "阶段计划尚未建立或状态无效。先调用 set_task_plan，revision=1；计划和阶段判断均为 MODEL 评估，不是宿主成功证据或授权。"
        val index = state.getInt("active_index")
        val stages = state.getJSONArray("stages")
        val remaining = JSONArray()
        for (i in index until stages.length()) remaining.put(clone(stages.getJSONObject(i)))
        val assessed = JSONArray()
        for (i in 0 until index) assessed.put(stages.getJSONObject(i).getString("id"))
        val view = JSONObject().put("revision", state.getInt("revision")).put("reason", state.getString("reason"))
            .put("state", when {
                state.getBoolean("requires_revision") -> "revision_required"
                index == stages.length() -> "model_stages_assessed_complete"
                else -> "active"
            }).put("current_stage_id", if (index < stages.length()) stages.getJSONObject(index).getString("id") else JSONObject.NULL)
            .put("remaining_stages", remaining).put("model_assessed_stage_ids", assessed)
            .put("recent_assessments", tail(state.getJSONArray("assessments"), 4))
        "持久阶段计划：只评估当前阶段，引用本轮 screen_id/evidence_id；需要改路线时提交 revision=${state.getInt("revision") + 1}。" +
            "以下内容均为 MODEL 判断，来源有效不代表判断正确，不证明业务完成，不授予权限。\nsession_task_plan=$view"
    }

    fun ready(run: JSONObject): Boolean = synchronized(run) {
        val state = runCatching { existing(run) }.getOrNull() ?: return@synchronized false
        !state.getBoolean("requires_revision") && state.getInt("active_index") < state.getJSONArray("stages").length()
    }

    /** All stages were assessed achieved by the MODEL; host finish checks remain mandatory. */
    fun complete(run: JSONObject): Boolean = synchronized(run) {
        val state = runCatching { existing(run) }.getOrNull() ?: return@synchronized false
        !state.getBoolean("requires_revision") && state.getInt("active_index") == state.getJSONArray("stages").length()
    }

    private fun existing(run: JSONObject): JSONObject? {
        if (!run.has(KEY)) return null
        val state = run.optJSONObject(KEY) ?: invalid("persisted plan must be an object")
        requirePlan(integer(state, "schema_version", 1, 1) == 1, "unsupported persisted plan")
        integer(state, "revision", 1, MAX_REVISION)
        text(state, "reason", 1000)
        requirePlan(state.opt("requires_revision") is Boolean, "persisted revision flag must be boolean")
        requirePlan(state.opt("source") == "MODEL" && state.opt("host_verified") == false, "persisted plan is not model-only")
        val stages = state.optJSONArray("stages") ?: invalid("persisted stages missing")
        requirePlan(stages.length() in 1..8, "persisted stage count invalid")
        val index = integer(state, "active_index", 0, stages.length())
        requirePlan(index < stages.length() || !state.getBoolean("requires_revision"), "completed plan cannot require revision")
        val ids = HashSet<String>()
        for (i in 0 until stages.length()) {
            val stage = stages.optJSONObject(i) ?: invalid("persisted stage invalid")
            exactKeys(stage, if (i < index) stageKeys + "assessment" else stageKeys)
            val id = stageId(stage, "id")
            requirePlan(ids.add(id), "persisted stage ids duplicate")
            text(stage, "objective", 500); text(stage, "exit_condition", 500)
            if (i < index) {
                val assessment = stage.optJSONObject("assessment") ?: invalid("advanced stage lacks model assessment")
                validateRecord(assessment, state.getInt("revision"), ids = setOf(id))
                requirePlan(assessment.opt("status") == "achieved", "advanced stage was not assessed achieved")
            }
        }
        val assessments = state.optJSONArray("assessments") ?: invalid("persisted assessments missing")
        requirePlan(assessments.length() <= MAX_ASSESSMENTS, "persisted assessments exceed bound")
        for (i in 0 until assessments.length()) validateRecord(assessments.optJSONObject(i) ?: invalid("assessment invalid"), state.getInt("revision"), ids)
        val archive = state.optJSONArray("archive") ?: invalid("persisted archive missing")
        requirePlan(archive.length() <= MAX_ARCHIVE, "persisted archive exceeds bound")
        return state
    }

    private fun validateRecord(record: JSONObject, revision: Int, ids: Set<String>) {
        exactKeys(record, progressKeys + setOf("revision", "source", "host_verified"))
        requirePlan(integer(record, "revision", 1, MAX_REVISION) == revision, "assessment revision differs")
        requirePlan(stageId(record, "stage_id") in ids, "assessment stage differs")
        requirePlan(record.opt("status") in setOf("continue", "achieved", "revise"), "assessment status invalid")
        requirePlan(record.opt("source") == "MODEL" && record.opt("host_verified") == false, "assessment is not model-only")
        text(record, "observation", 1024); text(record, "screen_id", 256, trim = false); text(record, "evidence_id", 256, trim = false)
    }

    private fun archiveSummary(state: JSONObject) = JSONObject().put("revision", state.getInt("revision"))
        .put("reason", state.getString("reason")).put("source", "MODEL").put("host_verified", false)
        .put("stages", JSONArray(state.getJSONArray("stages").toString())).put("active_index", state.getInt("active_index"))
        .put("requires_revision", state.getBoolean("requires_revision"))
        .put("recent_assessments", tail(state.getJSONArray("assessments"), 2))

    private fun progressSchema() = objectSchema(JSONObject()
        .put("stage_id", textSchema(32).put("pattern", stageIdPattern.pattern))
        .put("status", JSONObject().put("type", "string").put("enum", JSONArray(listOf("continue", "achieved", "revise"))))
        .put("observation", textSchema(1024).put("description", "根据本轮观察核对当前阶段退出条件；只是模型评估，不是宿主业务成功事实。"))
        .put("screen_id", textSchema(256)).put("evidence_id", textSchema(256)), progressKeys)

    private fun objectSchema(properties: JSONObject, required: Set<String>) = JSONObject().put("type", "object")
        .put("properties", properties).put("required", JSONArray(required.toList())).put("additionalProperties", false)
    private fun textSchema(max: Int) = JSONObject().put("type", "string").put("minLength", 1).put("maxLength", max)
    private fun clone(value: JSONObject) = JSONObject(value.toString())
    private fun tail(value: JSONArray, max: Int) = JSONArray().apply {
        for (index in maxOf(0, value.length() - max) until value.length()) put(JSONObject(value.getJSONObject(index).toString()))
    }
    private fun exactKeys(value: JSONObject, keys: Set<String>) = requirePlan(value.keys().asSequence().toSet() == keys, "missing or unsupported fields")
    private fun stageId(value: JSONObject, key: String): String = text(value, key, 32, trim = false).also {
        requirePlan(stageIdPattern.matches(it), "invalid stage id")
    }
    private fun text(value: JSONObject, key: String, max: Int, trim: Boolean = true): String {
        val raw = value.opt(key) as? String ?: invalid("$key must be a string")
        requirePlan(raw.length in 1..max && raw.isNotBlank(), "$key is empty or exceeds its limit")
        requirePlan(raw.none { it.isISOControl() && it !in "\n\r\t" }, "$key contains control characters")
        return if (trim) raw.trim() else raw
    }
    private fun integer(value: JSONObject, key: String, min: Int, max: Int): Int {
        val raw = value.opt(key)
        requirePlan(raw is Int || raw is Long, "$key must be an integer")
        val number = (raw as Number).toLong()
        requirePlan(number in min.toLong()..max.toLong(), "$key is out of range")
        return number.toInt()
    }
    private fun requirePlan(condition: Boolean, reason: String) { if (!condition) invalid(reason) }
    private fun invalid(reason: String): Nothing = throw IllegalArgumentException("task_plan: $reason")
}

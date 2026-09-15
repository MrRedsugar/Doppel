package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

/**
 * Host-owned route identity with optional, untrusted MODEL observations.
 * The engine must validate the request generation and current source before calling [assess].
 * Reaching every stage never verifies business success, authorizes an action, or finishes a run.
 */
internal object FeedbackOperator {
    private const val KEY = "feedback_operator"
    private const val MAX_REVISION = 1_000_000_000
    private const val MAX_COMPLETED = 256
    private const val MAX_ASSESSMENTS = 24
    private val stageInputKeys = setOf("objective", "exit_condition")
    private val stageKeys = stageInputKeys + "id"
    private val resultKeys = setOf("status", "observation")
    private val sourceKeys = setOf("capture_id", "screen_id", "evidence_id")
    private val observationKeys = sourceKeys + setOf("package_name", "sha256")
    private val recordKeys = resultKeys + setOf("revision", "stage_id", "assessment_kind", "host_verified", "source")
    private val statuses = setOf("ongoing", "reached", "deviated")
    private val actionTools = setOf("action", "navigate", "launch", "execute_plan", "execute_visual_plan",
        "locate_ui", "visual_action", "finish")

    fun ready(run: JSONObject): Boolean = synchronized(run) {
        val state = runCatching { existing(run) }.getOrNull() ?: return@synchronized false
        !state.getBoolean("requires_plan") && state.getInt("active_index") < state.getJSONArray("stages").length()
    }

    /** Only means the MODEL has observed all exit conditions; host completion checks still apply. */
    fun complete(run: JSONObject): Boolean = synchronized(run) {
        val state = runCatching { existing(run) }.getOrNull() ?: return@synchronized false
        !state.getBoolean("requires_plan") && state.getInt("active_index") == state.getJSONArray("stages").length()
    }

    fun needsPlan(run: JSONObject): Boolean = synchronized(run) {
        val state = runCatching { existing(run) }.getOrNull() ?: return@synchronized true
        state.getBoolean("requires_plan")
    }

    /** Validate the entire proposal before replacing only the remaining route. */
    fun submit(run: JSONObject, args: JSONObject): JSONObject = synchronized(run) {
        exactKeys(args, setOf("reason", "stages"))
        val previous = existing(run)
        val reason = text(args, "reason", 1000)
        val supplied = args.optJSONArray("stages") ?: invalid("stages must be an array")
        requireFeedback(supplied.length() in 1..8, "stages must contain 1..8 entries")
        val revision = (previous?.getInt("revision") ?: 0).toLong() + 1
        requireFeedback(revision <= MAX_REVISION, "revision limit reached")
        val completed = previous?.getJSONArray("completed")?.let { copy(it) } ?: JSONArray()
        // Reserve capacity for every new stage; reaching one must never silently evict old facts.
        requireFeedback(completed.length() + supplied.length() <= MAX_COMPLETED, "completed history capacity reached")
        val stages = JSONArray()
        for (i in 0 until supplied.length()) {
            val input = supplied.optJSONObject(i) ?: invalid("stage must be an object")
            exactKeys(input, stageInputKeys)
            stages.put(JSONObject().put("id", "r${revision}s${i + 1}")
                .put("objective", text(input, "objective", 500)).put("exit_condition", text(input, "exit_condition", 500)))
        }
        val next = JSONObject().put("schema_version", 1).put("assessment_kind", "model_observed").put("host_verified", false)
            .put("revision", revision.toInt()).put("reason", reason).put("stages", stages).put("active_index", 0)
            .put("requires_plan", false).put("completed", completed)
            .put("assessments", previous?.getJSONArray("assessments")?.let { copy(it) } ?: JSONArray())
        run.put(KEY, next)
        copy(next)
    }

    /**
     * Assess the stage active at response arrival, once. The accompanying action may target the
     * next stage after a reached report. Missing stage_result is deliberately a state-free no-op.
     */
    fun assess(run: JSONObject, args: JSONObject, source: JSONObject): JSONObject = synchronized(run) {
        if (!args.has("stage_result")) return@synchronized JSONObject()
        val result = args.optJSONObject("stage_result") ?: invalid("stage_result must be an object")
        exactKeys(result, resultKeys)
        val status = text(result, "status", 16, trim = false)
        requireFeedback(status in statuses, "invalid stage result status")
        val observation = text(result, "observation", 1024)
        val hostSource = sourceSnapshot(source)
        val previous = existing(run) ?: invalid("submit an initial plan first")
        val index = previous.getInt("active_index")
        val stages = previous.getJSONArray("stages")
        requireFeedback(!previous.getBoolean("requires_plan") && index < stages.length(), "no active stage; submit a remaining plan")
        val active = stages.getJSONObject(index)
        val record = JSONObject().put("revision", previous.getInt("revision")).put("stage_id", active.getString("id"))
            .put("status", status).put("observation", observation).put("source", hostSource)
            .put("assessment_kind", "model_observed").put("host_verified", false)
        val next = copy(previous)
        next.put("assessments", tail(next.getJSONArray("assessments").put(record), MAX_ASSESSMENTS))
        when (status) {
            "reached" -> {
                requireFeedback(next.getJSONArray("completed").length() < MAX_COMPLETED, "completed history capacity reached")
                next.getJSONArray("completed").put(copy(active).put("assessment", copy(record)))
                next.put("active_index", index + 1)
            }
            "deviated" -> next.put("requires_plan", true)
        }
        run.put(KEY, next)
        copy(next)
    }

    /**
     * The caller filters source to fields present in the original offered action schema. Model
     * copies of root IDs are removed even when that host field is absent. Preserve original args
     * separately for provider transcript replay; neither input is mutated here.
     */
    fun bindArguments(args: JSONObject, source: JSONObject): JSONObject {
        val bound = copy(args)
        bound.remove("stage_result")
        bound.remove("stage_status"); bound.remove("observed_result")
        for (key in sourceKeys) {
            bound.remove(key)
            if (source.has(key)) bound.put(key, text(source, key, 256, trim = false))
        }
        return bound
    }

    fun planTool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "plan_task")
        .put("description", "按当前观察规划或修订尚未完成的阶段，最多8段。已达到阶段由宿主保留；每段写目标与可观察退出条件，不写编号或未来坐标。计划不证明业务成功，也不授予权限。")
        .put("parameters", objectSchema(JSONObject().put("reason", textSchema(1000))
            .put("stages", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 8)
                .put("items", objectSchema(JSONObject().put("objective", textSchema(500))
                    .put("exit_condition", textSchema(500)), stageInputKeys))), setOf("reason", "stages"))))

    /** Only root source IDs are hidden; nested action schemas keep their own safety contracts. */
    fun decorate(tools: JSONArray): JSONArray {
        val result = JSONArray()
        for (i in 0 until tools.length()) {
            val tool = tools.optJSONObject(i)?.let { copy(it) } ?: invalid("tool must be an object")
            if (tool.optString("type") == "function") {
                val function = tool.optJSONObject("function") ?: invalid("function schema missing")
                val name = function.optString("name")
                if (name != "plan_task") {
                    val parameters = function.optJSONObject("parameters") ?: invalid("function parameters missing")
                    requireFeedback(parameters.optString("type") == "object", "function parameters must be an object schema")
                    val properties = parameters.optJSONObject("properties") ?: invalid("function properties missing")
                    sourceKeys.forEach { properties.remove(it) }
                    val oldRequired = parameters.optJSONArray("required")
                    requireFeedback(!parameters.has("required") || oldRequired != null, "required must be an array")
                    val required = LinkedHashSet<String>()
                    if (oldRequired != null) for (j in 0 until oldRequired.length()) {
                        val key = oldRequired.opt(j) as? String ?: invalid("required item must be a string")
                        if (key !in sourceKeys && key != "stage_result") required.add(key)
                    }
                    if (oldRequired != null) parameters.put("required", JSONArray(required.toList()))
                    if (name in actionTools || name.startsWith("propose_")) {
                        properties.remove("stage_result")
                        properties.put("stage_status", JSONObject().put("type", "string").put("enum", JSONArray(statuses.toList())))
                            .put("observed_result", textSchema(1024).put("description", "与stage_status一起选填：说明当前画面已经观察到的阶段结果，不能写计划执行的效果。"))
                    }
                }
            }
            result.put(tool)
        }
        return result
    }

    /** Flat actor contract avoids nested JSON strings; invalid types are rejected, never repaired. */
    fun assessmentArguments(args: JSONObject): JSONObject {
        requireFeedback(!args.has("stage_result"), "nested stage_result is not offered in this operator contract")
        if (!args.has("stage_status") && !args.has("observed_result")) return JSONObject()
        requireFeedback(args.has("stage_status") && args.has("observed_result"), "stage result fields must be supplied together")
        val status = text(args, "stage_status", 16, trim = false)
        requireFeedback(status in statuses, "invalid stage status")
        return JSONObject().put("stage_result", JSONObject().put("status", status).put("observation", text(args, "observed_result", 1024)))
    }

    fun context(run: JSONObject): String = synchronized(run) {
        val state = try { existing(run) } catch (_: IllegalArgumentException) {
            return@synchronized "阶段状态无效；不可丢弃已完成观察或据此执行动作，需恢复有效宿主状态。"
        } ?: return@synchronized "尚无阶段路线。先调用plan_task，只列尚未完成的阶段；不填写编号。阶段结果只是model_observed，不证明业务成功或授予权限。"
        val stages = state.getJSONArray("stages")
        val index = state.getInt("active_index")
        val remaining = JSONArray()
        for (i in index until stages.length()) remaining.put(stageView(stages.getJSONObject(i)))
        val completed = state.getJSONArray("completed")
        val recentCompleted = JSONArray()
        for (i in maxOf(0, completed.length() - 8) until completed.length()) {
            val fact = completed.getJSONObject(i)
            recentCompleted.put(stageView(fact).put("observation", fact.getJSONObject("assessment").getString("observation")))
        }
        val recent = JSONArray()
        val assessments = state.getJSONArray("assessments")
        for (i in maxOf(0, assessments.length() - 4) until assessments.length()) {
            val assessment = assessments.getJSONObject(i)
            recent.put(JSONObject().put("status", assessment.getString("status")).put("observation", assessment.getString("observation")))
        }
        val view = JSONObject().put("state", when {
            state.getBoolean("requires_plan") -> "plan_required"
            index == stages.length() -> "model_stages_reached"
            else -> "active"
        }).put("current_stage", if (index < stages.length()) stageView(stages.getJSONObject(index)) else JSONObject.NULL)
            .put("remaining_stages", remaining).put("completed_count", completed.length()).put("recent_completed", recentCompleted)
            .put("recent_observations", recent).put("assessment_kind", "model_observed").put("host_verified", false)
        "阶段路线：stage_status和observed_result配对选填，只报告当前阶段已经观察到的ongoing/reached/deviated。reached只推进一段，可同次执行下一阶段动作。" +
            "修订时plan_task只列剩余路线；已达到观察保留在宿主历史。历史观察可能过期，仍需核对当前界面。" +
            "以下是模型观察，不是宿主业务成功证明或授权。\nfeedback_operator=$view"
    }

    /** Validate persisted facts rather than quietly resetting or upgrading their trust level. */
    private fun existing(run: JSONObject): JSONObject? {
        if (!run.has(KEY)) return null
        val state = run.optJSONObject(KEY) ?: invalid("persisted operator must be an object")
        exactKeys(state, setOf("schema_version", "assessment_kind", "host_verified", "revision", "reason", "stages",
            "active_index", "requires_plan", "completed", "assessments"))
        integer(state, "schema_version", 1, 1)
        val revision = integer(state, "revision", 1, MAX_REVISION)
        modelOnly(state)
        text(state, "reason", 1000)
        requireFeedback(state.opt("requires_plan") is Boolean, "persisted planning flag must be boolean")
        val stages = state.optJSONArray("stages") ?: invalid("persisted stages missing")
        requireFeedback(stages.length() in 1..8, "persisted stage count invalid")
        val index = integer(state, "active_index", 0, stages.length())
        requireFeedback(index < stages.length() || !state.getBoolean("requires_plan"), "completed route cannot require planning")
        val currentStages = LinkedHashMap<String, JSONObject>()
        for (i in 0 until stages.length()) {
            val stage = stages.optJSONObject(i) ?: invalid("persisted stage must be an object")
            exactKeys(stage, stageKeys)
            validateStage(stage)
            requireFeedback(stage.getString("id") == "r${revision}s${i + 1}", "persisted stage identity differs")
            currentStages[stage.getString("id")] = stage
        }
        val completed = state.optJSONArray("completed") ?: invalid("persisted completed facts missing")
        requireFeedback(completed.length() <= MAX_COMPLETED, "persisted completed history exceeds limit")
        requireFeedback(completed.length() + stages.length() - index <= MAX_COMPLETED, "persisted route exceeds completed history capacity")
        val completedIds = HashSet<String>()
        for (i in 0 until completed.length()) {
            val fact = completed.optJSONObject(i) ?: invalid("completed fact must be an object")
            exactKeys(fact, stageKeys + "assessment")
            validateStage(fact)
            requireFeedback(completedIds.add(fact.getString("id")), "duplicate completed stage")
            val assessment = fact.optJSONObject("assessment") ?: invalid("completed observation missing")
            validateRecord(assessment, revision)
            requireFeedback(assessment.getString("stage_id") == fact.getString("id") && assessment.getString("status") == "reached",
                "completed stage lacks matching reached observation")
            if (assessment.getInt("revision") == revision) {
                val planned = currentStages[fact.getString("id")] ?: invalid("completed stage is outside current route")
                requireFeedback(stageInputKeys.all { planned.getString(it) == fact.getString(it) }, "completed facts differ from current stage")
            }
        }
        for (i in 0 until stages.length()) requireFeedback(
            (stages.getJSONObject(i).getString("id") in completedIds) == (i < index), "active index differs from completed observations")
        val assessments = state.optJSONArray("assessments") ?: invalid("persisted observations missing")
        requireFeedback(assessments.length() <= MAX_ASSESSMENTS, "persisted observations exceed limit")
        for (i in 0 until assessments.length()) validateRecord(assessments.optJSONObject(i) ?: invalid("observation must be an object"), revision)
        return state
    }

    private fun validateStage(stage: JSONObject) {
        text(stage, "objective", 500); text(stage, "exit_condition", 500)
        requireFeedback(Regex("r[1-9][0-9]{0,9}s[1-8]").matches(text(stage, "id", 24, trim = false)), "invalid host stage identity")
    }

    private fun validateRecord(record: JSONObject, currentRevision: Int) {
        exactKeys(record, recordKeys)
        val revision = integer(record, "revision", 1, currentRevision)
        val id = text(record, "stage_id", 24, trim = false)
        requireFeedback(Regex("r${revision}s[1-8]").matches(id), "observation stage identity differs")
        requireFeedback(record.opt("status") in statuses, "invalid persisted stage result")
        text(record, "observation", 1024)
        modelOnly(record)
        val source = record.optJSONObject("source") ?: invalid("observation source missing")
        requireFeedback(source.keys().asSequence().all { it in observationKeys }, "unsupported persisted source field")
        sourceSnapshot(source)
    }

    private fun sourceSnapshot(source: JSONObject): JSONObject {
        // A semantic observation always has a screen; evidence/capture are optional host capabilities.
        text(source, "screen_id", 256, trim = false)
        return JSONObject().apply {
            for (key in observationKeys) if (source.has(key)) {
                // DirectExecutionContext may have a valid screen but no identified foreground app.
                if (key !in sourceKeys && source.opt(key) is String && source.getString(key).isBlank()) continue
                put(key, text(source, key, 256, trim = false))
            }
        }
    }

    private fun modelOnly(value: JSONObject) = requireFeedback(value.opt("assessment_kind") == "model_observed" && value.opt("host_verified") == false,
        "stage state must remain an unverified model observation")
    private fun stageView(stage: JSONObject) = JSONObject().put("objective", stage.getString("objective")).put("exit_condition", stage.getString("exit_condition"))
    private fun resultSchema() = objectSchema(JSONObject()
        .put("status", JSONObject().put("type", "string").put("enum", JSONArray(statuses.toList())))
        .put("observation", textSchema(1024).put("description", "可选报告中说明当前画面与当前阶段退出条件的关系；reached仅为模型观察。")), resultKeys)
    private fun objectSchema(properties: JSONObject, required: Set<String>) = JSONObject().put("type", "object")
        .put("properties", properties).put("required", JSONArray(required.toList())).put("additionalProperties", false)
    private fun textSchema(max: Int) = JSONObject().put("type", "string").put("minLength", 1).put("maxLength", max)
    private fun copy(value: JSONObject) = JSONObject(value.toString())
    private fun copy(value: JSONArray) = JSONArray(value.toString())
    private fun tail(value: JSONArray, limit: Int) = JSONArray().apply {
        for (i in maxOf(0, value.length() - limit) until value.length()) put(copy(value.getJSONObject(i)))
    }
    private fun exactKeys(value: JSONObject, keys: Set<String>) = requireFeedback(value.keys().asSequence().toSet() == keys, "missing or unsupported fields")
    private fun text(value: JSONObject, key: String, max: Int, trim: Boolean = true): String {
        val raw = value.opt(key) as? String ?: invalid("$key must be a string")
        requireFeedback(raw.length in 1..max && raw.isNotBlank(), "$key is empty or exceeds its limit")
        requireFeedback(raw.none { it.isISOControl() && it !in "\n\r\t" }, "$key contains control characters")
        return if (trim) raw.trim() else raw
    }
    private fun integer(value: JSONObject, key: String, min: Int, max: Int): Int {
        val raw = value.opt(key)
        requireFeedback(raw is Int || raw is Long, "$key must be an integer")
        val number = (raw as Number).toLong()
        requireFeedback(number in min.toLong()..max.toLong(), "$key is out of range")
        return number.toInt()
    }
    private fun requireFeedback(condition: Boolean, reason: String) { if (!condition) invalid(reason) }
    private fun invalid(reason: String): Nothing = throw IllegalArgumentException("feedback_operator: $reason")
}

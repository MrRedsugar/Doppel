package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

/** All fields are schema paths, field names or types; model-provided scalar values are excluded. */
internal class SplitSchemaViolation(
    val path: String, val code: String, message: String,
    val missingFields: List<String> = emptyList(), val extraFields: List<String> = emptyList(),
    val branch: String? = null, val branchErrors: List<SplitSchemaViolation> = emptyList(),
    val branchesChecked: Int = 0
) : IllegalArgumentException(message) {
    fun inBranch(name: String) = if (branch != null) this else SplitSchemaViolation(path, code, message.orEmpty(),
        missingFields, extraFields, name, branchErrors, branchesChecked)

    fun diagnostic(): JSONObject = JSONObject().put("path", path).put("code", code).apply {
        if (branch != null) put("schema_branch", branch)
        if (missingFields.isNotEmpty()) put("missing_fields", JSONArray(missingFields))
        if (extraFields.isNotEmpty()) put("extra_fields", JSONArray(extraFields))
        if (branchErrors.isNotEmpty()) put("branch_errors", JSONArray(branchErrors.map { it.diagnostic() }))
        if (branchesChecked > 0) put("branches_checked", branchesChecked)
        if (branchesChecked > branchErrors.size) put("branches_truncated", true)
    }
}

/** Versioned, action-specific wire shapes. JSON mode alone cannot enforce these field contracts. */
internal object SplitOutputSchema {
    private val directions = listOf("up", "up_right", "right", "down_right", "down", "down_left", "left", "up_left")
    private val actions = SplitAgentProtocol.actions.toList()
    private fun enum(vararg values: String) = JSONObject().put("type", "string").put("enum", JSONArray(values.toList()))
    private fun string(limit: Int = 1500, empty: Boolean = false) = JSONObject().put("type", "string").put("minLength", if (empty) 0 else 1).put("maxLength", limit)
    private fun integer(min: Int, max: Int) = JSONObject().put("type", "integer").put("minimum", min).put("maximum", max)
    private fun array(item: JSONObject, min: Int = 0, max: Int = 30) = JSONObject().put("type", "array").put("items", item).put("minItems", min).put("maxItems", max)
    private fun any(values: List<JSONObject>) = JSONObject().put("anyOf", JSONArray(values))
    private fun obj(vararg fields: Pair<String, JSONObject>): JSONObject = obj(linkedMapOf(*fields))
    private fun obj(fields: Map<String, JSONObject>) = JSONObject().put("type", "object").put("properties", JSONObject(fields))
        .put("required", JSONArray(fields.keys.toList())).put("additionalProperties", false)
    private fun points(min: Int, max: Int) = array(array(JSONObject().put("type", "number").put("minimum", 0).put("maximum", 1000), 2, 2), min, max)
    private fun plannerDirection() = obj("gesture_semantics" to enum("reveal_content", "physical_gesture", "object_drag"),
        "target_relative_direction" to enum(*(directions + "unknown").toTypedArray()), "intended_finger_direction" to enum(*directions.toTypedArray()))
    private fun grounderDirection() = obj("target_relative_direction" to enum(*(directions + "unknown").toTypedArray()),
        "required_finger_direction" to enum(*directions.toTypedArray()), "direction_corrected" to JSONObject().put("type", "boolean"))
    private fun copyProperties(schema: JSONObject, into: MutableMap<String, JSONObject>) {
        val props = schema.getJSONObject("properties")
        props.keys().forEach { into[it] = props.getJSONObject(it) }
    }

    fun format(role: String, direct: Boolean = false, expectedAction: String? = null): JSONObject {
        require(role in setOf("primary", "grounding")) { "结构化输出角色无效" }
        if (expectedAction != null) require(expectedAction in actions) { "结构化输出动作无效" }
        val schema = if (role == "primary") {
            val branches = actions.map { plannerExecute(it, direct) }.toMutableList()
            branches += obj("kind" to enum("wait"), "duration_ms" to integer(100, 30000), "reason" to string(500),
                "wait_condition" to string(500), "evidence" to string(700))
            branches += obj("kind" to enum("finish"), "status" to enum("completed", "failed"), "message" to string(8000))
            branches += obj("kind" to enum("ask_user"), "message" to string(2000))
            branches += obj("kind" to enum("list_apps"), "query" to string(120, true))
            branches += obj("kind" to enum("read_notifications"), "package_name" to string(255, true))
            branches += obj("kind" to enum("read_clipboard"))
            branches += obj("kind" to enum("read_calendar"), "start_date" to string(10, true), "days" to integer(1, 31))
            branches += obj("kind" to enum("search_web"), "query" to string(300))
            branches += obj("kind" to enum("read_web"), "url" to string(2000))
            branches += obj("kind" to enum("load_skill"), "name" to string(120))
            branches += obj("kind" to enum("read_skill_resource"), "name" to string(120), "path" to string(500))
            val state = obj("phase" to string(500, true), "facts" to array(string(700)), "completed_steps" to array(string(700)),
                "remaining_steps" to array(string(700)), "failed_routes" to array(string(700)),
                "progress" to any(listOf(obj("plan" to array(string(60), 0, 5), "completed" to integer(0, 5),
                    "total_known" to JSONObject().put("type", "boolean")), JSONObject().put("type", "null"))))
            obj("decision" to any(branches), "state" to any(listOf(state, JSONObject().put("type", "null"))))
        } else {
            val branches = (expectedAction?.let(::listOf) ?: actions).map(::grounderLocated).toMutableList()
            branches += obj("status" to enum("not_found", "ambiguous", "unsupported", "intent_mismatch"), "reason" to string(500))
            obj("result" to any(branches))
        }
        val name = if (role == "primary") "doppel_a_${if (direct) "direct" else "ab"}_v3" else "doppel_b_${expectedAction ?: "all"}_v1"
        return JSONObject().put("type", "json_schema").put("json_schema", JSONObject().put("name", name).put("strict", true).put("schema", schema))
    }

    private fun plannerExecute(action: String, direct: Boolean): JSONObject {
        val fields = linkedMapOf("kind" to enum(action), "target" to string(2000),
            "expected" to string(1500), "screen_context" to string(1200, true))
        if (action in setOf("swipe", "swipe_sequence")) {
            fields["swipe_extent"] = enum("small", "large")
            fields["scroll_goal"] = enum("inspect", "boundary")
            fields["boundary_reason"] = string(500, true)
            if (action == "swipe") copyProperties(plannerDirection(), fields)
            else fields["gesture_contracts"] = array(plannerDirection(), 1, 8)
        }
        if (direct) coordinateFields(action, fields) else if (action == "type") fields["text"] = string(8000)
        if (action == "login_password") { fields["package_name"] = string(255); fields["credential_label"] = string(80) }
        if (action in setOf("login_phone", "login_code")) fields["package_name"] = string(255)
        if (action == "launch") fields["package_name"] = string(255)
        nativeFields(action, fields)
        return obj(fields)
    }

    private fun nativeFields(action: String, fields: MutableMap<String, JSONObject>) {
        if (action in setOf("volume", "adjust_volume")) {
            fields["stream"] = enum("media", "ring", "alarm")
            if (action == "volume") fields["percent"] = integer(0, 100) else fields["direction"] = enum("up", "down")
        }
        if (action in setOf("copy", "cut")) fields["selection"] = enum("current", "all")
    }

    private fun coordinateFields(action: String, fields: MutableMap<String, JSONObject>) {
        nativeFields(action, fields)
        when (action) {
            "tap", "long_press", "double_tap" -> {
                fields["points"] = points(1, if (action == "double_tap") 2 else 1)
                fields["duration_ms"] = if (action == "long_press") integer(500, 3000) else integer(40, 180)
                if (action == "double_tap") fields["interval_ms"] = integer(40, 300)
            }
            "swipe" -> { fields["points"] = points(2, 32); fields["duration_ms"] = integer(100, 3000) }
            "swipe_sequence" -> {
                fields["strokes"] = array(obj("points" to points(2, 32), "duration_ms" to integer(100, 3000)), 1, 8)
                fields["interval_ms"] = integer(0, 1000)
            }
            "type" -> fields["text"] = string(8000)
            "login_password" -> { fields["package_name"] = string(255); fields["credential_label"] = string(80) }
            "login_phone", "login_code" -> fields["package_name"] = string(255)
            "launch" -> fields["package_name"] = string(255)
        }
    }

    private fun grounderLocated(action: String): JSONObject {
        val assessment = linkedMapOf("alignment" to enum("consistent"))
        if (action == "swipe") copyProperties(grounderDirection(), assessment)
        else if (action == "swipe_sequence") assessment["gesture_contracts"] = array(grounderDirection(), 1, 8)
        val fields = linkedMapOf("status" to enum("located"), "action" to enum(action), "assessment" to obj(assessment))
        coordinateFields(action, fields)
        return obj(fields)
    }

    /** Additive memory may repeat retained strings; replacements and malformed values stay explicit. */
    fun normalizeAdditiveState(value: JSONObject, previousState: JSONObject? = null, format: JSONObject? = null): JSONObject {
        val state = value.optJSONObject("state") ?: return value
        val supportsProgress = format == null || format.optJSONObject("json_schema")?.optJSONObject("schema")
            ?.optJSONObject("properties")?.optJSONObject("state")?.optJSONArray("anyOf")?.let { branches ->
                (0 until branches.length()).any { branches.optJSONObject(it)?.optJSONObject("properties")?.has("progress") == true }
            } == true
        // Keep the provider response intact for diagnostics. Explicit nulls, wrong
        // types and extra fields are left untouched for the strict validator.
        return JSONObject(value.toString()).apply {
            val copy = getJSONObject("state")
            // Old replies can omit this additive update; a recorded v2 format must keep its exact shape.
            if (supportsProgress && !state.has("progress")) copy.put("progress", JSONObject.NULL)
            for (key in listOf("facts", "completed_steps", "failed_routes")) {
                if (!state.has(key)) { copy.put(key, JSONArray()); continue }
                val input = copy.optJSONArray(key) ?: continue
                val seen = mutableSetOf<String>()
                previousState?.optJSONArray(key)?.let { old ->
                    repeat(old.length()) { index ->
                        (old.opt(index) as? String)?.trim()?.takeIf { it.isNotBlank() }?.let(seen::add)
                    }
                }
                val additions = JSONArray()
                repeat(input.length()) { index ->
                    val item = input.get(index)
                    // Compare using merge's trim semantics, but never repair an
                    // invalid member or shorten/drop any genuinely new string.
                    if (item !is String || item.length !in 1..700 || item.isBlank() || seen.add(item.trim()))
                        additions.put(item)
                }
                copy.put(key, additions)
            }
        }
    }

    /** Response is copied; state=null means no durable-state update, including no plan clearing. */
    fun unwrap(value: JSONObject, role: String): JSONObject {
        require(role in setOf("primary", "grounding")) { "结构化输出角色无效" }
        val key = if (role == "primary") "decision" else "result"
        val body = value.optJSONObject(key) ?: throw IllegalArgumentException("结构化输出缺少 $key 对象")
        return JSONObject(body.toString()).apply {
            if (role == "primary") {
                // Wire v2/v3 share one discriminator. Convert the validated command to the engine's internal shape.
                val kind = opt("kind") as? String ?: throw IllegalArgumentException("结构化输出缺少 kind")
                require(kind in actions || kind in SplitAgentProtocol.readActions || kind in setOf("wait", "finish", "ask_user", "list_apps", "search_web", "read_web", "load_skill", "read_skill_resource")) { "结构化输出 kind 无效" }
                require(!has("action")) { "A 线协议只使用 kind，不包含 action" }
                if (kind in actions) put("kind", "execute").put("action", kind)
                // Empty optional context is an omission, not a blank field for the legacy semantic parser.
                if (opt("screen_context") is String && optString("screen_context").isBlank()) remove("screen_context")
                require(value.has("state") && (value.isNull("state") || value.opt("state") is JSONObject)) { "结构化输出 state 类型无效" }
                value.optJSONObject("state")?.let { put("state", JSONObject(it.toString())) }
            }
        }
    }

    /** Validates the exact supported schema subset, even if a compatible server ignores response_format. */
    fun validate(value: JSONObject, format: JSONObject) {
        require(format.optString("type") == "json_schema" && format.optJSONObject("json_schema")?.optBoolean("strict") == true) { "缺少严格输出结构" }
        checkValue(value, format.getJSONObject("json_schema").getJSONObject("schema"), "$", 0)
    }

    private fun checkValue(value: Any?, schema: JSONObject, path: String, depth: Int) {
        ensure(depth <= 24, path, "depth", "输出结构嵌套过深")
        schema.optJSONArray("anyOf")?.let { variants ->
            val branches = (0 until variants.length()).map(variants::getJSONObject)
            // Preserve a useful field diagnostic for a known kind/action instead of hiding it behind anyOf.
            if (value is JSONObject) {
                val matching = branches.filter { branch ->
                    val props = branch.optJSONObject("properties")
                    val tags = listOf("kind", "status", "action").filter { props?.optJSONObject(it)?.has("enum") == true }
                    tags.isNotEmpty() && tags.all { tag ->
                        val values = props!!.getJSONObject(tag).getJSONArray("enum")
                        value.opt(tag) is String && (0 until values.length()).any { values.getString(it) == value.optString(tag) }
                    }
                }
                if (matching.size == 1) { checkBranch(value, matching.single(), path, depth); return }
            }
            // state is object|null. Once its type is known, keep the object's specific field error.
            // This changes diagnostics only; every original branch is still validated by the same rules.
            val compatible = branches.filter { matchesType(value, it.optString("type")) }
            if (compatible.size == 1) { checkBranch(value, compatible.single(), path, depth); return }
            val failures = ArrayList<SplitSchemaViolation>()
            for (branch in branches) {
                try { checkBranch(value, branch, path, depth); return }
                catch (failure: SplitSchemaViolation) { if (failures.size < 8) failures += failure }
            }
            throw SplitSchemaViolation(path, "any_of", "$path 不符合动作结构分支", branchErrors = failures,
                branchesChecked = branches.size)
        }
        when (schema.getString("type")) {
            "null" -> ensure(value == null || value === JSONObject.NULL, path, "type", "$path 必须为空")
            "object" -> {
                ensure(value is JSONObject, path, "type", "$path 必须为对象")
                value as JSONObject
                val props = schema.getJSONObject("properties")
                val required = schema.getJSONArray("required")
                val missing = (0 until required.length()).map(required::getString).filterNot(value::has)
                val extra = value.keys().asSequence().filterNot(props::has).toList().sorted()
                    .take(32).mapIndexed { index, key -> StrictModelJson.diagnosticKey(key, index) }
                if (missing.isNotEmpty() || extra.isNotEmpty()) {
                    val reason = listOfNotNull(
                        missing.takeIf { it.isNotEmpty() }?.let { "缺少必填字段 ${it.joinToString(",")}" },
                        extra.takeIf { it.isNotEmpty() }?.let { "包含未定义字段 ${it.joinToString(",")}" }).joinToString("；")
                    throw SplitSchemaViolation(path, "object_fields", "$path $reason", missingFields = missing, extraFields = extra)
                }
                props.keys().forEach { key -> if (value.has(key)) checkValue(value.get(key), props.getJSONObject(key), "$path.$key", depth + 1) }
            }
            "array" -> {
                ensure(value is JSONArray, path, "type", "$path 必须为数组")
                value as JSONArray
                ensure(value.length() in schema.getInt("minItems")..schema.getInt("maxItems"), path, "array_length", "$path 数组长度无效")
                repeat(value.length()) { checkValue(value.get(it), schema.getJSONObject("items"), "$path[$it]", depth + 1) }
            }
            "string" -> {
                ensure(value is String, path, "type", "$path 必须为字符串")
                value as String
                if (schema.has("enum")) ensure(schema.getJSONArray("enum").let { options -> (0 until options.length()).any { options.getString(it) == value } }, path, "enum", "$path 取值无效")
                if (schema.has("minLength")) ensure(value.length in schema.getInt("minLength")..schema.getInt("maxLength"), path, "string_length", "$path 文本长度无效")
            }
            "number", "integer" -> {
                ensure(value is Number && value.toDouble().isFinite(), path, "type", "$path 必须为有限数值")
                value as Number
                if (schema.getString("type") == "integer") ensure(value.toDouble() == value.toLong().toDouble(), path, "integer", "$path 必须为整数")
                ensure(value.toDouble() >= schema.getDouble("minimum") && value.toDouble() <= schema.getDouble("maximum"), path, "range", "$path 数值超出范围")
            }
            "boolean" -> ensure(value is Boolean, path, "type", "$path 必须为布尔值")
            else -> error("不支持的输出结构类型")
        }
    }

    private fun ensure(valid: Boolean, path: String, code: String, message: String) {
        if (!valid) throw SplitSchemaViolation(path, code, message)
    }

    private fun matchesType(value: Any?, type: String): Boolean = when (type) {
        "null" -> value == null || value === JSONObject.NULL
        "object" -> value is JSONObject
        "array" -> value is JSONArray
        "string" -> value is String
        "number", "integer" -> value is Number
        "boolean" -> value is Boolean
        else -> false
    }

    private fun checkBranch(value: Any?, schema: JSONObject, path: String, depth: Int) {
        val props = schema.optJSONObject("properties")
        val tags = listOf("kind", "status", "action").mapNotNull { tag ->
            props?.optJSONObject(tag)?.optJSONArray("enum")?.let { values ->
                "$tag=" + (0 until values.length()).joinToString("|") { values.getString(it) }
            }
        }
        val label = tags.takeIf { it.isNotEmpty() }?.joinToString(",") ?: schema.optString("type", "anyOf")
        try { checkValue(value, schema, path, depth + 1) }
        catch (failure: SplitSchemaViolation) { throw failure.inBranch(label) }
    }
}

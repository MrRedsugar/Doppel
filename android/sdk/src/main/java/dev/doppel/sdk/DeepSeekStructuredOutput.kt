package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

/** A transport envelope only. No tool returned here is executed by this adapter. */
internal object DeepSeekStructuredOutput {
    private val names = Regex("doppel_[ab]_[a-z_]+_v[0-9]+")

    /** Mutates the caller's wire copy, never the original action schema. */
    fun prepare(body: JSONObject) {
        val format = body.optJSONObject("response_format") ?: return
        if (format.optString("type") != "json_schema") return
        val contract = format.getJSONObject("json_schema")
        require(contract.opt("strict") == true && names.matches(contract.optString("name"))) { "严格工具输出定义无效" }
        require(!body.has("tools") && !body.has("tool_choice")) { "任务输出不能混入其他工具定义" }
        val function = JSONObject().put("name", contract.getString("name")).put("strict", true)
            .put("description", "Return the next task decision as this function's JSON arguments. No extra text.")
            .put("parameters", providerSchema(contract.getJSONObject("schema"), 0))
        body.put("tools", JSONArray().put(JSONObject().put("type", "function").put("function", function)))
        if (body.optJSONObject("thinking")?.optString("type") == "enabled") {
            // DeepSeek rejects forced/required tool_choice with thinking (HTTP 400).
            // Auto selects among the sole strict result function; the returned
            // envelope and original action schema are still validated locally.
            body.put("tool_choice", "auto")
            val instruction = "最终决策通过调用函数 ${contract.getString("name")} 返回一次，参数遵循其 JSON 结构。"
            val messages = body.optJSONArray("messages") ?: JSONArray().also { body.put("messages", it) }
            val system = (0 until messages.length()).mapNotNull(messages::optJSONObject)
                .firstOrNull { it.optString("role") == "system" && it.opt("content") is String }
            if (system != null) system.put("content", system.getString("content") + "\n\n" + instruction)
            else body.put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", instruction)).apply {
                repeat(messages.length()) { put(messages.get(it)) }
            })
        } else body.put("tool_choice", JSONObject().put("type", "function")
            .put("function", JSONObject().put("name", contract.getString("name"))))
        body.remove("response_format")
    }

    private fun providerSchema(original: JSONObject, depth: Int): JSONObject {
        require(depth <= 24) { "严格工具输出结构嵌套过深" }
        val copy = JSONObject(original.toString())
        // DeepSeek's strict subset does not accept these four keywords. The
        // original SplitOutputSchema is still enforced locally before dispatch.
        val limits = mutableListOf<String>()
        if (copy.has("minLength") || copy.has("maxLength"))
            limits += "String length ${copy.optInt("minLength", 0)}..${copy.opt("maxLength") ?: "unbounded"}."
        if (copy.has("minItems") || copy.has("maxItems"))
            limits += "Array items ${copy.optInt("minItems", 0)}..${copy.opt("maxItems") ?: "unbounded"}."
        listOf("minLength", "maxLength", "minItems", "maxItems").forEach(copy::remove)
        if (limits.isNotEmpty()) copy.put("description", (copy.optString("description") + " " + limits.joinToString(" ")).trim())
        // Traverse schema nodes, not arbitrary object maps: a property may
        // legitimately be named minLength or anyOf.
        copy.optJSONObject("properties")?.let { properties ->
            properties.keys().asSequence().toList().forEach { key ->
                properties.put(key, providerSchema(properties.getJSONObject(key), depth + 1))
            }
        }
        copy.optJSONObject("items")?.let { copy.put("items", providerSchema(it, depth + 1)) }
        copy.optJSONArray("anyOf")?.let { branches ->
            copy.put("anyOf", JSONArray().apply { repeat(branches.length()) { put(providerSchema(branches.getJSONObject(it), depth + 1)) } })
        }
        return copy
    }

    /** Validate one strict result envelope; ordinary vision probes pass through. */
    fun normalize(response: JSONObject, wire: JSONObject): JSONObject {
        if (!wire.has("tools") && !wire.has("tool_choice")) return response
        val expectedTools = wire.optJSONArray("tools")
        require(expectedTools?.length() == 1) { "严格工具请求必须指定唯一结果" }
        val expectedTool = expectedTools!!.getJSONObject(0)
        val expectedFunction = expectedTool.getJSONObject("function")
        val name = expectedFunction.optString("name")
        val forced = wire.optJSONObject("tool_choice")?.let {
            it.optString("type") == "function" && it.optJSONObject("function")?.optString("name") == name
        } == true
        val thinkingAuto = wire.opt("tool_choice") == "auto" && wire.optJSONObject("thinking")?.optString("type") == "enabled"
        require(expectedTool.optString("type") == "function" && expectedFunction.opt("strict") == true && names.matches(name) &&
            (forced || thinkingAuto)) { "严格工具请求与动作结构不符" }
        val choices = response.optJSONArray("choices")
        require(choices?.length() == 1) { "模型必须返回唯一结果" }
        val choice = choices!!.getJSONObject(0)
        require(choice.optString("finish_reason") == "tool_calls") { "模型工具输出未完整结束" }
        val message = choice.getJSONObject("message")
        require(message.isNull("refusal") || (message.opt("refusal") as? String)?.isBlank() == true) { "模型拒绝输出动作" }
        require(message.isNull("content") || (message.opt("content") as? String)?.isBlank() == true) { "模型同时返回了冲突的输出形式" }
        val calls = message.optJSONArray("tool_calls")
        require(calls?.length() == 1) { "模型必须仅返回一个动作结果" }
        val call = calls!!.getJSONObject(0)
        val function = call.getJSONObject("function")
        require(call.optString("type") == "function" && function.optString("name") == name) { "模型返回的工具与当前动作结构不符" }
        val arguments = function.opt("arguments") as? String ?: throw IllegalArgumentException("模型工具参数必须是 JSON 文本")
        require(arguments.length in 2..24000) { "模型响应长度无效" }
        StrictModelJson.objectValue(arguments)
        return JSONObject(response.toString()).apply {
            getJSONArray("choices").getJSONObject(0).apply {
                put("finish_reason", "stop")
                getJSONObject("message").put("content", arguments)
            }
        }
    }
}

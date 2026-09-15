package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

/** A short policy-controlled plan; every target is resolved from a new host observation. */
internal class GuardedActionPlan private constructor(
    val objective: String, private val steps: List<JSONObject>, private val started: Long
) {
    sealed class Decision {
        data class Action(val value: JSONObject) : Decision()
        data class Replan(val reason: String) : Decision()
        object Observe : Decision()
        object Done : Decision()
    }
    private var index = 0
    private var pendingId: String? = null
    private var awaitingResult = false
    private var accepted = 0
    private var reads = 0
    private var resultExpectation: JSONObject? = null
    private var dispatchedEvidence: String? = null
    private var candidateEvidence: String? = null
    private var aborted: String? = null

    fun summary() = JSONObject().put("objective", objective).put("steps", steps.size).put("next_step", index + 1)
        .put("accepted_steps", accepted).put("state", when { aborted != null -> "replan"; index == steps.size && !awaitingResult -> "verify"; else -> "executing" })
        .put("reason", aborted ?: "").put("started_at", started)

    fun dispatched(commandId: String) { check(!awaitingResult); pendingId = commandId; awaitingResult = true; dispatchedEvidence = candidateEvidence }

    fun result(command: JSONObject, status: String, data: JSONObject) {
        if (command.optString("id") != pendingId) return
        pendingId = null; awaitingResult = false
        if (status != "ok" || data.optString("human_takeover").takeUnless { it == "null" }.orEmpty().isNotBlank()) {
            aborted = "步骤执行未确认，需根据当前画面重新规划，不能重放旧操作"; return
        }
        accepted++; resultExpectation = steps[index].optJSONObject("expect"); index++; reads = 0
    }

    fun next(screen: JSONObject, evidenceId: String?, now: Long): Decision {
        aborted?.let { return Decision.Replan(it) }
        if (now - started !in 0..90000) return Decision.Replan("局部计划超过有效期，需要重新观察并规划")
        if (awaitingResult) return Decision.Replan("上一动作结果尚未确认，不能派发下一步")
        if (screen.optBoolean("assistant_surface")) return Decision.Replan("助手窗口占据前台，局部计划已停止")
        if (evidenceId.isNullOrBlank()) return Decision.Replan("当前观察缺少宿主证据")
        if (dispatchedEvidence == evidenceId) {
            if (reads++ < 2) return Decision.Observe
            return Decision.Replan("动作后没有取得新观察，不能继续使用旧页面执行")
        }
        candidateEvidence = evidenceId
        if (resultExpectation != null && !conditionMet(screen, resultExpectation!!)) {
            if (reads++ < 2) return Decision.Observe
            return Decision.Replan("步骤${index}未观察到预期控件状态；检查编辑状态、遮挡或目标选择")
        }
        resultExpectation = null
        if (index >= steps.size) return Decision.Done
        val step = steps[index]
        if (screen.optString("package_name") != step.getString("package_name")) {
            if (reads++ < 2) return Decision.Observe
            return Decision.Replan("步骤${index + 1}的应用与当前前台不符，可能发生弹窗或跳转")
        }
        val kind = step.getString("kind")
        val condition = step.optJSONObject("when")
        if (condition != null && !conditionMet(screen, condition)) {
            if (reads++ < 2) return Decision.Observe
            return Decision.Replan("步骤${index + 1}的页面条件不成立")
        }
        val action = JSONObject().put("kind", kind).put("screen_id", screen.getString("screen_id"))
        if (kind == "back") return Decision.Action(action)
        val selected = resolve(screen, step.getJSONObject("selector"), kind)
        if (selected.size != 1) {
            if (selected.isEmpty() && reads++ < 2) return Decision.Observe
            return Decision.Replan(if (selected.isEmpty()) "步骤${index + 1}未找到目标；需要新定位或滚动查看" else "步骤${index + 1}目标不唯一；需要更具体的页面证据")
        }
        val node = selected.single()
        val id = node.optString("id")
        if (!id.matches(Regex("n[0-9]+(?:_[0-9]+)*"))) return Decision.Replan("节点引用不合法")
        action.put("target", id)
        if (kind == "type") action.put("text", step.getString("text"))
        if (kind == "ime_action") {
            if (!node.optBoolean("focused") || node.optString("ime_editor_id").isBlank() || node.optString("ime_action") != step.getString("action"))
                return Decision.Replan("当前编辑器未提供预期提交方式，需要重新查看输入状态")
            action.put("editor_id", node.getString("ime_editor_id")).put("action", step.getString("action"))
        }
        if (kind == "scroll") action.put("direction", step.getString("direction"))
        if (node.optBoolean("checkable")) {
            if (step.opt("desired_checked") !is Boolean) return Decision.Replan("勾选操作缺少明确的目标状态")
            action.put("desired_checked", step.getBoolean("desired_checked"))
        }
        reads = 0
        return Decision.Action(action)
    }

    companion object {
        private val kinds = setOf("tap", "long_press", "type", "scroll", "back", "ime_action")
        private val selectorKeys = setOf("text", "description", "resource_id", "role", "focused", "selected")
        fun parse(value: JSONObject, now: Long): GuardedActionPlan {
            require(value.keys().asSequence().all { it in setOf("objective", "steps") }) { "计划含未支持字段" }
            val objective = value.getString("objective").trim(); require(objective.length in 1..500)
            val input = value.getJSONArray("steps"); require(input.length() in 1..12) { "局部计划需要1至12个步骤" }
            val steps = (0 until input.length()).map {
                val step = JSONObject(input.getJSONObject(it).toString())
                require(step.keys().asSequence().all { key -> key in setOf("kind", "package_name", "selector", "text", "direction", "desired_checked", "when", "expect", "action") })
                val kind = step.getString("kind"); require(kind in kinds)
                require(step.getString("package_name").matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")))
                for (field in listOf("when", "expect")) if (step.has(field)) {
                    val predicate = step.getJSONObject(field)
                    require(predicate.keys().asSequence().all { key -> key in setOf("selector","exists","text_equals","checked_equals","selected_equals","focused_equals") })
                    val selector = predicate.getJSONObject("selector")
                    require(selector.length() in 1..6 && selector.keys().asSequence().all { key -> key in selectorKeys })
                    for (key in selector.keys()) {
                        if (key in setOf("focused","selected")) require(selector.opt(key) is Boolean)
                        else require(selector.getString(key).length in 1..255)
                    }
                    for (key in listOf("exists","checked_equals","selected_equals","focused_equals")) if(predicate.has(key)) require(predicate.opt(key) is Boolean)
                    if(predicate.has("text_equals")) require(predicate.getString("text_equals").length <= 4000)
                    require(predicate.length() >= 2) { "条件需明确exists或字段的预期值" }
                    if(predicate.opt("exists") == false) require(predicate.length() == 2)
                }
                if (kind != "back") {
                    val selector = step.getJSONObject("selector")
                    require(selector.length() in 1..6 && selector.keys().asSequence().all { key -> key in selectorKeys })
                    require(selector.keys().asSequence().any { key -> key in setOf("text", "description", "resource_id", "focused") }) { "目标需文字、资源标识或明确焦点" }
                    for (key in selector.keys()) {
                        if (key in setOf("focused", "selected")) require(selector.opt(key) is Boolean)
                        else require(selector.getString(key).length in 1..255)
                    }
                    // Focus-only is suitable for an input, never a generic click target.
                    if (selector.length() == 1 && selector.has("focused")) require(kind in setOf("type","ime_action") && selector.getBoolean("focused"))
                }
                if (kind == "ime_action") require(step.getString("action") in setOf("done","next"))
                if (kind == "type") require(step.getString("text").length in 1..4000)
                if (kind == "scroll") require(step.getString("direction") in setOf("up", "down", "left", "right"))
                step
            }
            return GuardedActionPlan(objective, steps, now)
        }
        private fun selectorMatches(node: JSONObject, selector: JSONObject, inheritedLabel: String = ""): Boolean = selector.keys().asSequence().all { key ->
            when (key) {
                "focused", "selected" -> node.opt(key) == selector.opt(key)
                "resource_id" -> node.optString(key) == selector.getString(key) || node.optString(key).substringAfterLast('/') == selector.getString(key)
                else -> node.optString(key).trim() == selector.getString(key).trim() ||
                    key in setOf("text","description") && inheritedLabel.isNotBlank() && inheritedLabel == selector.getString(key).trim()
            }
        }
        private fun conditionMet(screen: JSONObject, condition: JSONObject): Boolean {
            val nodes=screen.optJSONArray("nodes") ?: JSONArray()
            val matches=(0 until nodes.length()).mapNotNull {nodes.optJSONObject(it)}.filter {
                !it.optBoolean("password") && (!it.has("visible") || it.optBoolean("visible")) &&
                    selectorMatches(it,condition.getJSONObject("selector"),ModelScreenSummary.inheritedLabel(it,screen))
            }
            if(condition.opt("exists") == false)return matches.isEmpty()
            if(matches.isEmpty())return false
            if(condition.length()==2 && condition.opt("exists") == true)return true
            if(matches.size!=1)return false
            val node=matches.single()
            if(condition.has("text_equals") && node.opt("text") != condition.opt("text_equals"))return false
            return listOf("checked","selected","focused").all { !condition.has(it+"_equals") || node.opt(it)==condition.opt(it+"_equals") }
        }
        private fun resolve(screen: JSONObject, selector: JSONObject, kind: String): List<JSONObject> {
            val nodes=screen.optJSONArray("nodes") ?: return emptyList()
            return (0 until nodes.length()).mapNotNull { nodes.optJSONObject(it) }.filter { node ->
            val capability = when (kind) { "tap" -> "clickable"; "long_press" -> "long_clickable"; "type", "ime_action" -> "editable"; else -> "scrollable" }
            node.optBoolean("enabled") && !node.optBoolean("password") && (!node.has("visible") || node.optBoolean("visible")) && node.optBoolean(capability) &&
                selectorMatches(node, selector, if(kind in setOf("tap","long_press")) ModelScreenSummary.inheritedLabel(node,screen) else "")
            }
        }
        fun tool(): JSONObject {
            fun str() = JSONObject().put("type", "string")
            fun obj(properties: JSONObject, required: List<String>) = JSONObject().put("type", "object").put("properties", properties)
                .put("required", JSONArray(required)).put("additionalProperties", false)
            val selector = obj(JSONObject().put("text", str()).put("description", str()).put("resource_id", str()).put("role", str())
                .put("focused", JSONObject().put("type", "boolean")).put("selected", JSONObject().put("type", "boolean")), emptyList())
            val predicate = obj(JSONObject().put("selector",selector).put("exists",JSONObject().put("type","boolean"))
                .put("text_equals",str().put("description","Exact text content of the selected node, e.g. 127 or 4572; never a prose description of an effect."))
                .put("checked_equals",JSONObject().put("type","boolean")).put("selected_equals",JSONObject().put("type","boolean"))
                .put("focused_equals",JSONObject().put("type","boolean")),listOf("selector"))
            val step = obj(JSONObject().put("kind", str().put("enum", JSONArray(kinds.toList()))).put("package_name", str())
                .put("selector", selector).put("text", str()).put("direction", str().put("enum", JSONArray(listOf("up", "down", "left", "right"))))
                .put("action", str().put("enum",JSONArray(listOf("done","next")))).put("desired_checked", JSONObject().put("type", "boolean")).put("when", predicate).put("expect", predicate), listOf("kind", "package_name"))
            return JSONObject().put("type", "function").put("function", JSONObject().put("name", "execute_plan")
                .put("description", "Execute a short guarded plan without a model call between every action. Use exact visible text/description/resource_id selectors (not node IDs or guessed coordinates); when/expect are typed predicates {selector,exists:true} or {selector,text_equals:exactText}, never prose sentences. Omit an uncertain predicate; future page selectors are resolved against each NEW observation. Duplicate/missing targets or unmet typed when/expect predicates return to planning. Input text is replacement. Up to12 steps; use focused=true only for typing. The host independently authorizes every step. After completion inspect fresh evidence before finish.")
                .put("parameters", obj(JSONObject().put("objective", str()).put("steps", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 12).put("items", step)), listOf("objective", "steps"))))
        }
    }
}

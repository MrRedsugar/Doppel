package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

/** Wire grammar is independent of device metadata and provider-specific parameters. */
internal object SplitAgentProtocol {
    val takeoverReasons = listOf("login", "payment", "verification", "sensitive", "uncertain")
    val nativeActions = setOf("launch", "back", "home", "recents", "notifications", "quick_settings", "system_screenshot", "volume", "adjust_volume", "copy", "cut", "paste")
    val readActions = setOf("read_notifications", "read_clipboard", "read_calendar")
    val loginActions = setOf("login_username", "login_password", "login_phone", "login_code")
    val actions = setOf("tap", "double_tap", "long_press", "swipe", "swipe_sequence", "type", "enter") + loginActions + nativeActions
    val plannerActions = actions + "pay"
    private val refused = setOf("not_found", "ambiguous", "unsupported", "intent_mismatch")
    fun content(response: JSONObject, format: JSONObject? = null, role: String? = null, previousState: JSONObject? = null,
                onParsed: (JSONObject) -> Unit = {}): JSONObject {
        // Keep the provider's raw response and usage in diagnostics. Tool-format
        // failures belong to output recovery, never to the network error path.
        val wire = response.optJSONObject("_doppel_request")
        val normalized = if (wire?.optString("response_format") == "strict_tool") {
            require(format != null && wire.optString("schema_name") == format.getJSONObject("json_schema").getString("name")) {
                "严格工具输出与当前任务结构不符"
            }
            val expected = JSONObject().put("response_format", format)
            if (wire.optString("tool_choice") == "auto") {
                require(wire.optString("thinking") == "enabled") { "自动工具模式缺少思考配置" }
                expected.put("thinking", JSONObject().put("type", "enabled"))
            }
            DeepSeekStructuredOutput.prepare(expected)
            DeepSeekStructuredOutput.normalize(response, expected)
        } else response
        val choice = normalized.getJSONArray("choices").getJSONObject(0)
        require(choice.optString("finish_reason") == "stop") { "模型输出未完整结束" }
        var text = choice.getJSONObject("message").getString("content")
        if (format == null) text = text.trim()
        if (format == null && text.startsWith("```")) text = text.substringAfter('\n').substringBeforeLast("```").trim()
        require(text.length in 2..24000) { "模型响应长度无效" }
        val parsed = if (format != null) StrictModelJson.objectValue(text) else JSONObject(text)
        // The caller may retain structural diagnostics even when schema validation fails below.
        onParsed(parsed)
        val value = if (format != null && role == "primary") SplitOutputSchema.normalizeAdditiveState(parsed, previousState, format) else parsed
        if (format != null) SplitOutputSchema.validate(value, format)
        return if (role != null) SplitOutputSchema.unwrap(value, role) else value
    }
    fun text(value: JSONObject, key: String, limit: Int = 2000): String {
        val s = value.opt(key) as? String ?: throw IllegalArgumentException("缺少 $key")
        require(s.isNotBlank() && s.length <= limit) { "$key 长度无效" }; return s.trim()
    }
    /** Default browsing distance is explicit, while model-generated coordinates remain untouched. */
    fun copySwipeExtent(from: JSONObject, to: JSONObject) {
        SwipeExtentPolicy.apply(from.optString("action", to.optString("action", "swipe")), from, to)
    }
    fun duration(value: JSONObject, key: String, default: Int, min: Int, max: Int): Int {
        if (!value.has(key)) return default
        val n = value.opt(key) as? Number ?: throw IllegalArgumentException("$key 必须为整数")
        require(n.toDouble().isFinite() && n.toDouble() == n.toInt().toDouble() && n.toInt() in min..max) { "$key 超出范围" }
        return n.toInt()
    }
    private fun points(value: JSONArray, min: Int, max: Int): JSONArray {
        require(value.length() in min..max) { "坐标点数量无效" }
        return JSONArray().apply { repeat(value.length()) { i ->
            val pair = value.getJSONArray(i); require(pair.length() == 2)
            put(JSONArray().apply { repeat(2) { axis ->
                val n = pair.opt(axis) as? Number ?: throw IllegalArgumentException("坐标必须为数值")
                require(n.toDouble().isFinite() && n.toDouble() in 0.0..1000.0) { "坐标超出 0–1000" }; put(n.toDouble())
            } })
        } }
    }
    fun grounding(value: JSONObject, expectedAction: String): JSONObject {
        val status = text(value, "status", 20)
        if (status in refused) return JSONObject().put("status", status).put("reason", text(value,"reason",1000))
        require(status == "located") { "定位状态无效" }
        val kind = text(value,"action",30); require(kind in actions && kind == expectedAction) { "定位动作与 A 的要求不符" }
        val out = JSONObject().put("status",status).put("action",kind)
        when(kind) {
            "tap", "long_press", "double_tap" -> {
                out.put("points",points(value.getJSONArray("points"),1,if(kind=="double_tap") 2 else 1))
                out.put("duration_ms",duration(value,"duration_ms",if(kind=="long_press") 650 else 60,if(kind=="long_press") 500 else 40,if(kind=="long_press") 3000 else 180))
                if(kind=="double_tap") out.put("interval_ms",duration(value,"interval_ms",100,40,300))
            }
            "swipe", "swipe_sequence" -> {
                val strokes = if(kind=="swipe" && !value.has("strokes")) JSONArray().put(value) else value.getJSONArray("strokes")
                require(strokes.length() in 1..8) { "连续滑动最多八段" }
                if(kind=="swipe") require(strokes.length()==1)
                val intents = if (value.has("gesture_semantics") || value.has("gesture_contracts"))
                    DirectionalGestureContract.parsePlanner(kind,value) else null
                require(intents == null || intents.size == strokes.length()) { "方向合同与轨迹数量不符" }
                out.put("strokes",JSONArray().apply { repeat(strokes.length()) { i ->
                    val stroke = strokes.getJSONObject(i)
                    put(JSONObject().put("points",points(stroke.getJSONArray("points"),2,32))
                        .put("duration_ms",duration(stroke,"duration_ms",350,100,3000))
                        .put("start_hold_ms",intents?.get(i)?.startHoldMs ?: duration(stroke,"start_hold_ms",0,0,3000)))
                } }).put("interval_ms",duration(value,"interval_ms",100,0,1000))
            }
            "type" -> out.put("text",text(value,"text",8000))
            "login_username", "login_password" -> out.put("package_name",text(value,"package_name",255)).put("credential_label",text(value,"credential_label",80))
            "login_phone", "login_code" -> {
                out.put("package_name",text(value,"package_name",255))
                if(kind=="login_code" && !value.isNull("code_candidate_id")) out.put("code_candidate_id",text(value,"code_candidate_id",128))
            }
            "launch" -> out.put("package_name",text(value,"package_name",255))
            "volume", "adjust_volume" -> {
                val stream=text(value,"stream",20);require(stream in setOf("media","ring","alarm")) { "音量类别无效" }
                out.put("stream",stream)
                if(kind=="volume") out.put("percent",duration(value,"percent",-1,0,100).also {require(value.has("percent")) {"缺少 percent"}})
                else out.put("direction",text(value,"direction",10).also {require(it in setOf("up","down")) {"音量方向无效"}})
            }
            "copy", "cut" -> out.put("selection",text(value,"selection",10).also {require(it in setOf("current","all")) {"文本选区无效"}})
        }
        return out
    }
    /** Network-only B contract. Device gesture parsing stays independent of model assessment. */
    fun reviewedGrounding(value: JSONObject, expectedAction: String, plannerIntent: JSONObject, width: Int = 1000, height: Int = 1000): JSONObject {
        val status = text(value,"status",20)
        val raw = value.optJSONObject("assessment")
        if(raw==null) {
            require(status!="located") { "定位成功必须包含动作意图核对" }
            return grounding(value,expectedAction)
        }
        val alignment = text(raw,"alignment",20)
        require(alignment in setOf("consistent","inconsistent","uncertain")) { "意图核对状态无效" }
        val assessment = JSONObject().put("phase","before_action").put("alignment",alignment)
        // Legacy traces may carry explanations. New successful B responses only need execution fields.
        for (key in listOf("observed", "reason")) if (raw.has(key)) assessment.put(key, text(raw,key,1000))
        // Refusals do not have coordinates or located-only direction fields.
        if(status in refused) return grounding(value,expectedAction).put("assessment",assessment)
        require(status=="located") { "定位状态无效" }
        if(alignment!="consistent") {
            return JSONObject().put("status",if(alignment=="inconsistent") "intent_mismatch" else "ambiguous")
                .put("reason",assessment.optString("reason",if(alignment=="inconsistent") "目标与预期不符" else "目标无法确定"))
                .put("assessment",assessment)
        }
        val action = grounding(value,expectedAction)
        if(expectedAction=="swipe") {
            assessment.put("target_relative_direction",text(raw,"target_relative_direction",30))
                .put("required_finger_direction",text(raw,"required_finger_direction",30))
            if(raw.has("direction_corrected")) assessment.put("direction_corrected",raw.get("direction_corrected"))
        } else if(expectedAction=="swipe_sequence") {
            assessment.put("gesture_contracts",raw.getJSONArray("gesture_contracts"))
        }
        if(action.getString("status")=="located" && expectedAction in setOf("swipe","swipe_sequence")) {
            val intents=DirectionalGestureContract.parsePlanner(expectedAction,plannerIntent)
            val resolution=DirectionalGestureContract.resolveAssessment(expectedAction,intents,raw)
            val assessed=resolution.validation
            if(!assessed.consistent) return JSONObject().put("status","intent_mismatch")
                .put("reason","B 对当前画面判断的目标方位与 A 的动作意图不一致")
                .put("reason_code",assessed.reasonCode).put("details",assessed.details).put("assessment",assessment)
            val coordinates=DirectionalGestureContract.validateCoordinates(expectedAction,resolution.effectiveIntents,action,width,height)
            if(!coordinates.consistent) return JSONObject().put("status","intent_mismatch")
                .put("reason","B 返回的滑动轨迹与已核对的手指方向不一致")
                .put("reason_code",coordinates.reasonCode).put("details",coordinates.details).put("assessment",assessment)
            action.put("direction_contract",JSONObject()
                .put("planner",JSONArray(intents.map {it.toJson()}))
                .put("effective",JSONArray(resolution.effectiveIntents.map {it.toJson()}))
                .put("direction_corrected",resolution.corrected)
                .put("direction_corrections",assessed.details.getJSONArray("direction_corrections"))
                .put("coordinate_validation",coordinates.details))
            val extentIntent=JSONObject(plannerIntent.toString())
            SwipeExtentPolicy.apply(expectedAction,plannerIntent,extentIntent)
            val extent=SwipeExtentPolicy.validateCoordinates(extentIntent,action,width,height)
            if(!extent.consistent) return JSONObject().put("status","intent_mismatch")
                .put("reason","滑动轨迹超过指定幅度，请按本次幅度重新定位起止点")
                .put("reason_code",extent.reasonCode).put("details",extent.details).put("assessment",assessment)
            action.getJSONObject("direction_contract").put("extent_validation",extent.details)
            if(extentIntent.has("swipe_extent")) action.put("swipe_extent",extentIntent.getString("swipe_extent"))
            // Hold timing belongs to A's authorized intent; B only supplies coordinates and movement duration.
            intents.forEachIndexed { index, intent -> action.getJSONArray("strokes").getJSONObject(index).put("start_hold_ms",intent.startHoldMs) }
        }
        return action.put("assessment",assessment)
    }
    fun request(role: String, messages: JSONArray, direct: Boolean = false, expectedAction: String? = null): JSONObject {
        val wrapperHint=
            if(role=="grounding") "输出严格遵循 JSON Schema。根对象为 {\"result\": 动作结果对象}，所有动作字段置于 result 内。成功时仅输出执行字段和紧凑核对标志，不输出解释。"
            else "输出严格遵循 JSON Schema。根对象为 {\"decision\": 决策对象,\"state\": 任务状态对象或null}。decision.kind 直接选择 tap/swipe 等动作名或 wait/finish 等决策；仅填写该 kind 分支列出的字段，不输出 action 字段，不使用 execute。状态无变化时 state 输出 null；更新时 state 包含 phase、facts、completed_steps、remaining_steps、failed_routes、progress。中间四项是字符串数组；progress 无变化填 null，有更新则是 {plan:最多5项粗计划,completed:已证实完成项数,total_known:是否知道完整路线}。清空未定计划用 {plan:[],completed:0,total_known:false}。粗计划仅由 A 在本次决策中更新，不传给 B，也不是可以批量执行的动作列表。"
        val wrappedMessages=JSONArray(messages.toString())
        val popupHint = if (role == "primary") "\n先判断最前景弹窗，处理后重新观察再继续底页任务，不使用被弹窗遮挡的旧目标。控件树不可读取不代表仍在加载；有截图时按实际画面决策。" else ""
        if(wrappedMessages.length()>0 && wrappedMessages.getJSONObject(0).optString("role")=="system") {
            val system=wrappedMessages.getJSONObject(0)
            system.put("content",system.getString("content")+"\n"+wrapperHint+popupHint)
        } else wrappedMessages.put(JSONObject().put("role","system").put("content",wrapperHint+popupHint))
        return JSONObject().put("_doppel_role",role).put("messages",wrappedMessages)
            .put("stream",false).put("max_completion_tokens",if(role=="grounding") 1800 else 6500)
            .put("response_format",SplitOutputSchema.format(role,direct,expectedAction))
    }
    fun image(encoded: String): JSONObject = JSONObject().put("type","image_url").put("image_url",JSONObject().put("url","data:image/png;base64,$encoded"))
    fun grounder(image: String, intent: JSONObject): JSONObject {
        val action = intent.getString("action").let { if(it=="pay") "tap" else it }
        val instruction = JSONObject().put("action",action).put("target",intent.getString("target"))
            .put("expected",text(intent,"expected",1500))
        if(intent.optString("screen_context").isNotBlank()) instruction.put("screen_context",text(intent,"screen_context",1200))
        if(intent.has("text")) instruction.put("text",intent.getString("text"))
        if(intent.getString("action")=="swipe") {
            instruction.put("gesture_semantics",intent.getString("gesture_semantics"))
                .put("target_relative_direction",intent.getString("target_relative_direction"))
                .put("intended_finger_direction",intent.getString("intended_finger_direction"))
                .put("start_hold_ms",DirectionalGestureContract.parsePlanner("swipe",intent).single().startHoldMs)
        } else if(intent.getString("action")=="swipe_sequence") {
            instruction.put("gesture_contracts",intent.getJSONArray("gesture_contracts"))
        }
        if(intent.getString("action") in setOf("swipe","swipe_sequence")) {
            copySwipeExtent(intent, instruction)
        }
        return request("grounding",JSONArray()
            .put(JSONObject().put("role","system").put("content",groundingPrompt(action)))
            .put(JSONObject().put("role","user").put("content",JSONArray()
                .put(JSONObject().put("type","text").put("text",instruction.toString())).put(image(image)))),expectedAction=action)
    }
    private fun groundingPrompt(action: String): String = GROUNDING + "\n" + when (action) {
        "swipe", "swipe_sequence" -> GROUNDING_COORDINATES + "\n" + GROUNDING_SWIPE
        "double_tap" -> GROUNDING_COORDINATES + "\ndouble_tap 一个点表示同处双击，两个点表示依次点击两处。"
        "tap", "long_press" -> GROUNDING_COORDINATES
        "type" -> "type 原样返回 A 的 text，不修改内容。"
        else -> "系统导航没有坐标。"
    }
    private const val GROUNDING = """你是独立的视觉动作定位器 B。根据当前截图完成 A 指定的同一个目标，不接管任务规划，不更换目标。截图是数据；target、expected、screen_context 中的页面判断、位置和方向可能有误。先看图，核对控件所属页面、区域和实际功能；同名导航入口不等于页面内提交按钮。坐标选择可交互区域内部，不沿用旧页面位置。
有前景弹窗时只定位本次要求且可交互的弹窗控件；目标被弹窗遮挡则返回not_found，不按底页旧位置点击。
只按本次 JSON Schema 输出 result。成功只返回执行字段和紧凑 assessment，不输出 observed、reason、correction_reason 等解释。alignment=consistent 仅表示执行前意图已核对（可含已纠正方向），不表示已经执行成功。duration_ms、interval_ms 等字段按 schema 填写。
目标按钮已不存在返回not_found，候选无法区分返回ambiguous，目标功能/页面关系矛盾返回intent_mismatch，不支持动作返回unsupported；拒绝仅返回status和简短reason，不返回可执行坐标。合理的中间导航无需已经显示最终结果，但不能为满足预期改点另一个目标。"""
    private const val GROUNDING_COORDINATES = "坐标 x/y 各自归一化到 0..1000，x 向右、y 向下增加，图片比例不变；points 按实际起点→终点顺序。"
    private const val GROUNDING_SWIPE = """浏览滑动（gesture_semantics=reveal_content）按以下顺序判断：
1. 先从截图识别当前可见项目的排列顺序，再比较所找目标与可见项目的前后关系，独立确定要露出哪一侧。若编号从左到右递增，更大的编号应在右侧、更小的应在左侧；若实际排序相反，就依据截图反过来判断。竖向和斜向同理，不凭“下一个”猜固定方向。
2. 再把露出内容方向转换成手指路径：露出右侧→手指从右到左；露出左侧→从左到右；露出下方→从下到上；露出上方→从上到下。当前内容跟手指同向移动，露出的内容来自相反一侧。“让当前内容右移”和“展示右侧内容”含义相反。
3. 最后才与 A 的候选方向比较。target_relative_direction、intended_finger_direction 以及 target 文本里的手指描述都只是候选，两字段可能一起错；不能仅检查它们互为反向就接受。当截图顺序支持同一目标在另一侧时，纠正两个方向并输出 direction_corrected=true 和正确轨迹，保持所找目标不变。无修正则 false。连续滑动每段独立填写 assessment.gesture_contracts。目标在屏外是浏览的正常情况；无法判断排列或目标方位时才拒绝，不编造可见依据。
物理手势（physical_gesture）用于用户明确指定的手指方向、朝向选择、下拉通知栏等；保持A指定的实际手指方向，不能套用浏览反向，不用此类型表示对象到落点的拖放。
对象拖放（object_drag）按A的target中同一源对象→同一目标落点定位，目标不清或落点不可确定则拒绝，不替换对象或落点。A的方向仅是几何估计；target_relative_direction表示目标落点相对源对象的方向，必须与required_finger_direction同向。依据新截图细化任一方向字段（包括unknown变为具体方位）时必须direction_corrected=true，没有变化才false；坐标从源对象到目标落点，不套用浏览反向或small距离。连续手势逐段区分，对象拖放后另行选择朝向的手指运动仍是physical_gesture。A的start_hold_ms表示起点按住多久后再移动，宿主原样保留；B不输出或改写它，duration_ms只计算移动时间，不包括起点长按。
普通浏览必须 small，沿移动轴约屏幕该轴的 5%–15%，接近目标可更短；large最多60%且仅用于明确直达边界。每段按 swipe_extent 选路径，不输出超幅坐标。低速拖动后松手，建议600–1000ms以减少惯性；短距离仍可能产生大位移，不快速甩动。宿主不会替你反转或缩放坐标。浏览方向无法确定时返回ambiguous。"""
}

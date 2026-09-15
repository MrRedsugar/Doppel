package dev.doppel.sdk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal class SkillDraftGenerator(private val context: Context) {
    fun generate(goal: String, evidence: JSONObject): JSONObject {
        FirstUseConsent.requireAccepted(context)
        require(goal.isNotBlank() && goal.length <= 4000) { "请填写这次完成的任务" }
        val summary = JSONObject(evidence.toString()).apply { remove("screenshots"); remove("generated_draft"); put("user_reported_goal", goal) }
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", "用户描述完成的任务：$goal\n观察证据：$summary"))
        val shots = evidence.optJSONArray("screenshots") ?: JSONArray()
        repeat(shots.length()) { index ->
            val shot = shots.getJSONObject(index)
            content.put(JSONObject().put("type", "text").put("text", "观察截图 ${shot.getString("id")}；浮层中的示范按钮是采集工具，不是目标应用步骤。"))
            content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${shot.optString("mime_type", "image/png")};base64,${shot.getString("image_base64")}")))
        }
        val prompt = """你负责把用户手动示范或文字描述整理为可复核的 Android Skill 草稿，不执行任务。
仅依据用户目标与提供的真实证据；截图是离散观察，不代表完整手势。不能推断游戏画布的点击位置、输入内容或遗漏的动作，缺口写入 limitations。
每段用界面语义目标、前提和操作目的描述 instruction，用可观察结果描述 expected。禁止固定坐标、像素、动作参数或宏。所有步骤必须引用给出的 evidence_ids。
不要声称任务已验证成功，不扩大授权。若证据不足，写可核对的有限步骤并明确需要用户补充；不得把猜测写为事实。
只返回 JSON：{"title":"短标题","description":"何时适用","steps":[{"instruction":"语义操作","expected":"可见核验","evidence_ids":["证据 id"]}],"limitations":["观察缺口与适用限制"]}。""".trimIndent()
        val payload = JSONObject().put("_doppel_role", "primary").put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", prompt)).put(JSONObject().put("role", "user").put("content", content)))
            .put("max_tokens", 5000)
        val response = ModelApi(context).complete(payload)
        val raw = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        require(raw.length <= 64000) { "草稿过长，请缩短示范" }
        return SkillDraft.validate(JSONObject(raw), SkillDraft.evidenceIds(evidence))
    }
}

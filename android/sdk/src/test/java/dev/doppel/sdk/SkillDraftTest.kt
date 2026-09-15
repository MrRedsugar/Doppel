package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SkillDraftTest {
    private fun draft() = JSONObject("""{"title":"打开设置","description":"需要在示例应用里打开设置时使用","steps":[{"instruction":"查找齿轮形设置入口并打开","expected":"设置标题可见","evidence_ids":["ev-1"]}],"limitations":["只观察过一次，需核对当前页面"]}""")
    @Test fun semanticDraftKeepsEvidenceAndIsUnverified() {
        val result = SkillDraft.validate(draft(), setOf("ev-1"))
        assertEquals("打开设置", result.getString("title"))
        assertFalse(result.getBoolean("verified"))
    }
    @Test fun fixedCoordinateMacroAndInventedEvidenceAreRejected() {
        val coordinate = draft().apply { getJSONArray("steps").getJSONObject(0).put("instruction", "点击坐标 (340, 550)") }
        assertThrows(IllegalArgumentException::class.java) { SkillDraft.validate(coordinate, setOf("ev-1")) }
        assertThrows(IllegalArgumentException::class.java) { SkillDraft.validate(draft(), setOf("other-event")) }
    }
    @Test fun emptyModelAnswerCannotBecomeSavedSkill() {
        assertThrows(IllegalArgumentException::class.java) { SkillDraft.validate(JSONObject(), emptySet()) }
    }
}

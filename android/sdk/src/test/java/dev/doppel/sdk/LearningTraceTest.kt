package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LearningTraceTest {
    private fun identity() = JSONObject().put("package_name", "com.android.settings").put("version_code", "28")
        .put("version_name", "9").put("system", "android-28-test").put("locale", "zh-CN")
    private fun page(id: String, label: String) = JSONObject().put("screen_id", id).put("package_name", "com.android.settings")
        .put("width", 1440).put("height", 3200).put("nodes", JSONArray().put(JSONObject().put("id", "n1").put("text", label)
            .put("enabled", true).put("clickable", true)))
    private fun run() = JSONObject().put("id", "run-a").put("status", "completed").put("goal", "private-goal-never-copy")
    private fun tap() = JSONObject().put("id", "command-a").put("kind", "tap").put("target", "n1").put("text", "never-copy-input")
        .put("approved", true).put("visual_permit", "never-copy-permit")
    @Test fun labeledRowsPreferTheirTitleOverDynamicSummary() {
        val page = page("a", "设置")
        val row = JSONObject().put("id", "row").put("clickable", true).put("bounds", JSONArray(listOf(0, 100, 1440, 300)))
        page.put("nodes", JSONArray().put(row)
            .put(JSONObject().put("resource_id", "android:id/title").put("text", "高级").put("bounds", JSONArray(listOf(20, 110, 300, 180))))
            .put(JSONObject().put("resource_id", "android:id/summary").put("text", "当前显示了 字体大小 项（已添加 屏保 项）").put("bounds", JSONArray(listOf(20, 190, 1200, 270)))))
        assertEquals("高级", LearningTrace.targetLabel(page, row))
    }
    @Test fun newlyRevealedLabelsAreKeptWhenTheTopOfThePageDoesNotChange() {
        val before = page("a", "高级")
        val after = page("b", "高级")
        repeat(8) { i ->
            before.getJSONArray("nodes").put(JSONObject().put("text", "已有选项$i"))
            after.getJSONArray("nodes").put(JSONObject().put("text", "已有选项$i"))
        }
        after.getJSONArray("nodes").put(JSONObject().put("text", "字体大小"))
        val run = run(); LearningTrace.record(run, tap(), before, after, identity())
        assertTrue(LearningTrace.finish(run, after)!!.getJSONArray("steps").getJSONObject(0).getJSONObject("after").getJSONArray("labels").toString().contains("字体大小"))
    }
    @Test fun observedNavigationBecomesBoundedEvidenceWithoutInputOrPermissions() {
        val run = run(); val after = page("b", "字体大小")
        LearningTrace.record(run, tap(), page("a", "显示"), after, identity())
        val result = requireNotNull(LearningTrace.finish(run, after))
        assertEquals(1, result.getJSONArray("steps").length())
        assertEquals("显示", result.getJSONArray("steps").getJSONObject(0).getString("label"))
        assertFalse(result.toString().contains("never-copy")); assertFalse(result.toString().contains("private-goal"))
        assertFalse(result.toString().contains("approved")); assertFalse(result.toString().contains("bounds"))
        assertFalse(result.getBoolean("proves_business_success"))
    }
    @Test fun failedTasksUnchangedPagesAndMissingVersionsDoNotBecomeRoutes() {
        for (mode in 0..2) {
            val run = run(); val before = page("a", "显示"); val after = if (mode == 1) before else page("b", "字体大小")
            LearningTrace.record(run, tap(), before, after, if (mode == 2) null else identity())
            if (mode == 0) run.put("status", "failed")
            assertNull(LearningTrace.finish(run, after))
        }
    }
    @Test fun sensitiveActionsAndUnsupportedCanvasDoNotLeakIntoLearnedFiles() {
        for (kind in listOf("type", "login_code", "visual_gesture")) {
            val run = run(); val after = page("b", "欢迎")
            LearningTrace.record(run, tap().put("kind", kind), page("a", "验证码 123456"), after, identity())
            assertNull(LearningTrace.finish(run, after)); assertFalse(run.optJSONObject("learning_trace").toString().contains("123456"))
        }
        val run = run(); val after = page("b", "付款结果")
        LearningTrace.record(run, tap(), page("a", "立即付款"), after, identity())
        assertNull(LearningTrace.finish(run, after))
    }
    @Test fun brokenChainsCrossAppAndOverlongRoutesAreNotAdvertisedAsComplete() {
        for (mode in 0..2) {
            val run = run(); var after = page("b", "字体大小")
            LearningTrace.record(run, tap(), page("a", "显示"), after, identity())
            if (mode == 0) LearningTrace.record(run, tap(), page("different", "字体大小"), page("c", "预览"), identity())
            if (mode == 1) { after = page("c", "预览").put("package_name", "other.app"); LearningTrace.record(run, tap(), page("b", "字体大小"), after, identity()) }
            if (mode == 2) repeat(25) { i ->
                val next = page("next-$i", "预览"); LearningTrace.record(run, tap().put("id", "c-$i"), after, next, identity()); after = next
            }
            assertNull(LearningTrace.finish(run, after))
        }
    }
}

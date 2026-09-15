package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskPresentationTest {
    @Test fun automaticLabelUsesStoredSourceAndRetainsTheActualStatus() {
        for (source in listOf("schedule", "trigger")) {
            val run = JSONObject().put("source", source).put("title", "用户的原标题")
            assertEquals("自动任务 · 已暂停", TaskPresentation.withSourceLabel(run, "已暂停"))
            assertEquals("自动任务", TaskPresentation.withSourceLabel(run, ""))
            assertEquals("用户的原标题", run.getString("title"))
        }
        for (source in listOf("user", "", "unknown"))
            assertEquals("执行中", TaskPresentation.withSourceLabel(JSONObject().put("source", source), "执行中"))
        assertEquals("执行中", TaskPresentation.withSourceLabel(null, "执行中"))
    }

    @Test fun pausedTaskExplainsLimitAndNextStepWithoutInventingSuccess() {
        val run = JSONObject().put("status", "paused").put("message", "已达到本机任务调用或时间上限，请结束后创建新任务")
        val detail = TaskPresentation.detail(run, false)
        assertTrue(detail.contains("任务上限"))
        assertTrue(detail.contains("下一步"))
        assertTrue(detail.contains("结束"))
    }

    @Test fun pauseWithoutReasonExplicitlySaysTheReasonIsUnknown() {
        for (message in listOf("", "已暂停", "{\"error\":123}")) {
            val detail = TaskPresentation.detail(JSONObject().put("status", "paused").put("message", message), false)
            assertTrue(detail, detail.contains("未收到具体原因"))
            assertFalse(detail.contains("已达到"))
        }
    }

    @Test fun completionNoticeExpiresWithoutExtendingOnEveryPoll() {
        val notice = CompanionNotice()
        assertFalse(notice.collapsed("one", "completed", 100))
        assertFalse(notice.collapsed("one", "completed", 3000))
        assertTrue(notice.collapsed("one", "completed", 4100))
        assertTrue(notice.collapsed("one", "completed", 8000))
        assertFalse(notice.collapsed("two", "completed", 8100))
    }

    @Test fun newRunningTaskResetsCompletionNotice() {
        val notice = CompanionNotice()
        notice.collapsed("one", "failed", 10)
        assertTrue(notice.collapsed("one", "failed", 4010))
        assertFalse(notice.collapsed("two", "running", 5000))
        assertFalse(notice.collapsed("two", "cancelled", 5100))
    }

    @Test fun actionNamesAreHumanReadableWithoutShowingStructuredPayloads() {
        assertEquals("正在点击", TaskPresentation.message("正在执行：tap"))
        assertEquals("点击：已执行", TaskPresentation.message("tap：已执行"))
        assertEquals("", TaskPresentation.message("{\"bounds\":[12,30,40,60]}"))
        assertEquals("", TaskPresentation.message("```json\n{\"tool\":\"tap\"}\n```"))
        assertEquals("已整理 12 行数据", TaskPresentation.message("已整理 12 行数据"))
    }

    @Test fun processKeepsActualRecentProgressWithoutRawCommandsOrDuplicates() {
        val events = JSONArray().put(JSONObject().put("kind", "created").put("message", "整理表格"))
            .put(JSONObject().put("message", "正在执行：launch"))
            .put(JSONObject().put("message", "正在执行：launch"))
            .put(JSONObject().put("message", "当前界面已更新"))
            .put(JSONObject().put("message", "已读取 12 行数据"))
            .put(JSONObject().put("message", "{\"tool\":\"tap\"}"))
            .put(JSONObject().put("message", "已整理 12 行数据"))
        assertEquals(listOf("正在打开应用", "已读取 12 行数据"),
            TaskPresentation.process(events, "已整理 12 行数据"))
    }

    @Test fun localPauseHasPriorityOverRemoteProgressMessage() {
        val run = JSONObject().put("status", "running").put("message", "正在执行：tap")
        assertEquals("已暂停", TaskPresentation.title("running", true))
        assertTrue(TaskPresentation.detail(run, true).contains("未收到具体原因"))
        assertFalse(TaskPresentation.detail(run, true).contains("正在点击"))
        assertEquals("正在点击", TaskPresentation.detail(run, false))
    }
}

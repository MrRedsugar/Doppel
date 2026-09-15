package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PausePresentationTest {
    private fun run(id: String, state: String, reason: String) = JSONObject().put("id", id).put("status", state).put("message", reason)
    @Test fun specificRuntimeReasonWinsOverOlderLocalReceipt() {
        val current = run("a", "paused", "当前视觉目标无法确认：按钮被遮挡")
        val old = run("a", "paused", "电话需要你处理，任务已暂停")
        assertSame(current, PauseDetails.merge(current, old, true))
        assertEquals("画面未确认", PausePresentation.from(current)!!.category)
    }
    @Test fun localFailureCanExplainAStoppedWorkerWithStaleRemoteRunningStatus() {
        val current = run("a", "running", "正在执行：tap")
        val saved = run("a", "paused", "本机结果清理失败，已暂停")
        val display = PauseDetails.merge(current, saved, true)
        assertEquals("paused", display.getString("status"))
        assertEquals(saved.getString("message"), display.getString("message"))
        assertEquals("执行记录异常", PausePresentation.from(display)!!.category)
        assertEquals("running", current.getString("status"))
        assertSame(current, PauseDetails.merge(current, saved, false))
    }
    @Test fun oldReceiptCannotAttachToAnotherOrTerminalTask() {
        val saved = run("a", "paused", "原任务的隐私原因")
        for (status in listOf("paused", "running", "completed", "cancelled", "awaiting_approval")) {
            val other = run("b", status, "当前任务")
            assertSame(other, PauseDetails.merge(other, saved, true))
        }
        for (status in listOf("completed", "cancelled", "failed", "awaiting_approval", "awaiting_input")) {
            val ended = run("a", status, "当前状态")
            assertSame(ended, PauseDetails.merge(ended, saved, true))
        }
    }
    @Test fun structuredTakeoverReasonSurvivesGenericServerPauseAndHasManualAdvice() {
        val saved = run("a", "paused", "请处理当前页面校验").put("pending_request", JSONObject().put("reason", "verification"))
        val display = PauseDetails.merge(run("a", "paused", "已暂停"), saved, true)
        val info = PausePresentation.from(display)!!
        assertEquals("需要安全验证", info.category)
        assertTrue(info.nextStep.contains("手动"))
    }
    @Test fun unknownFailureIsNotMisrepresentedAsAQuotaOrPermissionBoundary() {
        val info = PausePresentation.from(run("a", "paused", "上游返回了未知状态"))!!
        assertEquals("执行中断", info.category)
        assertEquals("上游返回了未知状态", info.reason)
        assertFalse(info.userInitiated)
    }
    @Test fun knownUserPauseIsDistinguishedFromUnexplainedLegacyPause() {
        assertTrue(PausePresentation.from(run("a", "paused", "你已暂停任务，继续时将重新观察屏幕"))!!.userInitiated)
        assertFalse(PausePresentation.from(run("a", "paused", "已暂停"))!!.userInitiated)
        assertNull(PausePresentation.from(run("a", "running", "正在执行")))
    }
}

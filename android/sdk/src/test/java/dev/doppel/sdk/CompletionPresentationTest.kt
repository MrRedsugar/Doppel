package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class CompletionPresentationTest {
    @Test fun resultAnswersArePreservedAndFailuresNeverBecomeSuccess() {
        val answer = "共有 3 个已开启的闹钟：07:00、08:00、09:00。"
        assertEquals(answer, CompletionPresentation.from("completed", answer)!!.text)
        assertEquals("未完成", CompletionPresentation.from("failed", "未能读取全部闹钟")!!.title)
        assertNull(CompletionPresentation.from("running", answer))
        assertFalse(CompletionPresentation.from("cancelled", "")!!.speak)
    }
    @Test fun completionWithoutMessageHasAnHonestBriefReceipt() {
        assertEquals("任务已完成", CompletionPresentation.from("completed", "  ")!!.text)
    }
}

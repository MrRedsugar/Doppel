package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SplitProgressBudgetTest {
    private class Fixture(mode: String = "full") {
        var saved = ""
        var clock = 1000L
        val engine = SplitTaskEngine(null, { saved = it }, { clock })
        val id = engine.create(JSONObject().put("goal", "查阅资料并等待设备处理结果")
            .put("device_id", "direct-this-phone").put("mode", mode)).getString("id")
        init { screen() }
        fun run() = engine.internalRun(id)
        fun screen() {
            val command = engine.poll().getJSONObject("command")
            assertEquals("screenshot", command.getString("kind"))
            engine.result(JSONObject().put("run_id", id).put("command_id", command.getString("id"))
                .put("status", "ok").put("observation", JSONObject().put("screen_id", "screen-${clock}").put("package_name", "fixture.reader"))
                .put("data", JSONObject().put("image_base64", "cGl4ZWxz")
                    .put("visual_frame", JSONObject().put("capture_id", "frame-${clock++}"))))
        }
        fun reply(work: SplitTaskEngine.Work, decision: JSONObject, state: JSONObject? = null) {
            if (state != null) decision.put("state", state)
            engine.accept(work, SplitTestReply.response(decision))
        }
        fun step(work: SplitTaskEngine.Work, kind: String, state: JSONObject? = null, duration: Int = 500) {
            val decision = JSONObject().put("kind", kind)
            when (kind) {
                "wait" -> decision.put("duration_ms", duration)
                "search_web" -> decision.put("query", "公开操作资料")
                "read_web" -> decision.put("url", "https://example.org/reference")
            }
            reply(work, decision, state)
            if (kind == "wait") {
                val command = engine.poll().getJSONObject("command")
                assertEquals("wait", command.getString("kind"))
                assertEquals(duration, command.getInt("duration_ms"))
                clock += duration
                engine.result(JSONObject().put("run_id", id).put("command_id", command.getString("id")).put("status", "ok"))
                screen()
            } else {
                val local = requireNotNull(engine.takeWork()) { "The accepted final-budget tool must finish before pausing" }
                assertEquals(kind, local.localTool)
                engine.acceptLocal(local, JSONObject().put("ok", true).put("text", "同一份资料，尚未解决任务"))
            }
        }
    }

    private fun exhausted(kind: String): Fixture {
        val f = Fixture()
        var attempts = 0
        repeat(42) { index ->
            val work = f.engine.takeWork() ?: return@repeat
            assertFalse(work.grounding)
            assertNull(work.localTool)
            val tool = if (kind == "mixed") if (index < 25) "search_web" else "wait" else kind
            f.step(work, tool)
            attempts++
        }
        assertEquals("$kind must not reach the observed 42-round loop", 32, attempts)
        assertEquals(32, f.run().getInt("actions_since_progress"))
        assertEquals("paused", f.run().getString("status"))
        assertTrue(f.run().getString("message").contains("无进展"))
        assertTrue(f.engine.poll().isNull("command"))
        assertFalse(f.engine.readyForWork())
        return f
    }

    @Test fun repeatedSearchesCannotBypassProgressBudget() { exhausted("search_web") }
    @Test fun repeatedPageReadsCannotBypassProgressBudget() { exhausted("read_web") }
    @Test fun unchangedWaitsCannotBypassProgressBudget() { exhausted("wait") }
    @Test fun researchThenWaitLoopPausesDurablyAndCanResume() {
        val f = exhausted("mixed")
        assertEquals("paused", SplitTaskEngine(f.saved, {}).get(f.id).getString("status"))
        f.engine.control(f.id, "resume", JSONObject())
        assertEquals(0, f.run().getInt("actions_since_progress"))
        f.screen()
        f.reply(requireNotNull(f.engine.takeWork()), JSONObject().put("kind", "execute").put("action", "home")
            .put("target", "桌面").put("expected", "回到桌面重新开始"))
        val freshAction = f.engine.poll().getJSONObject("command")
        assertEquals("home", freshAction.getString("kind"))
        assertEquals("full", freshAction.getString("mode"))
        f.engine.result(JSONObject().put("run_id", f.id).put("command_id", freshAction.getString("id"))
            .put("status", "ok").put("data", JSONObject().put("action_state", "accepted")))
        assertEquals("A device receipt must not consume the same round twice", 1, f.run().getInt("actions_since_progress"))
        f.screen()
        f.reply(requireNotNull(f.engine.takeWork()), JSONObject().put("kind", "finish")
            .put("status", "completed").put("message", "用户核对后已确认结果"))
        assertEquals("completed", f.run().getString("status"))
    }

    @Test fun verifiedMilestonesAllowLongExternalWaitsAndShortWaitCanFinish() {
        val f = Fixture()
        repeat(40) { index ->
            val state = if (index % 5 == 4) JSONObject().put("completed_steps", JSONArray().put("下载进度已达到 ${(index + 1) * 2}%")) else null
            f.step(requireNotNull(f.engine.takeWork()), "wait", state, duration = 30000)
        }
        assertEquals("running", f.run().getString("status"))
        assertEquals(0, f.run().optInt("actions_since_progress"))
        f.step(requireNotNull(f.engine.takeWork()), "wait", duration = 500)
        f.reply(requireNotNull(f.engine.takeWork()), JSONObject().put("kind", "finish")
            .put("status", "completed").put("message", "处理已完成，结果已确认"))
        assertEquals("completed", f.run().getString("status"))
    }

    @Test fun emptyOrRepeatedMemoryAndReplanningDoNotInventProgress() {
        val f = Fixture()
        fun plan(completed: Int, first: String = "查阅资料") = JSONObject().put("plan", JSONArray().put(first).put("确认结果"))
            .put("completed", completed).put("total_known", true)
        f.step(requireNotNull(f.engine.takeWork()), "search_web", JSONObject().put("progress", plan(0)))
        f.step(requireNotNull(f.engine.takeWork()), "search_web", JSONObject().put("facts", JSONArray().put("取得了一份资料")))
        assertEquals(2, f.run().optInt("actions_since_progress"))
        f.step(requireNotNull(f.engine.takeWork()), "search_web", JSONObject().put("progress", plan(1)))
        assertEquals(0, f.run().getInt("actions_since_progress"))
        f.step(requireNotNull(f.engine.takeWork()), "search_web", JSONObject().put("progress", plan(1)))
        f.step(requireNotNull(f.engine.takeWork()), "search_web", JSONObject().put("progress", plan(0, "改查其他资料")))
        assertEquals(2, f.run().getInt("actions_since_progress"))
    }

    @Test fun finalBudgetDecisionCanRequestApprovalAndAnswerRestoresBudget() {
        val f = Fixture("ask")
        repeat(31) { f.step(requireNotNull(f.engine.takeWork()), "search_web") }
        f.reply(requireNotNull(f.engine.takeWork()), JSONObject().put("kind", "execute").put("action", "tap")
            .put("target", "结果入口").put("expected", "显示结果"))
        val pending = f.run().getJSONObject("pending_request")
        assertEquals("awaiting_approval", f.run().getString("status"))
        assertNull(f.engine.takeWork())
        f.engine.control(f.id, "answer", JSONObject().put("request_id", pending.getString("id")).put("approve", true))
        assertEquals(0, f.run().getInt("actions_since_progress"))
        f.screen()
        assertNotNull(f.engine.takeWork())
    }

    @Test fun finalBudgetGroundingCompletesButUnresolvedTargetCannotLoopForever() {
        val f = Fixture()
        repeat(31) { f.step(requireNotNull(f.engine.takeWork()), "search_web") }
        f.reply(requireNotNull(f.engine.takeWork()), JSONObject().put("kind", "execute").put("action", "tap")
            .put("target", "结果入口").put("expected", "显示结果"))
        f.screen()
        val grounding = requireNotNull(f.engine.takeWork())
        assertTrue(grounding.grounding)
        f.reply(grounding, JSONObject().put("status", "not_found").put("reason", "当前画面没有该入口"))
        assertNull(f.engine.takeWork())
        assertEquals("paused", f.run().getString("status"))
    }
}

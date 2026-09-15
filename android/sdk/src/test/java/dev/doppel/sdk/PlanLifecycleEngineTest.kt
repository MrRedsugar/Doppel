package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlanLifecycleEngineTest {
    private fun screen(id: String, label: String) = JSONObject().put("screen_id", id).put("package_name", "example.app")
        .put("width", 1080).put("height", 2400).put("nodes", JSONArray().put(JSONObject()
            .put("id", id).put("text", label).put("enabled", true).put("clickable", true).put("bounds", JSONArray(listOf(0, 0, 200, 100)))))
    private fun result(engine: DirectTaskEngine, command: JSONObject, id: String, label: String) = engine.result(JSONObject()
        .put("run_id", command.getString("run_id")).put("command_id", command.getString("id"))
        .put("status", "ok").put("data", JSONObject()).put("observation", screen(id, label)))
    private fun reply(name: String, args: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("finish_reason", "tool_calls").put("message", JSONObject().put("tool_calls", JSONArray().put(JSONObject()
            .put("function", JSONObject().put("name", name).put("arguments", args.toString())))))))
    private fun start(mode: String = "full", save: (String) -> Unit = {}): Pair<DirectTaskEngine, String> {
        val engine = DirectTaskEngine(null, save, { 2000L }, plannedControl = true)
        val run = engine.create(JSONObject().put("device_id", DirectRuntime.DEVICE_ID).put("mode", mode).put("goal", "新建笔记并保存"))
        result(engine, engine.poll().getJSONObject("command"), "n1", "新建")
        val work = engine.takeWork()!!
        val steps = JSONArray(listOf("新建", "文本", "编辑内容", "保存").map { label -> JSONObject()
            .put("kind", "tap").put("package_name", "example.app").put("selector", JSONObject().put("text", label)) })
        engine.accept(work, reply("execute_plan", JSONObject().put("objective", "新建并保存笔记").put("steps", steps)))
        return engine to run.getString("id")
    }
    private fun acceptedTwo(engine: DirectTaskEngine): JSONObject {
        assertNull(engine.takeWork()); result(engine, engine.poll().getJSONObject("command"), "n2", "文本")
        assertNull(engine.takeWork()); val second = engine.poll().getJSONObject("command")
        result(engine, second, "n3", "未聚焦的编辑器")
        return second
    }
    private fun fallback(engine: DirectTaskEngine): Pair<DirectTaskEngine.Work, JSONObject> {
        var read = JSONObject()
        repeat(2) { index ->
            assertNull(engine.takeWork()); read = engine.poll().getJSONObject("command")
            assertEquals("observe", read.getString("kind")); result(engine, read, "n${index + 4}", "未聚焦的编辑器")
        }
        return engine.takeWork()!! to read
    }

    @Test fun missingThirdTargetPreservesCountsAndMarksReplanBeforeFallbackDecision() {
        val (engine, id) = start(); acceptedTwo(engine)
        val (fallback, _) = fallback(engine)
        val plan = engine.get(id).getJSONObject("local_plan")
        assertEquals("replan", plan.getString("state")); assertEquals(2, plan.getInt("accepted_steps"))
        assertEquals(4, plan.getInt("steps")); assertEquals(3, plan.getInt("next_step"))
        assertTrue(plan.getString("reason").contains("未找到目标"))
        assertTrue(fallback.payload.toString().contains("replan")); assertFalse(engine.poll().has("target"))
    }

    @Test fun taskCompletedAfterFallbackDoesNotRewriteUnfinishedPlanAsExecutingOrSuccessful() {
        val (engine, id) = start(); acceptedTwo(engine); val (fallback, lastRead) = fallback(engine)
        engine.accept(fallback, reply("finish", JSONObject().put("summary", "根据当前画面报告结果")
            .put("outcome", "completed").put("basis", "current_screen").put("read_scope", "visible")
            .put("screen_id", "n5").put("evidence_id", lastRead.getString("id"))))
        assertEquals("completed", engine.get(id).getString("status"))
        val plan = engine.get(id).getJSONObject("local_plan")
        assertEquals("replan", plan.getString("state")); assertEquals(2, plan.getInt("accepted_steps"))
    }

    @Test fun cancelAndPausePreserveProgressAndRevokeFurtherPlanCommands() {
        for (operation in listOf("cancel", "pause")) {
            val (engine, id) = start(); acceptedTwo(engine)
            engine.control(id, operation, JSONObject())
            val plan = engine.get(id).getJSONObject("local_plan")
            assertEquals(if (operation == "cancel") "cancelled" else "paused", plan.getString("state"))
            assertEquals(2, plan.getInt("accepted_steps")); assertEquals(4, plan.getInt("steps"))
            assertTrue(plan.getString("reason").isNotBlank()); assertNull(engine.takeWork()); assertTrue(engine.poll().isNull("command"))
        }
    }

    @Test fun interruptPausesPlanAndResumeDoesNotResurrectItsOldSteps() {
        val (engine, id) = start(); acceptedTwo(engine); engine.interrupt("来电需先处理")
        assertEquals("paused", engine.get(id).getJSONObject("local_plan").getString("state"))
        engine.control(id, "resume", JSONObject())
        val command = engine.poll().getJSONObject("command"); assertEquals("observe", command.getString("kind"))
        result(engine, command, "n4", "编辑内容")
        assertNotNull(engine.takeWork()); assertTrue(engine.poll().isNull("command"))
        assertEquals("paused", engine.get(id).getJSONObject("local_plan").getString("state"))
    }

    @Test fun approvalWaitStopsLocalExecutorWithoutClaimingStepDispatch() {
        var persisted = ""
        val (engine, id) = start("ask") { persisted = it }; assertNull(engine.takeWork())
        assertEquals("awaiting_approval", engine.get(id).getString("status"))
        val plan = engine.get(id).getJSONObject("local_plan")
        assertEquals("paused", plan.getString("state")); assertEquals(0, plan.getInt("accepted_steps"))
        assertTrue(engine.poll().isNull("command"))
        assertEquals("paused", JSONArray(persisted).getJSONObject(0).getJSONObject("local_plan").getString("state"))
    }
}

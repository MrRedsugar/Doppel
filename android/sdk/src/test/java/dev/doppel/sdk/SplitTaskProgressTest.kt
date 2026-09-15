package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SplitTaskProgressTest {
    private class Fixture(val direct: Boolean = false) {
        var saved = ""
        var clock = 1000L
        var engine = SplitTaskEngine(null, { saved = it }, { clock }, enhancementEnabled = { !direct })
        val id = engine.create(JSONObject().put("goal", "打开设置查看手机型号")
            .put("device_id", "direct-this-phone").put("mode", "full")).getString("id")
        fun run() = engine.get(id)
        fun progress() = run().getJSONObject("task_state").getJSONObject("progress")
        fun screen() {
            val command = engine.poll().getJSONObject("command")
            assertEquals("screenshot", command.getString("kind"))
            engine.result(JSONObject().put("run_id", id).put("command_id", command.getString("id")).put("status", "ok")
                .put("observation", JSONObject().put("package_name", "fixture.settings"))
                .put("data", JSONObject().put("image_base64", "cGl4ZWxz").put("visual_frame", JSONObject()
                    .put("capture_id", "capture-${clock++}").put("display_width", 1000).put("display_height", 1000))))
        }
        fun reply(decision: JSONObject, update: JSONObject? = null) {
            if (update != null) decision.put("state", JSONObject().put("phase", "执行中")
                .put("facts", JSONArray()).put("completed_steps", JSONArray()).put("remaining_steps", JSONArray())
                .put("failed_routes", JSONArray()).put("progress", update))
            engine.accept(requireNotNull(engine.takeWork()), SplitTestReply.response(decision))
        }
        fun wait(update: JSONObject? = null) = reply(JSONObject().put("kind", "wait").put("duration_ms", 100), update)
        fun result() {
            val command = engine.poll().getJSONObject("command")
            engine.result(JSONObject().put("run_id", id).put("command_id", command.getString("id"))
                .put("status", "ok").put("data", JSONObject().put("action_state", "accepted")))
        }
        fun afterWait() { result(); screen() }
    }
    private fun plan(completed: Int = 0, known: Boolean = true, steps: List<String> = listOf("打开设置", "找到设备信息", "读取手机型号")) =
        JSONObject().put("plan", JSONArray(steps)).put("completed", completed).put("total_known", known)
    private fun context(work: SplitTaskEngine.Work): JSONObject {
        val messages = work.payload.getJSONArray("messages")
        return JSONObject(messages.getJSONObject(messages.length() - 1).getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test fun firstDecisionReturnsTheCoarsePlanWithoutASeparatePlanningRequest() {
        val fixture = Fixture()
        assertEquals(0, fixture.run().getInt("calls"))
        assertFalse(fixture.progress().getBoolean("total_known"))
        assertEquals(0, fixture.progress().getInt("completed"))
        assertEquals(0, fixture.progress().getJSONArray("plan").length())
        fixture.screen()
        val work = requireNotNull(fixture.engine.takeWork())
        assertEquals(1, fixture.run().getInt("calls"))
        assertFalse(work.grounding)
        assertTrue(work.payload.getJSONArray("messages").getJSONObject(0).getString("content").contains(TaskProgress.PROMPT))
        val first = JSONObject().put("kind", "execute").put("action", "tap").put("target", "设置图标").put("expected", "设置页面出现")
            .put("state", JSONObject().put("progress", plan()))
        fixture.engine.accept(work, SplitTestReply.response(first))
        assertEquals(plan().toString(), fixture.progress().toString())
        assertEquals(1, fixture.run().getInt("calls"))
        assertEquals("screenshot", fixture.engine.poll().getJSONObject("command").getString("kind"))
        fixture.screen()
        val grounder = requireNotNull(fixture.engine.takeWork())
        assertTrue(grounder.grounding)
        assertFalse("B must not receive the coarse plan or any progress contract", grounder.payload.toString().contains("\"progress\""))
        assertFalse(grounder.payload.toString().contains("找到设备信息"))
        fixture.engine.accept(grounder, SplitTestReply.response(JSONObject().put("status", "located").put("action", "tap")
            .put("points", JSONArray("[[100,200]]")).put("assessment", JSONObject().put("alignment", "consistent"))))
        val action = fixture.engine.poll().getJSONObject("command")
        assertEquals("tap", action.getJSONObject("action").getString("action"))
        assertFalse(action.toString().contains("\"progress\""))
        fixture.result()
        assertEquals("A device receipt alone cannot advance coarse progress", 0, fixture.progress().getInt("completed"))
    }

    @Test fun progressSurvivesNullUpdatesWaitingAndProcessRestoration() {
        val fixture = Fixture(direct = true)
        fixture.screen(); fixture.wait(plan(1)); fixture.afterWait()
        val retained = fixture.progress().toString()
        fixture.wait(); fixture.afterWait()
        assertEquals(retained, fixture.progress().toString())
        fixture.reply(JSONObject().put("kind", "wait").put("duration_ms", 100)
            .put("state", JSONObject().put("phase", "等待结果").put("progress", JSONObject.NULL)))
        fixture.afterWait()
        assertEquals(retained, fixture.progress().toString())
        fixture.engine.control(fixture.id, "pause", JSONObject())
        fixture.engine = SplitTaskEngine(fixture.saved, { fixture.saved = it }, { fixture.clock }, enhancementEnabled = { false })
        assertEquals(retained, fixture.progress().toString())
        assertTrue(fixture.engine.poll().isNull("command"))
        fixture.engine.control(fixture.id, "resume", JSONObject()); fixture.screen()
        assertEquals(retained, context(requireNotNull(fixture.engine.takeWork())).getJSONObject("task_state").getJSONObject("progress").toString())
    }

    @Test fun changedPlanCanMoveBackAndNeverBecomesAnActionQueue() {
        val fixture = Fixture(direct = true)
        fixture.screen(); fixture.wait(plan(2)); fixture.afterWait()
        val revised = plan(0, false, listOf("重新打开设置", "寻找型号信息"))
        fixture.wait(revised)
        assertEquals(revised.toString(), fixture.progress().toString())
        assertEquals("Only this round's decision may execute", "wait", fixture.engine.poll().getJSONObject("command").getString("kind"))
        fixture.afterWait()
        assertTrue(fixture.engine.poll().isNull("command"))
        assertEquals("A changed plan must not automatically consume another model request", 2, fixture.run().getInt("calls"))
    }

    @Test fun onlyCompletedFinishMarksTheEntireTaskDone() {
        for (status in listOf("completed", "failed")) {
            val fixture = Fixture(direct = true)
            fixture.screen(); fixture.wait(plan(1)); fixture.afterWait()
            assertEquals("running", fixture.run().getString("status"))
            fixture.reply(JSONObject().put("kind", "finish").put("status", status).put("message", "已核对画面中的结果"))
            assertEquals(status, fixture.run().getString("status"))
            assertEquals(if (status == "completed") 3 else 1, fixture.progress().getInt("completed"))
            assertTrue(fixture.engine.poll().isNull("command"))
        }
        val fixture = Fixture(direct = true)
        fixture.screen(); fixture.wait(plan(3))
        assertEquals("A full coarse count cannot terminate work without finish", "running", fixture.run().getString("status"))
        assertEquals("wait", fixture.engine.poll().getJSONObject("command").getString("kind"))
    }
}

package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContinuousAgentEngineTest {
    private var stored = ""
    private var evidence = ""
    private fun engine(previous: String? = null) = DirectTaskEngine(previous, { stored = it }, { 2000L },
        visualControl = true, plannedControl = true, continuousControl = true)
    private fun create(e: DirectTaskEngine) = e.create(JSONObject().put("device_id", DirectRuntime.DEVICE_ID)
        .put("mode", "full").put("goal", "打开设置并查看版本")).getString("id")
    private fun screen(id: String = "screen-a", canvas: Boolean = false) = JSONObject().put("screen_id", id)
        .put("package_name", "example.settings").put("width", 1080).put("height", 2400)
        .put("nodes", if (canvas) JSONArray() else JSONArray().put(JSONObject().put("id", "n1")
            .put("text", "设置").put("clickable", true).put("enabled", true).put("bounds", JSONArray(listOf(10, 10, 200, 200)))))
    private fun result(e: DirectTaskEngine, id: String = "screen-a", canvas: Boolean = false): JSONObject {
        val command = e.poll().getJSONObject("command")
        val data = JSONObject().put("device_profile", JSONObject().put("visual_gestures", true))
        if (canvas || command.optBoolean("include_screenshot")) data.put("image_base64", "dGVzdA==").put("mime_type", "image/png")
            .put("visual_frame", VisualFrame("capture-$id", id, "example.settings", 1080, 2400, 432, 960, 0, 1000, 46000, "a".repeat(64)).json())
        e.result(JSONObject().put("run_id", command.getString("run_id")).put("command_id", command.getString("id"))
            .put("status", "ok").put("observation", screen(id, canvas)).put("data", data))
        evidence = command.getString("id")
        return command
    }
    private fun names(w: DirectTaskEngine.Work) = w.payload.getJSONArray("tools").let { a ->
        (0 until a.length()).map { a.getJSONObject(it).getJSONObject("function").getString("name") }
    }
    private fun reply(name: String, args: JSONObject, thought: String = "private analysis") = JSONObject().put("choices", JSONArray()
        .put(JSONObject().put("finish_reason", "tool_calls").put("message", JSONObject().put("role", "assistant")
            .put("content", JSONObject.NULL).put("reasoning_content", thought).put("tool_calls", JSONArray().put(JSONObject()
                .put("id", "call-" + java.util.UUID.randomUUID()).put("type", "function").put("function", JSONObject()
                    .put("name", name).put("arguments", args.toString())))))))
    private fun plan() = JSONObject().put("revision", 1).put("reason", "根据当前任务建立路线")
        .put("stages", JSONArray().put(JSONObject().put("id", "settings").put("objective", "进入设置")
            .put("exit_condition", "设置页面可见")).put(JSONObject().put("id", "version").put("objective", "读取版本")
            .put("exit_condition", "当前版本号可见")))
    private fun progress(stage: String, status: String = "continue", screen: String = "screen-a", ref: String = evidence) = JSONObject()
        .put("stage_id", stage).put("status", status).put("observation", "根据当前画面核对阶段")
        .put("screen_id", screen).put("evidence_id", ref)
    private fun planned(e: DirectTaskEngine): String {
        val id = create(e); result(e)
        e.accept(requireNotNull(e.takeWork()), reply("set_task_plan", plan()))
        return id
    }

    @Test fun initialRequestRequiresPlanAndKeepsOneMultimodalIdentity() {
        val e = engine(); create(e); result(e)
        val work = requireNotNull(e.takeWork())
        assertTrue("initial stage tool is missing", "set_task_plan" in names(work))
        assertFalse("actions may not precede plan", "action" in names(work))
        assertEquals("mimo-v2.5", work.payload.getString("model"))
    }

    @Test fun completedNativeActionReturnsActualTransactionAndPreservesFullAssistant() {
        val e = engine(); val id = planned(e)
        val action = JSONObject().put("kind", "tap").put("target", "n1").put("task_progress", progress("settings"))
        val response = reply("action", action, "original private reasoning " + "x".repeat(1800))
        e.accept(requireNotNull(e.takeWork()), response)
        val sent = result(e, "screen-b")
        assertEquals("tap", sent.getString("kind"))
        assertFalse(sent.has("task_progress"))
        val next = requireNotNull(e.takeWork())
        val messages = next.payload.getJSONArray("messages")
        val turns = (0 until messages.length()).map { messages.getJSONObject(it) }
        val original = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val replay = turns.first { it.optString("role") == "assistant" && it.optString("reasoning_content") == original.getString("reasoning_content") }
        assertEquals(original.getJSONArray("tool_calls").toString(), replay.getJSONArray("tool_calls").toString())
        assertTrue(turns.any { it.optString("role") == "tool" && it.toString().contains(sent.getString("id")) })
        assertFalse(e.get(id).toString().contains("original private reasoning"))
        assertFalse(e.list().toString().contains("original private reasoning"))
        assertTrue(stored.contains("original private reasoning"))
    }

    @Test fun staleStageAssessmentCannotDispatchEvenThoughTargetIsValid() {
        val e = engine(); val id = planned(e)
        e.accept(requireNotNull(e.takeWork()), reply("action", JSONObject().put("kind", "tap").put("target", "n1")
            .put("task_progress", progress("settings", "achieved", ref = "old-evidence"))))
        assertEquals("running", e.get(id).getString("status"))
        assertEquals("observe", e.poll().getJSONObject("command").getString("kind"))
        assertTrue(e.get(id).getJSONObject("recovery_feedback").optBoolean("action_executed").not())
    }

    @Test fun reviseReturnsToPlanningWithoutDispatchingRequestedAction() {
        val e = engine(); planned(e)
        e.accept(requireNotNull(e.takeWork()), reply("action", JSONObject().put("kind", "tap").put("target", "n1")
            .put("task_progress", progress("settings", "revise"))))
        assertEquals("observe", e.poll().getJSONObject("command").getString("kind"))
        result(e, "screen-b")
        val work = requireNotNull(e.takeWork())
        assertTrue("set_task_plan" in names(work)); assertFalse("action" in names(work))
    }

    @Test fun pauseRecoveryRetainsStageAndHistoryButNoActionIsReplayed() {
        val e = engine(); val id = planned(e)
        e.accept(requireNotNull(e.takeWork()), reply("action", JSONObject().put("kind", "tap").put("target", "n1")
            .put("task_progress", progress("settings"))))
        val oldId = e.poll().getJSONObject("command").getString("id")
        e.control(id, "pause", JSONObject())
        val restored = engine(stored)
        assertEquals("paused", restored.get(id).getString("status"))
        assertTrue(restored.poll().isNull("command"))
        assertTrue(restored.get(id).has("session_task_plan"))
        restored.control(id, "resume", JSONObject())
        val current = result(restored, "screen-new")
        assertEquals("observe", current.getString("kind")); assertNotEquals(oldId, current.getString("id"))
        val work = requireNotNull(restored.takeWork())
        assertTrue(work.payload.toString().contains("settings"))
        assertEquals("mimo-v2.5", work.payload.getString("model"))
    }

    @Test fun finishingLastStageUsesCurrentDeviceEvidenceInTheSameTurn() {
        val e = engine(); val id = planned(e)
        e.accept(requireNotNull(e.takeWork()), reply("action", JSONObject().put("kind", "tap").put("target", "n1")
            .put("task_progress", progress("settings", "achieved"))))
        result(e, "version-page")
        e.accept(requireNotNull(e.takeWork()), reply("finish", JSONObject().put("outcome", "completed")
            .put("summary", "当前页面版本信息已读取").put("screen_id", "version-page").put("evidence_id", evidence)
            .put("basis", "current_screen").put("read_scope", "visible")
            .put("task_progress", progress("version", "achieved", "version-page"))))
        assertEquals("completed", e.get(id).getString("status"))
        assertTrue(e.poll().isNull("command"))
        assertFalse(e.get(id).toString().contains("private analysis"))
        val privateRun = JSONArray(stored).getJSONObject(0)
        val transactions = privateRun.getJSONObject("_session_trajectory").getJSONArray("transactions")
        val finalReceipt = JSONObject(transactions.getJSONObject(transactions.length() - 1).getJSONObject("tool").getString("content"))
        assertEquals("completed", finalReceipt.getString("run_status"))
        assertEquals("settled", finalReceipt.getString("pending_disposition"))
    }

    @Test fun earlyCompletionCannotBypassUnfinishedStage() {
        val e = engine(); val id = planned(e)
        e.accept(requireNotNull(e.takeWork()), reply("finish", JSONObject().put("outcome", "completed")
            .put("summary", "完成").put("screen_id", "screen-a").put("evidence_id", evidence)
            .put("read_scope", "visible").put("task_progress", progress("settings", "achieved"))))
        assertEquals("running", e.get(id).getString("status"))
        assertEquals("observe", e.poll().getJSONObject("command").getString("kind"))
    }

    @Test fun semanticToCanvasTransitionRetainsAssistantTransactionAndPlan() {
        val e = engine(); planned(e)
        e.accept(requireNotNull(e.takeWork()), reply("action", JSONObject().put("kind", "tap").put("target", "n1")
            .put("task_progress", progress("settings", "achieved")), "retain-through-canvas"))
        result(e, "canvas-page", canvas = true)
        val work = requireNotNull(e.takeWork())
        assertTrue(work.visualAgent)
        assertEquals("mimo-v2.5", work.payload.getString("model"))
        assertTrue(work.payload.getJSONArray("messages").toString().contains("retain-through-canvas"))
        assertTrue(work.payload.getJSONArray("messages").toString().contains("image_url"))
        assertTrue(work.payload.getJSONArray("messages").toString().contains("version"))
        val props = work.payload.getJSONArray("tools").let { tools ->
            (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }.first { it.getString("name") == "propose_tap" }
        }.getJSONObject("parameters").getJSONObject("properties")
        assertTrue(props.has("task_progress"))
        assertFalse("inspect_screen" in names(work))
    }

    @Test fun userAnswerRemainsVisibleToTheContinuousOperator() {
        val e = engine(); val id = planned(e)
        e.accept(requireNotNull(e.takeWork()), reply("ask_user", JSONObject().put("question", "要读取哪个版本？")
            .put("task_progress", progress("settings"))))
        val pending = e.get(id).getJSONObject("pending_request").getString("id")
        e.control(id, "answer", JSONObject().put("request_id", pending).put("text", "查看当前稳定版的版本号"))
        result(e, "screen-new")
        assertTrue(requireNotNull(e.takeWork()).payload.getJSONArray("messages").toString().contains("查看当前稳定版的版本号"))
    }

    @Test fun malformedToolEnvelopeCannotExecuteWithoutReplayableContext() {
        for (missing in listOf("id", "type")) {
            val e = engine(); planned(e)
            val response = reply("action", JSONObject().put("kind", "tap").put("target", "n1").put("task_progress", progress("settings")))
            response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getJSONArray("tool_calls").getJSONObject(0).remove(missing)
            e.accept(requireNotNull(e.takeWork()), response)
            assertEquals("observe", e.poll().getJSONObject("command").getString("kind"))
        }
    }
}

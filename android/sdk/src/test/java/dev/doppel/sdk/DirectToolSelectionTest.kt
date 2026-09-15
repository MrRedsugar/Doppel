package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectToolSelectionTest {
    private var stored = ""
    private fun engine(catalog: JSONObject = JSONObject().put("items", JSONArray())) = DirectTaskEngine(null, { stored = it }, { 2000L }, skillCatalog = { catalog })
    private fun screen() = JSONObject().put("screen_id", "screen-a").put("package_name", "dev.fixture").put("width", 1080).put("height", 2400)
        .put("nodes", JSONArray().put(JSONObject().put("id", "n1").put("text", "下一页").put("enabled", true).put("clickable", true)))
    private fun create(engine: DirectTaskEngine, mode: String = "full"): String {
        val id = engine.create(JSONObject().put("device_id", DirectRuntime.DEVICE_ID).put("goal", "操作当前测试界面").put("mode", mode)).getString("id")
        deliver(engine); return id
    }
    private fun deliver(engine: DirectTaskEngine, data: JSONObject = JSONObject()) {
        val command = engine.poll().getJSONObject("command")
        engine.result(JSONObject().put("run_id", command.getString("run_id")).put("command_id", command.getString("id"))
            .put("status", "ok").put("observation", screen()).put("data", data))
    }
    private fun call(name: String, args: JSONObject) = JSONObject().put("function", JSONObject().put("name", name).put("arguments", args.toString()))
    private fun tap() = call("action", JSONObject().put("kind", "tap").put("target", "n1"))
    private fun reply(calls: JSONArray?) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls")
        .put("message", JSONObject().apply { if (calls != null) put("tool_calls", calls) })))
    @Test fun multipleActionsSelectFirstAndNeverReplaySiblingsAfterFreshResult() {
        val engine = engine(); val id = create(engine)
        val finish = call("finish", JSONObject().put("outcome", "completed").put("summary", "sibling-secret").put("screen_id", "screen-a"))
        engine.accept(engine.takeWork()!!, reply(JSONArray().put(tap()).put(finish).put(tap())))
        assertEquals("tap", engine.poll().getJSONObject("command").getString("kind"))
        assertEquals(1, engine.get(id).getInt("calls"))
        val diagnostic = engine.get(id).getJSONObject("model_tool_diagnostic")
        assertEquals(3, diagnostic.getInt("count")); assertEquals(2, diagnostic.getInt("discarded_count"))
        assertEquals("action", diagnostic.getString("selected_name")); assertEquals("first_only", diagnostic.getString("selection"))
        assertEquals(listOf("finish", "action"), (0 until 2).map { diagnostic.getJSONArray("discarded_names").getString(it) })
        deliver(engine)
        assertEquals("running", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command"))
        assertEquals(1, engine.get(id).getInt("successful_mutations")); assertFalse(stored.contains("sibling-secret"))
        assertNull(engine.takeWork()!!.localTool)
    }
    @Test fun multipleActionsCannotBypassApprovalAndOnlyApprovedFirstIsQueued() {
        val engine = engine(); val id = create(engine, "ask")
        engine.accept(engine.takeWork()!!, reply(JSONArray().put(tap()).put(tap())))
        assertEquals("awaiting_approval", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command"))
        val request = engine.get(id).getJSONObject("pending_request").getString("id")
        engine.control(id, "answer", JSONObject().put("request_id", request).put("approve", true)); deliver(engine)
        assertEquals("tap", engine.poll().getJSONObject("command").getString("kind")); deliver(engine)
        assertTrue(engine.poll().isNull("command")); assertEquals("running", engine.get(id).getString("status"))
    }
    @Test fun aReadOnlyFirstToolNeverQueuesItsFollowingDeviceAction() {
        val engine = engine(); val id = create(engine)
        engine.accept(engine.takeWork()!!, reply(JSONArray().put(call("load_skill", JSONObject().put("name", "guide"))).put(tap())))
        assertTrue(engine.poll().isNull("command"))
        val local = engine.takeWork()!!; assertEquals("load_skill", local.localTool)
        engine.acceptLocal(local, JSONObject().put("found", true).put("name", "guide").put("instructions", "Reference only"))
        assertTrue(engine.poll().isNull("command")); assertEquals(0, engine.get(id).getInt("successful_mutations"))
    }
    @Test fun emptyMissingAndOversizeCallArraysAreRejectedWithExactCounts() {
        for (count in listOf(-1, 0, 9)) {
            val engine = engine(); val id = create(engine)
            val calls = if (count == -1) null else JSONArray().apply { repeat(count) { put(tap()) } }
            engine.accept(engine.takeWork()!!, reply(calls))
            assertEquals("paused", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command"))
            val diagnostic = engine.get(id).getJSONObject("model_tool_diagnostic")
            if (count == -1) assertTrue(diagnostic.isNull("count")) else assertEquals(count, diagnostic.getInt("count"))
            assertEquals("rejected", diagnostic.getString("selection")); assertTrue(diagnostic.isNull("selected_name"))
        }
    }
    @Test fun unknownSiblingNamesAndTheirArgumentsDoNotEnterDiagnosticsOrExecution() {
        val engine = engine(); val id = create(engine)
        engine.accept(engine.takeWork()!!, reply(JSONArray().put(tap()).put(call("private-key-as-name", JSONObject().put("secret", "private-screen")))))
        assertEquals("tap", engine.poll().getJSONObject("command").getString("kind"))
        val diagnostic = engine.get(id).getJSONObject("model_tool_diagnostic")
        assertEquals("unknown", diagnostic.getJSONArray("discarded_names").getString(0))
        assertFalse(stored.contains("private"))
    }
    @Test fun multipleVisualCandidatesStillPauseWithoutDispatch() {
        val engine = engine(); val id = engine.create(JSONObject().put("device_id", DirectRuntime.DEVICE_ID).put("goal", "查看画布").put("mode", "full")).getString("id")
        deliver(engine, JSONObject().put("device_profile", JSONObject().put("visual_gestures", true)))
        engine.accept(engine.takeWork()!!, reply(JSONArray().put(call("visual_action", JSONObject().put("intent", "点击目标")))))
        val frame = VisualFrame("capture-a", "screen-a", "dev.fixture", 1080, 2400, 486, 1080, 0, 1000, 46000, "hash")
        deliver(engine, JSONObject().put("image_base64", "aW1hZ2U=").put("mime_type", "image/png").put("visual_frame", frame.json()))
        val work = engine.takeWork()!!; assertTrue(work.grounding)
        val rejected = call("cannot_ground", JSONObject().put("reason", "uncertain"))
        engine.accept(work, reply(JSONArray().put(rejected).put(rejected)))
        assertEquals("paused", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command"))
    }
    @Test fun largeSkillCatalogRemainsValidJsonWithStructuralBounds() {
        val items = JSONArray().apply { repeat(40) { put(JSONObject().put("name", "skill-$it").put("description", "长目录".repeat(1000))) } }
        val engine = engine(JSONObject().put("items", items)); create(engine)
        val prompt = engine.takeWork()!!.payload.getJSONArray("messages").getJSONObject(1).getString("content")
        val catalog = JSONObject(prompt.substringAfter("可按需加载的技能目录：").substringBefore("\n外部参考资料"))
        assertEquals(20, catalog.getJSONArray("items").length()); assertTrue(catalog.getBoolean("truncated"))
        assertEquals(300, catalog.getJSONArray("items").getJSONObject(0).getString("description").length)
    }
}

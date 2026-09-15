package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectKnowledgeTest {
    private fun response(tool: String, args: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("finish_reason", "tool_calls").put("message", JSONObject().put("tool_calls", JSONArray().put(JSONObject()
            .put("function", JSONObject().put("name", tool).put("arguments", args.toString())))))))
    private fun ready(): Pair<DirectTaskEngine, String> {
        val engine = DirectTaskEngine(null, {}, skillCatalog = { JSONObject().put("items", JSONArray().put(JSONObject().put("name", "arknights").put("description", "关卡资料与操作基础")
            .put("revision", "verified-revision").put("source", "bundled"))) })
        val run = engine.create(JSONObject().put("goal", "处理手机任务").put("mode", "full").put("device_id", DirectRuntime.DEVICE_ID)).getString("id")
        val command = engine.poll().getJSONObject("command")
        engine.result(JSONObject().put("run_id", run).put("command_id", command.getString("id")).put("status", "ok")
            .put("observation", JSONObject().put("screen_id", "screen-1").put("package_name", "app.game").put("width", 1920).put("height", 1080).put("nodes", JSONArray())))
        return engine to run
    }
    @Test fun exposesReadOnlyKnowledgeToolsAndOnDemandCatalog() {
        val (engine, _) = ready()
        val work = engine.takeWork()!!
        val tools = work.payload.getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }
        assertTrue(names.containsAll(listOf("search_web", "read_web", "list_skills", "load_skill", "read_skill_resource")))
        assertTrue(work.payload.toString().contains("arknights"))
    }
    @Test fun localReadDoesNotCountAsPaidCallOrProduceDeviceAction() {
        val (engine, id) = ready()
        engine.accept(engine.takeWork()!!, response("load_skill", JSONObject().put("name", "arknights")))
        val local = engine.takeWork()!!
        assertEquals("load_skill", local.localTool)
        assertEquals(1, engine.get(id).getInt("calls"))
        assertTrue(engine.poll().isNull("command"))
        engine.acceptLocal(local, JSONObject().put("name", "arknights").put("instructions", "Read current stage metadata").put("source", "https://example.com/guide"))
        assertEquals(1, engine.get(id).getInt("knowledge_reads"))
        assertFalse(engine.get(id).has("knowledge"))
        assertTrue(engine.takeWork()!!.payload.toString().contains("Read current stage metadata"))
    }
    @Test fun cancelledReadCannotAppendContextOrRestartRun() {
        val (engine, id) = ready()
        engine.accept(engine.takeWork()!!, response("search_web", JSONObject().put("query", "手机操作")))
        val local = engine.takeWork()!!
        assertTrue(engine.isCurrent(local))
        engine.control(id, "pause", JSONObject())
        assertFalse(engine.isCurrent(local))
        engine.acceptLocal(local, JSONObject().put("content", "late result"))
        assertEquals("paused", engine.get(id).getString("status"))
        assertFalse(engine.get(id).has("knowledge_reads"))
        assertNull(engine.takeWork())
    }
    @Test fun unavailableSourceReturnsHonestContextWithoutPretendingSuccess() {
        val (engine, id) = ready()
        engine.accept(engine.takeWork()!!, response("read_web", JSONObject().put("url", "https://example.com")))
        engine.acceptLocal(engine.takeWork()!!, null, true)
        assertEquals("running", engine.get(id).getString("status"))
        assertTrue(engine.takeWork()!!.payload.toString().contains("资料暂不可获取"))
    }
    @Test fun explicitFailedWebResponseIsNotReportedAsRead() {
        val (engine, id) = ready()
        engine.accept(engine.takeWork()!!, response("read_web", JSONObject().put("url", "https://example.com")))
        engine.acceptLocal(engine.takeWork()!!, JSONObject().put("ok", false).put("error", "unavailable"))
        assertTrue(engine.takeWork()!!.payload.toString().contains("资料暂不可获取"))
        assertFalse(engine.events(id).toString().contains("已读取参考资料"))
    }
    @Test fun lengthyReferenceKeepsSourceAndRevisionInValidJson() {
        val (engine, _) = ready()
        engine.accept(engine.takeWork()!!, response("load_skill", JSONObject().put("name", "arknights")))
        engine.acceptLocal(engine.takeWork()!!, JSONObject().put("instructions", "甲".repeat(14000))
            .put("source", "bundled").put("revision", "verified-revision").put("name", "arknights"))
        val payload = engine.takeWork()!!.payload.toString()
        assertTrue(payload.contains("verified-revision")); assertTrue(payload.contains("bundled"))
        assertFalse(payload.contains("甲".repeat(6000)))
    }
}

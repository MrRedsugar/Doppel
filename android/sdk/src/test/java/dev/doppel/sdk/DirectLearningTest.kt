package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectLearningTest {
    private fun app() = JSONObject().put("package_name", "com.android.settings").put("version_code", "28").put("version_name", "9").put("system", "android-28").put("locale", "zh-CN")
    private fun page(id: String, text: String) = JSONObject().put("screen_id", id).put("package_name", "com.android.settings").put("width", 1440).put("height", 3200)
        .put("nodes", JSONArray().put(JSONObject().put("id", "n1").put("text", text).put("enabled", true).put("clickable", true)))
    private fun reply(name: String, args: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls").put("message", JSONObject()
        .put("tool_calls", JSONArray().put(JSONObject().put("function", JSONObject().put("name", name).put("arguments", args.toString())))))))
    private fun deliver(engine: DirectTaskEngine, screen: JSONObject): String {
        val cmd = engine.poll().getJSONObject("command")
        engine.result(JSONObject().put("run_id", cmd.getString("run_id")).put("command_id", cmd.getString("id")).put("status", "ok")
            .put("data", JSONObject()).put("observation", screen)); return cmd.getString("id")
    }
    @Test fun disabledOrDeletedLearnedContentIsRemovedFromTheNextModelInput() {
        var available = true
        val engine = DirectTaskEngine(null, {}, skillCatalog = {
            JSONObject().put("items", if (available) JSONArray().put(JSONObject().put("name", "learned-route").put("source", "learned").put("revision", "v1")) else JSONArray())
        })
        engine.create(JSONObject().put("device_id", DirectRuntime.DEVICE_ID).put("goal", "打开显示设置").put("mode", "full"))
        deliver(engine, page("a", "显示"))
        engine.accept(engine.takeWork()!!, reply("load_skill", JSONObject().put("name", "learned-route").put("revision", "v1")))
        engine.acceptLocal(engine.takeWork()!!, JSONObject().put("found", true).put("name", "learned-route").put("source", "learned").put("revision", "v1").put("instructions", "old-learned-instructions"))
        val withKnowledge = engine.takeWork()!!
        assertTrue(withKnowledge.payload.toString().contains("old-learned-instructions"))
        engine.accept(withKnowledge, reply("navigate", JSONObject().put("kind", "observe")))
        available = false; deliver(engine, page("a", "显示"))
        assertFalse(engine.takeWork()!!.payload.toString().contains("old-learned-instructions"))
    }
    @Test fun successfulTaskPublishesOnlyAfterCurrentCompletionEvidenceAndLoadsRelevantKnowledge() {
        var published: JSONObject? = null
        val engine = DirectTaskEngine(null, {}, learningIdentity = { app() }, onLearned = { published = it; JSONObject().put("name", "learned-example") },
            learnedReference = { _, _ -> JSONObject().put("found", true).put("name", "learned-reference").put("revision", "v1").put("instructions", "参考已观察到的显示入口；重新寻找当前目标") })
        val id = engine.create(JSONObject().put("device_id", DirectRuntime.DEVICE_ID).put("goal", "打开显示设置").put("mode", "full")).getString("id")
        deliver(engine, page("a", "显示")); val work = engine.takeWork()!!
        assertTrue(work.payload.toString().contains("参考已观察到的显示入口"))
        engine.accept(work, reply("action", JSONObject().put("kind", "tap").put("target", "n1")))
        val evidence = deliver(engine, page("b", "字体大小")); assertNull(published)
        engine.accept(engine.takeWork()!!, reply("finish", JSONObject().put("outcome", "completed").put("summary", "已打开显示设置")
            .put("screen_id", "b").put("evidence_id", evidence)))
        assertEquals("completed", engine.get(id).getString("status")); assertNotNull(published)
        assertEquals("learned-example", engine.get(id).getJSONObject("learning_result").getString("name"))
    }
}

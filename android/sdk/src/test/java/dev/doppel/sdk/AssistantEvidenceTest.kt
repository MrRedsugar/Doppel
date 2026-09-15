package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AssistantEvidenceTest {
    private fun page() = JSONObject().put("screen_id", "assistant").put("package_name", "dev.host")
        .put("assistant_surface", true).put("width", 100).put("height", 200)
        .put("nodes", JSONArray().put(JSONObject().put("id", "n1").put("text", "时钟已打开")
            .put("clickable", true).put("enabled", true)))
    private fun response(args: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls")
        .put("message", JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("function", JSONObject().put("name", "finish").put("arguments", args.toString())))))))
    @Test fun `assistant transcript is not an actionable screen`() {
        val summary = ModelScreenSummary.render(page(), "e")
        assertFalse(summary.text.contains("时钟已打开"))
        assertTrue(summary.targetIds.isEmpty())
    }
    @Test fun `own conversation cannot prove a device task completed`() {
        val engine = DirectTaskEngine(null, {})
        val id = engine.create(JSONObject().put("device_id", DirectRuntime.DEVICE_ID).put("goal", "打开时钟").put("mode", "full")).getString("id")
        val c = engine.poll().getJSONObject("command")
        engine.result(JSONObject().put("run_id", id).put("command_id", c.getString("id")).put("status", "ok").put("observation", page()))
        engine.accept(engine.takeWork()!!, response(JSONObject().put("outcome", "completed").put("summary", "时钟已打开")
            .put("screen_id", "assistant").put("evidence_id", c.getString("id"))))
        assertEquals("running", engine.get(id).getString("status"))
        assertEquals("observe", engine.poll().getJSONObject("command").getString("kind"))
    }
}

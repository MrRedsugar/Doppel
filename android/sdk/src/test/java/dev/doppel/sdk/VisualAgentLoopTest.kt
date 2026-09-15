package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VisualAgentLoopTest {
    private fun screen(id: String = "a") = JSONObject().put("screen_id", id).put("package_name", "dev.canvas")
        .put("width", 1920).put("height", 1080).put("nodes", JSONArray())
    private fun frame(id: String = "a") = VisualFrame("capture-$id", id, "dev.canvas", 1920, 1080, 960, 540, 1, 1000, 46000, "hash-$id")
    private fun reply(name: String, args: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls")
        .put("message", JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("function", JSONObject().put("name", name).put("arguments", args.toString())))))))
    private fun result(e: DirectTaskEngine, id: String, image: Boolean): JSONObject {
        val c=e.poll().getJSONObject("command")
        val data=JSONObject().put("device_profile", JSONObject().put("visual_gestures", true))
        if(image) data.put("image_base64", "aW1hZ2U=").put("mime_type", "image/png").put("visual_frame", frame(id).json())
        e.result(JSONObject().put("run_id", c.getString("run_id")).put("command_id", c.getString("id")).put("status", "ok").put("observation", screen(id)).put("data", data))
        return c
    }
    @Test fun `canvas goes from real frame directly to action then next frame decision`() {
        val e=DirectTaskEngine(null, {}, { 2000L }, visualControl = true)
        val run=e.create(JSONObject().put("device_id", DirectRuntime.DEVICE_ID).put("goal", "进入页面看看余量").put("mode", "full"))
        result(e,"a",false)
        assertNull(e.takeWork())
        assertTrue(e.poll().getJSONObject("command").getBoolean("include_screenshot"))
        result(e,"a",true)
        val first=e.takeWork()!!
        assertEquals(DirectPayload.VISION, first.payload.getString("model"))
        assertTrue(first.payload.getJSONArray("messages").toString().contains("image_url"))
        assertTrue(first.payload.getJSONArray("tools").toString().contains("propose_tap"))
        assertTrue(first.payload.getJSONArray("tools").toString().contains("finish"))
        e.accept(first, reply("propose_tap", JSONObject().put("capture_id","capture-a").put("x",480).put("y",440)
            .put("duration_ms",80).put("label","开始").put("screen_context","入口").put("safety","safe")))
        assertEquals("visual_gesture", e.poll().getJSONObject("command").getString("kind"))
        val action=result(e,"b",true)
        val next=e.takeWork()!!
        assertEquals(DirectPayload.VISION, next.payload.getString("model"))
        e.accept(next,reply("finish",JSONObject().put("outcome","completed").put("summary","当前余量100")
            .put("screen_id","b").put("evidence_id",action.getString("id"))))
        assertEquals("completed",e.get(run.getString("id")).getString("status"))
        assertEquals(2,e.get(run.getString("id")).getInt("calls"))
    }
    @Test fun `text only devices retain semantic planner`() {
        val e=DirectTaskEngine(null, {}, visualControl = true)
        e.create(JSONObject().put("device_id",DirectRuntime.DEVICE_ID).put("goal","读取").put("mode","assist"))
        val c=e.poll().getJSONObject("command")
        e.result(JSONObject().put("run_id",c.getString("run_id")).put("command_id",c.getString("id")).put("status","ok").put("observation",screen()))
        assertEquals(DirectPayload.PLANNER,e.takeWork()!!.payload.getString("model"))
    }
    @Test fun `visual actor receives current learned knowledge and skills catalog`() {
        val e = DirectTaskEngine(null, {}, { 2000L }, visualControl = true,
            skillCatalog = { JSONObject().put("items", JSONArray().put(JSONObject().put("name", "canvas-help"))) },
            learnedReference = { _, _ -> JSONObject().put("found", true).put("name", "learned-canvas").put("instructions", "existing-canvas-route") })
        e.create(JSONObject().put("device_id", DirectRuntime.DEVICE_ID).put("goal", "打开画布").put("mode", "full"))
        result(e, "a", true)
        val payload = e.takeWork()!!.payload.toString()
        assertTrue(payload.contains("existing-canvas-route"))
        assertTrue(payload.contains("canvas-help"))
    }
    @Test fun `malformed visual proposal is rejected and receives correction before any gesture`() {
        val e=DirectTaskEngine(null, {}, { 2000L }, visualControl = true)
        val id=e.create(JSONObject().put("device_id",DirectRuntime.DEVICE_ID).put("goal","打开页面").put("mode","full")).getString("id")
        result(e,"a",true)
        e.accept(e.takeWork()!!, reply("propose_tap",JSONObject().put("capture_id","capture-a").put("x",480).put("y",400)
            .put("duration_ms",80).put("label","入口").put("screen_context","主界面").put("unexpected",true)))
        assertEquals("running",e.get(id).getString("status"))
        assertEquals("observe",e.poll().getJSONObject("command").getString("kind"))
        result(e,"b",true)
        val corrected=e.takeWork()!!
        assertTrue(corrected.payload.toString().contains("visual_unknown_field"))
        e.control(id,"cancel",JSONObject())
        e.accept(corrected,reply("propose_tap",JSONObject().put("capture_id","capture-b").put("x",480).put("y",400)
            .put("duration_ms",80).put("label","入口").put("screen_context","主界面").put("safety","safe")))
        assertEquals("cancelled",e.get(id).getString("status"))
        assertTrue(e.poll().isNull("command"))
    }

    private fun priorImage(marker: Char, length: Int) = JSONObject().put("width", 960).put("height", 540)
        .put("image", marker.toString().repeat(length)).put("action", JSONObject().put("kind", "tap"))
    private fun imageParts(messages: JSONArray): List<String> = (0 until messages.length()).mapNotNull { index ->
        messages.getJSONObject(index).optJSONArray("content")?.let { content ->
            (0 until content.length()).mapNotNull { part ->
                content.getJSONObject(part).optJSONObject("image_url")?.optString("url")?.removePrefix("data:image/png;base64,")
            }
        }
    }.flatten()

    @Test fun `image budget retains newest fitting history and current frame at exact five MiB limit`() {
        val mib = 1024 * 1024
        val history = JSONArray().put(priorImage('A', 2 * mib)).put(priorImage('B', 2 * mib))
        val messages = VisualAgentLoop.messages(JSONObject().put("goal", "读取"), frame(), "C".repeat(3 * mib),
            "current screen", JSONObject(), JSONArray(), visualHistory = history)
        val images = imageParts(messages)
        assertEquals(listOf('B', 'C'), images.map { it.first() })
        assertEquals(listOf(2 * mib, 3 * mib), images.map { it.length })
        assertEquals(5 * mib, images.sumOf { it.length })
    }

    @Test fun `history images preserve chronological order before current when both fit`() {
        val mib = 1024 * 1024
        val history = JSONArray().put(priorImage('A', mib)).put(priorImage('B', mib))
        val messages = VisualAgentLoop.messages(JSONObject().put("goal", "读取"), frame(), "C".repeat(3 * mib),
            "current screen", JSONObject(), JSONArray(), visualHistory = history)
        val images = imageParts(messages)
        assertEquals(listOf('A', 'B', 'C'), images.map { it.first() })
        assertEquals(listOf(mib, mib, 3 * mib), images.map { it.length })
        assertEquals(5 * mib, images.sumOf { it.length })
    }

    @Test fun `full current image budget omits history without discarding current evidence`() {
        val mib = 1024 * 1024
        val history = JSONArray().put(priorImage('A', 4)).put(priorImage('B', 4))
        val messages = VisualAgentLoop.messages(JSONObject().put("goal", "读取"), frame(), "C".repeat(5 * mib),
            "current screen", JSONObject(), JSONArray(), visualHistory = history)
        val images = imageParts(messages)
        assertEquals(listOf('C'), images.map { it.first() })
        assertEquals(5 * mib, images.single().length)
    }
}

package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectPerceptionEfficiencyTest {
    private var saved = ""
    private var clock = 2000L
    private val engine = DirectTaskEngine(null, { saved = it }, { clock })
    private fun node(id: String, text: String = "", clickable: Boolean = false) = JSONObject()
        .put("id", id).put("text", text).put("description", text).put("enabled", true)
        .put("clickable", clickable).put("bounds", JSONArray(listOf(30, 40, 130, 140)))
    private fun screen(nodes: JSONArray = JSONArray().put(node("n1", "预览", true))) = JSONObject()
        .put("screen_id", "page").put("package_name", "dev.example").put("width", 1080).put("height", 2400)
        .put("captured_at", clock).put("nodes", nodes)
    private fun data(capture: String = "capture-a", bytes: String = "cGl4ZWxz") = JSONObject()
        .put("image_base64", bytes).put("mime_type", "image/png")
        .put("visual_frame", VisualFrame(capture, "page", "dev.example", 1080, 2400, 540, 1200, 0, 1000, 45000, "reported-pixels").json())
    private fun reply(name: String, args: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("finish_reason", "tool_calls").put("message", JSONObject().put("tool_calls", JSONArray().put(JSONObject()
            .put("function", JSONObject().put("name", name).put("arguments", args.toString())))))))
    private fun answer(text: String = "预览页面可见，尚未操作。") = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("finish_reason", "stop").put("message", JSONObject().put("content", text))))
    private fun deliver(after: JSONObject = screen(), image: JSONObject = JSONObject()): JSONObject {
        val command = engine.poll().getJSONObject("command")
        assertTrue(engine.result(JSONObject().put("run_id", command.getString("run_id"))
            .put("command_id", command.getString("id")).put("status", "ok").put("observation", after).put("data", image)).getBoolean("accepted"))
        return command
    }
    private fun ready(nodes: JSONArray? = null): String {
        val id = engine.create(JSONObject().put("goal", "查看预览").put("mode", "full").put("device_id", DirectRuntime.DEVICE_ID)).getString("id")
        deliver(nodes?.let(::screen) ?: screen(), JSONObject().put("device_profile", JSONObject().put("visual_gestures", true)))
        return id
    }
    private fun inspect(question: String = "现在是什么页面？", image: JSONObject = data(), after: JSONObject = screen()): DirectTaskEngine.Work {
        engine.accept(engine.takeWork()!!, reply("inspect_screen", JSONObject().put("question", question)))
        deliver(after, image)
        return engine.takeWork()!!
    }
    private fun plannerText(work: DirectTaskEngine.Work) = work.payload.getJSONArray("messages").getJSONObject(1).getString("content")

    @Test fun repeatedReadUsesFreshCaptureButDoesNotCallVisionTwice() {
        val id = ready()
        val first = inspect(); assertTrue(first.vision); engine.accept(first, answer())
        clock += 1000
        val next = inspect(image = data("capture-fresh"))
        assertFalse("An unchanged frame and question should return to planning without another visual model call", next.vision)
        assertTrue(plannerText(next).contains("预览页面可见"))
        assertEquals(1, engine.get(id).getJSONObject("perception_metrics").getInt("read_cache_hits"))
        val context = JSONArray(saved).getJSONObject(0).getJSONObject("execution_context")
        assertEquals("capture-fresh", context.getJSONObject("latest_visual_analysis").getJSONObject("source").getString("capture_id"))
        assertTrue(engine.poll().isNull("command"))
    }

    @Test fun actualImageChangesCannotHitEvenWhenReportedDigestAndCanvasIdStayConstant() {
        ready(); engine.accept(inspect(), answer())
        assertTrue(inspect(image = data("capture-new", "bmV3LXBpeGVscw==")).vision)
    }

    @Test fun newQuestionAndNewKnowledgeRequireNewAnalysis() {
        ready(); engine.accept(inspect(), answer())
        val other = inspect("画面里有几个开关？"); assertTrue(other.vision); engine.accept(other, answer("两个"))
        engine.accept(engine.takeWork()!!, reply("load_skill", JSONObject().put("name", "manual")))
        engine.acceptLocal(engine.takeWork()!!, JSONObject().put("name", "manual").put("instructions", "新的参考资料"))
        assertTrue(inspect("画面里有几个开关？").vision)
    }

    @Test fun changedSemanticStateRequiresNewAnalysisEvenWithSamePixels() {
        ready(); engine.accept(inspect(), answer())
        val changed = screen(JSONArray().put(node("n1", "预览", true).put("checked", true).put("checkable", true)))
        assertTrue(inspect(after = changed).vision)
    }

    @Test fun completedMutationClearsReadReuseEvenWhenResultLooksIdentical() {
        ready(); engine.accept(inspect(), answer())
        engine.accept(engine.takeWork()!!, reply("action", JSONObject().put("kind", "tap").put("target", "n1")))
        assertEquals("tap", deliver().getString("kind"))
        assertTrue(inspect().vision)
    }

    @Test fun pauseAndResumeDiscardReadReuse() {
        val id = ready(); engine.accept(inspect(), answer())
        engine.control(id, "pause", JSONObject()); engine.control(id, "resume", JSONObject()); deliver()
        assertTrue(inspect().vision)
    }

    @Test fun agedReadMustCallVisionAgain() {
        ready(); engine.accept(inspect(), answer()); clock += 31000
        assertTrue(inspect().vision)
    }

    @Test fun groundingNeverUsesReadReuse() {
        ready(); engine.accept(inspect(), answer())
        engine.accept(engine.takeWork()!!, reply("visual_action", JSONObject().put("intent", "点击预览")))
        deliver(image = data("grounding-fresh"))
        val grounding = engine.takeWork()!!
        assertTrue(grounding.vision); assertTrue(grounding.grounding)
        assertTrue(grounding.payload.toString().contains("grounding-fresh"))
    }

    @Test fun screenSummaryRemovesStructureNoiseButRetainsImportantState() {
        val nodes = JSONArray().put(node("n0").put("resource_id", "dev.example:id/root_container"))
            .put(node("n1", "蓝牙", true).put("state_description", "已开启").put("selected", true))
            .put(node("n2", "敏感密码").put("password", true))
            .put(node("n3", "账户余额 28 元"))
        ready(nodes)
        val text = plannerText(engine.takeWork()!!)
        assertFalse("Layout-only containers must not distract the planner", text.contains("root_container"))
        assertFalse(text.contains("蓝牙 蓝牙")); assertFalse(text.contains("敏感密码"))
        assertTrue(text.contains("蓝牙")); assertTrue(text.contains("已开启")); assertTrue(text.contains("selected=true"))
        assertTrue(text.contains("账户余额 28 元")); assertTrue(text.contains("受保护的密码输入框"))
        // A unique label can still identify a navigation entry instead of the intended business action.
        // Keep the source geometry; only structural noise should disappear from the summary.
        assertTrue(text.contains("coordinate_space=device_pixels"))
        val targetRow = text.lineSequence().first { it.startsWith("n1:") }
        assertTrue("Every interactive target retains its geometry", targetRow.contains("bounds=[30,40,130,140]"))
        assertTrue(targetRow.contains("region=top"))
    }

    @Test fun duplicateTargetsRemainDistinctAndRetainLocationForDisambiguation() {
        ready(JSONArray().put(node("n1", "删除", true)).put(node("n2", "删除", true).put("bounds", JSONArray(listOf(30, 200, 130, 300)))))
        val work = engine.takeWork()!!
        val text = plannerText(work)
        assertTrue(text.contains("n1:")); assertTrue(text.contains("n2:"))
        assertTrue(text.contains("[30,40,130,140]")); assertTrue(text.contains("[30,200,130,300]"))
        val tools = work.payload.getJSONArray("tools").toString()
        assertTrue(tools.contains("n1")); assertTrue(tools.contains("n2"))
    }
}

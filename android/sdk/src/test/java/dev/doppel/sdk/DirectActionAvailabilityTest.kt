package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectActionAvailabilityTest {
    private fun node(id: String) = JSONObject().put("id", id).put("enabled", true).put("text", "选项 $id")
    private fun work(nodes: JSONArray, data: JSONObject = JSONObject()): Pair<DirectTaskEngine, DirectTaskEngine.Work> {
        val engine = DirectTaskEngine(null, {})
        engine.create(JSONObject().put("device_id", DirectRuntime.DEVICE_ID).put("mode", "full").put("goal", "看看这个页面有哪些选项"))
        val command = engine.poll().getJSONObject("command")
        engine.result(JSONObject().put("run_id", command.getString("run_id")).put("command_id", command.getString("id"))
            .put("status", "ok").put("data", data).put("observation", JSONObject()
                .put("screen_id", "settings").put("package_name", "com.android.settings").put("width", 1440).put("height", 3200).put("nodes", nodes)))
        return engine to engine.takeWork()!!
    }
    private fun kinds(work: DirectTaskEngine.Work): Set<String> {
        val tools = work.payload.getJSONArray("tools")
        val action = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }.singleOrNull { it.getString("name") == "action" }
            ?: return emptySet()
        val values = action.getJSONObject("parameters").getJSONObject("properties").getJSONObject("kind").getJSONArray("enum")
        return (0 until values.length()).map { values.getString(it) }.toSet()
    }
    @Test fun aSettingsPageWithOnlyClickableRowsDoesNotAdvertiseScrollOrInput() {
        val (engine, work) = work(JSONArray().put(node("n1").put("clickable", true)))
        assertEquals(setOf("tap"), kinds(work))
        // A model ignoring the schema still cannot cause an unsupported gesture.
        engine.accept(work, JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls").put("message", JSONObject()
            .put("tool_calls", JSONArray().put(JSONObject().put("function", JSONObject().put("name", "action")
                .put("arguments", JSONObject().put("kind", "scroll").put("target", "n1").put("direction", "down").toString()))))))))
        assertTrue(engine.poll().isNull("command"))
        assertEquals("paused", engine.get(work.runId).getString("status"))
        assertEquals("目标不可滚动", engine.get(work.runId).getString("message"))
    }
    @Test fun checkedStateIsOnlyOfferedForCheckableTargetsAndWrongStateIsRejectedBeforeDispatch() {
        fun properties(work: DirectTaskEngine.Work): JSONObject {
            val tools = work.payload.getJSONArray("tools")
            return (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }
                .single { it.getString("name") == "action" }.getJSONObject("parameters").getJSONObject("properties")
        }
        val (engine, work) = work(JSONArray().put(node("n1").put("clickable", true)))
        assertFalse(properties(work).has("desired_checked"))
        assertTrue(properties(work(JSONArray().put(node("n1").put("clickable", true).put("checkable", true).put("checked", false))).second).has("desired_checked"))
        engine.accept(work, JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls").put("message", JSONObject()
            .put("tool_calls", JSONArray().put(JSONObject().put("function", JSONObject().put("name", "action")
                .put("arguments", JSONObject().put("kind", "tap").put("target", "n1").put("desired_checked", false).toString()))))))))
        assertTrue(engine.poll().isNull("command"))
        assertEquals("当前控件不可勾选，请重新观察", engine.get(work.runId).getString("message"))
    }
    @Test fun realEditableLongPressAndKnownScrollCapabilitiesRemainAvailable() {
        val (_, work) = work(JSONArray().put(node("n1").put("editable", true))
            .put(node("n2").put("long_clickable", true))
            .put(node("n3").put("scrollable", true)),
            JSONObject().put("scroll_directions", JSONObject().put("n3", JSONArray(listOf("down")))))
        assertEquals(setOf("type", "login_phone", "login_code", "long_press", "scroll"), kinds(work))
    }
    @Test fun hiddenDisabledProtectedAndOmittedTargetsCannotAdvertiseActions() {
        val nodes = JSONArray().put(node("n1").put("clickable", true))
            .put(node("n2").put("editable", true).put("enabled", false))
            .put(node("n3").put("long_clickable", true).put("visible", false))
            .put(node("n4").put("editable", true).put("password", true))
            .put(node("n5").put("scrollable", true))
        repeat(220) { nodes.put(node("n${it + 6}")) }
        nodes.put(node("n999").put("editable", true))
        assertEquals(setOf("tap"), kinds(work(nodes).second))
        assertEquals(emptySet<String>(), kinds(work(JSONArray().put(node("n1"))).second))
    }
}

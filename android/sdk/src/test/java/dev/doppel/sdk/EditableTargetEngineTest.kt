package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** A current semantic editor is a concrete input target even when it occupies most of the display. */
class EditableTargetEngineTest {
    private var stored = ""
    private fun screen(editable: Boolean = true, password: Boolean = false) = JSONObject()
        .put("screen_id", "current-editor-page").put("package_name", "example.editor").put("width", 1440).put("height", 3200)
        .put("nodes", JSONArray().put(JSONObject().put("id", "n0_1_0").put("text", "").put("description", "")
            .put("resource_id", if (editable) "example.editor:id/body_editor" else "example.editor:id/canvas")
            .put("role", if (editable) "input" else "android.view.SurfaceView").put("editable", editable)
            .put("clickable", true).put("long_clickable", true).put("focused", false).put("enabled", true).put("password", password)
            .put("bounds", JSONArray(listOf(0, 200, 1440, 3055)))))

    private fun ready(page: JSONObject): Pair<DirectTaskEngine, String> {
        val engine = DirectTaskEngine(null, { stored = it }, { 1000L }, visualControl = true)
        val id = engine.create(JSONObject().put("device_id", DirectRuntime.DEVICE_ID).put("mode", "full")
            .put("goal", "在当前已授权的空白编辑器输入一行文字")).getString("id")
        val read = engine.poll().getJSONObject("command")
        assertEquals("observe", read.getString("kind"))
        engine.result(JSONObject().put("run_id", id).put("command_id", read.getString("id")).put("status", "ok")
            .put("observation", page).put("data", JSONObject().put("device_profile", JSONObject().put("visual_gestures", true))))
        return engine to id
    }

    private fun propose(engine: DirectTaskEngine, kind: String) {
        val work = checkNotNull(engine.takeWork())
        assertFalse(work.vision)
        val action = JSONObject().put("kind", kind).put("target", "n0_1_0")
        if (kind == "type") action.put("text", "测试正文")
        engine.accept(work, JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls")
            .put("message", JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("function", JSONObject()
                .put("name", "action").put("arguments", action.toString()))))))))
    }

    @Test fun largeUnfocusedEditableInputTapDispatchesAgainstTheSemanticNode() {
        val (engine, id) = ready(screen())
        propose(engine, "tap")
        val command = engine.poll().getJSONObject("command")
        assertEquals("An editable input must not be mistaken for an unnamed canvas", "tap", command.getString("kind"))
        assertEquals("n0_1_0", command.getString("target"))
        assertEquals("current-editor-page", command.getString("screen_id"))
        assertFalse(command.optBoolean("include_screenshot"))
        assertEquals("running", engine.get(id).getString("status"))
        assertFalse(JSONArray(stored).getJSONObject(0).has("recovery_feedback"))
    }

    @Test fun equallyLargeNonEditableCanvasStillRequiresCurrentVisualLocalization() {
        val (engine, _) = ready(screen(editable = false))
        propose(engine, "tap")
        assertVisualRecovery(engine)
    }

    @Test fun longPressOnLargeUnnamedEditorRetainsTheExistingContainerProtection() {
        val (engine, _) = ready(screen())
        propose(engine, "long_press")
        assertVisualRecovery(engine)
    }

    @Test fun directTypeAlreadyUsesTheCurrentUnfocusedEditableNodeWithoutAnExtraTap() {
        val (engine, _) = ready(screen())
        propose(engine, "type")
        val command = engine.poll().getJSONObject("command")
        assertEquals("type", command.getString("kind"))
        assertEquals("n0_1_0", command.getString("target"))
        assertEquals("测试正文", command.getString("text"))
        assertFalse(command.optBoolean("include_screenshot"))
    }

    private fun assertVisualRecovery(engine: DirectTaskEngine) {
        val command = engine.poll().getJSONObject("command")
        assertEquals("observe", command.getString("kind"))
        assertTrue(command.getBoolean("include_screenshot"))
        val saved = JSONArray(stored).getJSONObject(0)
        assertEquals("unlocalized_container", saved.getJSONObject("recovery_feedback").getString("code"))
        assertFalse(saved.getJSONObject("recovery_feedback").getBoolean("action_executed"))
        assertEquals(0, saved.getInt("successful_mutations"))
    }
}

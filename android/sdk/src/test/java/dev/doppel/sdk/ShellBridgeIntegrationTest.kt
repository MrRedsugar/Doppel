package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ShellBridgeIntegrationTest {
    private fun state() = JSONObject().put("enabled", true).put("connected", true).put("uid", 2000)
    private fun node(id: String = "n1") = JSONObject().put("id", id).put("focused", true).put("editable", true)
        .put("password", false).put("text", "正文").put("description", "正文")
    private fun ime() = JSONObject().put("input_available", true).put("available", true).put("package_name", "dev.notes")
        .put("editor_id", "editor-session").put("action", "done")
    @Test fun shellAvailabilityRequiresExplicitEnableConnectionAndActualShellUid() {
        assertTrue(ShellBridgeIntegration.ready(state()))
        for (changed in listOf(state().put("enabled", false), state().put("connected", false), state().put("uid", 0), JSONObject()))
            assertFalse(ShellBridgeIntegration.ready(changed))
    }
    @Test fun focusedEditorReceivesCurrentImeSessionWithoutCopyingInputValues() {
        val nodes=JSONArray().put(node())
        ShellBridgeIntegration.attachIme(nodes,"dev.notes",ime().put("private_input", "must-not-copy"))
        assertEquals("editor-session",nodes.getJSONObject(0).getString("ime_editor_id"))
        assertEquals("done",nodes.getJSONObject(0).getString("ime_action"))
        assertFalse(nodes.toString().contains("must-not-copy"))
    }
    @Test fun wrongPackageUnfocusedOrAmbiguousEditorsGetNoCapability() {
        val cases=listOf(JSONArray().put(node().put("focused",false)),JSONArray().put(node()).put(node("n2")))
        for(nodes in cases) { ShellBridgeIntegration.attachIme(nodes,"dev.notes",ime()); assertFalse(nodes.toString().contains("ime_editor_id")) }
        val nodes=JSONArray().put(node()); ShellBridgeIntegration.attachIme(nodes,"other.app",ime())
        assertFalse(nodes.toString().contains("ime_editor_id"))
    }
    @Test fun protectedOrCodeFieldsNeverAdvertiseImeFallback() {
        for(item in listOf(node().put("password",true),node().put("description","验证码"),node().put("description","手机号"))) {
            val nodes=JSONArray().put(item);ShellBridgeIntegration.attachIme(nodes,"dev.notes",ime())
            assertFalse(nodes.toString().contains("ime_editor_id"))
        }
    }
    @Test fun absentActionCanAdvertiseInputButSendNeverBecomesDone() {
        for(action in listOf("", "send", "go")) {
            val nodes=JSONArray().put(node()); ShellBridgeIntegration.attachIme(nodes,"dev.notes",ime().put("action",action))
            assertTrue(nodes.getJSONObject(0).getBoolean("ime_input_available"))
            assertEquals("",nodes.getJSONObject(0).getString("ime_action"))
        }
    }
}

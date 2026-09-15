package dev.doppel.sdk
import org.json.JSONArray
import org.json.JSONObject
internal object ShellBridgeIntegration {
    fun ready(state: JSONObject) = state.optBoolean("enabled") && state.optBoolean("connected") && state.optInt("uid") == 2000
    fun attachIme(nodes: JSONArray, packageName: String, ime: JSONObject) {
        if (!ime.optBoolean("input_available") || ime.optString("package_name") != packageName || ime.optString("editor_id").isBlank()) return
        val focused = (0 until nodes.length()).mapNotNull { nodes.optJSONObject(it) }
            .filter { it.optBoolean("focused") && it.optBoolean("editable") }
        if (focused.size != 1) return
        val node=focused.single()
        val label=listOf("text", "description", "resource_id").joinToString(" ") { node.optString(it) }
        if(node.optBoolean("password") || Policy.financialCredential(label) || Policy.phoneInput(label)) return
        node.put("ime_editor_id",ime.optString("editor_id")).put("ime_input_available",true)
            .put("ime_action",ime.optString("action").takeIf { it in setOf("done","next") }.orEmpty())
    }
}

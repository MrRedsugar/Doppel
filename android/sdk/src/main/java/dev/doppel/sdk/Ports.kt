package dev.doppel.sdk

import org.json.JSONObject

interface ObservationProvider { fun observe(): JSONObject }
interface ActionExecutor { fun execute(command: JSONObject): JSONObject }
interface DeviceWorker { fun pause(); fun resume(); fun cancel() }
data class PluginDescriptor(val id: String, val version: Int, val capabilities: Set<String>, val inputSchema: JSONObject)
interface NativePlugin {
    val descriptor: PluginDescriptor
    fun invoke(input: JSONObject, cancellation: () -> Boolean): JSONObject
}

/** Registration describes capabilities; the host must authorize each invocation. */
class PluginRegistry {
    private val plugins = mutableMapOf<String, NativePlugin>()
    fun register(plugin: NativePlugin) {
        require(plugin.descriptor.version == 1 && plugin.descriptor.id.matches(Regex("[a-z][a-z0-9_.]{2,80}")))
        require(plugin.descriptor.capabilities.none { it in setOf("raw_coordinates", "payment", "password") })
        require(plugins.putIfAbsent(plugin.descriptor.id, plugin) == null)
    }
    fun invoke(id: String, input: JSONObject, granted: Set<String>, cancelled: () -> Boolean): JSONObject {
        val plugin = requireNotNull(plugins[id])
        require(granted.containsAll(plugin.descriptor.capabilities))
        check(!cancelled())
        return plugin.invoke(input, cancelled)
    }
}

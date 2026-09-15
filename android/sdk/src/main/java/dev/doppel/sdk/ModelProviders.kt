package dev.doppel.sdk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class ModelRequestTarget(val provider: ModelProvider, val selection: ModelSelection, val secret: ModelSecret, val vision: ModelVision, val fingerprint: String)
data class ModelResolved(val provider: ModelProvider, val selection: ModelSelection, val vision: ModelVision)

/** Only ciphertext is persisted, outside Android backup. No secret is exposed by public configuration APIs. */
class ModelProviders(context: Context) {
    private val app = context.applicationContext
    private val file get() = AtomicFile(File(app.noBackupFilesDir, "model-providers-v1.bin"))
    private val alias get() = "${app.packageName}.model.providers.v1"
    companion object { private val lock = Any() }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "模型连接无法解密，请重新配置" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun load(): JSONObject {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return initial()
        return try {
            val bytes = file.readFully()
            check(bytes.size in 29..1048576 && bytes[0].toInt() == 1)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(1, 13))) }
            val plain = cipher.doFinal(bytes.copyOfRange(13, bytes.size))
            try { JSONObject(String(plain, Charsets.UTF_8)) } finally { plain.fill(0) }
        } catch (_: Exception) { error("模型连接无法读取，请在模型设置中重置后重新配置") }
    }
    private fun write(state: JSONObject) {
        val plain = state.toString().toByteArray(Charsets.UTF_8)
        val encrypted = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(true)) }
            byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
        } finally { plain.fill(0) }
        val output = file.startWrite()
        try { output.write(encrypted); file.finishWrite(output) } catch (error: Exception) { file.failWrite(output); throw error }
    }
    private fun initial() = JSONObject().put("providers", JSONArray().put(providerJson(ModelProvider.qwen())))
        .put("routing", routingJson(ModelRouting.defaults()))
    private fun providerJson(provider: ModelProvider) = JSONObject().put("id", provider.id).put("name", provider.name).put("url", provider.baseUrl).put("preset", provider.preset)
    private fun provider(json: JSONObject) = ModelProvider(json.getString("id"), json.getString("name"), json.getString("url"), json.optString("preset", "custom"))
    private fun selection(json: JSONObject) = ModelSelection(json.getString("provider"), json.getString("model"))
    private fun selectionJson(selection: ModelSelection) = JSONObject().put("provider", selection.providerId).put("model", selection.model)
    private fun routing(state: JSONObject) = state.getJSONObject("routing").let { ModelRouting(selection(it.getJSONObject("primary")), it.optBoolean("enabled"), selection(it.getJSONObject("enhancement"))) }
    private fun routingJson(routing: ModelRouting) = JSONObject().put("primary", selectionJson(routing.primary)).put("enhancement", selectionJson(routing.enhancement)).put("enabled", routing.enhancementEnabled)
    private fun entries(state: JSONObject) = state.getJSONArray("providers").let { list -> (0 until list.length()).map(list::getJSONObject) }
    private fun entry(state: JSONObject, id: String) = entries(state).firstOrNull { it.optString("id") == id } ?: error("模型平台不存在，请重新选择")
    private fun vision(entry: JSONObject, model: String) = runCatching { ModelVision.valueOf(entry.optJSONObject("vision")?.optJSONObject(model)?.optString("state").orEmpty()) }.getOrDefault(ModelVision.UNKNOWN)
    private fun hasCredentials(entry: JSONObject) = entry.optString("key").isNotBlank() || (entry.optJSONObject("headers")?.length() ?: 0) > 0
    fun list(): List<ModelProvider> = synchronized(lock) { entries(load()).map(::provider) }
    fun routing(): ModelRouting = synchronized(lock) { routing(load()) }
    fun vision(providerId: String, model: String): ModelVision = synchronized(lock) { vision(entry(load(), providerId), model) }
    fun hasCredentials(providerId: String): Boolean = synchronized(lock) { hasCredentials(entry(load(), providerId)) }
    fun headerNames(providerId: String): List<String> = synchronized(lock) { entry(load(), providerId).optJSONObject("headers")?.keys()?.asSequence()?.toList().orEmpty() }
    fun resolve(role: String): ModelResolved = synchronized(lock) {
        val state = load(); val selected = routing(state).select(role); val record = entry(state, selected.providerId)
        ModelResolved(provider(record), selected, vision(record, selected.model))
    }
    fun isReady(): Boolean = runCatching { synchronized(lock) {
        val state = load(); val routing = routing(state)
        listOf(routing.primary, routing.select("grounding")).all { selected ->
            val record = entry(state, selected.providerId)
            selected.model.isNotBlank() && hasCredentials(record) && vision(record, selected.model) == ModelVision.VERIFIED
        }
    } }.getOrDefault(false)
    fun saveProvider(provider: ModelProvider, apiKey: String? = null, headers: Map<String, String>? = null): String = synchronized(lock) {
        require(provider.name.trim().length in 1..80) { "请输入平台名称" }
        val id = provider.id.ifBlank { UUID.randomUUID().toString() }
        require(id.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "平台标识无效" }
        val safe = provider.copy(id = id, name = provider.name.trim(), baseUrl = ModelEndpoint.normalize(provider.baseUrl))
        apiKey?.let { require(it.length <= 4096 && it.all { char -> char.code in 33..126 }) { "API Key 必须是单行文本" } }
        headers?.let { ModelHeaders.parse(JSONObject(it).toString()) }
        val state = load(); val old = entries(state).firstOrNull { it.optString("id") == id }
        check(old != null || entries(state).size < 30) { "最多保存 30 个平台" }
        if (old != null && ModelEndpoint.authenticationMustChange(old.getString("url"), safe.baseUrl)) {
            require(apiKey != null && headers != null) { "API 域名或端口已更换，请重新输入全部认证信息" }
        }
        val record = providerJson(safe).put("key", apiKey ?: old?.optString("key").orEmpty())
            .put("headers", headers?.let(::JSONObject) ?: old?.optJSONObject("headers") ?: JSONObject())
        val unchanged = old != null && old.optString("url") == safe.baseUrl && old.optString("preset") == safe.preset &&
            old.optString("key") == record.optString("key") && old.optJSONObject("headers").toString() == record.optJSONObject("headers").toString()
        if (unchanged) record.put("vision", old?.optJSONObject("vision") ?: JSONObject())
        state.put("providers", JSONArray(entries(state).filter { it.optString("id") != id } + record)); write(state); id
    }
    fun saveRouting(value: ModelRouting) = synchronized(lock) {
        val state = load()
        listOf(value.primary, value.enhancement).forEach { selected ->
            entry(state, selected.providerId)
            require(selected.model.length in 1..200 && selected.model.all { it.code in 33..126 }) { "请输入有效的模型名称" }
        }
        state.put("routing", routingJson(value)); write(state)
    }
    fun deleteProvider(id: String) = synchronized(lock) {
        val state = load(); val routing = routing(state)
        check(routing.primary.providerId != id && (!routing.enhancementEnabled || routing.enhancement.providerId != id)) { "请先为默认模型和视觉增强选择其他平台" }
        if (routing.enhancement.providerId == id) state.put("routing", routingJson(routing.copy(enhancement = routing.primary)))
        state.put("providers", JSONArray(entries(state).filter { it.optString("id") != id })); write(state)
    }
    private fun secret(record: JSONObject): ModelSecret {
        check(hasCredentials(record)) { "请先保存此平台的 API Key 或认证请求头" }
        return ModelSecret(record.optString("key"), ModelHeaders.parse(record.optJSONObject("headers")?.toString().orEmpty()))
    }
    internal fun requestTarget(providerId: String, model: String): ModelRequestTarget = synchronized(lock) {
        requestTarget(entry(load(), providerId), ModelSelection(providerId, model))
    }
    internal fun requestTarget(role: String): ModelRequestTarget = synchronized(lock) {
        val state = load(); val selected = routing(state).select(role)
        requestTarget(entry(state, selected.providerId), selected)
    }
    private fun requestTarget(record: JSONObject, selected: ModelSelection) = ModelRequestTarget(
        provider(record), selected, secret(record), vision(record, selected.model), fingerprint(record))
    internal fun recordVision(providerId: String, model: String, result: ModelVision, fingerprint: String) = synchronized(lock) {
        val state = load(); val record = entry(state, providerId)
        if (fingerprint(record) != fingerprint) return@synchronized
        val capabilities = record.optJSONObject("vision") ?: JSONObject()
        capabilities.put(model, JSONObject().put("state", result.name).put("checked_at", System.currentTimeMillis()))
        record.put("vision", capabilities); write(state)
    }
    private fun fingerprint(record: JSONObject): String = java.security.MessageDigest.getInstance("SHA-256").digest(
        listOf(record.optString("url"), record.optString("key"), record.optString("preset"), record.optJSONObject("headers").toString()).joinToString("\u0000").toByteArray()
    ).joinToString("") { "%02x".format(it) }
    fun importLegacyMimo(): ModelSelection {
        val legacy = DirectCredentials(app).readForExplicitMigration()
        val provider = ModelProvider.presets().first { it.id == "mimo" }
        saveProvider(provider, legacy, emptyMap())
        val selected = ModelSelection(provider.id, "mimo-v2.5-pro")
        saveRouting(ModelRouting(selected, false, selected)); return selected
    }
    fun reset() = synchronized(lock) {
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
        Unit
    }
}

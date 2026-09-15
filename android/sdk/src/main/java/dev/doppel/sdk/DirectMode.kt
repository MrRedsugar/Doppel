package dev.doppel.sdk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.Collections
import java.util.IdentityHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object DirectMode {
    private val owners = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    val settingsVisible: Boolean get() = synchronized(owners) { owners.isNotEmpty() }
    internal fun enterSettings(owner: Any) { synchronized(owners) { owners.add(owner) } }
    internal fun leaveSettings(owner: Any) { synchronized(owners) { owners.remove(owner) } }
    fun available(context: Context): Boolean {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
        return context.packageManager.getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA)
            .metaData?.getBoolean("dev.doppel.DEVELOPER_BUILD", false) == true
    }
    fun isDeveloperBuild(context: Context) = available(context)
    fun isEnabled(context: Context) = available(context) && context.getSharedPreferences("doppel", Context.MODE_PRIVATE).getBoolean("direct_mode", false)
    /** Finish first-time model setup without replacing an existing connection choice. */
    internal fun enableConfiguredDefault(context: Context) {
        if (!available(context)) return
        val prefs = context.getSharedPreferences("doppel", Context.MODE_PRIVATE)
        if (prefs.contains("direct_mode") || listOf("base_url", "token", "device_id", "active_run")
                .any { !prefs.getString(it, "").isNullOrBlank() }) return
        if (ModelProviders(context).isReady()) {
            FirstUseConsent.requireAccepted(context)
            configure(context, true)
        }
    }
    fun configure(context: Context, enabled: Boolean) {
        check(available(context)) { "直连模式仅供开发构建使用" }
        val gateway = Gateway(context)
        if (isEnabled(context) == enabled) {
            check(gateway.prefs.edit().putBoolean("direct_mode", enabled).commit()) { "连接配置保存失败" }
            return
        }
        val active = gateway.prefs.getString("active_run", "").orEmpty()
        if (active.isNotBlank()) {
            val status = gateway.request("GET", "/runs/$active").optString("status")
            check(status in setOf("completed", "failed", "cancelled")) { "请先结束当前任务再切换连接" }
        }
        check(!DirectRuntime.get(context).hasUnfinishedRun()) { "请先结束本机任务再切换连接" }
        if (enabled) check(ModelProviders(context).isReady()) { "请先配置模型连接，并验证默认模型与视觉增强支持图片" }
        val editor = gateway.prefs.edit().putBoolean("direct_mode", enabled).remove("active_run")
        if (enabled) {
            editor.putString("server_device_id", gateway.prefs.getString("device_id", ""))
            editor.putString("device_id", DirectRuntime.DEVICE_ID)
        } else editor.putString("device_id", gateway.prefs.getString("server_device_id", ""))
        check(editor.commit()) { "连接配置保存失败" }
    }
}

class DirectCredentials(private val context: Context) {
    private val prefs = context.getSharedPreferences("doppel_direct_credentials", Context.MODE_PRIVATE)
    private val alias = "${context.packageName}.developer.mimo.v1"
    fun hasKey(): Boolean = DirectMode.available(context) && prefs.contains("ciphertext")
    private fun key(create: Boolean): SecretKey {
        check(DirectMode.available(context)) { "直连凭据仅限开发构建" }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "凭据不可读取，请重新配置" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(value: String) {
        require(value.length in 16..512 && value.all { it.code in 33..126 }) { "API Key 格式无效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(true)) }
        val bytes = value.toByteArray(Charsets.UTF_8)
        try {
            val encrypted = cipher.doFinal(bytes)
            check(prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "凭据保存失败" }
        } finally { bytes.fill(0) }
    }
    internal fun read(): String {
        check(DirectMode.isEnabled(context)) { "本机直连模式未启用" }
        return decrypt()
    }
    /** An explicit settings action may copy this key only to the fixed legacy MiMo provider. */
    internal fun readForExplicitMigration(): String {
        check(DirectMode.available(context)) { "旧版凭据仅限开发构建" }
        return decrypt()
    }
    private fun decrypt(): String {
        return try {
            val iv = Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)
            val encrypted = Base64.decode(prefs.getString("ciphertext", ""), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, iv)) }
            val bytes = cipher.doFinal(encrypted)
            try { String(bytes, Charsets.UTF_8) } finally { bytes.fill(0) }
        } catch (_: Exception) { error("凭据不可读取，请重新配置 MiMo API Key") }
    }
    fun delete() { check(DirectMode.available(context)); check(!DirectMode.isEnabled(context)) { "请先关闭直连模式" }; check(prefs.edit().clear().commit()) }
}

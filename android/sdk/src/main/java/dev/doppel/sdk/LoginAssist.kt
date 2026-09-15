package dev.doppel.sdk

import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class LoginProfile(val packageName: String, val phone: String, val signature: String, val enabled: Boolean)

class LoginAssist(private val context: Context) {
    companion object {
        @Volatile var settingsVisible = false
        internal val session = LoginSession()
        private val cleanupHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private val cleanup = object : Runnable {
            override fun run() { if (session.expire()) cleanupHandler.postDelayed(this, 30_000) }
        }
        private fun scheduleCleanup() { cleanupHandler.removeCallbacks(cleanup); cleanupHandler.postDelayed(cleanup, 30_000) }
        fun redact(packageName: String, value: String) = session.redact(packageName, value)
        internal fun protectPassword(value: String) = session.protectPassword(value)
        internal fun containsPrivateValue(value: String) = session.containsPrivateValue(value)
        fun sensitiveSessionActive() = session.active()
        fun clearSession() = session.clear()
    }
    private val prefs = context.getSharedPreferences("doppel_login", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val alias = "${context.packageName}.login.v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun read(): JSONObject {
        val saved = prefs.getString("vault", null) ?: return JSONObject().put("profiles", JSONArray())
        try {
            val payload = JSONObject(saved)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(payload.getString("iv"), Base64.NO_WRAP)))
            return JSONObject(String(cipher.doFinal(Base64.decode(payload.getString("data"), Base64.NO_WRAP)), Charsets.UTF_8))
        } catch (_: Exception) { throw IllegalStateException("登录资料无法解密，请清除后重新设置") }
    }

    private fun write(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val body = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(cipher.doFinal(value.toString().toByteArray()), Base64.NO_WRAP))
        check(prefs.edit().putString("vault", body.toString()).commit()) { "登录资料保存失败" }
        clearSession()
    }

    fun commonPhone() = read().optString("phone")
    fun saveCommonPhone(phone: String) {
        require(phone.isEmpty() || phone.matches(Regex("\\+?[1-9][0-9]{7,14}"))) { "手机号格式不正确" }
        write(read().put("phone", phone))
    }
    fun profiles(): List<LoginProfile> {
        val values = read().optJSONArray("profiles") ?: JSONArray()
        return (0 until values.length()).map { values.getJSONObject(it) }.map {
            LoginProfile(it.getString("package"), it.optString("phone"), it.getString("signature"), it.optBoolean("enabled"))
        }
    }
    fun save(profile: LoginProfile) {
        require(profile.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) { "应用包名不正确" }
        require(profile.phone.isEmpty() || profile.phone.matches(Regex("\\+?[1-9][0-9]{7,14}"))) { "手机号格式不正确" }
        require(profile.signature.length in 2..40 && profile.signature.none { it.isISOControl() }) { "请填写短信中的服务名称" }
        val all = profiles().filter { it.packageName != profile.packageName } + profile
        require(all.size <= 100) { "应用数量已达到上限" }
        writeProfiles(all)
    }
    fun remove(packageName: String) = writeProfiles(profiles().filter { it.packageName != packageName })
    fun clearAll() { prefs.edit().clear().commit(); clearSession() }
    private fun writeProfiles(profiles: List<LoginProfile>) {
        val rows = JSONArray()
        profiles.forEach { rows.put(JSONObject().put("package", it.packageName).put("phone", it.phone).put("signature", it.signature).put("enabled", it.enabled)) }
        write(read().put("profiles", rows))
    }
    fun notificationAccess(): Boolean = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        .orEmpty().split(':').any { android.content.ComponentName.unflattenFromString(it)?.let { component ->
            component.packageName == context.packageName && component.className == LoginNotificationService::class.java.name
        } == true }

    /** Availability only. The run-bound code stays local and is not consumed by an observation. */
    internal fun taskStatus(packageName: String, runId: String? = null): JSONObject = runCatching {
        val profile = profiles().singleOrNull { it.packageName == packageName && it.enabled }
        val state = session.readiness(packageName, runId.orEmpty())
        JSONObject().put("package_name", packageName).put("enabled", profile != null)
            .put("phone_available", profile != null && (profile.phone.isNotBlank() || commonPhone().isNotBlank()))
            .put("notification_access", notificationAccess()).put("session_started", state.active)
            .put("code_ready", state.codeReady).put("code_state", state.state).put("expires_in_ms", state.expiresInMs)
    }.getOrElse { JSONObject().put("package_name", packageName).put("enabled", false).put("phone_available", false)
        .put("notification_access", notificationAccess()).put("session_started", false).put("code_ready", false).put("code_state", "inactive").put("expires_in_ms", 0) }

    fun valueFor(kind: String, packageName: String, runId: String): String? {
        require(kind in setOf("login_phone", "login_code"))
        val profile = profiles().firstOrNull { it.packageName == packageName && it.enabled }
            ?: throw IllegalStateException("请先在登录辅助中授权此应用")
        if (kind == "login_phone") {
            val phone = profile.phone.ifBlank { commonPhone() }
            check(phone.isNotEmpty()) { "请先设置常用手机号" }
            session.protectPassword(phone)
            session.begin(packageName, runId, phone, profile.signature)
            scheduleCleanup()
            return phone
        }
        check(notificationAccess()) { "请先授权短信通知访问，或手动输入验证码" }
        return session.consume(packageName, runId)
    }
}

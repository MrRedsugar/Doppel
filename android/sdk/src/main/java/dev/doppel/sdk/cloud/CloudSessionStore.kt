package dev.doppel.sdk.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.doppel.sdk.DirectMode
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials never appear in generated data-class strings or in host execution contexts. */
internal class CloudSession(
    baseUrl: String,
    val accountId: String,
    val sessionId: String,
    val token: String,
    allowLocalHttp: Boolean = false,
) {
    val baseUrl = validatedBaseUrl(baseUrl, allowLocalHttp)
    init {
        require(listOf(accountId, sessionId).all { it.length in 1..128 && it.all { c -> c.code in 33..126 } }) {
            "服务器会话身份无效"
        }
        require(token.length in 1..4096 && token.all { it.code in 33..126 }) { "服务器会话凭据无效" }
    }
    override fun toString() = "CloudSession([private])"

    companion object {
        internal fun validatedBaseUrl(value: String, allowLocalHttp: Boolean): String {
            val url = requireNotNull(value.toHttpUrlOrNull()) { "服务器地址无效" }
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "服务器地址不能包含凭据、查询参数或片段"
            }
            require(url.isHttps || (allowLocalHttp && url.host in setOf("127.0.0.1", "::1", "localhost", "10.0.2.2"))) {
                "服务器连接必须使用 HTTPS"
            }
            return url.toString().trimEnd('/')
        }
    }
}

/** One encrypted session, outside backups. Execution leases and account passwords are never stored. */
internal class CloudSessionStore(context: Context) {
    private val app = context.applicationContext
    private val file = AtomicFile(File(app.noBackupFilesDir, "cloud-session-v1.bin"))
    private val alias = "${app.packageName}.cloud.session.v1"
    private val allowLocalHttp = DirectMode.isDeveloperBuild(app)
    companion object { private val lock = Any() }

    fun load(): CloudSession? = synchronized(lock) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized null
        try {
            val encrypted = file.readFully()
            check(encrypted.size in 29..32768 && encrypted[0].toInt() == 1)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, encrypted.copyOfRange(1, 13)))
            }
            val plain = cipher.doFinal(encrypted.copyOfRange(13, encrypted.size))
            val state = try { JSONObject(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
            check(state.getInt("version") == 1 && state.has("session"))
            if (state.isNull("session")) null else state.getJSONObject("session").let {
                CloudSession(it.getString("base_url"), it.getString("account_id"), it.getString("session_id"),
                    it.getString("token"), allowLocalHttp)
            }
        } catch (_: Exception) { error("服务器会话无法读取，请重新登录") }
    }

    fun save(session: CloudSession) = synchronized(lock) {
        // Revalidate here: release builds must not restore a debug-only HTTP session.
        CloudSession.validatedBaseUrl(session.baseUrl, allowLocalHttp)
        write(JSONObject().put("base_url", session.baseUrl).put("account_id", session.accountId)
            .put("session_id", session.sessionId).put("token", session.token))
    }

    // A durable tombstone prevents a leftover AtomicFile backup from reviving a logged-out session.
    fun clear() = synchronized(lock) { write(null) }

    /** An old socket's revocation must never erase a newer successful login. */
    fun clearIfSession(sessionId: String): Boolean = synchronized(lock) {
        if (load()?.sessionId != sessionId) false else { write(null); true }
    }

    private fun write(session: JSONObject?) {
        val plain = JSONObject().put("version", 1).put("session", session ?: JSONObject.NULL)
            .toString().toByteArray(Charsets.UTF_8)
        val encrypted = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(true)) }
            byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
        } finally { plain.fill(0) }
        val output = file.startWrite()
        try { output.write(encrypted); file.finishWrite(output) }
        catch (failure: Exception) { file.failWrite(output); throw failure }
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "服务器会话密钥不可用" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}

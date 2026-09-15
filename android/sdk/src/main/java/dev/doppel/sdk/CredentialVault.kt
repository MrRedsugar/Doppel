package dev.doppel.sdk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Local encrypted vault. The PIN protects management; explicitly authorized tasks fill locally. */
class CredentialVault(context: Context) {
    data class Entry(val id: String, val packageName: String, val label: String, val username: String, val password: String, val allowTasks: Boolean = false)
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("doppel_credential_vault", Context.MODE_PRIVATE)
    private val file get() = File(app.noBackupFilesDir, "credential-vault-v1.bin")
    private val keyAlias get() = "${app.packageName}.credential.v1"
    private val pinAlias get() = "${app.packageName}.credential.pin.v1"
    private val random = SecureRandom()
    @Volatile private var unlockedUntil = 0L

    fun hasPin() = prefs.contains("pin_salt") && prefs.contains("pin_mac")
    fun isUnlocked() = unlockedUntil > android.os.SystemClock.elapsedRealtime()
    fun lock() { unlockedUntil = 0L }

    fun setPin(pin: String) {
        validatePin(pin)
        val salt = ByteArray(16).also(random::nextBytes)
        val mac = hmac(salt, pin)
        check(prefs.edit().putString("pin_salt", b64(salt)).putString("pin_mac", b64(mac)).putInt("pin_failures", 0).putLong("pin_next", 0).remove("pin_next_wall").remove("pin_boot").commit()) { "PIN 保存失败" }
        unlock(pin)
    }

    /** Returns false for a wrong PIN or a temporary lockout; never reveals which one. */
    fun unlock(pin: String): Boolean {
        validatePin(pin)
        val now = android.os.SystemClock.elapsedRealtime()
        val wallNow = System.currentTimeMillis()
        val boot = runCatching { android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)
        val savedBoot = prefs.getInt("pin_boot", -1)
        val remaining = CredentialPinCooldown.remaining(prefs.getInt("pin_failures", 0), prefs.getLong("pin_next", 0L),
            if (prefs.contains("pin_next_wall")) prefs.getLong("pin_next_wall", 0L) else null,
            savedBoot, now, wallNow, boot)
        if (remaining > 0L) {
            if (boot < 0 || savedBoot != boot || !prefs.contains("pin_next_wall")) {
                // Re-anchor after reboot (including clock rollback) so future calls
                // count down normally and cannot inherit days of previous uptime.
                check(prefs.edit().putLong("pin_next", now + remaining).putLong("pin_next_wall", wallNow + remaining)
                    .putInt("pin_boot", boot).commit()) { "PIN 冷却状态保存失败" }
            }
            return false
        }
        val salt = runCatching { Base64.decode(prefs.getString("pin_salt", ""), Base64.NO_WRAP) }.getOrNull() ?: return false
        val expected = runCatching { Base64.decode(prefs.getString("pin_mac", ""), Base64.NO_WRAP) }.getOrNull() ?: return false
        val actual = hmac(salt, pin)
        if (!java.security.MessageDigest.isEqual(expected, actual)) {
            val failures = (prefs.getInt("pin_failures", 0) + 1).coerceAtMost(10)
            val delay = CredentialPinCooldown.delay(failures)
            // Commit before returning: an immediate process kill must not discard
            // the failed attempt and bypass its cooldown.
            check(prefs.edit().putInt("pin_failures", failures).putLong("pin_next", now + delay)
                .putLong("pin_next_wall", wallNow + delay).putInt("pin_boot", boot).commit()) { "PIN 冷却状态保存失败" }
            return false
        }
        check(prefs.edit().putInt("pin_failures", 0).putLong("pin_next", 0).remove("pin_next_wall").remove("pin_boot").commit()) { "PIN 状态保存失败" }
        unlockedUntil = now + 5 * 60_000L
        return true
    }

    fun entries(): List<Entry> { requireUnlocked(); return readEntries() }
    private fun readEntries(): List<Entry> = read().optJSONArray("entries")?.let { rows ->
        (0 until rows.length()).mapNotNull { i -> rows.optJSONObject(i)?.let { row -> Entry(row.optString("id"), row.optString("package"), row.optString("label"), row.optString("username"), row.optString("password"), row.optBoolean("allow_tasks", false)) } }
    } ?: emptyList()
    /** Only names of explicitly authorized entries leave the vault, never usernames or passwords. */
    internal fun taskLabels(packageName: String): JSONArray = runCatching {
        val entries = readEntries().filter { it.packageName == packageName }
        // Restore local redaction after process recreation, before reading a still-filled app field.
        entries.forEach { LoginAssist.protectPassword(it.password) }
        JSONArray(entries.filter { it.allowTasks }.groupBy { it.label }
            .filterValues { it.size == 1 }.keys.map { JSONObject().put("package_name", packageName).put("credential_label", it) })
    }.getOrElse { JSONArray() }
    /** Management remains PIN-locked. A running host task may fill only an explicitly authorized native field. */
    internal fun fillForTask(packageName: String, label: String, node: android.view.accessibility.AccessibilityNodeInfo,
                             authorized: () -> Boolean): Boolean = runCatching {
        val keyguard = app.getSystemService(android.app.KeyguardManager::class.java)
        fun validTarget() = authorized() && !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked &&
            packageName != app.packageName && node.refresh() && node.packageName?.toString() == packageName &&
            node.isVisibleToUser && node.isEnabled && node.isEditable && node.isPassword &&
            !Policy.manualFinancial("${node.hintText?.toString().orEmpty()} ${node.contentDescription?.toString().orEmpty()} ${node.viewIdResourceName.orEmpty()}")
        if (!hasPin() || !validTarget()) return@runCatching false
        val matches = readEntries().filter { it.allowTasks && it.packageName == packageName && (label.isBlank() || it.label == label) }
        val entry = matches.singleOrNull() ?: return@runCatching false
        if (!validTarget()) return@runCatching false
        // Register before dispatch: the target can publish a text event immediately, even on a failed receipt.
        LoginAssist.protectPassword(entry.password)
        node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, entry.password)
        })
    }.getOrDefault(false)

    fun save(entry: Entry) {
        requireUnlocked(); require(entry.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) { "应用包名不正确" }
        require(entry.label.trim().length in 1..80) { "请输入名称" }; require(entry.username.length <= 256 && entry.password.length in 1..512) { "账号或密码格式不正确" }
        val id = entry.id.ifBlank { java.util.UUID.randomUUID().toString() }
        val rows = JSONArray(); entries().filter { it.id != id }.forEach { rows.put(json(it)) }; rows.put(json(entry.copy(id = id)))
        require(rows.length() <= 100) { "保存数量已达到上限" }; write(JSONObject().put("entries", rows))
    }
    fun remove(id: String) { requireUnlocked(); val rows = JSONArray(); entries().filter { it.id != id }.forEach { rows.put(json(it)) }; write(JSONObject().put("entries", rows)) }
    fun valueFor(packageName: String, label: String? = null): Entry? {
        requireUnlocked(); return entries().firstOrNull { it.packageName == packageName && (label.isNullOrBlank() || it.label == label) }
    }

    private fun requireUnlocked() { check(isUnlocked()) { "请先解锁密码管理" } }
    private fun validatePin(pin: String) { require(pin.matches(Regex("[0-9]{4}"))) { "PIN 必须是 4 位数字" } }
    private fun json(e: Entry) = JSONObject().put("id", e.id).put("package", e.packageName).put("label", e.label.trim()).put("username", e.username).put("password", e.password).put("allow_tasks", e.allowTasks)
    private fun read(): JSONObject {
        if (!file.exists()) return JSONObject().put("entries", JSONArray())
        return try {
            val bytes = file.readBytes(); check(bytes.size > 29 && bytes[0].toInt() == 1)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, aes(false), javax.crypto.spec.GCMParameterSpec(128, bytes.copyOfRange(1, 13))) }
            JSONObject(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8))
        } catch (_: Exception) { error("密码资料无法读取，请重新设置") }
    }
    private fun write(state: JSONObject) {
        val plain = state.toString().toByteArray(Charsets.UTF_8)
        val encrypted = try { val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, aes(true)) }; byteArrayOf(1) + cipher.iv + cipher.doFinal(plain) } finally { plain.fill(0) }
        val tmp = File(file.path + ".tmp"); tmp.writeBytes(encrypted); check(tmp.renameTo(file) || run { file.delete(); tmp.renameTo(file) }) { "密码资料保存失败" }
    }
    private fun aes(create: Boolean): SecretKey = keyStore().getKey(keyAlias, null) as? SecretKey ?: run {
        check(create) { "密码资料密钥不存在，请重新设置" }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()) }.generateKey()
    }
    private fun hmac(salt: ByteArray, pin: String): ByteArray = Mac.getInstance("HmacSHA256").apply { init(pinKey()) }.doFinal(salt + pin.toByteArray(Charsets.UTF_8))
    private fun pinKey(): SecretKey = keyStore().getKey(pinAlias, null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").apply { init(KeyGenParameterSpec.Builder(pinAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).setDigests(KeyProperties.DIGEST_SHA256).build()) }.generateKey()
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun b64(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
}

package dev.doppel.sdk

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Separate from the login vault: this is the owner-supplied SYSTEM lock credential. */
internal object AutomaticUnlockCredentials {
    private const val FAILURE_NOTIFICATION = 8322
    enum class Kind { PIN, PASSWORD }
    class Credential(val kind: Kind, val value: CharArray) : AutoCloseable {
        override fun close() { value.fill('\u0000') }
        override fun toString() = "AutomaticUnlockCredential(redacted)"
    }

    fun valid(kind: Kind, value: CharArray): Boolean = when (kind) {
        Kind.PIN -> value.size in 4..16 && value.all { it in '0'..'9' }
        Kind.PASSWORD -> value.size in 4..64 && value.all { it in ' '..'~' }
    }

    /** Saved ciphertext remains visible in settings even if its key has become unavailable. */
    @Synchronized fun hasSaved(context: Context): Boolean = file(context).baseFile.let {
        it.exists() || File(it.path + ".bak").exists()
    }

    @Synchronized fun isSuspended(context: Context): Boolean = state(context).getBoolean("suspended", false)

    @Synchronized fun isEnabled(context: Context): Boolean = hasSaved(context) && !isSuspended(context)

    fun isLanHandoffEnabled(context: Context): Boolean = state(context).getBoolean("lan_handoff", false)

    /** Enabling is called only after fresh system credential authentication in settings. */
    @Synchronized fun setLanHandoffEnabled(context: Context, enabled: Boolean) {
        if (enabled) { requireUnlocked(context); check(isEnabled(context)) { "请先启用自动解锁" } }
        val prefs = state(context)
        if (!prefs.edit().putBoolean("lan_handoff", enabled).commit()) {
            prefs.edit().putBoolean("lan_handoff", false).commit()
            error("未能保存免密码接管设置")
        }
    }

    /** Failure disables further automatic attempts without destroying the owner's saved password. */
    @Synchronized fun suspend(context: Context) {
        check(state(context).edit().putBoolean("suspended", true).commit()) { "无法保存自动解锁停用状态" }
    }

    /** A failed/uncertain attempt is different from manually switching off or updating settings. */
    @Synchronized fun suspendAfterFailedAttempt(context: Context) {
        val wasSuspended = isSuspended(context)
        suspend(context)
        if (wasSuspended) return
        // A denied notification permission must never undo the durable no-retry state.
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("task_pauses", "任务暂停原因", NotificationManager.IMPORTANCE_DEFAULT))
            if (!manager.areNotificationsEnabled()) return@runCatching
            val open = PendingIntent.getActivity(context, FAILURE_NOTIFICATION,
                Intent(context, AutomaticUnlockSettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            manager.notify(FAILURE_NOTIFICATION, Notification.Builder(context, "task_pauses")
                .setSmallIcon(UiIcons.history).setContentTitle("自动解锁已停用，等待你检查")
                .setContentText("本次解锁失败或被中断，已停止重试。保存的密码仍保留，请手动解锁后检查设置。")
                .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open)
                .setOnlyAlertOnce(true).setAutoCancel(true).build())
        }.onFailure { android.util.Log.w("DoppelAutoUnlock", "suspension_notification_unavailable") }
    }

    private fun clearFailureNotice(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(FAILURE_NOTIFICATION) }
    }

    /** Call close/use after use. Corrupt/unavailable credentials never produce a guessed password. */
    @Synchronized fun read(context: Context): Credential? {
        if (!isEnabled(context)) return null
        return readSaved(context)
    }

    /** For authenticated settings and an already started session's local owner verification only. */
    @Synchronized fun readSaved(context: Context): Credential? {
        if (!hasSaved(context)) return null
        return runCatching {
            val encrypted = file(context).readFully()
            check(encrypted.size in 30..1024 && encrypted[0].toInt() == 1)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(context, false), GCMParameterSpec(128, encrypted.copyOfRange(1, 13)))
            }
            val plain = cipher.doFinal(encrypted.copyOfRange(13, encrypted.size))
            try {
                val kind = Kind.entries.getOrNull(plain[0].toInt()) ?: error("invalid credential type")
                val decoded = Charsets.UTF_8.decode(ByteBuffer.wrap(plain, 1, plain.size - 1))
                val value = CharArray(decoded.remaining())
                try { decoded.get(value) } finally { if (decoded.hasArray()) decoded.array().fill('\u0000') }
                if (!valid(kind, value)) { value.fill('\u0000'); error("invalid credential") }
                Credential(kind, value)
            } finally { plain.fill(0) }
        }.getOrNull()
    }

    /** Only the settings activity calls this after fresh DEVICE_CREDENTIAL authentication. */
    @Synchronized fun save(context: Context, kind: Kind, value: CharArray) {
        require(valid(kind, value)) { "锁屏密码格式不支持" }
        requireUnlocked(context)
        // A failed write/enable leaves the existing or newly written ciphertext disabled and intact.
        suspend(context)
        val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(value))
        val plain = ByteArray(encoded.remaining() + 1).apply { this[0] = kind.ordinal.toByte() }
        try {
            encoded.get(plain, 1, plain.size - 1)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(context, true)) }
            val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
            val target = file(context)
            val output = target.startWrite()
            try { output.write(encrypted); target.finishWrite(output) } catch (e: Exception) { target.failWrite(output); throw e }
        } finally {
            plain.fill(0)
            if (encoded.hasArray()) encoded.array().fill(0)
        }
        reenable(context)
    }

    /** The caller must first complete fresh DEVICE_CREDENTIAL authentication in settings. */
    @Synchronized fun reenable(context: Context) {
        requireUnlocked(context)
        suspend(context)
        check(readSaved(context)?.use { true } == true) { "保存的密码无法读取，请重新填写" }
        val prefs = state(context)
        if (!prefs.edit().remove("suspended").remove("attempt").remove("protected").commit()) {
            // SharedPreferences updates memory before committing; restore the fail-closed value too.
            runCatching { suspend(context) }
            error("无法保存自动解锁启用状态，仍保持停用")
        }
        clearFailureNotice(context)
    }

    /** Explicit owner deletion only. Failure/recovery must call suspend instead. */
    @Synchronized fun clear(context: Context) {
        check(!AutomaticUnlockSession.active) { "请先结束自动任务再删除密码" }
        // Delete the key as well: residual filesystem blocks cannot decrypt the old credential.
        try { store().deleteEntry(alias(context)) } finally { file(context).delete() }
        check(state(context).edit().remove("suspended").remove("attempt").remove("protected").remove("lan_handoff").commit()) { "无法清除自动解锁设置" }
        clearFailureNotice(context)
    }

    private fun requireUnlocked(context: Context) {
        check(!AutomaticUnlockSession.active) { "请先结束自动任务再修改密码" }
        val lock = context.getSystemService(KeyguardManager::class.java)
        check(lock?.isDeviceSecure == true) { "请先设置系统锁屏密码" }
        check(!lock.isDeviceLocked && !lock.isKeyguardLocked) { "请先用系统锁屏密码解锁设备" }
    }
    private fun state(context: Context) = context.getSharedPreferences("doppel_automatic_unlock_state", Context.MODE_PRIVATE)
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "automatic-unlock-v1.bin"))
    private fun alias(context: Context) = "${context.packageName}.automatic-unlock.v1"
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(context: Context, create: Boolean): SecretKey = store().getKey(alias(context), null) as? SecretKey ?: run {
        check(create) { "credential unavailable" }
        // Must be usable while keyguard is locked; system auth protects configuration, not background reads.
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias(context), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}

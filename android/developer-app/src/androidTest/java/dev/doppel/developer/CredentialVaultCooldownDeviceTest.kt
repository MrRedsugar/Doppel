package dev.doppel.developer

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.CredentialVault
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Real preferences/Keystore; inject only a disposable vault's old clock metadata. No device clock changes. */
class CredentialVaultCooldownDeviceTest {
    @Test fun recreatedVaultRetainsCooldownAndOldBootDeadlineCannotLockOutForDays() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "vault-cooldown-${UUID.randomUUID()}-"
        val names = mutableSetOf<String>()
        val directory = File(context.cacheDir, prefix).apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName() = context.packageName + ".fixture" + prefix.filter(Char::isLetterOrDigit)
            override fun getNoBackupFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                names += prefix + name
                return context.getSharedPreferences(prefix + name, mode)
            }
        }
        val prefs = isolated.getSharedPreferences("doppel_credential_vault", 0)
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val aliases = listOf("${isolated.packageName}.credential.v1", "${isolated.packageName}.credential.pin.v1")
        val checks = JSONObject().put("actual_device_reboot", false).put("clock_metadata_fixture", true)
        var passed = false
        try {
            assertTrue(aliases.none(keys::containsAlias))
            val vault = CredentialVault(isolated)
            vault.setPin("7352")
            vault.save(CredentialVault.Entry("cooldown-fixture", "dev.doppel.fixture", "Cooldown fixture", "fixture-user", "fixture-only-secret"))
            val file = File(directory, "credential-vault-v1.bin")
            val ciphertext = file.readBytes()
            vault.lock()
            assertFalse(vault.unlock("7353"))
            assertEquals(1, prefs.getInt("pin_failures", 0))
            assertFalse("A new vault instance must retain the persisted wrong-PIN penalty", CredentialVault(isolated).unlock("7352"))
            checks.put("new_instance_retains_penalty", true)

            // Reproduce the old format after long uptime, without touching any real user's vault.
            assertTrue(prefs.edit().putLong("pin_next", SystemClock.elapsedRealtime() + 7 * 86_400_000L)
                .remove("pin_boot").remove("pin_next_wall").commit())
            assertFalse(CredentialVault(isolated).unlock("7352"))
            assertTrue("Legacy deadlines must be migrated to at most this failure's 30-second penalty",
                prefs.getLong("pin_next", 0L) - SystemClock.elapsedRealtime() in 0L..30_000L)
            assertTrue(prefs.contains("pin_next_wall"))
            assertEquals("Migration must retain the anti-guessing failure count", 1, prefs.getInt("pin_failures", 0))
            assertArrayEquals("Cooldown migration must not alter the credential ciphertext", ciphertext, file.readBytes())
            checks.put("legacy_deadline_bounded_and_ciphertext_unchanged", true)

            val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            assertTrue("This emulator must expose a boot count for the cross-boot regression", boot >= 0)
            assertTrue(prefs.edit().putInt("pin_boot", boot - 1)
                .putLong("pin_next", SystemClock.elapsedRealtime() + 7 * 86_400_000L)
                .putLong("pin_next_wall", System.currentTimeMillis() - 1_000L).commit())
            val afterReboot = CredentialVault(isolated)
            assertTrue("Expired previous-boot wall deadline must allow the correct PIN immediately", afterReboot.unlock("7352"))
            assertTrue(afterReboot.entries().single().id == "cooldown-fixture")
            assertArrayEquals(ciphertext, file.readBytes())
            checks.put("expired_previous_boot_unlocks_without_data_loss", true)

            afterReboot.lock()
            assertFalse(afterReboot.unlock("7353"))
            assertTrue(prefs.edit().putInt("pin_boot", boot - 1)
                .putLong("pin_next_wall", System.currentTimeMillis() + 7 * 86_400_000L).commit())
            assertFalse(CredentialVault(isolated).unlock("7352"))
            val anchored = prefs.getLong("pin_next", 0L)
            assertTrue("A backward wall-clock jump on reboot must not extend the penalty past its cap",
                anchored - SystemClock.elapsedRealtime() in 0L..30_000L)
            assertEquals(boot, prefs.getInt("pin_boot", -1))
            assertFalse(CredentialVault(isolated).unlock("7352"))
            assertEquals("Same-boot reopening must not restart the cooldown", anchored, prefs.getLong("pin_next", 0L))
            assertArrayEquals(ciphertext, file.readBytes())
            checks.put("rollback_reanchors_once_and_reopen_does_not_reset_timer", true)
            passed = true
        } finally {
            aliases.forEach { if (keys.containsAlias(it)) keys.deleteEntry(it) }
            names.forEach { context.deleteSharedPreferences(it) }
            directory.deleteRecursively()
            val folder = File(context.getExternalFilesDir(null), "full-feature/vault-cooldown").apply { mkdirs() }
            File(folder, "result.json").writeText(checks.put("ok", passed).toString(2))
        }
    }
}

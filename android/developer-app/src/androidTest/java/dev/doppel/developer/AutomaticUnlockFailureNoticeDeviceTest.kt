@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.doppel.developer

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.AutomaticUnlockCredentials
import dev.doppel.sdk.AutomaticUnlockSession
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Keystore, isolated ciphertext/preferences and actual Android notifications; no credential injection.
 * Host supplies an already-unlocked secure emulator and temporarily grants POST_NOTIFICATIONS.
 */
class AutomaticUnlockFailureNoticeDeviceTest {
    @Test fun failedAttemptNotifiesOnceRetainsCiphertextAndExplicitReenableClearsNotice() {
        val inst = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("unlock_notice_test") == "true")
        val context = inst.targetContext
        val lock = context.getSystemService(KeyguardManager::class.java)
        assertTrue("Host must provide its unlocked secure fixture", lock.isDeviceSecure && !lock.isDeviceLocked && !lock.isKeyguardLocked)
        assertFalse(AutomaticUnlockSession.active)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue("Host controls and restores notification permission", manager.areNotificationsEnabled())
        assertTrue("Preserve a real owner's existing failure reminder", manager.activeNotifications.none { it.id == 8322 })
        val prefix = "unlock-notice-${UUID.randomUUID()}-"
        val names = mutableSetOf<String>()
        val folder = File(context.cacheDir, prefix).apply { check(mkdirs()) }
        // Keep the real package so the actual notification opens the real settings Activity.
        // The shared Keystore alias is preserved if pre-existing; ciphertext and state are isolated.
        val isolated = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = folder
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                names += prefix + name
                return context.getSharedPreferences(prefix + name, mode)
            }
        }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "${context.packageName}.automatic-unlock.v1"
        val hadKey = keys.containsAlias(alias)
        val report = JSONObject().put("passed", false).put("model_requests", 0).put("system_password_attempts", 0)
        fun notice() = manager.activeNotifications.singleOrNull { it.id == 8322 }
        fun await(message: String, condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 6000
            while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
            assertTrue(message, condition())
        }
        try {
            val pin = "681429".toCharArray()
            try { AutomaticUnlockCredentials.save(isolated, AutomaticUnlockCredentials.Kind.PIN, pin) }
            finally { pin.fill('\u0000') }
            val file = File(folder, "automatic-unlock-v1.bin")
            val original = file.readBytes()
            assertNull("Successful setup must not post a failure notice", notice())
            val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
            val start = CountDownLatch(1); val done = CountDownLatch(4)
            repeat(4) {
                Thread {
                    try { start.await(); AutomaticUnlockCredentials.suspendAfterFailedAttempt(isolated) }
                    catch (failure: Throwable) { failures.add(failure) }
                    finally { done.countDown() }
                }.start()
            }
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS)); assertTrue(failures.isEmpty())
            await("Failure transition must post an actual notification") { notice() != null }
            val first = requireNotNull(notice())
            assertTrue(first.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("自动解锁已停用"))
            assertTrue(first.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("已停止重试"))
            assertFalse(first.notification.extras.toString().contains("681429"))
            assertNotNull(first.notification.contentIntent)
            assertEquals(Notification.VISIBILITY_PRIVATE, first.notification.visibility)
            assertTrue(AutomaticUnlockCredentials.hasSaved(isolated))
            assertTrue(AutomaticUnlockCredentials.isSuspended(isolated))
            assertFalse(AutomaticUnlockCredentials.isEnabled(isolated))
            assertNull(AutomaticUnlockCredentials.read(isolated))
            assertArrayEquals(original, file.readBytes())
            AutomaticUnlockCredentials.readSaved(isolated)?.close() ?: fail("Saved ciphertext/key must remain readable")
            SystemClock.sleep(250)
            AutomaticUnlockCredentials.suspendAfterFailedAttempt(isolated)
            SystemClock.sleep(250)
            assertEquals("Further cleanup/recovery cannot post a second alert", first.postTime, notice()?.postTime)
            AutomaticUnlockCredentials.reenable(isolated)
            await("Explicit reenable must remove the stale failure reminder") { notice() == null }
            assertTrue(AutomaticUnlockCredentials.isEnabled(isolated))
            assertArrayEquals(original, file.readBytes())
            AutomaticUnlockCredentials.suspend(isolated)
            assertNull("A manual disable is not an automatic failure", notice())
            report.put("passed", true).put("concurrent_failures_one_notice", true).put("repeated_failure_same_notification", true)
                .put("ciphertext_retained", true).put("background_read_disabled", true).put("reenable_clears_notice", true)
                .put("successful_setup_and_manual_disable_silent", true)
        } finally {
            manager.cancel(8322)
            names.forEach { context.deleteSharedPreferences(it) }
            if (!hadKey && keys.containsAlias(alias)) keys.deleteEntry(alias)
            check(folder.canonicalFile.parentFile == context.cacheDir.canonicalFile); check(folder.deleteRecursively())
            val evidence = File(context.getExternalFilesDir(null), "automatic-unlock-failure-notice").apply { mkdirs() }
            File(evidence, "result.json").writeText(report.toString(2))
        }
    }
}

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.doppel.developer

import android.app.KeyguardManager
import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Host: seed -> wait for READY -> force-stop within 12s -> restore original accessibility grant -> verify -> cleanup.
 * Tests the actual production notice window/timer, not a running task or automatic password input.
 * Only a proven terminal active_run pointer is isolated; its recovery manifest remains device-private.
 */
class AutomaticTaskNoticeRestartDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val ui by lazy { inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }
    private val prefs get() = context.getSharedPreferences("doppel", 0)
    private val directory get() = File(context.noBackupFilesDir, "qa-automatic-notice-restart-v1")
    private val manifest get() = File(directory, "manifest.json")
    private val evidence get() = File(context.getExternalFilesDir(null), "automatic-notice-restart").apply { mkdirs() }
    private val protectedPaths = listOf("direct-runs-v1.json", "schedules-v1.json")
    private fun meta() = JSONObject(manifest.readText())
    private fun atomic(file: File, json: JSONObject) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val target = AtomicFile(file); val stream = target.startWrite()
        try { stream.write(json.toString(2).toByteArray()); target.finishWrite(stream) }
        catch (error: Throwable) { target.failWrite(stream); throw error }
    }
    private fun await(message: String, timeout: Long = 6000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
        assertTrue(message, condition())
    }
    private fun requireOptIn() {
        assumeTrue("Use -e notice_restart true -e emulatorOnly true", args.getString("notice_restart") == "true")
        assertEquals("true", args.getString("emulatorOnly"))
        val emulator = android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("getprop ro.boot.qemu"))
            .use { String(it.readBytes()).trim() }
        assertEquals("Never force-stop a physical phone for this fixture", "1", emulator)
    }
    private fun hash(path: String): String {
        val file = File(context.noBackupFilesDir, path)
        if (!file.exists()) return "absent"
        return MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }
    private fun shot(name: String) {
        val image = requireNotNull(ui.takeScreenshot())
        try { File(evidence, "$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
    }
    private fun assertNoTaskChanges(m: JSONObject) {
        protectedPaths.forEach { assertEquals("Task/schedule data must remain byte-identical", m.getJSONObject("hashes").getString(it), hash(it)) }
        assertEquals(m.getString("trigger_rules"), context.getSharedPreferences("doppel_auto_triggers", 0).getString("rules", "").orEmpty())
        assertNull(DeviceWorkerService.instance)
        assertFalse(TaskSubmissionGate.creating.get())
    }
    private fun restore() {
        if (!manifest.exists()) return
        val m = meta()
        inst.runOnMainSync {
            AutomaticTaskNotice.dismiss(m.getString("key"))
            AutomaticTaskNotice.dismiss(m.getString("key") + "-fresh")
        }
        assertNoTaskChanges(m)
        val previous = m.getString("active_run")
        assertTrue("Never replace a new task pointer", prefs.getString("active_run", "").orEmpty() in setOf("", previous))
        check(prefs.edit().apply { if (m.getBoolean("had_active")) putString("active_run", previous) else remove("active_run") }.commit())
        assertEquals(m.getBoolean("had_active"), prefs.contains("active_run"))
        assertEquals(previous, prefs.getString("active_run", "").orEmpty())
    }

    @Test fun seed() {
        requireOptIn()
        assertFalse("Run cleanup before another seed", manifest.exists())
        assertNull(DeviceWorkerService.instance)
        assertFalse(TaskSubmissionGate.creating.get())
        assertFalse(AutomaticUnlockSession.active)
        assertTrue(FirstUseConsent.isAccepted(context))
        assertTrue(Settings.canDrawOverlays(context))
        val lock = context.getSystemService(KeyguardManager::class.java)
        assertFalse("This test never unlocks the device", lock.isDeviceLocked || lock.isKeyguardLocked)
        assertTrue(AutoTriggerStore(context).list().none { it.enabled })
        val runs = File(context.noBackupFilesDir, "direct-runs-v1.json").takeIf(File::exists)?.readText()?.let(::JSONArray) ?: JSONArray()
        repeat(runs.length()) { assertTrue("Leave unfinished tasks untouched", runs.getJSONObject(it).optString("status") in setOf("completed", "failed", "cancelled")) }
        val previous = prefs.getString("active_run", "").orEmpty()
        assertTrue("Isolate only a known terminal pointer", previous.isBlank() || (0 until runs.length()).any { runs.getJSONObject(it).optString("id") == previous })
        val schedules = File(context.noBackupFilesDir, "schedules-v1.json").takeIf(File::exists)?.readText()?.let(::JSONObject)?.optJSONArray("items") ?: JSONArray()
        repeat(schedules.length()) { assertFalse("Leave enabled schedules untouched", schedules.getJSONObject(it).optBoolean("enabled")) }
        val key = "notice-restart-${UUID.randomUUID()}"
        val m = JSONObject().put("key", key).put("seed_pid", Process.myPid())
            .put("boot", Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1))
            .put("had_active", prefs.contains("active_run")).put("active_run", previous)
            .put("trigger_rules", context.getSharedPreferences("doppel_auto_triggers", 0).getString("rules", "").orEmpty())
            .put("hashes", JSONObject().apply { protectedPaths.forEach { put(it, hash(it)) } })
        atomic(manifest, m)
        try {
            check(prefs.edit().remove("active_run").commit())
            if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
            assertNull(AutomaticTaskNotice.localBlockReason(context))
            val shown = AtomicLong()
            inst.runOnMainSync {
                assertTrue(AutomaticTaskNotice.show(context, key, "自动预告重启验证", "测试预告，强停后不得重放；不会创建任务",
                    valid = { true }, onExecute = { atomic(File(directory, "old-executed.json"), JSONObject().put("at", SystemClock.elapsedRealtime())) },
                    onSkip = {}, onShown = { shown.set(SystemClock.elapsedRealtime()) }))
            }
            await("The real notice must attach") { shown.get() > 0 }
            atomic(manifest, m.put("shown_at", shown.get()).put("ready", true))
            shot("01-before-process-stop")
            atomic(File(evidence, "seed.json"), JSONObject().put("ready", true).put("pid", Process.myPid())
                .put("shown_at", shown.get()).put("deadline", shown.get() + 15000).put("created_tasks", 0))
            inst.sendStatus(0, Bundle().apply { putString("automatic_notice_restart_ready", key); putInt("notice_restart_pid", Process.myPid()) })
            SystemClock.sleep(12000)
            fail("Host must force-stop while the original notice is still counting down")
        } finally { restore() }
    }

    @Test fun verify() {
        requireOptIn()
        val m = meta()
        assertTrue(m.optBoolean("ready"))
        assertNotEquals("Actual process death is required", m.getInt("seed_pid"), Process.myPid())
        assertEquals("This case tests process death, not reboot", m.getInt("boot"), Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1))
        val report = JSONObject().put("passed", false).put("model_requests", 0).put("created_tasks", 0)
        try {
            assertNoTaskChanges(m)
            assertTrue(prefs.getString("active_run", "").isNullOrBlank())
            if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
            assertNull(AutomaticTaskNotice.localBlockReason(context))
            assertFalse("Cold start cannot inherit an old approval", AutomaticTaskNotice.approved(m.getString("key")))
            val deadline = m.getLong("shown_at") + 16000
            while (SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(minOf(1000, deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1))
            assertFalse("The killed notice must never execute after its old deadline", File(directory, "old-executed.json").exists())
            val shown = AtomicLong(); val executed = AtomicLong(); val calls = AtomicInteger()
            inst.runOnMainSync {
                assertTrue("A new occurrence must be allowed to show a new notice", AutomaticTaskNotice.show(context, m.getString("key") + "-fresh",
                    "新的自动预告", "重启后重新评估，必须完整等待 15 秒", valid = { true },
                    onExecute = { calls.incrementAndGet(); executed.set(SystemClock.elapsedRealtime()) }, onSkip = {},
                    onShown = { shown.set(SystemClock.elapsedRealtime()) }))
            }
            await("New notice must be actually attached") { shown.get() > 0 }
            shot("02-fresh-full-countdown")
            while (SystemClock.elapsedRealtime() < shown.get() + 12000) SystemClock.sleep(100)
            assertEquals("No shortened countdown after process restart", 0, calls.get())
            await("The fresh full countdown must execute once", 6000) { calls.get() == 1 }
            assertTrue(executed.get() - shown.get() >= 15000)
            SystemClock.sleep(1200)
            assertEquals(1, calls.get())
            assertFalse(File(directory, "old-executed.json").exists())
            assertNoTaskChanges(m)
            report.put("passed", true).put("old_approval_replayed", false).put("old_callback_replayed", false)
                .put("fresh_countdown_ms", executed.get() - shown.get()).put("fresh_callback_count", calls.get()).put("pid_changed", true)
        } finally {
            try { restore(); report.put("active_pointer_restored", true) }
            finally { atomic(File(evidence, "verify.json"), report) }
        }
    }

    @Test fun cleanup() {
        requireOptIn()
        if (!manifest.exists()) return
        restore()
        atomic(File(evidence, "cleanup.json"), JSONObject().put("restored", true))
        check(directory.canonicalFile == File(context.noBackupFilesDir.canonicalFile, "qa-automatic-notice-restart-v1"))
        check(directory.deleteRecursively())
    }
}

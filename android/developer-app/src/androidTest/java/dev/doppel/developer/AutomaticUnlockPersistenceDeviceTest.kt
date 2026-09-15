package dev.doppel.developer

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Host runs seed, force-stops the app, then runs verify. Only the public emulator PIN is used. */
class AutomaticUnlockPersistenceDeviceTest {
    @get:org.junit.Rule val terminalTaskPointer: org.junit.rules.TestRule = TerminalTaskPointerTestRule()
    @Test fun suspensionSurvivesRealProcessRestart() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val mode = InstrumentationRegistry.getArguments().getString("unlock_persistence_stage")
        assumeTrue(mode in setOf("seed", "verify"))
        val context = inst.targetContext
        assertEquals(34, Build.VERSION.SDK_INT)
        assertTrue("Only the LDPlayer fixture may run this test", Build.MODEL.contains("sdk") ||
            android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand("getprop ro.boot.qemu"))
                .use { String(it.readBytes()).trim() } == "1")
        val prefs = context.getSharedPreferences("doppel", Context.MODE_PRIVATE)
        assertTrue("Never interrupt a task", prefs.getString("active_run", "").isNullOrEmpty())
        val lock = context.getSystemService(KeyguardManager::class.java)
        assertTrue("Host must leave the temporary system PIN unlocked", lock.isDeviceSecure && !lock.isDeviceLocked)
        val credentials = Class.forName("dev.doppel.sdk.AutomaticUnlockCredentials").getField("INSTANCE").get(null)
        fun call(name: String, vararg args: Any?): Any? = credentials.javaClass.declaredMethods.single {
            it.name == name && it.parameterCount == args.size && !java.lang.reflect.Modifier.isStatic(it.modifiers)
        }.invoke(credentials, *args)
        val fixture = context.getSharedPreferences("unlock_retention_restart_fixture", Context.MODE_PRIVATE)
        val cipher = File(context.noBackupFilesDir, "automatic-unlock-v1.bin")
        fun hash() = MessageDigest.getInstance("SHA-256").digest(cipher.readBytes()).joinToString("") { "%02x".format(it) }
        if (mode == "seed") {
            assertTrue("Do not overwrite an existing fixture", fixture.all.isEmpty())
            assertFalse("Do not overwrite any saved configuration", cipher.exists())
            assertEquals(false, call("hasSaved", context))
            val kind = Class.forName("dev.doppel.sdk.AutomaticUnlockCredentials\$Kind").enumConstants.single { (it as Enum<*>).name == "PIN" }
            val pin = "681429".toCharArray()
            try { call("save", context, kind, pin) } finally { pin.fill('\u0000') }
            call("suspend", context)
            assertEquals(true, call("isSuspended", context))
            assertTrue(fixture.edit().putInt("pid", Process.myPid()).putString("sha256", hash()).commit())
        } else {
            assertTrue("Only clean up the configuration created by seed", fixture.contains("pid"))
            try {
                assertNotEquals("The app must have really restarted", fixture.getInt("pid", -1), Process.myPid())
                assertEquals("Ciphertext must survive unchanged", fixture.getString("sha256", ""), hash())
                assertEquals(true, call("hasSaved", context))
                assertEquals(true, call("isSuspended", context))
                assertEquals(false, call("isEnabled", context))
                assertNull("Background reads remain disabled after restart", call("read", context))
                val saved = call("readSaved", context) as? AutoCloseable
                assertNotNull("The retained key must still decrypt for owner confirmation", saved)
                saved?.close()
                val folder = File(context.getExternalFilesDir(null), "automatic-unlock-retention").apply { mkdirs() }
                File(folder, "restart.json").writeText(JSONObject().put("process_changed", true)
                    .put("ciphertext_unchanged", true).put("retained_key_readable", true)
                    .put("suspended_after_restart", true).put("background_read_blocked", true).toString(2))
            } finally {
                // This is the fixture owner's explicit deletion, never failure recovery.
                call("clear", context)
                fixture.edit().clear().commit()
            }
            assertFalse(cipher.exists())
            assertEquals(false, call("hasSaved", context))
            assertNull(call("readSaved", context))
        }
    }
}

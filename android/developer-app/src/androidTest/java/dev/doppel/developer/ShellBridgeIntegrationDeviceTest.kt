package dev.doppel.developer

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ComponentName
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DirectRuntime
import dev.doppel.sdk.DoppelAccessibilityService
import dev.doppel.sdk.ShellBridgeClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.UUID

/** Opt-in emulator-only backend reads; setup may rebind only this app's accessibility component. */
class ShellBridgeIntegrationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation by lazy { instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }

    @Before fun requireExplicitIdleEmulator() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit shell_bridge_live=true",args.getString("shell_bridge_live") == "true")
        check(args.getString("emulatorOnly") == "true") { "Requires explicit emulatorOnly=true" }
        ShellBridgeSdkReflection.verifyContract()
        verifyEmulator()
        check(!DirectRuntime.get(context).hasUnfinishedRun()) { "Finish existing tasks before this isolated backend smoke" }
        check(DeviceWorkerService.instance == null) { "Stop the existing worker before this isolated backend smoke" }
    }

    @Test fun connectedBackendAndHostScreenshotHaveCurrentProvenance() {
        val client=ShellBridgeClient.get(context)
        val state=client.status()
        assertTrue("Activate the bridge with the opt-in developer setup first; diagnostic=${state.optJSONObject("connection_error")}",state.optBoolean("enabled") && state.optBoolean("connected"))
        assertEquals(2000,state.getInt("uid"))
        assertFalse(state.getBoolean("arbitrary_shell"))
        withBoundAccessibility { service ->
            val command=JSONObject().put("id","bridge-read-"+UUID.randomUUID()).put("run_id","bridge-read-only-smoke")
                .put("kind","observe").put("include_screenshot",true)
            val result=service.execute(command)
            assertEquals("Host screenshot status: ${result.optString("message")}","ok",result.getString("status"))
            val data=result.getJSONObject("data")
            assertEquals("adb_shell",data.getString("capture_backend"))
            val frame=data.getJSONObject("visual_frame")
            assertEquals(result.getJSONObject("observation").getString("screen_id"),frame.getString("screen_id"))
            assertEquals(result.getJSONObject("observation").getString("package_name"),frame.getString("package_name"))
            assertTrue(frame.getString("capture_id").isNotBlank())
            assertTrue(data.getString("image_base64").isNotBlank())
        }
    }

    @Test fun changedSourceRejectsBeforeAKeyCanReachTheHelper() {
        val client=ShellBridgeClient.get(context)
        assertTrue(client.status().optBoolean("connected"))
        val source=JSONObject().put("screen_id","old").put("package_name","dev.notes").put("width",1440).put("height",3200).put("rotation",0)
            .put("captured_at",android.os.SystemClock.elapsedRealtime())
        val result=client.executeAuthorized("bridge-rejected-"+UUID.randomUUID(),"bridge-read-only-smoke",source,"back",JSONObject(),
            { JSONObject(source.toString()).put("screen_id","changed") }, { true })
        assertEquals("stale",result.getString("status"))
        assertEquals("not_dispatched",result.getString("action_state"))
        assertEquals("source_changed",result.getString("reason_code"))
    }

    private fun withBoundAccessibility(block:(DoppelAccessibilityService)->Unit) {
        // Instrumentation can restart the target process. First allow the existing system binding to recover.
        if (await(2000) { DoppelAccessibilityService.instance != null }) {
            block(requireNotNull(DoppelAccessibilityService.instance));return
        }
        check(!DirectRuntime.get(context).hasUnfinishedRun() && DeviceWorkerService.instance == null) { "A task appeared; do not rebind its service" }
        val own=ComponentName(context,DoppelAccessibilityService::class.java)
        val savedServices=readSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val savedGlobal=readSetting(Settings.Secure.ACCESSIBILITY_ENABLED)
        val original=components(savedServices)
        val originalOwn=original.filter { ComponentName.unflattenFromString(it) == own }
        val originalOthers=original.filter { ComponentName.unflattenFromString(it) != own }
        var touched=false
        var globalTouched=false
        var failure:Throwable?=null
        try {
            touched=true
            writeSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,withoutOwn(own).joinToString(":"))
            val removedAt=SystemClock.elapsedRealtime()
            check(await(4000) { SystemClock.elapsedRealtime()-removedAt>=400 && DoppelAccessibilityService.instance == null && !systemLists(own) }) {
                "Doppel accessibility removal was not acknowledged"
            }
            writeSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,(withoutOwn(own)+own.flattenToString()).joinToString(":"))
            globalTouched=true
            writeSetting(Settings.Secure.ACCESSIBILITY_ENABLED,"1")
            check(await(8000) { DoppelAccessibilityService.instance != null }) { "Doppel accessibility did not rebind within 8 seconds" }
            block(requireNotNull(DoppelAccessibilityService.instance))
        } catch(error:Throwable) { failure=error;throw error
        } finally {
            if(touched) {
                val currentOthers=withoutOwn(own)
                val currentGlobal=readSetting(Settings.Secure.ACCESSIBILITY_ENABLED)
                // Restore exact originals when isolated; merge only our entry if another service changed meanwhile.
                val unchangedOthers=currentOthers==originalOthers
                val restoreServices=if(unchangedOthers) savedServices else (currentOthers+originalOwn).joinToString(":")
                var cleanupFailure:Throwable?=null
                fun restore(action:()->Unit) { try { action() } catch(error:Throwable) {
                    if(cleanupFailure==null) cleanupFailure=error else cleanupFailure!!.addSuppressed(error)
                } }
                restore { writeSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,restoreServices) }
                if(unchangedOthers && globalTouched && currentGlobal=="1") restore { writeSetting(Settings.Secure.ACCESSIBILITY_ENABLED,savedGlobal) }
                restore {
                    check(components(readSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)).filter { ComponentName.unflattenFromString(it)==own }==originalOwn) {
                        "Doppel accessibility setting was not restored"
                    }
                }
                cleanupFailure?.let { cleanup -> failure?.addSuppressed(cleanup) ?: throw cleanup }
            }
        }
    }
    private fun systemLists(own:ComponentName):Boolean = context.getSystemService(AccessibilityManager::class.java)
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            ComponentName(it.resolveInfo.serviceInfo.packageName,it.resolveInfo.serviceInfo.name)==own
        }
    private fun components(value:String?)=value.orEmpty().split(':').filter { it.isNotBlank() && it!="null" }
    private fun withoutOwn(own:ComponentName)=components(readSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        .filter { ComponentName.unflattenFromString(it)!=own }
    private fun readSetting(name:String)=Settings.Secure.getString(context.contentResolver,name)
    private fun writeSetting(name:String,value:String?) {
        check(name in setOf(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,Settings.Secure.ACCESSIBILITY_ENABLED))
        check(value.isNullOrEmpty() || value.matches(Regex("""[\p{L}\p{N}_./:$-]+""")))
        if(Build.VERSION.SDK_INT>=29) {
            automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
            try { check(Settings.Secure.putString(context.contentResolver,name,value)) }
            finally { automation.dropShellPermissionIdentity() }
        } else {
            // Android 8/9 tokenizes this command directly; quotes would be stored as literal characters.
            val command=if(value.isNullOrEmpty()) "settings delete secure $name" else "settings put secure $name $value"
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { stream ->
                val buffer=ByteArray(1024);while(stream.read(buffer)!=-1) { /* No settings or device data in logs. */ }
            }
        }
        check(readSetting(name).orEmpty()==value.orEmpty()) { "Accessibility setup/restore write failed" }
    }
    private fun await(timeout:Long,condition:()->Boolean):Boolean {
        val deadline=SystemClock.elapsedRealtime()+timeout
        while(!condition() && SystemClock.elapsedRealtime()<deadline) Thread.sleep(80)
        return condition()
    }
    private fun verifyEmulator() {
        fun property(name:String)=ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("getprop $name"))
            .bufferedReader().use { it.readText().trim() }
        val identity=listOf(Build.FINGERPRINT,Build.MODEL,Build.BRAND,Build.PRODUCT,Build.HARDWARE,Build.MANUFACTURER).joinToString(" ").lowercase(Locale.ROOT)
        val marker=listOf("generic","emulator","sdk_gphone","sdk_google","goldfish","ranchu","ldplayer","leidian","mumu","nox").any(identity::contains)
        val ldProfile=Build.MODEL=="LDY-ANO0" && Build.SUPPORTED_ABIS.any { it in setOf("x86","x86_64") }
        check(property("ro.kernel.qemu")=="1" || property("ro.boot.qemu")=="1" || marker || ldProfile) { "No convincing emulator identity; refusing backend smoke" }
    }
}

package dev.doppel.developer

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ComponentName
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DirectRuntime
import dev.doppel.sdk.DoppelAccessibilityService
import dev.doppel.sdk.ShellBridgeClient
import dev.doppel.sdk.ShellBridgeImeService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import java.util.Locale

/** Shared setup for explicitly opted-in, idle emulator capability tests. Contains no tests. */
internal class ShellBridgeLiveHarness {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context get() = instrumentation.targetContext
    val automation: UiAutomation by lazy {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }
    val cleanupFailures = JSONArray()
    val imeDiagnostics = JSONArray()
    private var optInValidated = false

    fun requireOptIn() {
        optInValidated = false
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit shell_bridge_capabilities=true", args.getString("shell_bridge_capabilities") == "true")
        check(args.getString("emulatorOnly") == "true") { "Requires explicit emulatorOnly=true" }
        verifyEmulator()
        requireIdle()
        val state = ShellBridgeClient.get(context).status()
        check(state.optBoolean("enabled") && state.optBoolean("connected") && state.optInt("uid", -1) == 2000) {
            "Requires an enabled, connected shell bridge running as UID 2000"
        }
        optInValidated = true
    }

    fun withService(block: (DoppelAccessibilityService) -> Unit) {
        check(optInValidated) { "Call requireOptIn before live setup" }
        // Instrumentation may restart the target process. Let its existing binding recover first.
        if (waitUntil(2000) { DoppelAccessibilityService.instance != null }) {
            block(requireNotNull(DoppelAccessibilityService.instance))
            return
        }
        requireIdle()
        val own = ComponentName(context, DoppelAccessibilityService::class.java)
        val savedServices = readSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val savedGlobal = readSetting(Settings.Secure.ACCESSIBILITY_ENABLED)
        validateSettingValue(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, savedServices)
        validateSettingValue(Settings.Secure.ACCESSIBILITY_ENABLED, savedGlobal)
        val original = entries(savedServices)
        val originalOwn = original.filter { ComponentName.unflattenFromString(it) == own }
        val originalOthers = original.filter { ComponentName.unflattenFromString(it) != own }
        var touched = false
        var globalTouched = false
        var primary: Throwable? = null
        try {
            touched = true
            writeSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, accessibilityOthers(own).joinToString(":"))
            val removedAt = SystemClock.elapsedRealtime()
            await(4000, "Doppel accessibility removal was not acknowledged") {
                SystemClock.elapsedRealtime() - removedAt >= 400 && DoppelAccessibilityService.instance == null && !systemLists(own)
            }
            writeSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (accessibilityOthers(own) + own.flattenToString()).joinToString(":"))
            globalTouched = true
            writeSetting(Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            await(8000, "Doppel accessibility did not rebind within 8 seconds") { DoppelAccessibilityService.instance != null }
            block(requireNotNull(DoppelAccessibilityService.instance))
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            if (touched) {
                val cleanup = Cleanup()
                cleanup.run("accessibility_services_restore") {
                    val currentOthers = accessibilityOthers(own)
                    val restore = if (currentOthers == originalOthers) savedServices else (currentOthers + originalOwn).joinToString(":")
                    writeSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, restore)
                }
                cleanup.run("accessibility_global_restore") {
                    if (globalTouched && accessibilityOthers(own) == originalOthers && readSetting(Settings.Secure.ACCESSIBILITY_ENABLED) == "1") {
                        writeSetting(Settings.Secure.ACCESSIBILITY_ENABLED, savedGlobal)
                    }
                }
                cleanup.run("accessibility_restore_verify") {
                    check(entries(readSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)).filter { ComponentName.unflattenFromString(it) == own } == originalOwn) {
                        "Doppel accessibility setting was not restored"
                    }
                }
                cleanup.finish(primary)
            }
        }
    }

    fun withTaskIme(block: () -> Unit) {
        check(optInValidated) { "Call requireOptIn before live setup" }
        requireIdle()
        val own = ComponentName(context, ShellBridgeImeService::class.java)
        val savedDefault = readSetting(Settings.Secure.DEFAULT_INPUT_METHOD)
        val savedActual = actualImeId()
        val savedEnabled = readSetting(Settings.Secure.ENABLED_INPUT_METHODS)
        val savedSubtype = readSetting(Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE)
        validateSettingValue(Settings.Secure.DEFAULT_INPUT_METHOD, savedDefault)
        validateSettingValue(Settings.Secure.ENABLED_INPUT_METHODS, savedEnabled)
        validateSettingValue(Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE, savedSubtype)
        val originalDefault = requireNotNull(ComponentName.unflattenFromString(savedActual.orEmpty())) {
            "A restorable actual input method binding is required"
        }
        val configuredDefault = requireNotNull(ComponentName.unflattenFromString(savedDefault.orEmpty())) { "A restorable configured input method is required" }
        val installed = inputMethods().inputMethodList
        val originalActualId = requireNotNull(installed.singleOrNull { ComponentName(it.packageName, it.serviceName) == originalDefault }?.id)
        val ownId = requireNotNull(installed.singleOrNull { ComponentName(it.packageName, it.serviceName) == own }?.id) {
            "Doppel task input method is not installed"
        }
        validateImeId(ownId)
        // Use the exact captured system ID; never enable or choose an arbitrary replacement keyboard.
        check(installed.any { it.id == savedDefault && ComponentName(it.packageName, it.serviceName) == configuredDefault }) {
            "The original configured input method is not installed"
        }
        val original = entries(savedEnabled)
        check(original.any { imeComponent(it) == originalDefault }) { "The original default input method is not enabled" }
        val originalOwn = original.filter { imeComponent(it) == own }
        val originalOthers = original.filter { imeComponent(it) != own }
        var touched = false
        var primary: Throwable? = null
        recordImeBinding("before")
        try {
            touched = true
            if (originalOwn.isEmpty()) {
                ownImeCommand("enable", ownId)
                await(3000, "Doppel task input method did not enable") { imeEntries().any { imeComponent(it) == own } }
            }
            if (originalDefault != own) {
                ownImeCommand("set", ownId)
                await(3000, "Doppel task input method actual binding was not selected") { defaultIme() == own }
            }
            recordImeBinding("selected")
            block()
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            if (touched) {
                val cleanup = Cleanup()
                cleanup.run("ime_default_restore") {
                    val currentDefault = defaultIme()
                    check(currentDefault == own || currentDefault == originalDefault) {
                        "Default input method changed externally; preserving its current selection"
                    }
                    if (currentDefault != originalDefault) {
                        check(imeEntries().any { imeComponent(it) == originalDefault }) { "Original input method is no longer enabled" }
                        check(inputMethods().inputMethodList.any { it.id == originalActualId && ComponentName(it.packageName, it.serviceName) == originalDefault }) {
                            "Original input method is no longer installed"
                        }
                        validateImeId(originalActualId)
                        drainShell("ime set $originalActualId")
                        await(3000, "Original actual input method binding was not restored") { defaultIme() == originalDefault }
                    }
                }
                cleanup.run("ime_configured_default_restore") {
                    check(defaultIme() == originalDefault) { "Actual input method changed externally; do not rewrite its configuration" }
                    val configuredNow = ComponentName.unflattenFromString(readSetting(Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty())
                    check(configuredNow in setOf(own, originalDefault, configuredDefault)) { "Configured input method changed externally" }
                    if (readSetting(Settings.Secure.DEFAULT_INPUT_METHOD) != savedDefault) writeSetting(Settings.Secure.DEFAULT_INPUT_METHOD, savedDefault)
                    // On systems where writing the configured value switches binding, restore the saved actual choice once.
                    if (defaultIme() != originalDefault) {
                        validateImeId(originalActualId); drainShell("ime set $originalActualId")
                        await(3000, "Original actual binding changed during configuration restoration") { defaultIme() == originalDefault }
                    }
                }
                cleanup.run("ime_selected_subtype_restore") {
                    // The selected subtype is separate from the enabled subtype list. Preserve an external IME choice.
                    if (defaultIme() == originalDefault && readSetting(Settings.Secure.DEFAULT_INPUT_METHOD) == savedDefault &&
                        readSetting(Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE) != savedSubtype) {
                        writeSetting(Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE, savedSubtype)
                    }
                }
                cleanup.run("ime_own_enabled_restore") {
                    val currentlyEnabled = imeEntries().any { imeComponent(it) == own }
                    if (originalOwn.isEmpty() && currentlyEnabled) {
                        check(defaultIme() != own) { "Cannot disable the selected task input method after default restoration failed" }
                        ownImeCommand("disable", ownId)
                        await(3000, "Doppel task input method did not disable") { imeEntries().none { imeComponent(it) == own } }
                    } else if (originalOwn.isNotEmpty() && !currentlyEnabled) {
                        ownImeCommand("enable", ownId)
                        await(3000, "Doppel task input method membership was not restored") { imeEntries().any { imeComponent(it) == own } }
                    }
                }
                cleanup.run("ime_entries_restore") {
                    if (originalOwn.isEmpty()) {
                        check(defaultIme() != own) { "Cannot remove the selected task input method" }
                    }
                    val currentOthers = imeEntries().filter { imeComponent(it) != own }
                    // Subtype suffixes and concurrently changed unrelated IMEs are preserved verbatim.
                    val restore = if (currentOthers == originalOthers) savedEnabled else (currentOthers + originalOwn).joinToString(":")
                    if (readSetting(Settings.Secure.ENABLED_INPUT_METHODS).orEmpty() != restore.orEmpty()) {
                        writeSetting(Settings.Secure.ENABLED_INPUT_METHODS, restore)
                    }
                }
                cleanup.run("ime_restore_verify") {
                    check(imeEntries().filter { imeComponent(it) == own } == originalOwn) { "Doppel input method membership was not restored" }
                    check(readSetting(Settings.Secure.DEFAULT_INPUT_METHOD) == savedDefault) { "Original default input method was not restored" }
                    check(defaultIme() == originalDefault) { "Original actual input method was not restored" }
                    check(readSetting(Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE) == savedSubtype) { "Original input method subtype was not restored" }
                }
                cleanup.run("ime_binding_report") { recordImeBinding("restored") }
                cleanup.finish(primary)
            }
        }
    }

    fun await(timeoutMs: Long, message: String, condition: () -> Boolean) {
        check(waitUntil(timeoutMs, condition)) { message }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        require(timeoutMs in 0..60_000)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (condition()) return true
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return false
            Thread.sleep(minOf(80L, remaining))
        }
    }

    private fun requireIdle() {
        check(!DirectRuntime.get(context).hasUnfinishedRun()) { "Finish existing tasks before this isolated capability test" }
        check(DeviceWorkerService.instance == null) { "An existing worker must finish before this isolated capability test" }
    }

    private fun systemLists(own: ComponentName): Boolean = context.getSystemService(AccessibilityManager::class.java)
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) == own
        }

    private fun entries(value: String?) = value.orEmpty().split(':').filter { it.isNotBlank() && it != "null" }
    private fun accessibilityOthers(own: ComponentName) = entries(readSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        .filter { ComponentName.unflattenFromString(it) != own }
    private fun inputMethods() = context.getSystemService(InputMethodManager::class.java)
    private fun imeEntries() = entries(readSetting(Settings.Secure.ENABLED_INPUT_METHODS))
    private fun imeComponent(entry: String) = ComponentName.unflattenFromString(entry.substringBefore(';'))
    private fun defaultIme() = ComponentName.unflattenFromString(actualImeId().orEmpty())
    private fun actualImeId(): String? = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys input_method"))
        .bufferedReader().use { input ->
            val text = StringBuilder(); val buffer = CharArray(4096)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                if (text.length + count > 512 * 1024) return@use null
                text.append(buffer, 0, count)
            }
            ShellBridgeImeBinding.actualId(text.toString())
        }
    private fun recordImeBinding(stage: String) {
        if (imeDiagnostics.length() >= 12) return
        imeDiagnostics.put(JSONObject().put("stage", stage).put("actual_ime", actualImeId() ?: JSONObject.NULL)
            .put("configured_ime", readSetting(Settings.Secure.DEFAULT_INPUT_METHOD) ?: JSONObject.NULL)
            .put("task_input_available", ShellBridgeImeService.capability().optBoolean("input_available")))
    }
    fun requireTaskImeBinding() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "IME binding preflight must not block the Android main thread" }
        check(defaultIme() == ComponentName(context, ShellBridgeImeService::class.java)) { "Actual task input method binding changed" }
        check(ShellBridgeImeService.capability().optBoolean("input_available")) { "Actual task input connection is not available" }
        recordImeBinding("input_ready")
    }
    private fun readSetting(name: String) = Settings.Secure.getString(context.contentResolver, name)

    private fun ownImeCommand(verb: String, id: String) {
        check(verb in setOf("enable", "set", "disable"))
        validateImeId(id)
        check(ComponentName.unflattenFromString(id) == ComponentName(context, ShellBridgeImeService::class.java))
        drainShell("ime $verb $id")
    }

    private fun validateImeId(id: String) {
        check(id.matches(Regex("""[\p{L}\p{N}_./$-]+""")) && ComponentName.unflattenFromString(id) != null) { "Invalid input method component" }
    }

    private fun validateSettingValue(name: String, value: String?) {
        check(name in setOf(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, Settings.Secure.ACCESSIBILITY_ENABLED,
            Settings.Secure.ENABLED_INPUT_METHODS, Settings.Secure.DEFAULT_INPUT_METHOD, Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE))
        when (name) {
            Settings.Secure.ACCESSIBILITY_ENABLED -> check(value.isNullOrEmpty() || value == "0" || value == "1")
            Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE -> check(value == null || value.toIntOrNull()?.toString() == value)
            Settings.Secure.DEFAULT_INPUT_METHOD -> if (!value.isNullOrEmpty()) validateImeId(value)
            Settings.Secure.ENABLED_INPUT_METHODS -> check(value.isNullOrEmpty() || value.matches(Regex("""[\p{L}\p{N}_./:$;-]+""")))
            else -> check(value.isNullOrEmpty() || value.matches(Regex("""[\p{L}\p{N}_./:$-]+""")))
        }
    }

    private fun writeSetting(name: String, value: String?) {
        validateSettingValue(name, value)
        if (Build.VERSION.SDK_INT >= 29) {
            automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
            try { check(Settings.Secure.putString(context.contentResolver, name, value)) }
            finally { automation.dropShellPermissionIdentity() }
        } else {
            // UiAutomation tokenizes directly on Android 8/9. Do not introduce shell quoting or sh -c.
            drainShell(if (value.isNullOrEmpty()) "settings delete secure $name" else "settings put secure $name $value")
        }
        check(readSetting(name).orEmpty() == value.orEmpty()) { "Live setup/restore setting write failed" }
    }

    private fun drainShell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { stream ->
            val buffer = ByteArray(1024)
            while (stream.read(buffer) != -1) { /* Never retain shell output or user settings in reports. */ }
        }
    }

    private fun verifyEmulator() {
        fun property(name: String): String {
            check(name in setOf("ro.kernel.qemu", "ro.boot.qemu"))
            return ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("getprop $name"))
                .bufferedReader().use { it.readText().trim() }
        }
        val identity = listOf(Build.FINGERPRINT, Build.MODEL, Build.BRAND, Build.PRODUCT, Build.HARDWARE, Build.MANUFACTURER)
            .joinToString(" ").lowercase(Locale.ROOT)
        val marker = listOf("generic", "emulator", "sdk_gphone", "sdk_google", "goldfish", "ranchu", "ldplayer", "leidian", "mumu", "nox").any(identity::contains)
        val ldProfile = Build.MODEL == "LDY-ANO0" && Build.SUPPORTED_ABIS.any { it in setOf("x86", "x86_64") }
        check(property("ro.kernel.qemu") == "1" || property("ro.boot.qemu") == "1" || marker || ldProfile) {
            "No convincing emulator identity; refusing live setup"
        }
    }

    private inner class Cleanup {
        private var first: Throwable? = null

        fun run(stage: String, action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                cleanupFailures.put(JSONObject().put("stage", stage).put("exception_class", error.javaClass.name))
                if (first == null) first = error else first!!.addSuppressed(error)
            }
        }

        fun finish(primary: Throwable?) {
            first?.let { cleanup -> if (primary != null) primary.addSuppressed(cleanup) else throw cleanup }
        }
    }
}

package dev.doppel.developer

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.AutomaticUnlockSettingsActivity
import dev.doppel.sdk.DeviceWorkerService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.ArrayDeque
import java.util.UUID

/** Opt-in emulator fixture with public system PIN 681429, already unlocked by the host.
 * Only the real settings UI saves credentials; no direct save call, model call or task submission.
 * Host must ensure no worker is running before instrumentation restarts the application process.
 */
class AutomaticUnlockSettingsDeviceTest {
    @get:org.junit.Rule val terminalTaskPointer: org.junit.rules.TestRule = TerminalTaskPointerTestRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val automation get() = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val credentials by lazy { Class.forName("dev.doppel.sdk.AutomaticUnlockCredentials").getField("INSTANCE").get(null) }
    private var activity: Activity? = null
    private val promptTitle = "确认自动解锁设置"
    private val fixturePin = "681429"

    private fun call(name: String) = credentials.javaClass.getMethod(name, Context::class.java).invoke(credentials, context)
    private fun enabled() = call("isEnabled") as Boolean
    private fun noTask() {
        assertNull("Settings verification must not start a worker", DeviceWorkerService.instance)
        assertTrue("Retain any existing task", context.getSharedPreferences("doppel", Context.MODE_PRIVATE).getString("active_run", "").isNullOrBlank())
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < deadline) { noTask(); if (condition()) return; Thread.sleep(80) }
        assertTrue(message, condition())
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .use { String(it.readBytes(), Charsets.UTF_8).trim() }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun mainViews(action: (List<View>) -> Unit) = inst.runOnMainSync { action(views(requireNotNull(activity).window.decorView)) }
    private fun nativeNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>(); val pending = ArrayDeque<AccessibilityNodeInfo>(); pending.add(root)
        while (pending.isNotEmpty() && result.size < 1000) {
            val node = pending.removeFirst()
            if (node.isVisibleToUser) result.add(node)
            repeat(node.childCount) { node.getChild(it)?.let(pending::add) }
        }
        return result
    }
    private fun authenticationNodes(): List<AccessibilityNodeInfo> = automation.windows.sortedByDescending { it.layer }
        .mapNotNull { it.root }.filter { it.packageName?.toString() in setOf("com.android.systemui", "com.android.settings") }
        .map(::nativeNodes).firstOrNull { nodes -> nodes.any { it.text?.toString() == promptTitle } }.orEmpty()
    private fun nativeField() = authenticationNodes().singleOrNull { it.isPassword && it.isEditable && it.isEnabled }
    private fun press(label: String) = mainViews { nodes ->
        val button = nodes.filterIsInstance<Button>().single { it.text.toString() == label }
        assertTrue(button.isEnabled)
        button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
        assertTrue("The actual UI button must have its production listener", button.hasOnClickListeners())
        button.performClick() // Verify each resulting UI/state transition at its caller.
    }
    private fun key(label: String) = mainViews { nodes ->
        val button = nodes.filterIsInstance<Button>().single { it.tag == "automatic_unlock_key_$label" }
        assertTrue(button.hasOnClickListeners()); button.performClick()
    }
    private fun singleEntry(length: Int) = mainViews { nodes ->
        val fields = nodes.filterIsInstance<EditText>()
        assertEquals("Enter twice in sequence, never in two simultaneous fields", 1, fields.size)
        val field = fields.single()
        assertEquals(length, field.text.length)
        assertFalse(field.showSoftInputOnFocus); assertFalse(field.onCheckIsTextEditor())
        assertNull(field.onCreateInputConnection(EditorInfo()))
        assertFalse(field.isSaveEnabled); assertFalse(field.isSaveFromParentEnabled)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, field.importantForAutofill)
        assertFalse(field.onTextContextMenuItem(android.R.id.copy))
        assertFalse(field.onTextContextMenuItem(android.R.id.paste))
        assertNull("Accessibility must never expose the editable password", field.createAccessibilityNodeInfo().text)
        val display = field.transformationMethod.getTransformation(field.text, field)
        assertTrue("Every character stays masked, including the last one", (0 until display.length).all { display[it] == '\u2022' })
    }
    private fun noLocalIme() {
        await("Local credential entry must not summon an input-method window") {
            var hidden = false
            mainViews { hidden = requireNotNull(activity).window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true }
            hidden && automation.windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }
    }
    private fun fillCurrentPin(value: String = fixturePin) { key("清空"); value.forEach { key(it.toString()) }; singleEntry(value.length); noLocalIme() }
    private fun firstBuffer(): CharArray? {
        var buffer: CharArray? = null
        inst.runOnMainSync {
            val first = requireNotNull(activity).javaClass.getDeclaredField("firstEntry").apply { isAccessible = true }.get(activity)
            buffer = first?.javaClass?.getMethod("getValue")?.invoke(first) as? CharArray
        }
        return buffer
    }
    private fun captureBlankUi(folder: File, name: String) {
        await("The blank settings screen must finish layout for visual review") {
            var ready = false
            mainViews { nodes ->
                val root = requireNotNull(activity).window.decorView
                ready = activity?.hasWindowFocus() == true && root.isLaidOut && !root.isLayoutRequested &&
                    nodes.filterIsInstance<EditText>().all { it.isLaidOut && it.width > 0 && it.text.isEmpty() }
            }
            ready
        }
        inst.runOnMainSync {
            val root = requireNotNull(activity).window.decorView
            assertTrue("Evidence contains no entered password", views(root).filterIsInstance<EditText>().all { it.text.isEmpty() })
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                File(folder, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
    }
    private fun fillAndAccept(accept: Boolean) {
        var firstStep = false
        mainViews { firstStep = it.filterIsInstance<Button>().any { button -> button.text.toString() == "下一步" } }
        if (firstStep) {
            fillCurrentPin(); press("下一步"); singleEntry(0)
            assertNotNull("The first entry remains only in the transient confirmation buffer", firstBuffer())
        }
        fillCurrentPin()
        mainViews { nodes ->
            val risk = nodes.filterIsInstance<CheckBox>().single()
            if (risk.isChecked != accept) risk.performClick()
            assertEquals("The risk checkbox must reach the requested state", accept, risk.isChecked)
        }
    }
    private fun selectKind(label: String) {
        mainViews { nodes -> nodes.single { it.contentDescription?.toString() == "系统锁屏类型" }.performClick() }
        await("The credential-type selector must appear") {
            automation.rootInActiveWindow?.let(::nativeNodes)?.any { it.text?.toString() == label } == true
        }
        var choice = requireNotNull(automation.rootInActiveWindow).let(::nativeNodes).last { it.text?.toString() == label }
        while (!choice.isClickable) choice = requireNotNull(choice.parent)
        assertTrue(choice.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("The credential-type selector must close") {
            var selected = false
            mainViews { nodes -> selected = nodes.any { it.contentDescription?.toString() == "系统锁屏类型" && (it as? TextView)?.text?.toString() == label } }
            selected
        }
        singleEntry(0)
    }
    private fun cancelNativeForm() {
        waitForNativeForm()
        val cancel = authenticationNodes().firstOrNull { it.isClickable && it.text?.toString() in setOf("取消", "Cancel", "CANCEL") }
        if (cancel != null) assertTrue(cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        else {
            shell("input keyevent 4"); Thread.sleep(350)
            if (authenticationNodes().isNotEmpty()) shell("input keyevent 4")
        }
        await("Cancel must return to focused settings without authentication") {
            var focused = false; mainViews { focused = activity?.hasWindowFocus() == true }
            authenticationNodes().isEmpty() && focused
        }
    }
    private fun open() {
        activity = inst.startActivitySync(Intent(context, AutomaticUnlockSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("Settings must have a visible focused window") {
            var focused = false; inst.runOnMainSync { focused = activity?.hasWindowFocus() == true }; focused
        }
    }
    private fun close() { activity?.let { page -> inst.runOnMainSync { page.finish() } }; activity = null; inst.waitForIdleSync() }
    private fun waitForNativeForm() {
        await("Only the real system credential form may receive the public fixture PIN") { nativeField() != null }
        Thread.sleep(700) // Its nodes precede the end of the native dialog entrance animation.
    }
    private fun authenticateNormally() {
        waitForNativeForm()
        val field = requireNotNull(nativeField())
        assertTrue("Never append to a partly entered system credential", field.text.isNullOrEmpty())
        if (!field.isFocused) {
            val bounds = Rect().also(field::getBoundsInScreen)
            shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        }
        await("The real credential field must have input focus") { nativeField()?.isFocused == true }
        shell("input text $fixturePin")
        await("All six public fixture digits must reach the real form") { nativeField()?.text?.length == 6 }
        shell("input keyevent 66")
    }

    @Test fun consentNativeAuthenticationSaveReopenAndDelete() {
        assumeTrue("Public-PIN emulator fixture is opt-in", InstrumentationRegistry.getArguments().getString("automatic_unlock_test") == "true")
        assertEquals(34, Build.VERSION.SDK_INT)
        assertEquals("Never type the public fixture PIN on a physical phone", "1", shell("getprop ro.boot.qemu"))
        val lock = context.getSystemService(KeyguardManager::class.java)
        assertTrue("Host must leave the secure fixture unlocked", lock.isDeviceSecure && !lock.isDeviceLocked && !lock.isKeyguardLocked && context.getSystemService(PowerManager::class.java).isInteractive)
        noTask()
        val session = Class.forName("dev.doppel.sdk.AutomaticUnlockSession").getField("INSTANCE").get(null)
        assertFalse("Retain any active automatic session", session.javaClass.getMethod("getActive").invoke(session) as Boolean)
        assertTrue("Retain pending recovery state", context.getSharedPreferences("doppel_automatic_unlock_state", Context.MODE_PRIVATE).all.isEmpty())
        val file = File(context.noBackupFilesDir, "automatic-unlock-v1.bin")
        val alias = "${context.packageName}.automatic-unlock.v1"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse("Retain even unreadable credentials", listOf("", ".bak", ".new").any { File(file.path + it).exists() })
        assertFalse("Retain an existing encryption key", keyStore.containsAlias(alias))
        val triggerRules = context.getSharedPreferences("doppel_auto_triggers", Context.MODE_PRIVATE).getString("rules", null)
        val rules = JSONArray(triggerRules ?: "[]")
        assertFalse("Do not run existing control rules", (0 until rules.length()).any { rules.getJSONObject(it).optBoolean("enabled") })
        val schedules = File(context.noBackupFilesDir, "schedules-v1.json")
        val beforeSchedules = schedules.takeIf(File::exists)?.readText()
        val jobs = beforeSchedules?.let { JSONObject(it).getJSONArray("items") } ?: JSONArray()
        assertFalse("Do not run existing schedules", (0 until jobs.length()).any { jobs.getJSONObject(it).optBoolean("enabled") })
        val runs = File(context.noBackupFilesDir, "direct-runs-v1.json")
        val beforeRuns = runs.takeIf(File::exists)?.readText()
        val evidence = File(context.getExternalFilesDir(null), "automatic-unlock-settings-verification/${UUID.randomUUID()}").apply { check(mkdirs()) }
        val report = JSONObject().put("ok", false).put("model_requests", 0).put("submitted_tasks", 0)
        var stage = "open_settings"
        var passed = false
        var primaryFailure: Throwable? = null
        val oldInfo = automation.serviceInfo
        try {
            automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            open()
            singleEntry(0); noLocalIme()
            captureBlankUi(evidence, "settings-first-entry.png")
            mainViews { nodes ->
                assertTrue(requireNotNull(activity).window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertTrue(nodes.filterIsInstance<CheckBox>().isEmpty())
            }
            stage = "sequential_entry_and_mismatch"
            fillCurrentPin("123"); press("下一步"); singleEntry(3)
            assertNull(firstBuffer()); assertTrue(authenticationNodes().isEmpty())
            fillCurrentPin(); press("下一步"); singleEntry(0)
            captureBlankUi(evidence, "settings-confirm-entry.png")
            fillAndAccept(true)
            fillCurrentPin("123456"); press("验证身份并开启")
            singleEntry(0); assertNotNull(firstBuffer())
            assertFalse(enabled()); assertFalse(file.exists()); assertTrue(authenticationNodes().isEmpty())
            report.put("sequential_entry_and_mismatch", true)

            stage = "risk_consent_required"
            fillAndAccept(false); press("验证身份并开启")
            Thread.sleep(750)
            assertFalse("No consent must mean no saved credential", enabled())
            assertTrue("No consent must not open system authentication", authenticationNodes().isEmpty())
            report.put("risk_consent_required", true)

            stage = "cancel_authentication"
            val cancelledBuffer = requireNotNull(firstBuffer())
            fillAndAccept(true); press("验证身份并开启"); waitForNativeForm()
            cancelNativeForm(); singleEntry(0)
            assertNull(firstBuffer()); assertTrue(cancelledBuffer.all { it == '\u0000' })
            mainViews { assertTrue("Cancellation returns to the first step with no retained consent", it.filterIsInstance<CheckBox>().isEmpty()) }
            assertFalse(enabled()); assertNull(call("read")); assertFalse(file.exists())
            report.put("cancel_did_not_save", true)

            stage = "native_authentication_and_ui_save"
            fillAndAccept(true); press("验证身份并开启"); authenticateNormally()
            await("Only successful native authentication may save and enable") { enabled() && authenticationNodes().isEmpty() }
            val read = requireNotNull(call("read"))
            try {
                val value = read.javaClass.getMethod("getValue").invoke(read) as CharArray
                assertTrue("Decrypted value must equal only the public fixture", value.contentEquals(fixturePin.toCharArray()))
                assertEquals("PIN", (read.javaClass.getMethod("getKind").invoke(read) as Enum<*>).name)
            } finally { (read as AutoCloseable).close() }
            val encrypted = file.readBytes()
            assertTrue(encrypted.size > 29 && encrypted[0].toInt() == 1)
            assertFalse("Disk must not contain a plaintext PIN", String(encrypted, Charsets.ISO_8859_1).contains(fixturePin))
            assertTrue(keyStore.containsAlias(alias))
            report.put("native_authentication_saved", true).put("encrypted_read_matches", true)

            stage = "ascii_keyboard_cancel_and_lifecycle_wipe"
            close(); open(); assertTrue(enabled())
            mainViews { assertTrue("Never refill saved passwords", it.filterIsInstance<EditText>().isEmpty()) }
            press("更新密码"); fillCurrentPin("1234")
            var replacedField: EditText? = null; mainViews { replacedField = it.filterIsInstance<EditText>().single() }
            selectKind("英文、数字与符号密码")
            assertTrue("Changing type wipes the previous editable buffer", requireNotNull(replacedField).text.isEmpty())
            val available = mutableSetOf<Char>()
            fun collectKeys() = mainViews { nodes -> nodes.filterIsInstance<Button>().map { it.text.toString() }.filter { it.length == 1 }.forEach { available.add(it.single()) } }
            collectKeys(); key("大写"); collectKeys(); key("符号"); collectKeys(); available.add(' ')
            assertTrue("The local keyboard must cover all supported ASCII characters", (' '..'~').all { it in available })
            key("字母"); key("小写"); key("a"); key("大写"); key("B"); key("符号"); key("@"); key("1"); key("空格")
            singleEntry(5); noLocalIme()
            mainViews { nodes -> assertTrue("Local keys preserve exact case, punctuation and space", nodes.filterIsInstance<EditText>().single().text.toString() == "aB@1 ") }
            press("下一步"); singleEntry(0)
            val asciiFirst = requireNotNull(firstBuffer())
            assertTrue("First ASCII entry preserves all typed characters", asciiFirst.contentEquals("aB@1 ".toCharArray()))
            key("x"); press("重新输入")
            assertTrue("Restart must wipe first-entry bytes", asciiFirst.all { it == '\u0000' }); singleEntry(0)
            key("a"); key("b"); key("c"); key("d"); press("下一步")
            val backgroundBuffer = requireNotNull(firstBuffer())
            key("x")
            var backgroundField: EditText? = null; mainViews { backgroundField = it.filterIsInstance<EditText>().single() }
            val originalPage = requireNotNull(activity)
            shell("input keyevent 3")
            await("Backgrounding must wipe both buffers before destruction") {
                val noFirstEntry = firstBuffer() == null
                var wiped = false
                inst.runOnMainSync { wiped = !originalPage.hasWindowFocus() && noFirstEntry &&
                    requireNotNull(backgroundField).text.isEmpty() && backgroundBuffer.all { it == '\u0000' } }
                wiped
            }
            assertFalse("This verifies onPause, not destruction", originalPage.isDestroyed)
            inst.runOnMainSync { originalPage.startActivity(Intent(originalPage, AutomaticUnlockSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            await("Return to the same settings instance") { originalPage.hasWindowFocus() }
            assertSame(originalPage, activity)
            singleEntry(0); assertNull(firstBuffer()); noLocalIme(); assertTrue(enabled())
            press("取消设置")
            assertTrue("Cancelled editing retains the encrypted password", encrypted.contentEquals(file.readBytes()))
            report.put("ascii_local_keyboard_complete", true).put("type_restart_exit_wiped_buffers", true).put("local_entry_no_ime", true)

            stage = "suspend_preserves_ciphertext"
            press("停用自动解锁")
            await("Stopping automatic attempts must retain encrypted credentials") { !enabled() && call("hasSaved") == true && call("isSuspended") == true }
            assertNull(call("read")); assertTrue(encrypted.contentEquals(file.readBytes())); assertTrue(keyStore.containsAlias(alias))
            val retained = requireNotNull(call("readSaved"))
            try { assertTrue((retained.javaClass.getMethod("getValue").invoke(retained) as CharArray).contentEquals(fixturePin.toCharArray())) }
            finally { (retained as AutoCloseable).close() }
            close(); open()
            mainViews { nodes -> assertTrue(nodes.filterIsInstance<TextView>().any { it.text.toString() == "已停用，等待用户检查" }) }
            captureBlankUi(evidence, "settings-suspended.png")
            assertFalse(enabled()); assertTrue(call("isSuspended") as Boolean)
            report.put("suspend_preserved_ciphertext_and_key", true).put("reopen_retained_suspension", true)

            stage = "cancel_reenable_then_authenticate"
            press("确认原密码并重新启用"); cancelNativeForm()
            assertFalse(enabled()); assertTrue(encrypted.contentEquals(file.readBytes()))
            report.put("cancel_reenable_stayed_suspended", true)
            press("确认原密码并重新启用"); authenticateNormally()
            await("Re-enable needs fresh successful device authentication") { enabled() && authenticationNodes().isEmpty() }
            assertFalse(call("isSuspended") as Boolean); assertTrue(encrypted.contentEquals(file.readBytes()))
            report.put("native_authentication_reenabled_without_rewriting_password", true)

            stage = "update_suspended_password"
            press("停用自动解锁"); press("更新密码")
            fillAndAccept(true); press("验证身份并更新"); authenticateNormally()
            await("Updating a suspended password must save and enable only after system authentication") { enabled() && authenticationNodes().isEmpty() }
            assertFalse(call("isSuspended") as Boolean)
            report.put("native_authentication_updated_suspended_password", true)

            stage = "explicit_delete"
            press("删除保存的密码")
            await("Only explicit deletion removes the saved credential and key") { !enabled() && !file.exists() && !keyStore.containsAlias(alias) }
            assertNull(call("read"))
            assertFalse(call("hasSaved") as Boolean); assertFalse(call("isSuspended") as Boolean)
            assertFalse(listOf(".bak", ".new").any { File(file.path + it).exists() })
            assertEquals(beforeSchedules, schedules.takeIf(File::exists)?.readText())
            assertEquals(beforeRuns, runs.takeIf(File::exists)?.readText())
            assertEquals(triggerRules, context.getSharedPreferences("doppel_auto_triggers", Context.MODE_PRIVATE).getString("rules", null))
            report.put("reopen_did_not_reveal_password", true).put("explicit_delete_removed_ciphertext_and_key", true).put("user_tasks_unchanged", true)
            passed = true
        } catch (error: Throwable) {
            primaryFailure = error
            report.put("failure_stage", stage).put("failure_type", error.javaClass.simpleName)
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
                }
            }
            cleanup { close() }
            cleanup { call("clear") }
            cleanup {
                assertFalse("Cleanup must remove this fixture's credential files", listOf("", ".bak", ".new").any { File(file.path + it).exists() })
                assertFalse("Cleanup must remove this fixture's key", keyStore.containsAlias(alias))
            }
            cleanup { automation.serviceInfo = oldInfo }
            cleanup { inst.sendStatus(0, android.os.Bundle().apply { putString("stream", "\nSettings UI evidence: ${evidence.absolutePath}/result.json\n") }) }
            report.put("ok", passed && primaryFailure == null && cleanupFailure == null).put("last_stage", stage)
            cleanupFailure?.let { report.put("cleanup_failure_type", it.javaClass.simpleName) }
            cleanup { File(evidence, "result.json").writeText(report.toString(2)) }
            cleanupFailure?.let { if (primaryFailure != null) primaryFailure!!.addSuppressed(it) else throw it }
        }
    }
}

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")

package dev.doppel.developer

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.CredentialVault
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DoppelAccessibilityService
import dev.doppel.sdk.SplitTaskEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID

/** Real native login fields + Android Keystore, driven by a separate real task engine with no model calls. */
class CredentialInputDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val automation by lazy { inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }
    private val targetPackage = "dev.doppel.testapp"

    @Test fun authorizedTaskFillsNativePasswordWhileManagementStaysLockedAndRejectsOtherContexts() {
        assertNull("Stop the worker before operating the disposable fixture; retain all user tasks", DeviceWorkerService.instance)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        assertFalse(keyguard.isDeviceLocked || keyguard.isKeyguardLocked)
        context.packageManager.getPackageInfo(targetPackage, 0)
        AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        val prefix = "credential-input-${UUID.randomUUID()}-"
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
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val aliases = listOf("${isolated.packageName}.credential.v1", "${isolated.packageName}.credential.pin.v1")
        assertTrue(aliases.none(store::containsAlias))
        val before = protectedState()
        val folder = File(context.getExternalFilesDir(null), "full-feature/vault-balance").apply { mkdirs() }
        val checks = JSONObject()
        var passed = false
        try {
            val admin = CredentialVault(isolated)
            admin.setPin("7352")
            // An entry without the new consent retains the safe old-data default.
            admin.save(CredentialVault.Entry("", targetPackage, "Fixture login", "fixture-user", "fixture-only-password-two"))
            val entry = admin.entries().single()
            admin.lock()
            val reader = CredentialVault(isolated)
            assertFalse(reader.isUnlocked())
            assertEquals(0, reader.taskLabels(targetPackage).length())
            val engine = SplitTaskEngine(null, {})
            val runId = engine.create(JSONObject().put("device_id", "direct-this-phone").put("goal", "本机密码控件回归").put("mode", "assist")).getString("id")
            fun active() = engine.statusOrNull(runId) == "running"
            context.startActivity(Intent().setClassName(targetPackage, "$targetPackage.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            click("密码填写验证")
            expect("登录密码")
            fun fill(pkg: String = targetPackage, field: String = "password", accountTarget: Boolean = field == "username"): Boolean {
                val description = if (accountTarget) "测试账号输入框" else "测试密码输入框"
                val node = find { it.isEditable && it.contentDescription?.toString() == description }
                return try { reader.fillForTask(pkg, "Fixture login", node, field, ::active) } finally { node.recycle() }
            }
            assertFalse("Unconsented saved entries must never fill", fill())
            checks.put("old_entry_unconsented", true)
            assertTrue(admin.unlock("7352")); admin.save(entry.copy(allowTasks = true)); admin.lock()
            val hints = reader.taskLabels(targetPackage)
            assertEquals(1, hints.length())
            assertEquals(targetPackage, hints.getJSONObject(0).getString("package_name"))
            assertTrue(hints.getJSONObject(0).getBoolean("has_username"))
            assertTrue(hints.getJSONObject(0).getBoolean("has_password"))
            assertFalse("Only app and label metadata can reach the planner", hints.toString().contains("fixture-user") || hints.toString().contains("fixture-only-password"))
            assertFalse("An exact package mismatch must fail", fill("com.android.settings"))
            assertTrue("Granted native input must work even while management remains PIN-locked", fill())
            assertFalse(reader.isUnlocked())
            assertThrows(IllegalStateException::class.java) { reader.entries() }
            click("验证测试输入"); expect("测试内容匹配")
            checks.put("wrong_app_rejected", true).put("native_input_confirmed", true).put("management_stayed_locked", true)
            capture(folder)
            verifyPrivateCapture("masked", folder)
            click("显示密码（保留类型）")
            verifyPrivateCapture("revealed-password-type", folder)
            click("显示密码（普通文本）")
            verifyPrivateCapture("revealed-plain-text", folder)
            click("回显敏感字段")
            verifyPrivateCapture("echoed-fields", folder)
            click("清除回显"); click("隐藏密码")
            checks.put("masked_login_capture_continues", true).put("both_reveal_modes_masked", true)
                .put("echoed_text_description_hint_and_state_redacted", true)

            clearField()
            assertFalse("An account must never be written into a password field", fill(field = "username", accountTarget = false))
            fun accountFocus(focused: Boolean) {
                // A distinct focus target prevents Android from refocusing its only text field.
                val node = if (focused) find { it.isEditable && it.contentDescription?.toString() == "测试账号输入框" }
                    else find { !it.isEditable && it.text?.toString() == "回显已清除" }
                try {
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                    if (!node.isFocused) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                } finally { node.recycle() }
                inst.waitForIdleSync()
                find { it.isEditable && it.contentDescription?.toString() == "测试账号输入框" && it.isFocused == focused }.recycle()
            }
            accountFocus(true)
            assertFalse("Account fill still requires the exact saved app", fill("com.android.settings", "username"))
            assertFalse("Unknown credential fields must not default to a password", fill(field = "unknown"))
            assertTrue("A focused plain-text field receives the local account", fill(field = "username"))
            find { it.isEditable && it.text?.toString() == "fixture-user" }.recycle()
            assertFalse("Reading account metadata must leave management locked", reader.isUnlocked())
            verifyPrivateCapture("username", folder, "测试账号输入框")
            accountFocus(false)
            assertFalse("An unfocused plain-text field cannot receive an account", fill(field = "username"))
            accountFocus(true)
            assertTrue(admin.unlock("7352")); admin.save(entry.copy(username = "", allowTasks = true)); admin.lock()
            assertFalse(reader.taskLabels(targetPackage).getJSONObject(0).getBoolean("has_username"))
            assertFalse("A missing account must not clear the target", fill(field = "username"))
            find { it.isEditable && it.text?.toString() == "fixture-user" }.recycle()
            assertTrue(admin.unlock("7352")); admin.save(entry.copy(allowTasks = false)); admin.lock()
            assertFalse("Disabling consent also disables account fill", fill(field = "username"))
            assertTrue(admin.unlock("7352")); admin.save(entry.copy(allowTasks = true)); admin.lock()
            clearField("测试账号输入框"); clearField()
            checks.put("account_local_native_input_confirmed", true).put("account_plain_focused_field_required", true)
                .put("account_metadata_and_capture_redacted", true).put("account_missing_and_unconsented_rejected", true)

            assertTrue(admin.unlock("7352"))
            admin.save(entry.copy(id = "", allowTasks = true))
            val duplicate = admin.entries().single { it.id != entry.id }
            admin.lock()
            assertFalse("Duplicate authorized labels cannot choose an arbitrary password", fill())
            assertTrue(admin.unlock("7352")); admin.remove(duplicate.id); admin.lock()
            engine.control(runId, "pause", JSONObject())
            assertFalse("Paused task cannot fill", fill())
            engine.control(runId, "cancel", JSONObject())
            assertFalse("Ended task cannot fill", fill())
            click("验证测试输入"); expect("输入框为空")
            checks.put("duplicate_label_rejected", true).put("paused_and_ended_task_rejected", true)

            // The next real fixture task has its own run identity; no user runtime or global task is changed.
            val next = engine.create(JSONObject().put("device_id", "direct-this-phone").put("goal", "支付输入边界回归").put("mode", "assist")).getString("id")
            click("切换支付密码"); expect("支付密码")
            val financial = find { it.isEditable && it.hintText?.toString() == "支付密码" }
            try { assertFalse("Payment-password native fields must stay manual", reader.fillForTask(targetPackage, "Fixture login", financial) { engine.statusOrNull(next) == "running" }) }
            finally { financial.recycle() }
            click("验证测试输入"); expect("输入框为空")
            checks.put("financial_password_rejected", true)
            passed = true
        } finally {
            runCatching { clearField("测试账号输入框"); clearField(); click("返回场景") }
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_HOME)
            names.forEach { context.deleteSharedPreferences(it) }
            aliases.forEach { if (store.containsAlias(it)) store.deleteEntry(it) }
            assertTrue("Delete only the isolated test directory", directory.deleteRecursively())
            val preserved = before == protectedState()
            File(folder, "credential-input-report.json").writeText(JSONObject().put("passed", passed && preserved)
                .put("original_state_preserved", preserved).put("model_calls_requested", 0)
                .put("scope", "Real native fill API with isolated Keystore and real independent task engine; no user task is started or altered")
                .put("checks", checks).toString(2))
            assertTrue("Never change existing credentials, model configuration or task/chat history", preserved)
        }
    }

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (root == null) emptyList() else listOf(root) +
        (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val until = SystemClock.elapsedRealtime() + 8000
        do {
            val nodes = nodes(automation.rootInActiveWindow)
            val match = nodes.firstOrNull { it.packageName?.toString() == targetPackage && predicate(it) }
            nodes.filter { it !== match }.forEach { it.recycle() }
            if (match != null) return match
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < until)
        error("The disposable password fixture did not expose the expected control")
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = find { it.text?.toString() == label }
        while (node != null) {
            val current = node
            current.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            if (current.isClickable) {
                try { assertTrue(current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) } finally { current.recycle() }
                inst.waitForIdleSync(); return
            }
            node = current.parent; current.recycle()
        }
        error("Fixture control is not clickable")
    }
    private fun expect(label: String) { find { it.text?.toString() == label && it.isVisibleToUser }.recycle() }
    private fun clearField(description: String = "测试密码输入框") {
        val node = find { it.isEditable && it.contentDescription?.toString() == description }
        try { assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        })) } finally { node.recycle() }
    }
    private fun capture(folder: File) {
        inst.waitForIdleSync(); SystemClock.sleep(450)
        val bitmap = automation.takeScreenshot() ?: return
        File(folder, "credential-native-filled.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun verifyPrivateCapture(name: String, folder: File, description: String = "测试密码输入框") {
        val service = requireNotNull(DoppelAccessibilityService.instance) { "The real host accessibility service must remain enabled" }
        val field = find { it.isEditable && it.contentDescription?.toString() == description }
        try { field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) } finally { field.recycle() }
        inst.waitForIdleSync(); SystemClock.sleep(500)
        val observation = service.observe()
        assertEquals(targetPackage, observation.getString("package_name"))
        assertFalse("No observed field may export a filled account or password", observation.toString().contains("fixture-only-password-two") || observation.toString().contains("fixture-user"))
        val shot = service.execute(JSONObject().put("id", "credential-privacy-${UUID.randomUUID()}")
            .put("run_id", "credential-privacy-fixture").put("kind", "screenshot").put("split_agent", true).put("mode", "full"))
        assertEquals("Normal password login must retain its screenshot loop: ${shot.optString("message")}", "ok", shot.optString("status"))
        val data = shot.getJSONObject("data")
        assertTrue(data.getInt("privacy_mask_count") > 0)
        val bytes = android.util.Base64.decode(data.getString("image_base64"), android.util.Base64.NO_WRAP)
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            val node = find { it.isEditable && it.contentDescription?.toString() == description }
            val bounds = Rect()
            try { node.getBoundsInScreen(bounds) } finally { node.recycle() }
            val frame = data.getJSONObject("visual_frame")
            val x = (bounds.centerX().toLong() * bitmap.width / frame.getInt("display_width")).toInt().coerceIn(0, bitmap.width - 1)
            val y = (bounds.centerY().toLong() * bitmap.height / frame.getInt("display_height")).toInt().coerceIn(0, bitmap.height - 1)
            assertEquals("Delivered pixels must mask the password even when its native flag changes", 0xff333333.toInt(), bitmap.getPixel(x, y))
            File(folder, "credential-private-$name.png").writeBytes(bytes)
        } finally { bitmap.recycle() }
    }
    private fun protectedState(): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        listOf("doppel", "doppel_credential_vault", "doppel_automatic_unlock_state", "doppel_auto_triggers", "doppel_login").forEach {
            result[it] = context.getSharedPreferences(it, 0).all.toMap()
        }
        listOf("credential-vault-v1.bin", "model-providers-v1.bin", "automatic-unlock-v1.bin", "direct-runs-v1.json").forEach {
            val file = File(context.noBackupFilesDir, it)
            result[it] = if (file.exists()) MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList() else null
        }
        return result
    }
}

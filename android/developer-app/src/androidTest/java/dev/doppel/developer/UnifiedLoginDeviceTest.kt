@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")

package dev.doppel.developer

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.CredentialVault
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.LoginAssist
import dev.doppel.sdk.LoginProfile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Isolated Android Keystore and real native fields; no worker, user vault or model request. */
class UnifiedLoginDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val automation by lazy { inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }
    private val fixture = "dev.doppel.testapp"

    @Test fun legacyMigrationAndSelectedMethodEnforceOneLoginAuthorityWithoutLosingCredentials() {
        assertEquals("This test may only use its disposable APK", "dev.doppel.loginqa", context.packageName)
        assertNull("Never touch a running task", DeviceWorkerService.instance)
        assertTrue(context.getSharedPreferences("doppel", 0).getString("active_run", "").isNullOrBlank())
        assertFalse(LoginAssist.sensitiveSessionActive())
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        assertFalse(keyguard.isDeviceLocked || keyguard.isKeyguardLocked)
        AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        val prefix = "unified-login-${UUID.randomUUID()}-"
        val names = mutableSetOf<String>()
        val directory = File(context.cacheDir, prefix).apply { check(mkdirs()) }
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
        val aliases = listOf("${isolated.packageName}.credential.v1", "${isolated.packageName}.credential.pin.v1", "${isolated.packageName}.login.v1")
        assertTrue(aliases.none(store::containsAlias))
        val folder = File(context.getExternalFilesDir(null), "unified-login/${UUID.randomUUID()}").apply { check(mkdirs()) }
        val checks = JSONObject()
        var passed = false
        var opened = false
        try {
            val admin = CredentialVault(isolated)
            admin.setPin("7352")
            val first = admin.save(CredentialVault.Entry("", fixture, "Fixture first", "fixture-first", "fixture-password-first", true))
            val second = admin.save(CredentialVault.Entry("", fixture, "Fixture second", "fixture-second", "fixture-password-second", true))
            admin.save(CredentialVault.Entry("", "dev.fixture.password", "Password only", "fixture-other", "fixture-password-other", true))
            val originalEntries = admin.entries()
            val vaultFile = File(directory, "credential-vault-v1.bin")
            val originalBytes = vaultFile.readBytes()
            val pinState = isolated.getSharedPreferences("doppel_credential_vault", 0).all.toMap()
            admin.lock()
            val login = LoginAssist(isolated)
            // Seed the old encrypted schema through the store's own writer, not a parallel crypto implementation.
            LoginAssist::class.java.getDeclaredMethod("write", JSONObject::class.java).apply { isAccessible = true }
                .invoke(login, JSONObject().put("profiles", JSONArray()
                    .put(JSONObject().put("package", fixture).put("phone", "19900000013").put("enabled", true).put("signature", "Legacy company"))
                    .put(JSONObject().put("package", "dev.fixture.sms").put("phone", "19900000013").put("enabled", true))))
            val merged = login.profiles().associateBy { it.packageName }
            assertEquals("", merged.getValue(fixture).method)
            assertFalse("Conflicting legacy opt-ins must await an explicit method choice", merged.getValue(fixture).enabled)
            assertEquals("sms", merged.getValue("dev.fixture.sms").method)
            assertTrue(merged.getValue("dev.fixture.sms").enabled)
            assertEquals("password", merged.getValue("dev.fixture.password").method)
            assertTrue(merged.getValue("dev.fixture.password").enabled)
            val reader = CredentialVault(isolated)
            assertEquals(0, reader.taskLabels(fixture).length())
            checks.put("legacy_conflict_disabled", true).put("legacy_single_method_preserved", true)

            context.startActivity(Intent().setClassName(fixture, "$fixture.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            click("密码填写验证"); opened = true
            fun nativeValue(description: String): String {
                val node = find { it.isEditable && it.contentDescription?.toString() == description }
                return try { if (node.isShowingHintText) "" else node.text?.toString().orEmpty() } finally { node.recycle() }
            }
            fun revealedPassword(): String {
                click("显示密码（普通文本）")
                return try {
                    val visible = find { it.isEditable && it.contentDescription?.toString() == "测试密码输入框" &&
                        !it.isPassword && it.text?.any { character -> character != '•' } == true }
                    try { visible.text.toString() } finally { visible.recycle() }
                } finally {
                    click("隐藏密码")
                    find { it.isEditable && it.contentDescription?.toString() == "测试密码输入框" && it.isPassword }.recycle()
                }
            }
            fun clearFields() {
                for (description in listOf("测试账号输入框", "测试密码输入框")) {
                    val node = find { it.isEditable && it.contentDescription?.toString() == description }
                    try { assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                    })) } finally { node.recycle() }
                }
            }
            fun fill(label: String, field: String = "password"): Boolean {
                val description = if (field == "username") "测试账号输入框" else "测试密码输入框"
                val node = find { it.isEditable && it.contentDescription?.toString() == description }
                return try {
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                    if (field == "username") assertTrue(node.isFocused || node.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                    reader.fillForTask(fixture, label, node, field) { true }
                } finally { node.recycle() }
            }
            val password = LoginProfile(fixture, "19900000013", true, "password", first)
            login.save(password)
            assertEquals("Fixture first", reader.taskLabels(fixture).getJSONObject(0).getString("credential_label"))
            assertEquals(1, reader.taskLabels(fixture).length())
            assertThrows(IllegalStateException::class.java) { login.valueFor("login_phone", fixture, "isolated-run") }
            assertFalse("Unselected saved account cannot fill", fill("Fixture second"))
            assertTrue(fill("Fixture first", "username")); assertTrue(fill("Fixture first"))
            assertEquals("fixture-first", nativeValue("测试账号输入框"))
            assertEquals("fixture-password-first", revealedPassword())
            assertFalse(reader.isUnlocked())
            checks.put("selected_password_native_username_and_password_filled", true).put("password_rejects_sms_and_other_account", true)

            clearFields()
            val sms = password.copy(method = "sms", credentialId = "")
            login.save(sms)
            assertEquals(0, reader.taskLabels(fixture).length())
            assertFalse(fill("Fixture first")); assertEquals("", nativeValue("测试密码输入框"))
            assertEquals("19900000013", login.valueFor("login_phone", fixture, "isolated-run"))
            assertTrue(LoginAssist.session.receive("fixture-sms", "fixture-sms", fixture, "登录验证码 482615", System.currentTimeMillis()))
            assertTrue(login.taskStatus(fixture, "isolated-run").getBoolean("code_ready"))
            val candidate = LoginAssist.session.candidates(fixture, "isolated-run").single().id
            login.save(password.copy(credentialId = second))
            assertNull("Changing methods revokes pending SMS codes", LoginAssist.session.consume(fixture, "isolated-run", candidate))
            assertFalse(login.taskStatus(fixture, "isolated-run").getBoolean("code_ready"))
            assertThrows(IllegalStateException::class.java) { login.valueFor("login_code", fixture, "isolated-run", candidate) }
            assertEquals("Fixture second", reader.taskLabels(fixture).getJSONObject(0).getString("credential_label"))
            assertTrue(fill("Fixture second")); assertEquals("fixture-password-second", revealedPassword())
            checks.put("sms_rejects_password_native_fill", true).put("method_switch_revokes_sms_candidate", true)
                .put("second_saved_account_selectable", true)

            clearFields()
            login.save(password.copy(enabled = false, credentialId = second))
            assertEquals(0, reader.taskLabels(fixture).length())
            assertFalse(fill("Fixture second")); assertEquals("", nativeValue("测试密码输入框"))
            assertThrows(IllegalStateException::class.java) { login.valueFor("login_phone", fixture, "isolated-run") }
            login.save(sms)
            login.valueFor("login_phone", fixture, "isolated-run")
            assertTrue(LoginAssist.session.receive("fixture-sms", "fixture-sms", fixture, "登录验证码 915286", System.currentTimeMillis()))
            login.save(sms.copy(enabled = false))
            assertNull("Disabling the master switch invalidates an already received SMS", LoginAssist.session.consume(fixture, "isolated-run"))
            assertThrows(IllegalStateException::class.java) { login.valueFor("login_code", fixture, "isolated-run") }
            assertThrows(IllegalStateException::class.java) { login.valueFor("login_phone", fixture, "isolated-run") }
            assertEquals(0, reader.taskLabels(fixture).length()); assertFalse(fill("Fixture first"))
            checks.put("disabled_password_and_sms_both_rejected", true).put("disabled_sms_revokes_pending_code", true)

            assertTrue("Switching methods must never rewrite saved multi-account passwords", originalBytes.contentEquals(vaultFile.readBytes()))
            assertEquals("The same vault PIN remains configured", pinState, isolated.getSharedPreferences("doppel_credential_vault", 0).all)
            assertTrue(admin.unlock("7352"))
            assertEquals(originalEntries, admin.entries())
            assertEquals("sms", login.profiles().single { it.packageName == "dev.fixture.sms" }.method)
            assertEquals("password", login.profiles().single { it.packageName == "dev.fixture.password" }.method)
            checks.put("all_saved_accounts_and_pin_preserved", true).put("other_app_methods_preserved", true)
            passed = true
        } finally {
            LoginAssist.clearSession()
            if (opened) runCatching { click("返回场景") }
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_HOME)
            names.forEach { context.deleteSharedPreferences(it) }
            aliases.forEach { if (store.containsAlias(it)) store.deleteEntry(it) }
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            check(directory.deleteRecursively())
            File(folder, "report.json").writeText(JSONObject().put("passed", passed).put("checks", checks)
                .put("model_requests", 0).put("isolated_native_fields", true)
                .put("sms_revocation_scope", "Local session contract; actual notification delivery is covered by NativeLoginCodeDeviceTest").toString(2))
            inst.sendStatus(0, Bundle().apply { putString("unified_login_report", folder.absolutePath) })
        }
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val until = SystemClock.elapsedRealtime() + 8000
        do {
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            fun collect(node: AccessibilityNodeInfo) {
                nodes += node
                for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
            }
            automation.rootInActiveWindow?.let(::collect)
            val found = nodes.firstOrNull { it.packageName?.toString() == fixture && predicate(it) }
            nodes.filter { it !== found }.forEach { it.recycle() }
            if (found != null) return found
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < until)
        error("The native login fixture control is unavailable")
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
}

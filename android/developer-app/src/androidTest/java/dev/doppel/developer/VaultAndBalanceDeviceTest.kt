@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")

package dev.doppel.developer

import android.app.Activity
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest

/** Installed UI, real encrypted storage and real preferences. No model calls or task submissions. */
class VaultAndBalanceDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val automation by lazy { inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }
    private val evidence = JSONArray()
    private val folder by lazy { File(context.getExternalFilesDir(null), "full-feature/vault-balance").apply { mkdirs() } }
    private val pin = "7352"
    private val fixtureLabel = "Doppel UI 测试资料"
    private val editedLabel = "Doppel UI 测试资料已编辑"
    private val fixturePackage = "dev.doppel.fixture.login"

    @Test fun passwordManagementCreatesEditsLocksReopensAndDeletesOnlyItsOwnFixture() {
        assertEquals("Password UI regression may only use its disposable APK", "dev.doppel.loginqa", context.packageName)
        assertNull("Never interrupt a task for login settings", DeviceWorkerService.instance)
        assertTrue(context.getSharedPreferences("doppel", 0).getString("active_run", "").isNullOrBlank())
        assertFalse(context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        assertFalse(LoginAssist.sensitiveSessionActive())
        val vaultPrefs = context.getSharedPreferences("doppel_credential_vault", 0)
        val loginPrefs = context.getSharedPreferences("doppel_login", 0)
        val vaultFile = File(context.noBackupFilesDir, "credential-vault-v1.bin")
        val temporaryFile = File(vaultFile.path + ".tmp")
        val aliases = listOf("${context.packageName}.credential.v1", "${context.packageName}.credential.pin.v1", "${context.packageName}.login.v1")
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val pristine = vaultPrefs.all.isEmpty() && loginPrefs.all.isEmpty() && !vaultFile.exists() && !temporaryFile.exists() && aliases.none(store::containsAlias)
        if (!pristine) {
            report("vault", false, true, "Not run: an existing vault, PIN, ciphertext or key must be preserved")
            fail("Cannot run the vault UI fixture on an existing vault; preserve the user's PIN, entries and keys")
        }
        val before = protectedState(excludeVault = true)
        var activity: Activity? = null
        var passed = false
        try {
            activity = launch(LoginSettingsActivity::class.java)
            expectText("设置 4 位 PIN")
            fill("4 位数字 PIN", pin)
            fill("再次输入 PIN", pin)
            click("保存并解锁")
            expectText("已解锁")
            assertTrue("The real UI must persist a PIN", CredentialVault(context).hasPin())
            capture("vault-01-created", activity)

            click("添加应用")
            click("选择应用", description = true)
            click("手动填写包名")
            fill("应用包名", fixturePackage)
            click("登录方式", description = true)
            click("账号密码")
            fill("资料名称，例如 常用账号", fixtureLabel)
            fill("账号或手机号", "fixture-user")
            fill("密码", "fixture-only-password-one")
            click("保存")
            expectText("账号密码 · $fixtureLabel · 编辑")
            assertFixture(fixtureLabel, "fixture-user", "fixture-only-password-one")
            assertFalse("Stored credentials must not be plaintext", vaultFile.readBytes().toString(Charsets.ISO_8859_1).contains("fixture-only-password-one"))

            click(fixturePackage)
            expectText("编辑登录设置")
            fill("资料名称，例如 常用账号", editedLabel)
            fill("账号或手机号", "fixture-edited-user")
            fill("密码", "fixture-only-password-two")
            click("保存")
            expectText("账号密码 · $editedLabel · 编辑")
            assertFixture(editedLabel, "fixture-edited-user", "fixture-only-password-two")
            capture("vault-02-edited", activity)

            click(fixturePackage)
            expectText("编辑登录设置")
            val current = requireNotNull(activity)
            val dialogField = LoginSettingsActivity::class.java.getDeclaredField("activeDialog").apply { isAccessible = true }
            val dialog = ui { dialogField.get(current) as android.app.Dialog }
            fun views(view: android.view.View): List<android.view.View> = listOf(view) +
                if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
            val editorInputs = ui { views(requireNotNull(dialog.window).decorView).filterIsInstance<android.widget.EditText>() }
            assertTrue(editorInputs.isNotEmpty())
            assertTrue("The editor must not duplicate the application-list switch", ui {
                views(requireNotNull(dialog.window).decorView).none { it is android.widget.CompoundButton }
            })
            inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_HOME)
            await("Leaving password management must dismiss its editor and erase editable values") {
                ui { dialogField.get(current) == null && !dialog.isShowing && editorInputs.all { it.text.isEmpty() } }
            }
            context.startActivity(Intent(context, LoginSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            expectText("解锁登录设置")
            assertFalse("An old editor cannot survive over the PIN screen", hasText("编辑登录设置") || hasText("账号密码 · $editedLabel · 编辑"))
            fill("4 位数字 PIN", pin); click("解锁"); expectText("账号密码 · $editedLabel · 编辑")

            click("锁定")
            expectText("解锁登录设置")
            fill("4 位数字 PIN", "0000")
            click("解锁")
            await("A wrong PIN must be rejected") { vaultPrefs.getInt("pin_failures", 0) == 1 }
            expectText("解锁登录设置")
            assertFalse("Credentials must stay hidden after a wrong PIN", hasText("账号密码 · $editedLabel · 编辑"))
            val retryAt = vaultPrefs.getLong("pin_next", 0)
            assertTrue("Wrong PIN must impose its real cooldown", retryAt > SystemClock.elapsedRealtime())
            fill("4 位数字 PIN", pin)
            click("解锁")
            expectText("解锁登录设置")
            assertEquals("A cooldown rejection must not add another wrong attempt", 1, vaultPrefs.getInt("pin_failures", 0))
            capture("vault-03-wrong-pin-rejected", activity)
            // Wait for the production 30-second first-attempt cooldown; never bypass or rewrite it.
            await("The first PIN cooldown must expire normally", 35_000) { SystemClock.elapsedRealtime() >= retryAt }
            click("解锁")
            expectText("已解锁")
            expectText("账号密码 · $editedLabel · 编辑")
            assertEquals(0, vaultPrefs.getInt("pin_failures", -1))

            finish(activity)
            activity = launch(LoginSettingsActivity::class.java)
            expectText("解锁登录设置")
            assertFalse("Reopening must require PIN before displaying saved entries", hasText("账号密码 · $editedLabel · 编辑"))
            fill("4 位数字 PIN", pin)
            click("解锁")
            expectText("账号密码 · $editedLabel · 编辑")
            click(fixturePackage)
            expectText(fixturePackage)
            expectField("资料名称，例如 常用账号", editedLabel)
            expectField("账号或手机号", "fixture-edited-user")
            click("取消")
            assertFixture(editedLabel, "fixture-edited-user", "fixture-only-password-two")
            capture("vault-04-reopened", activity)
            click(fixturePackage)
            click("删除")
            expectText("删除 $fixturePackage 的登录资料？")
            click("删除")
            expectText("已解锁")
            assertFalse("The deleted entry must disappear from the real UI", hasText("账号密码 · $editedLabel · 编辑"))
            val reader = CredentialVault(context)
            assertTrue(reader.unlock(pin))
            assertTrue("Delete must persist an empty vault", reader.entries().isEmpty())
            assertTrue("Delete must also remove its unified login profile", LoginAssist(context).profiles().isEmpty())
            reader.lock()
            capture("vault-05-deleted", activity)
            passed = true
        } finally {
            finish(activity)
            // The precondition proves these exact files, preferences and aliases belong to this test only.
            assertTrue(vaultPrefs.edit().clear().commit())
            context.deleteSharedPreferences("doppel_credential_vault")
            assertTrue(loginPrefs.edit().clear().commit())
            context.deleteSharedPreferences("doppel_login")
            LoginAssist.clearSession()
            listOf(vaultFile, temporaryFile).forEach { if (it.exists()) assertTrue("Remove only test vault files", it.delete()) }
            aliases.forEach { if (store.containsAlias(it)) store.deleteEntry(it) }
            val restored = before == protectedState(excludeVault = true) &&
                context.getSharedPreferences("doppel_credential_vault", 0).all.isEmpty() &&
                context.getSharedPreferences("doppel_login", 0).all.isEmpty() &&
                !vaultFile.exists() && !temporaryFile.exists() && aliases.none(store::containsAlias)
            report("vault", passed && restored, restored, "Real UI; naturally elapsed first wrong-PIN cooldown; secure windows retained")
            assertTrue("Remove only the fixture; preserve all other configuration, encrypted files and task history", restored)
        }
    }

    @Test fun balanceUiResetsAddsPersistsAndShowsItsLowBalanceReminder() {
        preflight()
        val prefs = context.getSharedPreferences("doppel", 0)
        val original = prefs.all.toMap()
        val before = protectedState(excludeMain = true)
        val editableKeys = setOf("balance_amount", "cost_per_million", "balance_alert_threshold", "balance_usage_baseline", "draft_goal")
        var activity: Activity? = null
        var passed = false
        try {
            activity = launch(MainActivity::class.java)
            navigate("用量")
            expectText("余额监控（可选）")
            fill("当前余额（元）", "123.45")
            fill("每百万 Token 费用（元）", "2.5")
            fill("余额提醒阈值（元，可选）", "5")
            click("重新设置余额")
            await("Reset must finish its asynchronous usage read and commit all balance fields") {
                prefs.getString("balance_amount", null) == "123.45" && prefs.getString("cost_per_million", null) == "2.5" &&
                    prefs.getString("balance_alert_threshold", null) == "5" && prefs.contains("balance_usage_baseline")
            }
            expectText("余额 ¥123.45")
            expectField("当前余额（元）", "123.45")
            expectField("每百万 Token 费用（元）", "2.5")
            assertEquals("123.45", prefs.getString("balance_amount", null))
            assertEquals("2.5", prefs.getString("cost_per_million", null))
            assertEquals("5", prefs.getString("balance_alert_threshold", null))
            capture("balance-01-reset", activity)

            fill("当前余额（元）", "6.55")
            fill("每百万 Token 费用（元）", "3.25")
            fill("余额提醒阈值（元，可选）", "1")
            click("充值金额相加")
            await("Recharge must persist its amount and prices before checking the re-rendered form") {
                prefs.getString("balance_amount", "")?.toDoubleOrNull() == 130.0 && prefs.getString("cost_per_million", null) == "3.25" &&
                    prefs.getString("balance_alert_threshold", null) == "1"
            }
            expectText("余额 ¥130.00")
            expectField("当前余额（元）", "130.000000")
            assertEquals(130.0, prefs.getString("balance_amount", "")!!.toDouble(), 0.000001)
            assertEquals("3.25", prefs.getString("cost_per_million", null))
            assertEquals("1", prefs.getString("balance_alert_threshold", null))
            capture("balance-02-added", activity)

            finish(activity)
            activity = launch(MainActivity::class.java)
            navigate("用量")
            expectField("当前余额（元）", "130.000000")
            expectField("每百万 Token 费用（元）", "3.25")
            expectField("余额提醒阈值（元，可选）", "1")
            capture("balance-03-reopened", activity)

            // Zero test price makes the expected remaining amount independent of the user's existing usage.
            fill("当前余额（元）", "7.5")
            fill("每百万 Token 费用（元）", "0")
            fill("余额提醒阈值（元，可选）", "8")
            click("重新设置余额")
            expectText("余额 ¥7.50")
            expectText("余额即将用尽，请及时充值（剩余 ¥7.50）")
            capture("balance-04-low-reminder", activity)
            passed = true
        } finally {
            finish(activity)
            val editor = prefs.edit()
            editableKeys.forEach { key -> restore(editor, key, original[key]) }
            assertTrue(editor.commit())
            val restored = original == prefs.all && before == protectedState(excludeMain = true)
            report("balance", passed && restored, restored, "Existing common per-million price tested; separate input/output price fields are not implemented")
            assertTrue("Restore balance/pricing/draft fields exactly; preserve all other settings and task history", restored)
        }
    }

    private fun preflight() {
        assertTrue("Complete onboarding before this UI regression", FirstUseConsent.isAccepted(context) && !FirstUseConsent.needsGuide(context))
        assertTrue("Use the existing configured local connection", DirectMode.isEnabled(context) && Gateway(context).isConnected())
        assertNull("Stop the worker before settings tests; retain paused tasks", DeviceWorkerService.instance)
        assertFalse("Unlock the emulator first", context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        val file = File(context.noBackupFilesDir, "direct-runs-v1.json")
        val runs = if (file.exists()) dev.doppel.sdk.SplitTaskEngine.readPersistedRuns(file.readText()) else JSONArray()
        repeat(runs.length()) { assertTrue("Do not modify settings while a task executes", runs.getJSONObject(it).optString("status") in setOf("paused", "completed", "failed", "cancelled")) }
    }

    private fun launch(type: Class<out Activity>): Activity = inst.startActivitySync(Intent(context, type)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).also { activity ->
        inst.waitForIdleSync()
        await("The requested page must be foreground") { ui { activity.hasWindowFocus() } }
    }
    private fun finish(activity: Activity?) {
        if (activity == null) return
        ui { if (!activity.isFinishing) activity.finish() }
        inst.waitForIdleSync()
    }
    private fun navigate(section: String) { click("导航菜单", description = true); click(section) }

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (root == null) emptyList() else
        listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val all = nodes(automation.rootInActiveWindow)
        val chosen = all.firstOrNull { it.packageName?.toString() == context.packageName && predicate(it) }
        all.filter { it !== chosen }.forEach { it.recycle() }
        return chosen
    }
    private fun waitNode(label: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        await("Missing UI control: $label") { found = find(predicate); found != null }
        return requireNotNull(found)
    }
    private fun click(label: String, description: Boolean = false) {
        var node: AccessibilityNodeInfo? = waitNode(label) { if (description) it.contentDescription?.toString() == label else it.text?.toString() == label }
        node!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        while (node != null) {
            val current = node
            if (current.isClickable && current.isEnabled) {
                try { assertTrue("UI click must be handled: $label", current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
                finally { current.recycle() }
                inst.waitForIdleSync(); return
            }
            node = current.parent; current.recycle()
        }
        error("No clickable control for $label")
    }
    private fun fill(hint: String, value: String) {
        val field = waitNode(hint) { it.isEditable && it.hintText?.toString() == hint }
        try {
            field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            assertTrue("The actual input must accept text: $hint", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }))
        } finally { field.recycle() }
        inst.waitForIdleSync()
    }
    private fun hasText(label: String): Boolean = find { it.text?.toString() == label && it.isVisibleToUser }?.let { it.recycle(); true } ?: false
    private fun expectText(label: String) {
        val node = waitNode(label) { it.text?.toString() == label }
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id); node.recycle()
        await("UI must show $label") { hasText(label) }
    }
    private fun expectField(hint: String, expected: String) {
        // Accessibility click dispatch returns before the app's rerender reaches the accessibility cache.
        val node = waitNode("$hint must reload its saved value") { it.isEditable && it.hintText?.toString() == hint && it.text?.toString() == expected }
        try { assertEquals("Saved input must reload: $hint", expected, node.text?.toString()) } finally { node.recycle() }
    }
    private fun assertFixture(label: String, username: String, password: String) {
        val reader = CredentialVault(context)
        assertTrue("The saved fixture PIN must unlock actual encrypted storage", reader.unlock(pin))
        try {
            val entry = reader.entries().single()
            assertEquals(fixturePackage, entry.packageName); assertEquals(label, entry.label)
            assertEquals(username, entry.username); assertEquals(password, entry.password)
            assertTrue("The chosen account must be authorized for local task filling", entry.allowTasks)
            val profile = LoginAssist(context).profiles().single()
            assertEquals(fixturePackage, profile.packageName)
            assertEquals("password", profile.method)
            assertEquals(entry.id, profile.credentialId)
            assertTrue("New unified profiles default on and preserve their switch after edits", profile.enabled)
            val toggle = waitNode("The list must expose its login switch") { it.contentDescription?.toString() == "$fixturePackage 登录辅助" }
            try { assertTrue(toggle.isCheckable && toggle.isChecked) } finally { toggle.recycle() }
        } finally { reader.lock() }
    }
    private fun <T> ui(work: () -> T): T { var result: Result<T>? = null; inst.runOnMainSync { result = runCatching(work) }; return result!!.getOrThrow() }
    private fun await(message: String, timeout: Long = 8000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!condition()) { if (SystemClock.elapsedRealtime() >= until) fail(message); SystemClock.sleep(100) }
    }
    private fun capture(name: String, activity: Activity) {
        await("Capture only the foreground page") { ui { activity.hasWindowFocus() && activity.window.decorView.width > 0 } }
        inst.waitForIdleSync(); SystemClock.sleep(450)
        val secure = ui { activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 }
        val bitmap = automation.takeScreenshot()
        if (bitmap != null) {
            File(folder, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
        }
        evidence.put(JSONObject().put("name", name).put("secure_window", secure).put("screenshot_saved", bitmap != null))
    }
    private fun protectedState(excludeVault: Boolean = false, excludeMain: Boolean = false): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        val names = listOf("doppel", "doppel_ui", "doppel_consent", "doppel_credential_vault", "doppel_automatic_unlock_state", "doppel_auto_triggers", "doppel_skill_switches", "doppel_login")
        names.filterNot { excludeVault && it == "doppel_credential_vault" || excludeMain && it == "doppel" }
            .forEach { result["prefs:$it"] = context.getSharedPreferences(it, 0).all.toMap() }
        listOf("model-providers-v1.bin", "credential-vault-v1.bin", "automatic-unlock-v1.bin", "payment-active-grant", "direct-runs-v1.json", "task-review-memory-v1.json")
            .filterNot { excludeVault && it == "credential-vault-v1.bin" }.forEach { name ->
                val file = File(context.noBackupFilesDir, name)
                result["file:$name"] = if (file.exists()) MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList() else null
            }
        return result
    }
    private fun restore(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            else -> error("Unexpected saved setting type")
        }
    }
    private fun report(name: String, passed: Boolean, restored: Boolean, note: String) {
        File(folder, "$name-report.json").writeText(JSONObject().put("passed", passed).put("original_state_preserved", restored)
            .put("model_calls_requested", 0).put("note", note).put("entries", evidence).toString(2))
    }
}

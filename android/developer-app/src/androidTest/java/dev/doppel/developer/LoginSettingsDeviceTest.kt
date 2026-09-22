@file:Suppress("DEPRECATION", "INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.CredentialVault
import dev.doppel.sdk.LoginAssist
import dev.doppel.sdk.LoginSettingsActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Disposable APK only: real PIN UI, login-method changes, encrypted data and lifecycle cleanup. */
class LoginSettingsDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val pin = "7352"
    private var activity: Activity? = null
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun page() = views(requireNotNull(activity).window.decorView)
    private fun dialog(): List<View> = WindowInspector.getGlobalWindowViews().map(::views).last { items ->
        items.filterIsInstance<Button>().any { it.text.toString() == "保存" }
    }
    private fun main(action: () -> Unit) { inst.runOnMainSync(action); inst.waitForIdleSync() }
    private fun click(items: List<View>, text: String) {
        var view: View = items.filterIsInstance<TextView>().first { it.text.toString() == text }
        while (!view.isClickable) view = view.parent as View
        assertTrue("Click $text", view.performClick())
    }
    private fun field(items: List<View>, hint: String) = items.filterIsInstance<EditText>().single { it.hint.toString() == hint }
    private fun choose(title: String, option: String) {
        assertTrue(dialog().single { it.contentDescription?.toString() == title }.performClick())
        val picker = WindowInspector.getGlobalWindowViews().map(::views).last { items ->
            items.filterIsInstance<TextView>().any { it.text.toString() == option }
        }
        click(picker, option)
    }
    private fun open() {
        activity = inst.startActivitySync(Intent(context, LoginSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        inst.waitForIdleSync()
    }
    private fun close() { main { activity?.finish() }; activity = null }
    private fun unlock() = main { field(page(), "4 位数字 PIN").setText(pin); click(page(), "解锁") }

    @Test fun oneAppChoosesOneMethodWhileKeepingAccountsPinAndQuickSwitchState() {
        assertTrue("Use disposable loginqa APK, never user app", context.packageName.startsWith("dev.doppel.loginqa"))
        val login = LoginAssist(context)
        val admin = CredentialVault(context)
        assertTrue("Start with isolated empty login data", login.profiles().isEmpty())
        assertEquals("", login.commonPhone())
        assertFalse("Use a fresh disposable vault", admin.hasPin())
        var addedPackage: String? = null
        try {
            open()
            main {
                assertTrue(page().filterIsInstance<Switch>().isEmpty())
                assertFalse("Profiles stay behind PIN", page().filterIsInstance<TextView>().any { it.text.toString() == "添加应用" })
                field(page(), "4 位数字 PIN").setText(pin)
                field(page(), "再次输入 PIN").setText(pin)
                click(page(), "保存并解锁")
                click(page(), "添加应用")
                val editor = dialog()
                assertTrue("Editor has no enable switch", editor.filterIsInstance<Switch>().isEmpty())
                assertTrue("No sender-service field", editor.filterIsInstance<EditText>().none { it.hint.toString().contains("短信服务") })
                field(editor, "使用常用手机号").setText("+8613800138000")
                click(editor, "保存")
            }
            val created = login.profiles().single()
            addedPackage = created.packageName
            assertTrue("New app enabled immediately", created.enabled)
            assertEquals("sms", created.method)
            main {
                val unsavedPhone = field(page(), "手机号")
                unsavedPhone.setText("+8613900139000")
                val toggle = page().filterIsInstance<Switch>().single()
                toggle.performClick(); assertFalse(toggle.isChecked)
                assertEquals("Toggle preserves in-progress input", "+8613900139000", unsavedPhone.text.toString())
                click(page(), "短信验证码 · 编辑")
                field(dialog(), "使用常用手机号").setText("+8613700137000")
                click(dialog(), "保存")
            }
            assertFalse("Editing must retain disabled state", login.profiles().single().enabled)
            assertTrue(admin.unlock(pin))
            val first = admin.save(CredentialVault.Entry("", created.packageName, "常用账号", "fixture-first", "fixture-first-secret", true))
            val second = admin.save(CredentialVault.Entry("", created.packageName, "备用账号", "fixture-second", "fixture-second-secret", true))
            val originalAccounts = admin.entries().associateBy { it.id }
            main {
                click(page(), "短信验证码 · 编辑")
                choose("登录方式", "账号密码")
                choose("选择账号", "备用账号")
                assertEquals("fixture-second", field(dialog(), "账号或手机号").text.toString())
                click(dialog(), "保存")
            }
            var profile = login.profiles().single()
            assertEquals("password", profile.method)
            assertEquals(second, profile.credentialId)
            assertFalse("Changing method must not re-enable an existing disabled app", profile.enabled)
            assertEquals(originalAccounts, admin.entries().associateBy { it.id })
            main { page().filterIsInstance<Switch>().single().performClick() }
            assertTrue(login.profiles().single().enabled)
            assertEquals(1, admin.taskLabels(created.packageName).length())
            assertEquals("备用账号", admin.taskLabels(created.packageName).getJSONObject(0).getString("credential_label"))
            assertThrows(IllegalStateException::class.java) { login.valueFor("login_phone", created.packageName, "ui-check") }
            main {
                click(page(), "账号密码 · 备用账号 · 编辑")
                choose("登录方式", "短信验证码")
                assertEquals("+8613700137000", field(dialog(), "使用常用手机号").text.toString())
                click(dialog(), "保存")
            }
            assertEquals("sms", login.profiles().single().method)
            assertEquals(0, admin.taskLabels(created.packageName).length())
            assertEquals("Switching to SMS retains every password", originalAccounts, admin.entries().associateBy { it.id })
            main {
                click(page(), "短信验证码 · 编辑")
                choose("登录方式", "账号密码")
                choose("选择账号", "新增账号")
                field(dialog(), "资料名称，例如 常用账号").setText("新账号")
                field(dialog(), "账号或手机号").setText("fixture-new")
                field(dialog(), "密码").setText("fixture-new-secret")
                click(dialog(), "保存")
            }
            profile = login.profiles().single()
            assertTrue(profile.enabled)
            assertEquals("password", profile.method)
            assertTrue(profile.credentialId.isNotBlank() && profile.credentialId !in setOf(first, second))
            assertEquals(3, admin.entries().size)
            assertEquals(originalAccounts, admin.entries().filter { it.id in originalAccounts }.associateBy { it.id })
            assertEquals("新账号", admin.taskLabels(created.packageName).getJSONObject(0).getString("credential_label"))
            main { page().filterIsInstance<Switch>().single().performClick() }
            val newAccount = admin.entries().single { it.id == profile.credentialId }
            admin.save(newAccount.copy(allowTasks = false))
            main {
                page().filterIsInstance<Switch>().single().performClick()
                assertFalse("A switch cannot grant an unconsented account", page().filterIsInstance<Switch>().single().isChecked)
                assertTrue("Unconsented account opens editor", dialog().filterIsInstance<TextView>().any { it.text.toString() == "编辑登录设置" })
            }
            assertFalse(login.profiles().single().enabled)
            assertFalse(admin.entries().single { it.id == newAccount.id }.allowTasks)
            main { click(dialog(), "保存") }
            assertTrue("Explicit editor save authorizes this account only", admin.entries().single { it.id == newAccount.id }.allowTasks)
            assertFalse(login.profiles().single().enabled)
            var editorInputs = emptyList<EditText>()
            main {
                click(page(), "账号密码 · 新账号 · 编辑")
                editorInputs = dialog().filterIsInstance<EditText>()
                assertTrue(editorInputs.any { it.text.toString() == "fixture-new-secret" })
                assertTrue(dialog().single { it.contentDescription?.toString() == "选择账号" }.performClick())
            }
            assertEquals(0, admin.taskLabels(created.packageName).length())
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_HOME)
            val until = SystemClock.elapsedRealtime() + 5000
            var cleared = false
            while (!cleared && SystemClock.elapsedRealtime() < until) {
                main { cleared = editorInputs.all { it.text.isEmpty() } }
                if (!cleared) Thread.sleep(100)
            }
            assertTrue("Leaving clears all editor inputs", cleared)
            main { assertFalse("Child account picker must also close", WindowInspector.getGlobalWindowViews().map(::views).any { items ->
                items.filterIsInstance<TextView>().any { it.text.toString() == "选择账号" }
            }) }
            close(); open()
            main {
                assertTrue("Profiles hidden until PIN", page().filterIsInstance<Switch>().isEmpty())
                assertTrue(page().filterIsInstance<TextView>().any { it.text.toString() == "解锁登录设置" })
            }
            unlock()
            main { assertFalse("Disabled state survives reopening", page().filterIsInstance<Switch>().single().isChecked) }
            assertEquals(3, admin.entries().size)
            File(context.getExternalFilesDir(null), "login-settings-check.json").writeText(JSONObject()
                .put("pin_required", true).put("new_enabled", true).put("one_login_method", true)
                .put("editor_without_switch_or_service_hint", true).put("quick_toggle_preserves_unsaved_phone", true)
                .put("edit_preserves_disabled", true).put("mode_switch_keeps_all_accounts", true)
                .put("unconsented_password_switch_opens_editor_without_grant", true)
                .put("new_account_keeps_old_accounts", true).put("leaving_clears_inputs_and_relocks", true)
                .put("recreated_activity_preserves_switch", true).put("model_calls", 0).toString(2))
        } finally {
            close()
            addedPackage?.let { pkg ->
                if (admin.unlock(pin)) admin.entries().filter { it.packageName == pkg }.forEach { admin.remove(it.id) }
                login.remove(pkg)
            }
            admin.lock()
        }
    }
}

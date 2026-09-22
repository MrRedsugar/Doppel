package dev.doppel.sdk

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*

/** One PIN-protected manager; task secrets remain in their existing encrypted stores. */
class LoginSettingsActivity : Activity() {
    private val login by lazy { LoginAssist(this) }
    private val vault by lazy { CredentialVault(this) }
    private lateinit var content: LinearLayout
    private var activeDialog: UiDialog? = null
    private val dialogs = linkedSetOf<UiDialog>()
    private var setupHandled = false
    private val privacyNotice = "账号、密码和手机号仅加密保存在本机，不上传至 Doppel 服务器或云端 AI 模型，不参与云端同步或备份。自动登录时由本机填入授权应用；登录信息的提交与验证由该应用自行处理。"
    private fun dp(value: Int) = UiTheme.dp(this, value)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        UiTheme.init(this); render()
    }
    override fun onResume() {
        super.onResume(); LoginAssist.settingsVisible = true; DirectMode.enterSettings(this)
        DeviceWorkerService.instance?.let { if (it.isPaused) it.dismissPauseNotice() else it.suspendLocally() }; render()
    }
    override fun onPause() {
        dialogs.toList().forEach { it.dismiss() }
        if (::content.isInitialized) { clearInputs(content); content.removeAllViews() }
        vault.lock(); LoginAssist.settingsVisible = false; DirectMode.leaveSettings(this); super.onPause()
    }
    override fun onDestroy() { DirectMode.leaveSettings(this); super.onDestroy() }
    private fun render() {
        if (::content.isInitialized) clearInputs(content)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(UiTheme.background) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(24), dp(6)) }
        header.addView(UiTheme.icon(this, UiIcons.back, "返回") { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(UiTheme.text(this, "登录设置", 18f, bold = true)); root.addView(header)
        root.addView(UiTheme.divider(this))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(32)) }
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        UiTheme.window(this, root); setContentView(root)
        try {
            if (!vault.hasPin()) pinPage(true) else if (!vault.isUnlocked()) pinPage(false) else unlocked()
        } catch (e: Exception) { content.addView(UiTheme.text(this, e.message ?: "登录资料暂时无法读取", color = UiTheme.danger)) }
    }
    private fun pinPage(create: Boolean) {
        content.addView(UiTheme.text(this, if (create) "设置 4 位 PIN" else "解锁登录设置", 23f, bold = true))
        content.addView(UiTheme.text(this, "$privacyNotice\n\nPIN 保护登录资料管理页面。离开后会重新锁定。忘记原 PIN 无法恢复已保存的密码。", 13f, UiTheme.muted).apply { setPadding(0, dp(12), 0, dp(18)) })
        val pin = UiTheme.field(this, "4 位数字 PIN", secret = true).apply { inputType = 2 or 16 }
        content.addView(pin)
        val confirm = if (create) UiTheme.field(this, "再次输入 PIN", secret = true).apply { inputType = 2 or 16 }.also {
            content.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        } else null
        content.addView(UiTheme.command(this, if (create) "保存并解锁" else "解锁", true) {
            attempt {
                if (create) {
                    check(!vault.hasPin()) { "已有 PIN，请重新进入并解锁" }
                    check(pin.text.toString() == confirm?.text.toString()) { "两次 PIN 不一致" }
                    vault.setPin(pin.text.toString())
                } else check(vault.unlock(pin.text.toString())) { "PIN 不正确或暂时锁定，请稍后再试" }
                render()
            }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(16) })
    }
    private fun unlocked() {
        content.addView(UiTheme.text(this, "已解锁", 21f, bold = true))
        content.addView(UiTheme.text(this, privacyNotice, 13f, UiTheme.muted).apply { setPadding(0, dp(10), 0, dp(12)) })
        content.addView(UiTheme.command(this, "锁定") { vault.lock(); render() })
        heading("常用手机号")
        val phone = UiTheme.field(this, "手机号", login.commonPhone()).apply { inputType = android.text.InputType.TYPE_CLASS_PHONE }
        content.addView(phone)
        content.addView(UiTheme.command(this, "保存手机号") { attempt {
            check(vault.isUnlocked()) { "请重新解锁登录设置" }
            login.saveCommonPhone(phone.text.toString().trim()); toast("已保存到本机")
        } }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        heading("短信验证码")
        content.addView(UiTheme.row(this, "短信通知访问", if (login.notificationAccess()) "已授权" else "未授权", android.R.drawable.ic_dialog_email) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        content.addView(UiTheme.text(this, "获取或重发登录验证码时，自动识别新收到的短信通知，无需填写短信服务名称。验证码留在本机，AI 只根据隐藏验证码后的短信内容选择。", 13f, UiTheme.muted).apply { setPadding(0, dp(8), 0, dp(8)) })
        heading("已设置应用")
        val profiles = login.profiles()
        val entries = vault.entries()
        if (profiles.isEmpty()) content.addView(UiTheme.text(this, "暂无登录设置", 14f, UiTheme.muted))
        for (profile in profiles) {
            var current = profile
            val name = appName(profile.packageName)
            val detail = when (profile.method) {
                "sms" -> "短信验证码 · 编辑"
                "password" -> "账号密码 · " + (entries.singleOrNull { it.id == profile.credentialId }?.label ?: "已有账号") + " · 编辑"
                else -> "待选择登录方式"
            }
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(UiTheme.row(this, name, detail, UiIcons.lock) { editProfile(current) }, LinearLayout.LayoutParams(0, -2, 1f))
            val enabled = UiTheme.toggle(this, "", profile.enabled).apply { contentDescription = "$name 登录辅助" }
            var restoring = false
            enabled.setOnCheckedChangeListener { button, checked ->
                if (!restoring) try {
                    check(vault.isUnlocked()) { "请重新解锁登录设置" }
                    val passwordReady = current.method != "password" || vault.entries().filter {
                        it.packageName == current.packageName && it.allowTasks &&
                            (current.credentialId.isBlank() || it.id == current.credentialId)
                    }.groupBy { it.label }.values.any { it.size == 1 && it.single().password.isNotEmpty() }
                    if (current.method.isBlank() || (checked && !passwordReady)) {
                        restoring = true; button.isChecked = false; restoring = false
                        editProfile(current)
                    } else {
                        val updated = current.copy(enabled = checked)
                        login.save(updated); current = updated
                    }
                } catch (e: Exception) {
                    restoring = true; button.isChecked = current.enabled; restoring = false
                    toast(e.message ?: "保存失败")
                }
            }
            row.addView(enabled, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
            content.addView(row); content.addView(UiTheme.divider(this))
        }
        content.addView(UiTheme.command(this, "添加应用", true) { editProfile(null) }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(16) })
        val requestedPackage = intent.getStringExtra("package_name").orEmpty()
        if (!setupHandled && requestedPackage.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) {
            setupHandled = true
            content.post { if (!isFinishing && vault.isUnlocked()) editProfile(profiles.singleOrNull { it.packageName == requestedPackage }, requestedPackage) }
        }
    }
    private fun editProfile(existing: LoginProfile?, requestedPackage: String? = null) {
        if (!vault.isUnlocked()) { render(); return }
        val allEntries = vault.entries()
        val configured = login.profiles().map { it.packageName }.toSet()
        val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName && (existing != null || it.activityInfo.packageName !in configured) }
            .distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(packageManager).toString() }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var selectedPackage = existing?.packageName ?: requestedPackage ?: apps.firstOrNull()?.activityInfo?.packageName.orEmpty()
        val manualIndex = apps.size
        var appIndex = apps.indexOfFirst { it.activityInfo.packageName == selectedPackage }.let { if (it < 0) manualIndex else it }
        val packageField = UiTheme.field(this, "应用包名", selectedPackage).apply { visibility = if (existing == null && appIndex == manualIndex) View.VISIBLE else View.GONE }
        val phone = UiTheme.field(this, "使用常用手机号", existing?.phone.orEmpty()).apply { inputType = android.text.InputType.TYPE_CLASS_PHONE }
        var method = existing?.method ?: "sms"
        var account: CredentialVault.Entry? = null
        var preserveAccounts = false
        var accountChoiceMissing = false
        var label: EditText? = null
        var username: EditText? = null
        var password: EditText? = null
        val accountPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun accountFields(entry: CredentialVault.Entry?) {
            account = entry
            label = UiTheme.field(this, "资料名称，例如 常用账号", entry?.label.orEmpty())
            username = UiTheme.field(this, "账号或手机号", entry?.username.orEmpty())
            password = UiTheme.field(this, "密码", entry?.password.orEmpty(), true)
        }
        fun showAccounts() {
            clearInputs(accountPanel); accountPanel.removeAllViews()
            val available = allEntries.filter { it.packageName == selectedPackage }
            val bound = available.indexOfFirst { it.id == existing?.credentialId }
            val canPreserve = available.filter { it.allowTasks }.groupBy { it.label }.values.any { it.size == 1 }
            val options = listOf(if (canPreserve) "沿用已授权账号" else "选择账号") + available.map { it.label } + "新增账号"
            val initial = if (bound >= 0) bound + 1 else if (available.isEmpty()) options.lastIndex else 0
            val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            fun select(index: Int) {
                preserveAccounts = index == 0 && canPreserve
                accountChoiceMissing = index == 0 && !canPreserve
                clearInputs(fields); fields.removeAllViews()
                accountFields(available.getOrNull(index - 1))
                if (index != 0) listOfNotNull(label, username, password).forEach {
                    fields.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                }
            }
            accountPanel.addView(selector("选择账号", options, initial, ::select))
            accountPanel.addView(fields); select(initial)
        }
        if (existing == null) form.addView(selector("选择应用", apps.map { it.loadLabel(packageManager).toString() } + "手动填写包名", appIndex) {
            appIndex = it; selectedPackage = apps.getOrNull(it)?.activityInfo?.packageName.orEmpty()
            packageField.visibility = if (it == manualIndex) View.VISIBLE else View.GONE
            packageField.setText(selectedPackage); showAccounts()
        }) else form.addView(UiTheme.text(this, appName(existing.packageName), 15f, bold = true))
        form.addView(packageField)
        val methods = if (method.isBlank()) listOf("选择登录方式", "短信验证码", "账号密码") else listOf("短信验证码", "账号密码")
        fun showMethod() { phone.visibility = if (method == "sms") View.VISIBLE else View.GONE; accountPanel.visibility = if (method == "password") View.VISIBLE else View.GONE }
        form.addView(selector("登录方式", methods, if (method == "password") methods.lastIndex else 0) {
            method = when (methods[it]) { "短信验证码" -> "sms"; "账号密码" -> "password"; else -> "" }; showMethod()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        form.addView(phone, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        form.addView(accountPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        showAccounts(); showMethod()
        form.addView(UiTheme.text(this, "每个应用使用一种登录方式。切换方式或关闭开关不会删除已保存资料。", 12f, UiTheme.muted).apply { setPadding(0, dp(12), 0, 0) })
        val builder = UiDialog.Builder(this).setTitle(if (existing == null) "添加登录设置" else "编辑登录设置")
            .setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null)
        if (existing != null) builder.setNeutralButton("删除") { _, _ ->
            val confirm = UiDialog.Builder(this).setTitle("删除 ${appName(existing.packageName)} 的登录资料？")
                .setMessage("将删除此应用的手机号设置及全部已保存账号和密码，其他应用不受影响。")
                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> attempt {
                    vault.entries().filter { it.packageName == existing.packageName }.forEach { vault.remove(it.id) }
                    login.remove(existing.packageName); render()
                } }.show()
            secureDialog(confirm, null)
        }
        val shown = builder.show(); secureDialog(shown, form)
        shown.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            attempt {
                check(vault.isUnlocked()) { "请重新解锁登录设置" }
                val pkg = if (existing != null) existing.packageName else if (appIndex == manualIndex) packageField.text.toString().trim() else selectedPackage
                require(pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) { "应用包名不正确" }
                check(existing != null || login.profiles().none { it.packageName == pkg }) { "此应用已有设置，请返回列表编辑" }
                require(method in setOf("sms", "password")) { "请选择登录方式" }
                val valuePhone = if (method == "sms") phone.text.toString().trim() else existing?.phone.orEmpty()
                require(valuePhone.isEmpty() || valuePhone.matches(Regex("\\+?[1-9][0-9]{7,14}"))) { "手机号格式不正确" }
                val enabled = existing?.takeUnless { it.method.isBlank() }?.enabled ?: true
                var credentialId = existing?.credentialId.orEmpty()
                var savedId: String? = null
                val previous = account
                if (method == "password") {
                    check(!accountChoiceMissing) { "请选择已有账号或新增账号" }
                    if (preserveAccounts) credentialId = "" else {
                        val entry = CredentialVault.Entry(account?.id.orEmpty(), pkg, label?.text.toString().trim(), username?.text.toString(), password?.text.toString(), true)
                        require(entry.label.length in 1..80) { "请输入资料名称" }
                        require(entry.username.length <= 256 && entry.password.length in 1..512) { "账号或密码格式不正确" }
                        credentialId = vault.save(entry); savedId = credentialId
                    }
                }
                try { login.save(LoginProfile(pkg, valuePhone, enabled, method, credentialId)) }
                catch (failure: Exception) {
                    savedId?.let { id -> if (previous == null) vault.remove(id) else vault.save(previous) }
                    throw failure
                }
                shown.dismiss(); render()
            }
        }
    }
    private fun secureDialog(dialog: UiDialog, fields: View?) {
        dialogs.add(dialog); activeDialog = dialog; dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener { fields?.let(::clearInputs); dialogs.remove(dialog); activeDialog = dialogs.lastOrNull() }
    }
    private fun selector(title: String, options: List<String>, initial: Int, selected: (Int) -> Unit): TextView {
        var current = initial
        val view = UiTheme.selector(this, title, options, current) {}
        view.setOnClickListener {
            val dialog = UiDialog.Builder(this).setTitle(title).setSingleChoiceItems(options.toTypedArray(), current) { sheet, index ->
                current = index; view.text = options[index]; selected(index); sheet.dismiss()
            }.setNegativeButton("关闭", null).show()
            secureDialog(dialog, null)
        }
        return view
    }
    private fun clearInputs(view: View) {
        if (view is EditText) view.text?.clear()
        if (view is ViewGroup) for (index in 0 until view.childCount) clearInputs(view.getChildAt(index))
    }
    private fun heading(title: String) { content.addView(UiTheme.text(this, title, 13f, UiTheme.muted, true).apply { setPadding(0, dp(24), 0, dp(12)) }) }
    private fun attempt(work: () -> Unit) { try { work() } catch (e: Exception) { toast(e.message ?: "保存失败") } }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun appName(pkg: String) = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
}

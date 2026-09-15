package dev.doppel.sdk

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.*

class PasswordSettingsActivity : Activity() {
    private val vault by lazy { CredentialVault(this) }
    private lateinit var content: LinearLayout
    private var activeDialog: UiDialog? = null
    private fun dp(v: Int) = UiTheme.dp(this, v)
    override fun onCreate(state: Bundle?) { super.onCreate(state); window.addFlags(WindowManager.LayoutParams.FLAG_SECURE); UiTheme.init(this); render() }
    override fun onResume() { super.onResume(); DirectMode.enterSettings(this); DeviceWorkerService.instance?.suspendLocally(); render() }
    override fun onPause() { activeDialog?.dismiss(); clearInputs(content); content.removeAllViews(); vault.lock(); DirectMode.leaveSettings(this); super.onPause() }
    override fun onDestroy() { DirectMode.leaveSettings(this); super.onDestroy() }
    private fun render() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(UiTheme.background) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(24), dp(6)) }
        header.addView(UiTheme.icon(this, UiIcons.back, "返回") { finish() }, LinearLayout.LayoutParams(dp(44), dp(44))); header.addView(UiTheme.text(this, "密码管理", 18f, UiTheme.ink, true)); root.addView(header); root.addView(UiTheme.divider(this))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(32)) }; root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f)); UiTheme.window(this, root); setContentView(root)
        if (!vault.hasPin()) setup(root) else if (!vault.isUnlocked()) unlock(root) else unlocked(root)
    }
    private fun setup(root: LinearLayout) {
        content.addView(UiTheme.text(this, "设置 4 位 PIN", 23f, UiTheme.ink, true)); content.addView(UiTheme.text(this, "PIN 只用于进入此页面和临时解锁密码资料。忘记 PIN 后无法恢复已保存密码。", 13f, UiTheme.muted).apply { setPadding(0, dp(10), 0, dp(18)) })
        val pin = UiTheme.field(this, "4 位数字 PIN", secret = true).apply { inputType = 2 or 16 }; val confirm = UiTheme.field(this, "再次输入 PIN", secret = true).apply { inputType = 2 or 16 }; content.addView(pin); content.addView(confirm, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }); content.addView(UiTheme.command(this, "保存并解锁", true) { runCatching { check(pin.text.toString() == confirm.text.toString()) { "两次 PIN 不一致" }; vault.setPin(pin.text.toString()); render() }.onFailure { toast(it.message ?: "保存失败") } }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(16) })
    }
    private fun unlock(root: LinearLayout) {
        content.addView(UiTheme.text(this, "解锁密码管理", 23f, UiTheme.ink, true)); content.addView(UiTheme.text(this, "密码只会在本机受保护区域内使用，不会发送给模型。", 13f, UiTheme.muted).apply { setPadding(0, dp(10), 0, dp(18)) }); val pin = UiTheme.field(this, "4 位数字 PIN", secret = true).apply { inputType = 2 or 16 }; content.addView(pin); content.addView(UiTheme.command(this, "解锁", true) { if (runCatching { vault.unlock(pin.text.toString()) }.getOrDefault(false)) render() else toast("PIN 不正确或暂时锁定，请稍后再试") }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(16) })
    }
    private fun unlocked(root: LinearLayout) {
        content.addView(UiTheme.text(this, "已解锁", 23f, UiTheme.ink, true)); content.addView(UiTheme.text(this, "PIN 保护此管理页面。允许任务填写的资料可由你发起或已开启的自动任务在指定应用中使用，密码不会发送给模型。", 13f, UiTheme.muted).apply { setPadding(0, dp(10), 0, dp(16)) }); content.addView(UiTheme.command(this, "锁定", false) { vault.lock(); render() }); content.addView(UiTheme.divider(this)); vault.entries().forEach { e -> val id = e.id; content.addView(UiTheme.row(this, e.label, "${e.packageName}\n账号：${e.username}\n${if(e.allowTasks) "允许任务填写" else "未授权任务填写"}", UiIcons.lock) { edit(id) }); content.addView(UiTheme.divider(this)) }; content.addView(UiTheme.command(this, "添加登录资料", true) { edit(null) }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(16) })
    }
    private fun edit(id: String?) {
        if (!vault.isUnlocked()) { render(); return }
        val existing = if (id == null) null else runCatching { vault.entries().single { it.id == id } }.getOrElse { toast("登录资料不可读取，请重新解锁"); render(); return }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; val pkg = UiTheme.field(this, "应用包名", existing?.packageName.orEmpty()); val label = UiTheme.field(this, "名称，例如 京东", existing?.label.orEmpty()); val user = UiTheme.field(this, "账号或手机号", existing?.username.orEmpty()); val pass = UiTheme.field(this, "密码", existing?.password.orEmpty(), true); listOf(pkg, label, user, pass).forEach { form.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
        val allowTasks = UiTheme.check(this, "允许任务在此应用填写", existing?.allowTasks ?: true)
        form.addView(allowTasks)
        form.addView(UiTheme.text(this, "开启后，你发起或允许的自动任务可在此应用的登录密码框填写；设备锁屏、任务停止或支付验证时不会填写。关闭不删除资料。", 12f, UiTheme.muted))
        val dialog = UiDialog.Builder(this).setTitle(if (id == null) "添加登录资料" else "编辑登录资料").setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null)
        if (id != null) dialog.setNeutralButton("删除") { _, _ -> runCatching { vault.remove(id) }.onFailure { toast(it.message ?: "删除失败") }; render() }
        val shown = dialog.show(); activeDialog = shown
        shown.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        shown.setOnDismissListener { clearInputs(form); if (activeDialog === shown) activeDialog = null }
        shown.getButton(-1).setOnClickListener { runCatching { vault.save(CredentialVault.Entry(id.orEmpty(), pkg.text.toString().trim(), label.text.toString().trim(), user.text.toString(), pass.text.toString(), allowTasks.isChecked)); shown.dismiss(); render() }.onFailure { toast(it.message ?: "保存失败") } }
    }
    private fun clearInputs(view: android.view.View) {
        if (view is EditText) view.text?.clear()
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) clearInputs(view.getChildAt(i))
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

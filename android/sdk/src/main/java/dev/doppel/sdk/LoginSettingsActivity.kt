package dev.doppel.sdk

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class LoginSettingsActivity : Activity() {
    private val login by lazy { LoginAssist(this) }
    private lateinit var content: LinearLayout
    private fun dp(value: Int) = UiTheme.dp(this, value)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        render()
    }
    override fun onResume() { super.onResume(); LoginAssist.settingsVisible = true; if (::content.isInitialized) render() }
    override fun onPause() { LoginAssist.settingsVisible = false; super.onPause() }
    private fun render() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(UiTheme.background) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(24), dp(6)) }
        header.addView(UiTheme.icon(this, android.R.drawable.ic_media_previous, "返回") { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(UiTheme.text(this, "登录辅助", 18f, bold = true)); root.addView(header)
        root.addView(UiTheme.divider(this))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(2), dp(24), dp(32)) }
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        UiTheme.window(this, root); setContentView(root)
        try {
            heading("常用手机号")
            val phone = UiTheme.field(this, "手机号", login.commonPhone()).apply { inputType = android.text.InputType.TYPE_CLASS_PHONE }
            content.addView(phone)
            content.addView(UiTheme.command(this, "保存手机号", true) { attempt { login.saveCommonPhone(phone.text.toString().trim()); Toast.makeText(this, "已保存到本机", Toast.LENGTH_SHORT).show() } }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
            heading("验证码")
            content.addView(UiTheme.row(this, "短信通知访问", if (login.notificationAccess()) "已授权" else "未授权", android.R.drawable.ic_dialog_email) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
            content.addView(UiTheme.divider(this))
            heading("已授权应用")
            val profiles = login.profiles()
            if (profiles.isEmpty()) content.addView(UiTheme.text(this, "暂无授权应用", 14f, UiTheme.muted).apply { setPadding(0, dp(4), 0, dp(8)) })
            for (profile in profiles) {
                val appName = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(profile.packageName, 0)).toString() } catch (_: Exception) { profile.packageName }
                content.addView(UiTheme.row(this, appName, "${if (profile.enabled) "已授权" else "已停用"} · ${profile.signature}", android.R.drawable.ic_menu_myplaces) { editProfile(profile) })
                content.addView(UiTheme.divider(this))
            }
            content.addView(UiTheme.command(this, "添加应用") { editProfile(null) }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(16) })
        } catch (e: IllegalStateException) { content.addView(UiTheme.text(this, e.message.orEmpty(), color = UiTheme.danger)) }
        heading("本机资料")
        content.addView(UiTheme.row(this, "删除全部登录资料", "常用手机号与应用授权", android.R.drawable.ic_menu_delete) {
            AlertDialog.Builder(this).setTitle("删除登录资料？").setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> login.clearAll(); render() }.show()
        })
    }
    private fun heading(title: String) { content.addView(UiTheme.text(this, title, 13f, UiTheme.muted, true).apply { setPadding(0, dp(26), 0, dp(12)) }) }
    private fun attempt(work: () -> Unit) { try { work() } catch (e: Exception) { Toast.makeText(this, e.message ?: "保存失败", Toast.LENGTH_LONG).show() } }
    private fun editProfile(existing: LoginProfile?) {
        val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName }.distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(packageManager).toString() }
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(16)) }
        val selected = Spinner(this).apply {
            adapter = ArrayAdapter(this@LoginSettingsActivity, android.R.layout.simple_spinner_dropdown_item, apps.map { it.loadLabel(packageManager).toString() })
            minimumHeight = dp(48)
        }
        if (existing == null) fields.addView(selected) else fields.addView(UiTheme.text(this, existing.packageName, 13f, UiTheme.muted))
        val phone = UiTheme.field(this, "使用常用手机号", existing?.phone.orEmpty()).apply { inputType = android.text.InputType.TYPE_CLASS_PHONE }
        val marker = UiTheme.field(this, "短信签名，例如：美团", existing?.signature.orEmpty())
        val enabled = Switch(this).apply {
            text = "允许自动填入手机号与登录验证码"; isChecked = existing?.enabled ?: false; minHeight = dp(64)
            textSize = 14f; letterSpacing = 0f; setTextColor(UiTheme.ink); setPadding(0, dp(12), 0, dp(8)); switchPadding = dp(12)
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(UiTheme.ink, UiTheme.muted))
        }
        fields.addView(phone, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        fields.addView(marker, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        fields.addView(enabled)
        val dialog = AlertDialog.Builder(this).setTitle(if (existing == null) "授权应用" else "应用登录资料")
            .setView(ScrollView(this).apply { addView(fields) }).setNegativeButton("取消", null).setPositiveButton("保存", null)
        if (existing != null) dialog.setNeutralButton("删除") { _, _ -> attempt { login.remove(existing.packageName); render() } }
        val shown = dialog.show()
        shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            attempt {
                val app = existing?.packageName ?: apps.getOrNull(selected.selectedItemPosition)?.activityInfo?.packageName ?: error("没有可授权应用")
                login.save(LoginProfile(app, phone.text.toString().trim(), marker.text.toString().trim(), enabled.isChecked)); shown.dismiss(); render()
            }
        }
    }
}

package dev.doppel.sdk

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

class LegalActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); UiTheme.init(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        UiTheme.bind(root) { root.setBackgroundColor(UiTheme.background) }; UiTheme.window(this, root)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(16), dp(8)) }
        header.addView(UiTheme.icon(this, UiIcons.back, "返回") { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val privacy = intent.getBooleanExtra("privacy", false)
        header.addView(UiTheme.text(this, if (privacy) "隐私说明" else "使用条款", 18f, UiTheme.ink, true))
        root.addView(header)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(32)) }
        content.addView(UiTheme.text(this, if (privacy) LegalDocuments.privacy else LegalDocuments.terms, 15f).apply {
            setLineSpacing(dp(6).toFloat(), 1f); setTextIsSelectable(true)
        })
        if (FirstUseConsent.isAccepted(this)) {
            val record = FirstUseConsent.record(this)
            val date = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(record.acceptedAt))
            content.addView(UiTheme.text(this, "本机确认时间：$date", 12f, UiTheme.muted).apply { setPadding(0, dp(24), 0, dp(12)) })
            content.addView(UiTheme.command(this, "撤回本机同意") {
                UiDialog.Builder(this).setTitle("撤回同意？").setMessage("执行服务将停止。已有任务、文件和第三方副本不会因此自动删除。")
                    .setNegativeButton("保留", null).setPositiveButton("撤回") { _, _ ->
                        if (FirstUseConsent.revoke(this)) {
                            DeviceWorkerService.instance?.cancel(); stopService(Intent(this, DeviceWorkerService::class.java))
                            startActivity(Intent(this, OnboardingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                            finish()
                        } else Toast.makeText(this, "同意记录未更新，请重试", Toast.LENGTH_LONG).show()
                    }.show()
            })
        }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }
    private fun dp(value: Int) = UiTheme.dp(this, value)
}

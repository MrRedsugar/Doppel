package dev.doppel.sdk

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SpeechSettingsActivity : Activity() {
    private lateinit var connectionStatus: TextView
    private lateinit var microphoneStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); UiTheme.init(this)
        title = "语音识别"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(UiTheme.background) }
        UiTheme.bind(root) { root.setBackgroundColor(UiTheme.background) }
        UiTheme.window(this, root); setContentView(root)
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(24), dp(6))
        }
        header.addView(UiTheme.icon(this, UiIcons.back, "返回") { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(UiTheme.text(this, "语音识别", 18f, UiTheme.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(26), dp(24), dp(32))
        }
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val identity = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        identity.addView(ImageView(this).apply {
            setImageResource(UiIcons.microphone)
            UiTheme.bind(this) { imageTintList = ColorStateList.valueOf(UiTheme.ink) }
            background = UiTheme.glass(this@SpeechSettingsActivity, 16)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) })
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(UiTheme.text(this, "离线中文识别", 20f, UiTheme.ink, true))
        heading.addView(UiTheme.text(this, "模型已内置 · 无需联网", 13f, UiTheme.muted).apply { setPadding(0, dp(6), 0, 0) })
        identity.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(identity, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(24) })
        connectionStatus = info(content, "中文模型", "")
        info(content, "使用方法", "按住说话时预览文字，松手提交。向左或向右滑动取消本次录音。")
        microphoneStatus = info(content, "麦克风权限", "")
        content.addView(UiTheme.text(this, "音频与隐私", 16f, UiTheme.ink, true).apply { setPadding(0, dp(28), 0, dp(10)) })
        val privacyDetail = UiTheme.text(this,
            "录音仅在手机本地识别，不上传音频，不保存原始录音。松手提交后的识别文字会进入任务记录，并用于执行手机任务。",
            14f, UiTheme.muted).apply { setLineSpacing(dp(4).toFloat(), 1f) }; content.addView(privacyDetail)
        content.addView(UiTheme.text(this, "识别说明", 16f, UiTheme.ink, true).apply { setPadding(0, dp(26), 0, dp(10)) })
        val networkDetail = UiTheme.text(this,
            "语音转文字无需账户、网络或额外费用。以普通话为主，环境噪声、人名和英文缩写可能影响识别。每次最长 30 秒；任务执行仍会使用你配置的模型连接。",
            14f, UiTheme.muted).apply { setLineSpacing(dp(4).toFloat(), 1f) }; content.addView(networkDetail)
    }

    override fun onResume() {
        super.onResume()
        val available = SpeechModels.embeddedAvailable(this)
        connectionStatus.text = if (available) "已内置 · 约 78 MiB" else "内置模型缺失，请重新安装完整版本"
        connectionStatus.setTextColor(if (available) UiTheme.green else UiTheme.muted)
        val microphoneGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        microphoneStatus.text = if (microphoneGranted) "已授权" else "尚未授权"
        microphoneStatus.setTextColor(if (microphoneGranted) UiTheme.ink else UiTheme.muted)
    }

    private fun info(parent: LinearLayout, title: String, value: String): TextView {
        parent.addView(UiTheme.text(this, title, 12f, UiTheme.muted, true).apply { setPadding(0, dp(16), 0, dp(7)) })
        val detail = UiTheme.text(this, value, 15f).apply { setPadding(0, 0, 0, dp(16)); setLineSpacing(dp(3).toFloat(), 1f) }
        parent.addView(detail, LinearLayout.LayoutParams(-1, -2))
        parent.addView(UiTheme.divider(this))
        return detail
    }

    private fun dp(value: Int) = UiTheme.dp(this, value)
}

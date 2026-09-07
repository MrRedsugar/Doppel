package dev.doppel.sdk

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.*

class SpeechSettingsActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var install: Button
    private lateinit var cancel: Button
    private lateinit var remove: Button
    private val observer: (SpeechModels.State) -> Unit = { state ->
        status.text = state.message; progress.progress = state.progress
        progress.visibility = if (state.busy) View.VISIBLE else View.GONE
        install.isEnabled = !state.busy; cancel.isEnabled = state.busy; cancel.visibility = if (state.busy) View.VISIBLE else View.GONE; remove.isEnabled = !state.busy && SpeechModels.installed(this)
        install.text = if (SpeechModels.installed(this)) "重新下载" else "下载中文模型"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "语音识别"
        val pad = UiTheme.dp(this, 20)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        UiTheme.window(this, root); setContentView(root)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(UiTheme.dp(this@SpeechSettingsActivity, 8), UiTheme.dp(this@SpeechSettingsActivity, 8), pad, UiTheme.dp(this@SpeechSettingsActivity, 8)) }
        header.addView(UiTheme.icon(this, android.R.drawable.ic_media_previous, "返回") { finish() }, LinearLayout.LayoutParams(UiTheme.dp(this, 44), UiTheme.dp(this, 44)))
        header.addView(UiTheme.text(this, "语音识别", 19f, UiTheme.ink, true)); root.addView(header); root.addView(UiTheme.divider(this))
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(ScrollView(this).apply { setBackgroundColor(UiTheme.background); addView(layout) }, LinearLayout.LayoutParams(-1, 0, 1f))
        layout.addView(UiTheme.text(this, "识别方式", 12f, UiTheme.muted, true).apply { setPadding(0, 0, 0, UiTheme.dp(this@SpeechSettingsActivity, 10)) })
        val prefs = getSharedPreferences("doppel", MODE_PRIVATE)
        val values = listOf("auto", "local", "system")
        val providers = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf("自动", "本地中文", "系统识别").forEachIndexed { index, title ->
            providers.addView(RadioButton(this).apply {
                id = View.generateViewId(); text = title; textSize = 15f; setTextColor(UiTheme.ink); buttonTintList = ColorStateList.valueOf(UiTheme.green); isChecked = prefs.getString("speech_provider", "auto") == values[index]
                setOnCheckedChangeListener { _, checked -> if (checked) prefs.edit().putString("speech_provider", values[index]).apply() }
            }, LinearLayout.LayoutParams(-1, UiTheme.dp(this, 50)))
        }; layout.addView(providers); layout.addView(UiTheme.divider(this))
        layout.addView(UiTheme.text(this, "中文模型", 19f, UiTheme.ink, true).apply { setPadding(0, UiTheme.dp(this@SpeechSettingsActivity, 28), 0, UiTheme.dp(this@SpeechSettingsActivity, 8)) })
        layout.addView(UiTheme.text(this, "Vosk · 简体中文 · 42 MiB", 13f, UiTheme.muted))
        status = UiTheme.text(this, "", 14f, UiTheme.green).apply { setPadding(0, UiTheme.dp(this@SpeechSettingsActivity, 18), 0, UiTheme.dp(this@SpeechSettingsActivity, 14)) }; layout.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progressTintList = ColorStateList.valueOf(UiTheme.green) }; layout.addView(progress, LinearLayout.LayoutParams(-1, UiTheme.dp(this, 4)))
        install = UiTheme.command(this, "下载中文模型", true) { SpeechModels.install(this) }; layout.addView(install, LinearLayout.LayoutParams(-1, UiTheme.dp(this, 46)).apply { topMargin = UiTheme.dp(this@SpeechSettingsActivity, 16) })
        cancel = UiTheme.command(this, "取消下载") { SpeechModels.cancel() }; layout.addView(cancel, LinearLayout.LayoutParams(-1, UiTheme.dp(this, 44)).apply { topMargin = UiTheme.dp(this@SpeechSettingsActivity, 10) })
        remove = UiTheme.command(this, "删除本地模型") { AlertDialog.Builder(this).setTitle("删除中文模型？").setNegativeButton("保留", null).setPositiveButton("删除") { _, _ -> SpeechModels.delete(this) }.show() }.apply { setTextColor(UiTheme.danger) }; layout.addView(remove, LinearLayout.LayoutParams(-1, UiTheme.dp(this, 44)).apply { topMargin = UiTheme.dp(this@SpeechSettingsActivity, 10) })
        layout.addView(UiTheme.text(this, "Vosk 0.3.75 · 模型 0.22 · Apache 2.0", 11f, UiTheme.muted).apply { setPadding(0, UiTheme.dp(this@SpeechSettingsActivity, 24), 0, 0) })
    }
    override fun onStart() { super.onStart(); SpeechModels.observe(this, observer) }
    override fun onStop() { SpeechModels.removeObserver(observer); super.onStop() }
}

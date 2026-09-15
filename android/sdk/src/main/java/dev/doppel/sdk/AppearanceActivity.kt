package dev.doppel.sdk

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

class AppearanceActivity : Activity() {
    private val choices = mutableMapOf<ThemeMode, View>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); UiTheme.init(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        UiTheme.bind(root) { root.setBackgroundColor(UiTheme.background) }; UiTheme.window(this, root)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(20), dp(8)) }
        header.addView(UiTheme.icon(this, UiIcons.back, "返回") { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(UiTheme.text(this, "外观", 18f, UiTheme.ink, true)); root.addView(header)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(28)) }
        for (mode in ThemeMode.entries) {
            val row = UiTheme.navigationRow(this, mode.label, when (mode) { ThemeMode.SYSTEM -> UiIcons.device; ThemeMode.LIGHT -> UiIcons.sparkles; ThemeMode.DARK -> UiIcons.brand }, ThemeController.mode(this) == mode) {
                choices.forEach { (key, view) -> view.isSelected = key == mode }
                ThemeController.select(this, mode, choices[mode])
            }
            row.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.RadioButton::class.java.name; info.isCheckable = true; info.isChecked = host.isSelected
                }
            }
            choices[mode] = row; content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        content.addView(UiTheme.text(this, "预览", 12f, UiTheme.muted).apply { setPadding(0, dp(30), 0, dp(18)) })
        content.addView(UiTheme.text(this, "Doppel", 22f, UiTheme.ink, true))
        content.addView(UiTheme.text(this, "今天想完成什么？", 16f, UiTheme.muted).apply { setPadding(0, dp(12), 0, dp(22)) })
        val preview = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = UiTheme.glass(this@AppearanceActivity); setPadding(dp(18), dp(12), dp(10), dp(12)) }
        preview.addView(UiTheme.text(this, "整理今天的计划", 15f), LinearLayout.LayoutParams(0, -2, 1f))
        preview.addView(UiTheme.icon(this, UiIcons.arrowUp, "预览发送按钮", true) {}.apply { isClickable = false; isFocusable = false }, LinearLayout.LayoutParams(dp(44), dp(44)))
        content.addView(preview)
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }
    private fun dp(value: Int) = UiTheme.dp(this, value)
}

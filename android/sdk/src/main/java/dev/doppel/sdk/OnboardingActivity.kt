package dev.doppel.sdk

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Optional permissions are separate from explicit acceptance of the preview notices. */
class OnboardingActivity : Activity() {
    private lateinit var root: LinearLayout
    private var step = 0
    private var agreed = false
    private val clock = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); UiTheme.init(this)
        step = if (!FirstUseConsent.isAccepted(this)) 0 else savedInstanceState?.getInt("step")?.coerceIn(1, 2) ?: 1
        agreed = savedInstanceState?.getBoolean("agreed") ?: false
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        UiTheme.bind(root) { root.setBackgroundColor(UiTheme.background) }; UiTheme.window(this, root)
        setContentView(root); render()
    }

    private fun render() {
        clock.removeCallbacksAndMessages(null); root.removeAllViews()
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(20), dp(8)) }
        if (step > 0) header.addView(UiTheme.icon(this, UiIcons.close, "稍后设置") { finishGuide() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        else header.addView(UiTheme.icon(this, UiIcons.brand, "Doppel") {}.apply { isClickable = false; isFocusable = false }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(UiTheme.text(this, "Doppel", 18f, UiTheme.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(UiTheme.text(this, "${step + 1} / 3", 12f, UiTheme.muted))
        root.addView(header)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(28)) }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(20)) }
        root.addView(footer)
        when (step) { 0 -> consent(content, footer); 1 -> permissions(content, footer); else -> practice(content, footer) }
    }

    private fun title(content: LinearLayout, text: String, detail: String) {
        content.addView(UiTheme.text(this, text, 26f, UiTheme.ink, true).apply { setPadding(0, 0, 0, dp(14)) })
        content.addView(UiTheme.text(this, detail, 15f, UiTheme.muted).apply { setLineSpacing(dp(5).toFloat(), 1f); setPadding(0, 0, 0, dp(24)) })
    }

    private fun consent(content: LinearLayout, footer: LinearLayout) {
        title(content, "开始之前", "请了解任务、屏幕和语音的处理方式，再决定是否使用开发者预览版。")
        content.addView(UiTheme.row(this, "任务与屏幕", "任务需要的界面内容与截图可能发送到你配置的服务和模型。", UiIcons.scan) { openLegal(true) })
        content.addView(UiTheme.divider(this))
        content.addView(UiTheme.row(this, "语音与声音", "长按时使用内置离线中文语音识别；结果可能自动播报，可在下一步关闭。", UiIcons.microphone) { openLegal(true) })
        content.addView(UiTheme.divider(this))
        content.addView(UiTheme.row(this, "使用条款", FirstUseConsent.TERMS_VERSION, UiIcons.files) { openLegal(false) })
        content.addView(UiTheme.row(this, "隐私说明", FirstUseConsent.PRIVACY_VERSION, UiIcons.lock) { openLegal(true) })
        val choice = UiTheme.check(this, "我已阅读并同意使用条款与隐私说明", agreed).apply { tag = "first_use_consent_choice" }
        content.addView(choice, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
        val proceed = UiTheme.command(this, "同意并继续", true) {
            if (!agreed) return@command
            if (!FirstUseConsent.accept(this)) { Toast.makeText(this, "同意记录未保存，请重试", Toast.LENGTH_LONG).show(); return@command }
            step = 1; render()
        }.apply { isEnabled = agreed; tag = "first_use_consent_continue" }
        choice.setOnCheckedChangeListener { _, checked -> agreed = checked; proceed.isEnabled = checked }
        footer.addView(proceed, LinearLayout.LayoutParams(-1, dp(50)))
        footer.addView(UiTheme.command(this, "暂不使用") { setResult(RESULT_CANCELED); finish() }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
    }

    private fun permissions(content: LinearLayout, footer: LinearLayout) {
        title(content, "连接与权限", "只开启你准备使用的能力。未授予的权限可以稍后在设置中开启。")
        val gateway = Gateway(this)
        val connected = gateway.isConnected()
        if (DirectMode.isDeveloperBuild(this)) content.addView(UiTheme.row(this, "模型连接", if (connected) "已配置" else "连接支持视觉的模型或开发网关", UiIcons.connection) {
            startActivity(Intent(this, ModelSettingsActivity::class.java))
        }) else content.addView(UiTheme.text(this, if (connected) "账号与服务已连接" else "账号与服务可在设置中连接", 14f, UiTheme.muted))
        content.addView(UiTheme.row(this, "无障碍服务", if (DoppelAccessibilityService.instance != null) "已启用" else "用于读取任务界面并执行操作", UiIcons.accessibility) {
            openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        content.addView(UiTheme.row(this, "悬浮入口", if (Settings.canDrawOverlays(this)) "已授权" else "用于任务入口、执行状态与结果", UiIcons.brand) {
            openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        if (Build.VERSION.SDK_INT < 30) content.addView(UiTheme.row(this, "屏幕识别", if (LegacyScreenCaptureService.isReady) "本次已授权" else "可选，用于识别游戏与图片界面", UiIcons.scan) {
            startActivity(Intent(this, LegacyScreenCaptureActivity::class.java))
        })
        content.addView(UiTheme.row(this, BackgroundActivitySettings.title, BackgroundActivitySettings.detail, UiIcons.device) {
            BackgroundActivitySettings.open(this)
        })
        val microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        content.addView(UiTheme.row(this, "麦克风", if (microphone) "已授权" else "可选，长按说话时使用", UiIcons.microphone) {
            if (!microphone) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 70)
        })
        val notifications = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        content.addView(UiTheme.row(this, "通知", if (notifications) "已授权" else "可选，用于后台状态与结果", UiIcons.mail) {
            if (Build.VERSION.SDK_INT >= 33 && !notifications) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 71)
        })
        content.addView(UiTheme.text(this, "执行偏好", 12f, UiTheme.muted).apply { setPadding(0, dp(24), 0, dp(8)) })
        for ((label, key) in listOf("自动播报结果" to "completion_speech")) {
            content.addView(UiTheme.toggle(this, label, gateway.prefs.getBoolean(key, true)) { gateway.prefs.edit().putBoolean(key, it).apply() })
        }
        content.addView(UiTheme.text(this, "执行时会显示点击和滑动轨迹。自动播报会发出声音，附近的人可能听到任务结果。", 13f, UiTheme.muted).apply { setLineSpacing(dp(4).toFloat(), 1f) })
        footer.addView(UiTheme.command(this, "继续", true) { step = 2; render() }, LinearLayout.LayoutParams(-1, dp(50)))
    }

    private fun practice(content: LinearLayout, footer: LinearLayout) {
        title(content, "熟悉悬浮入口", "下面的练习只在此页面响应，不录音，也不会操作其他应用。")
        val message = UiTheme.text(this, "点击或长按下方图标", 16f, UiTheme.ink, true).apply { gravity = Gravity.CENTER; minHeight = dp(62); maxLines = 3 }
        content.addView(message, LinearLayout.LayoutParams(-1, dp(82)))
        val practice = UiTheme.icon(this, UiIcons.brand, "练习悬浮入口", true) {}
        content.addView(practice, LinearLayout.LayoutParams(dp(72), dp(72)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12); bottomMargin = dp(26) })
        installPractice(practice, message)
        for ((label, detail, icon) in listOf(
            Triple("点击", "打开文字编辑，确认后开始任务。", UiIcons.edit),
            Triple("长按与松手", "按住说话，松手提交最终识别结果。", UiIcons.microphone),
            Triple("向左或向右滑动", "取消本次语音，不提交内容。", UiIcons.back),
            Triple("接管执行", "触屏暂停开启时，触摸屏幕即可接管；也可以打开任务面板暂停或停止。", UiIcons.pause)
        )) {
            content.addView(UiTheme.row(this, label, detail, icon) {})
        }
        footer.addView(UiTheme.command(this, "完成设置", true) { finishGuide() }, LinearLayout.LayoutParams(-1, dp(50)))
    }

    private fun installPractice(view: View, message: TextView) {
        var down = 0f; var held = false; var cancelled = false
        val hold = Runnable { held = true; view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); message.text = "正在听 · 松手提交，左右滑动取消" }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { down = event.rawX; held = false; cancelled = false; clock.postDelayed(hold, 400); true }
                MotionEvent.ACTION_MOVE -> {
                    if (HorizontalHoldCancel.crossed(down, event.rawX, resources.displayMetrics.widthPixels.toFloat(), dp(72).toFloat(), ViewConfiguration.get(this).scaledTouchSlop.toFloat())) {
                        cancelled = true; clock.removeCallbacks(hold); message.text = "已取消，不会提交"
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    clock.removeCallbacks(hold)
                    message.text = when { cancelled -> "已取消，不会提交"; held -> "已松手，语音会在识别完成后提交"; else -> "点击会打开文字编辑" }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { clock.removeCallbacks(hold); message.text = "练习已取消"; true }
                else -> false
            }
        }
    }

    private fun openLegal(privacy: Boolean) { startActivity(Intent(this, LegalActivity::class.java).putExtra("privacy", privacy)) }
    private fun openSettings(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { Toast.makeText(this, "系统未提供此设置入口，请从系统设置开启", Toast.LENGTH_LONG).show() }
    }
    private fun finishGuide() {
        if (!FirstUseConsent.isAccepted(this)) { setResult(RESULT_CANCELED); finish(); return }
        if (!FirstUseConsent.finishGuide(this)) { Toast.makeText(this, "设置记录未保存，请重试", Toast.LENGTH_LONG).show(); return }
        setResult(RESULT_OK); finish()
    }
    override fun onResume() { super.onResume(); if (::root.isInitialized && step == 1) render() }
    override fun onPause() { clock.removeCallbacksAndMessages(null); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("step", step); outState.putBoolean("agreed", agreed); super.onSaveInstanceState(outState) }
    @Deprecated("Platform callback") override fun onBackPressed() { if (step == 2) { step = 1; render() } else if (step == 1) finishGuide() else { setResult(RESULT_CANCELED); finish() } }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { super.onRequestPermissionsResult(requestCode, permissions, grantResults); if (step == 1) render() }
    override fun onDestroy() { clock.removeCallbacksAndMessages(null); super.onDestroy() }
    private fun dp(value: Int) = UiTheme.dp(this, value)
}

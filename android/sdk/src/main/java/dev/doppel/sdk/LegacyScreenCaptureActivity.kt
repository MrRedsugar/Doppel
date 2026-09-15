package dev.doppel.sdk

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Explains the optional Android 8–10 system grant; the consent Intent is never saved. */
class LegacyScreenCaptureActivity : Activity() {
    private companion object { const val REQUEST_CAPTURE = 98 }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var authorize: Button
    private lateinit var stop: Button
    private var requesting = false
    private var startingAt = 0L
    private var lastNotice: String? = null
    private val supported get() = Build.VERSION.SDK_INT in 26..29
    private val readiness = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || startingAt == 0L) return
            if (LegacyScreenCaptureService.isReady) {
                startingAt = 0; setResult(RESULT_OK); finish(); return
            }
            if (SystemClock.elapsedRealtime() - startingAt >= 5000) {
                startingAt = 0
                stopService(Intent(this@LegacyScreenCaptureActivity, LegacyScreenCaptureService::class.java))
                update("屏幕共享未能开启，请重试")
            } else main.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); UiTheme.init(this)
        requesting = savedInstanceState?.getBoolean("requesting") ?: false
        startingAt = savedInstanceState?.getLong("starting_at") ?: 0L
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        UiTheme.bind(root) { root.setBackgroundColor(UiTheme.background) }; UiTheme.window(this, root)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(20), dp(8)) }
        header.addView(UiTheme.icon(this, UiIcons.back, "返回") { leave() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(UiTheme.text(this, "屏幕读取", 18f, UiTheme.ink, true))
        root.addView(header)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(28)) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = UiTheme.glass(this@LegacyScreenCaptureActivity)
            setPadding(dp(22), dp(24), dp(22), dp(24))
        }
        card.addView(UiTheme.icon(this, UiIcons.scan, "屏幕读取") {}.apply { isClickable = false; isFocusable = false }, LinearLayout.LayoutParams(dp(48), dp(48)))
        card.addView(UiTheme.text(this, if (supported) "让 Doppel 看见当前屏幕" else "此设备无需额外授权", 24f, UiTheme.ink, true)
            .apply { setPadding(0, dp(18), 0, dp(14)) })
        val detail = if (supported) "为了识别按钮和画面，请允许系统屏幕共享。共享期间系统会持续提供画面，Doppel 只在任务需要时读取新画面，其余画面立即丢弃，不作识别或保存。\n\n你可以随时从通知栏停止共享。授权仅在本次服务运行期间有效，停止后需要重新开启。"
            else "此设备支持直接读取屏幕，无需另行开启系统屏幕共享。你可以返回继续设置。"
        card.addView(UiTheme.text(this, detail, 15f, UiTheme.muted).apply { setLineSpacing(dp(5).toFloat(), 1f) })
        content.addView(card)
        status = UiTheme.text(this, "", 14f, UiTheme.muted).apply { setPadding(0, dp(20), 0, dp(14)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addView(status)
        authorize = UiTheme.command(this, "允许屏幕共享", true) { requestGrant() }.apply { tag = "legacy_capture_authorize" }
        content.addView(authorize, LinearLayout.LayoutParams(-1, -2))
        stop = UiTheme.command(this, "停止读取") {
            stopService(Intent(this, LegacyScreenCaptureService::class.java)); update("正在停止屏幕读取")
            main.postDelayed({ if (!isFinishing) update() }, 150)
        }.apply { tag = "legacy_capture_stop" }
        content.addView(stop, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        content.addView(UiTheme.command(this, "返回") { leave() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root); update()
    }

    private fun update(message: String? = null) {
        lastNotice = message
        val ready = LegacyScreenCaptureService.isReady
        status.text = message ?: when {
            !supported -> "无需开启兼容屏幕共享"
            requesting -> "请在系统窗口中选择是否允许"
            startingAt > 0 -> "正在准备屏幕读取…"
            ready -> "屏幕读取已开启，可以返回继续任务"
            else -> "未开启时仍可使用其他功能"
        }
        authorize.visibility = if (supported) View.VISIBLE else View.GONE
        authorize.isEnabled = !requesting && startingAt == 0L
        authorize.text = if (ready) "已开启，返回" else "允许屏幕共享"
        stop.visibility = if (supported && ready) View.VISIBLE else View.GONE
    }

    @Suppress("DEPRECATION")
    private fun requestGrant() {
        if (!supported || LegacyScreenCaptureService.isReady) { leave(); return }
        if (requesting || startingAt > 0 || !FirstUseConsent.allowEntry(this)) return
        try {
            requesting = true; update()
            startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), REQUEST_CAPTURE)
        } catch (_: Exception) { requesting = false; update("无法打开系统屏幕授权，请稍后重试") }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        requesting = false
        if (resultCode != RESULT_OK || data == null) { update("未开启屏幕共享，仍可使用其他功能"); return }
        try {
            LegacyScreenCaptureService.authorize(this, resultCode, data)
            startingAt = SystemClock.elapsedRealtime(); update(); main.post(readiness)
        } catch (_: Exception) { startingAt = 0; update("屏幕共享未能开启，请重试") }
    }

    private fun leave() {
        if (startingAt > 0 && !LegacyScreenCaptureService.isReady) stopService(Intent(this, LegacyScreenCaptureService::class.java))
        startingAt = 0
        setResult(if (LegacyScreenCaptureService.isReady) RESULT_OK else RESULT_CANCELED)
        finish()
    }
    override fun onResume() { super.onResume(); if (::status.isInitialized) { update(lastNotice); if (startingAt > 0) main.post(readiness) } }
    override fun onStop() { main.removeCallbacks(readiness); super.onStop() }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("requesting", requesting); outState.putLong("starting_at", startingAt)
        super.onSaveInstanceState(outState)
    }
    @Suppress("DEPRECATION")
    override fun onBackPressed() { leave() }
    private fun dp(value: Int) = UiTheme.dp(this, value)
}

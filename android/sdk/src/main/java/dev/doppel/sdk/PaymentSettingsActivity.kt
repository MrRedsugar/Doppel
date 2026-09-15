package dev.doppel.sdk

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class PaymentSettingsActivity : Activity() {
    private val consent by lazy { PaymentConsent(this) }
    private val flow = PaymentConsentFlow()
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var acknowledgement: CheckBox? = null
    private var continueButton: TextView? = null
    private val tick = object : Runnable {
        override fun run() {
            if (!resumed || !flow.active) return
            refreshCountdown()
            handler.postDelayed(this, 200)
        }
    }
    private fun dp(value: Int) = UiTheme.dp(this, value)
    override fun onCreate(savedInstanceState: Bundle?) {
        PaymentConsent.enterSettings(this)
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        window.decorView.filterTouchesWhenObscured = true
        DeviceWorkerService.instance?.pause()
        renderOverview()
    }
    override fun onResume() {
        super.onResume(); resumed = true; PaymentConsent.enterSettings(this)
        DeviceWorkerService.instance?.pause()
        renderOverview()
    }
    override fun onPause() {
        resumed = false; flow.reset(); handler.removeCallbacks(tick)
        super.onPause()
    }
    override fun onStop() { PaymentConsent.leaveSettings(this); super.onStop() }
    override fun onDestroy() { handler.removeCallbacks(tick); flow.reset(); PaymentConsent.leaveSettings(this); super.onDestroy() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && flow.active) { flow.reset(); handler.removeCallbacks(tick); renderOverview("确认已取消，尚未开启代为支付") }
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val obscured = event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0 ||
            Build.VERSION.SDK_INT >= 29 && event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0
        if (obscured) return true
        return super.dispatchTouchEvent(event)
    }
    private fun protect(view: View) {
        view.filterTouchesWhenObscured = true
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if (action in setOf(AccessibilityNodeInfo.ACTION_CLICK, AccessibilityNodeInfo.ACTION_LONG_CLICK, AccessibilityNodeInfo.ACTION_SET_TEXT)) return false
                return super.performAccessibilityAction(host, action, args)
            }
        }
    }
    private fun layout(title: String): Pair<LinearLayout, LinearLayout> {
        handler.removeCallbacks(tick)
        acknowledgement = null; continueButton = null
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(UiTheme.background) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(24), dp(6)) }
        header.addView(UiTheme.icon(this, android.R.drawable.ic_media_previous, "返回") { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(UiTheme.text(this, title, 18f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header); root.addView(UiTheme.divider(this))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(28)) }
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        UiTheme.window(this, root); setContentView(root)
        return root to content
    }
    private fun paragraph(content: LinearLayout, text: String, color: Int = UiTheme.muted) {
        content.addView(UiTheme.text(this, text, 15f, color).apply { setLineSpacing(dp(5).toFloat(), 1f) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    }
    private fun renderOverview(message: String = "") {
        val (_, content) = layout("支付授权")
        val enabled = consent.isEnabledForSettings()
        val storageFailure = consent.hasStorageFailure()
        content.addView(UiTheme.text(this, if (storageFailure) "仅本次运行停用" else if (enabled) "代为支付已开启" else "付款，由你决定", 24f, bold = true))
        paragraph(content, "开启后，Doppel 可以根据你的任务代点支付按钮，产生真实扣款。此权限仅保存在本机。")
        if (storageFailure) {
            paragraph(content, "本机存储出现问题。重启前请释放存储空间并重新关闭授权，确认关闭已保存。", UiTheme.danger)
            content.addView(UiTheme.command(this, "重新保存关闭状态", true) {
                val saved = consent.disable()
                DeviceWorkerService.instance?.pause()
                renderOverview(if (saved) "已关闭，尚未执行的支付授权已失效" else "关闭状态仍未保存，请处理存储问题后重试")
            }.apply { contentDescription = "payment_retry_disable" }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(16) })
        }
        val toggle = UiTheme.toggle(this, "允许代为支付", enabled).apply {
            contentDescription = "payment_toggle"
            maxLines = 2
            setOnCheckedChangeListener { _, checked ->
                if (!resumed || !hasWindowFocus()) { renderOverview(); return@setOnCheckedChangeListener }
                if (!checked) {
                    val saved = consent.disable()
                    DeviceWorkerService.instance?.pause()
                    renderOverview(if (saved) "已关闭，尚未执行的支付授权已失效" else "本次运行已停用，但保存失败。请释放存储空间后重试，重启前需确认关闭已保存。")
                } else {
                    flow.begin(SystemClock.elapsedRealtime())
                    renderRisk()
                }
            }
        }
        protect(toggle)
        content.addView(toggle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        content.addView(UiTheme.divider(this))
        paragraph(content, "请求批准和帮我批准模式仍保留付款确认；完全访问模式可按照你的任务付款。关闭后，所有模式均由你亲自支付。")
        paragraph(content, "支付密码、支付验证码、生物识别、转账和开通免密仍需你处理。付款需要多步确认或结果不明时，仍可能需要你接手。关闭权限无法撤销已经提交的付款。")
        if (message.isNotBlank()) paragraph(content, message, if (message.contains("失败")) UiTheme.danger else UiTheme.green)
    }
    private fun renderRisk() {
        if (!flow.active || flow.complete) return
        val (root, content) = layout("开启代为支付")
        val titles = listOf("会发生真实扣款", "退款并不保证成功", "确认授权范围")
        val details = listOf(
            "Doppel 将能够按照你的任务点击付款。金额、商家、优惠或收货信息识别错误，都可能产生你不想要的订单。开启前，请确认你愿意承担这类风险。",
            "已经支付的订单可能无法立即撤销。退款是否成功、到账时间以及费用由商家与平台决定。付款需要多步确认或结果不明时，仍可能需要你接手，并核对订单和账单。",
            "请求批准和帮我批准模式仍保留操作确认；完全访问模式可以根据你的任务执行普通付款。支付密码、支付验证码、生物识别、转账和开通免密仍需你处理。你可以随时在设置中关闭。"
        )
        val confirmations = listOf("我知道开启后会产生真实扣款", "我理解误付款与退款不确定的风险", "我同意在上述范围内授权本机代为支付")
        content.addView(UiTheme.text(this, "风险确认 ${flow.stage + 1} / 3", 13f, UiTheme.spectrumBlue, true).apply { contentDescription = "payment_risk_stage" })
        content.addView(UiTheme.text(this, titles[flow.stage], 24f, bold = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        paragraph(content, details[flow.stage], UiTheme.ink)
        acknowledgement = UiTheme.check(this, confirmations[flow.stage], false).apply {
            contentDescription = "payment_risk_acknowledgement"
            setPadding(0, dp(12), 0, dp(12)); setOnCheckedChangeListener { _, _ -> refreshCountdown() }
            protect(this)
        }
        content.addView(acknowledgement, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        val footer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(20)) }
        continueButton = UiTheme.command(this, "继续", true) {
            if (!resumed || !hasWindowFocus() || !flow.advance(SystemClock.elapsedRealtime(), acknowledgement?.isChecked == true)) return@command
            if (flow.complete) {
                val saved = consent.enable(flow)
                renderOverview(if (saved) "已开启，当前任务保持暂停" else "授权保存失败，代为支付未开启，请重新确认")
                if (!saved) Toast.makeText(this, "授权未保存", Toast.LENGTH_SHORT).show()
            } else renderRisk()
        }.apply { contentDescription = "payment_risk_continue"; protect(this) }
        footer.addView(continueButton, LinearLayout.LayoutParams(-1, dp(52)))
        footer.addView(UiTheme.command(this, "取消") {
            flow.reset(); renderOverview("确认已取消，尚未开启代为支付")
        }.apply { contentDescription = "payment_risk_cancel" }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        root.addView(UiTheme.divider(this)); root.addView(footer)
        refreshCountdown(); handler.postDelayed(tick, 200)
    }
    private fun refreshCountdown() {
        val remaining = flow.remainingMillis(SystemClock.elapsedRealtime())
        val title = if (flow.stage == 2) "同意并开启" else "继续"
        continueButton?.text = if (remaining > 0) "$title（${(remaining + 999) / 1000} 秒）" else title
        continueButton?.isEnabled = resumed && hasWindowFocus() && acknowledgement?.isChecked == true && remaining == 0L
    }
}

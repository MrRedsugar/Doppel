package dev.doppel.testapp

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*

/** Synthetic notifications and challenges; this fixture has no network permission. */
class InteractionFixtureActivity : Activity() {
    private lateinit var page: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); home() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
    private fun screen(title: String) {
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(20)); setBackgroundColor(Color.WHITE) }
        setContentView(ScrollView(this).apply { addView(page) }); label(title)
    }
    private fun label(value: String): TextView = TextView(this).apply { text = value; textSize = 20f; setTextColor(Color.DKGRAY); setPadding(0, dp(12), 0, dp(12)); page.addView(this) }
    private fun button(value: String, action: () -> Unit): Button = Button(this).apply { text = value; isAllCaps = false; setOnClickListener { action() }; page.addView(this, LinearLayout.LayoutParams(-1, dp(58))) }
    private fun home() {
        screen("交互验收")
        button("模拟登录") { login() }
        button("验证接管场景") { challenge() }
        button("手势反馈") { gestures() }
        button("控件触发验证") { controlTriggers() }
        button("CAPTCHA") { challenge() }
        button("控件扫描验证") { controlScan() }
    }
    private fun login() {
        screen("DoppelTest 登录")
        val phone = EditText(this).apply { hint = "登录手机号"; contentDescription = hint; inputType = android.text.InputType.TYPE_CLASS_PHONE }; page.addView(phone)
        val code = EditText(this).apply { hint = "登录验证码"; contentDescription = hint; inputType = android.text.InputType.TYPE_CLASS_NUMBER }; page.addView(code)
        page.addView(EditText(this).apply { hint = "搜索关键词"; contentDescription = hint })
        button("发送模拟验证码") {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("fixture_login", "模拟验证码", NotificationManager.IMPORTANCE_DEFAULT))
            handler.postDelayed({
                manager.notify(73, Notification.Builder(this, "fixture_login").setSmallIcon(android.R.drawable.ic_dialog_email)
                    .setContentTitle("DoppelTest").setContentText("[DoppelTest] 登录验证码 673921，5分钟内有效。")
                    .setOnlyAlertOnce(true).setAutoCancel(true).build())
            }, 800)
        }
        button("登录") {
            if (phone.text.toString() == "19900000013" && code.text.toString() == "673921") {
                getSystemService(NotificationManager::class.java).cancel(73)
                screen("登录成功"); button("返回验收") { home() }
            } else label("登录资料不匹配")
        }
        button("返回验收") { home() }
    }
    private fun challenge() {
        screen("请完成安全验证")
        label("请验证您是真人")
        button("受保护操作") { label("错误：验证前触发了操作") }
        button("我已完成人工验证") { screen("验证已完成"); button("继续业务") { label("业务已继续") } }
    }
    private fun gestures() {
        screen("手势反馈")
        val feedback = label("等待操作")
        button("点击目标") { feedback.text = "点击成功" }
        button("长按目标") { feedback.text = "普通点击" }.setOnLongClickListener { feedback.text = "长按成功"; true }
        for (index in 1..30) label("滚动条目 $index")
        button("返回验收") { home() }
    }

    private fun controlTriggers() {
        screen("控件触发验证")
        val feedback = label("触发次数 0")
        var count = 0
        val trigger = button("触发目标") { feedback.text = "触发次数 ${++count}" }.apply { id = android.R.id.button1 }
        button("切换目标显示") { trigger.visibility = if (trigger.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE }
        button("刷新页面事件") { label("页面已刷新") }
        button("返回验收") { home() }
    }

    private fun controlScan() {
        screen("控件扫描验证")
        val feedback = label("浅层 0 · 深层 0 · 描述 0")
        var shallow = 0; var deep = 0; var description = 0
        fun update() { feedback.text = "浅层 $shallow · 深层 $deep · 描述 $description" }
        // The deep target is visually first; BFS must still choose the shallower duplicate ID.
        val nested = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "嵌套控件容器"
        }
        page.addView(nested)
        val deepButton = button("深层同ID目标") { deep++; update() }.apply { id = android.R.id.button1 }
        page.removeView(deepButton)
        nested.addView(deepButton, LinearLayout.LayoutParams(-1, dp(58)))
        button("浅层同ID目标") { shallow++; update() }.id = android.R.id.button1
        button("") { description++; update() }.contentDescription = "无ID描述目标"
        val event = label("页面事件 0")
        var refreshes = 0
        button("刷新扫描页面") { event.text = "页面事件 ${++refreshes}" }
        button("返回验收") { home() }
    }
}

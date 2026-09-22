package dev.doppel.testapp

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** No network, account or payment provider. The targets deliberately have no accessibility nodes. */
class PaymentNavigationFixtureActivity : Activity() {
    private var navigationClicks = 0
    private var paymentClicks = 0
    private lateinit var state: TextView
    private lateinit var surface: Targets
    private val session get() = intent.getStringExtra("session").orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 40, 24, 24)
            setBackgroundColor(Color.rgb(246, 247, 249))
        }
        page.addView(TextView(this).apply {
            text = "个人中心 · 隔离测试\n待付款　待收货　待使用　待评价\n钱包　白条　京东快付"
            textSize = 20f
            setTextColor(Color.BLACK)
        })
        state = TextView(this).apply { textSize = 16f; setTextColor(Color.DKGRAY) }
        page.addView(state)
        surface = Targets()
        page.addView(surface, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        surface.post(::publish)
    }

    private fun publish() {
        val offset = IntArray(2).also { surface.getLocationOnScreen(it) }
        fun bounds(box: RectF) = JSONArray(listOf(box.left + offset[0], box.top + offset[1], box.right + offset[0], box.bottom + offset[1]))
        state.text = "订单入口点击 $navigationClicks 次；模拟付款点击 $paymentClicks 次"
        state.contentDescription = "payment-fixture:" + JSONObject().put("session", session)
            .put("navigation_clicks", navigationClicks).put("payment_clicks", paymentClicks)
            .put("history_bounds", bounds(surface.history)).put("payment_bounds", bounds(surface.payment))
    }

    private inner class Targets : View(this@PaymentNavigationFixtureActivity) {
        val history get() = RectF(width * .1f, height * .14f, width * .9f, height * .34f)
        val payment get() = RectF(width * .1f, height * .58f, width * .9f, height * .78f)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var pressed = ""
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { post { publish() } }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for ((box, label) in listOf(history to "全部订单", payment to "确认模拟付款")) {
                paint.color = Color.rgb(35, 103, 188)
                canvas.drawRoundRect(box, 16f, 16f, paint)
                paint.color = Color.WHITE
                paint.textSize = 24 * resources.displayMetrics.scaledDensity
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(label, box.centerX(), box.centerY() - (paint.ascent() + paint.descent()) / 2, paint)
            }
        }
        private fun hit(x: Float, y: Float) = when {
            history.contains(x, y) -> "history"
            payment.contains(x, y) -> "payment"
            else -> ""
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> pressed = hit(event.x, event.y)
                MotionEvent.ACTION_UP -> {
                    if (pressed.isNotBlank() && pressed == hit(event.x, event.y)) {
                        if (pressed == "history") navigationClicks++ else paymentClicks++
                        publish()
                    }
                    pressed = ""
                }
                MotionEvent.ACTION_CANCEL -> pressed = ""
            }
            return true
        }
    }
}

package dev.doppel.testapp

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot

/** Disposable local gesture target. Its evidence comes from MotionEvents, not executor receipts. */
class GestureEffectsFixtureActivity : Activity() {
    private var clipboardSaved = false
    private var originalClipboard: ClipData? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getStringExtra("mode") == "editor") { editor(); return }
        val heading = TextView(this).apply {
            textSize = 13f; setTextColor(Color.WHITE); setBackgroundColor(Color.DKGRAY)
            setPadding(12, 8, 12, 8); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val mode = intent.getStringExtra("mode").takeIf { it in setOf("pan", "drag", "events") } ?: "events"
        val surface = Surface(mode, heading)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(heading, LinearLayout.LayoutParams(-1, (60 * resources.displayMetrics.density).toInt()))
            addView(surface, LinearLayout.LayoutParams(-1, 0, 1f))
        })
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && intent.getStringExtra("mode") == "editor" && !clipboardSaved) {
            originalClipboard = getSystemService(ClipboardManager::class.java).primaryClip
            clipboardSaved = true
        }
    }
    private fun editor() {
        var submissions = 0
        val result = TextView(this).apply { text = "等待提交"; contentDescription = "editor-result"; textSize = 20f }
        val source = EditText(this).apply {
            hint = "源文本"; contentDescription = "editor-source"; setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText("alpha middle omega")
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) {
                    result.text = "提交 ${++submissions}：$text"; true
                } else false
            }
        }
        val destination = EditText(this).apply { hint = "粘贴目标"; contentDescription = "editor-destination"; setSingleLine(true) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24)
            addView(TextView(this@GestureEffectsFixtureActivity).apply {
                text = "独立编辑器验证"; contentDescription = "editor-session:${intent.getStringExtra("session").orEmpty()}"
            })
            addView(source); addView(destination); addView(result)
            addView(Button(this@GestureEffectsFixtureActivity).apply {
                text = "恢复测试前剪贴板"; contentDescription = "editor-restore-clipboard"
                setOnClickListener { restoreClipboard(); isEnabled = false; result.text = "剪贴板已恢复" }
            })
        })
    }
    private fun restoreClipboard() {
        if (!clipboardSaved) return
        val manager = getSystemService(ClipboardManager::class.java)
        originalClipboard?.let(manager::setPrimaryClip) ?: manager.clearPrimaryClip()
        originalClipboard = null; clipboardSaved = false
    }
    override fun onDestroy() { restoreClipboard(); super.onDestroy() }

    private inner class Surface(private val mode: String, private val heading: TextView) : View(this@GestureEffectsFixtureActivity) {
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f }
        private val handler = Handler(Looper.getMainLooper())
        private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private val strokes = JSONArray()
        private var down = 0; private var up = 0; private var cancel = 0; private var moves = 0
        private var taps = 0; private var longPresses = 0; private var doubles = 0
        private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var lastY = 0f
        private var downAt = 0L; private var lastTapAt = Long.MIN_VALUE
        private var lastTapX = 0f; private var lastTapY = 0f
        private var active = false; private var moved = false; private var longFired = false; private var dragging = false
        private var offsetX = 0f; private var offsetY = 0f
        private var tokenX = 0f; private var tokenY = 0f
        private var samples = JSONArray()
        private val longAction = Runnable {
            if (active && !moved) { longPresses++; longFired = true; publish() }
        }
        init {
            contentDescription = "gesture-surface"; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            setBackgroundColor(Color.rgb(247, 248, 250))
        }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            tokenX = w * .25f; tokenY = h * .7f; publish()
        }
        private fun sample(event: MotionEvent) {
            if (samples.length() < 128) samples.put(JSONArray(listOf(event.x, event.y, event.eventTime - downAt)))
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    down++; active = true; moved = false; longFired = false
                    downX = event.x; downY = event.y; lastX = event.x; lastY = event.y; downAt = event.eventTime
                    samples = JSONArray(); sample(event)
                    dragging = mode == "drag" && hypot(event.x - tokenX, event.y - tokenY) <= 48f
                    handler.postDelayed(longAction, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    moves++; sample(event)
                    if (hypot(event.x - downX, event.y - downY) > slop) { moved = true; handler.removeCallbacks(longAction) }
                    if (mode == "pan") { offsetX += event.x - lastX; offsetY += event.y - lastY }
                    if (dragging) { tokenX += event.x - lastX; tokenY += event.y - lastY }
                    lastX = event.x; lastY = event.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longAction); active = false; sample(event)
                    if (mode == "pan") { offsetX += event.x - lastX; offsetY += event.y - lastY }
                    if (dragging) { tokenX += event.x - lastX; tokenY += event.y - lastY }
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) cancel++ else {
                        up++
                        if (!moved && !longFired) {
                            taps++
                            if (lastTapAt != Long.MIN_VALUE && event.eventTime - lastTapAt <= ViewConfiguration.getDoubleTapTimeout() &&
                                hypot(event.x - lastTapX, event.y - lastTapY) <= slop) doubles++
                            lastTapAt = event.eventTime; lastTapX = event.x; lastTapY = event.y
                        }
                    }
                    strokes.put(JSONObject().put("down", JSONArray(listOf(downX, downY)))
                        .put("up", JSONArray(listOf(event.x, event.y))).put("duration_ms", event.eventTime - downAt)
                        .put("cancelled", event.actionMasked == MotionEvent.ACTION_CANCEL).put("points", samples))
                    while (strokes.length() > 12) strokes.remove(0)
                    dragging = false
                }
            }
            publish(); invalidate(); return true
        }
        private fun publish() {
            val evidence = JSONObject().put("session", intent.getStringExtra("session").orEmpty())
                .put("mode", mode).put("down", down).put("up", up).put("cancel", cancel)
                .put("moves", moves).put("taps", taps).put("long", longPresses).put("double", doubles)
                .put("offset", JSONArray(listOf(offsetX, offsetY))).put("token", JSONArray(listOf(tokenX, tokenY)))
                .put("target", JSONArray(listOf(width * .75f, height * .25f))).put("strokes", strokes)
            heading.text = "$mode · DOWN $down / UP $up · 单击 $taps · 长按 $longPresses · 双击 $doubles"
            heading.contentDescription = "gesture-result:$evidence"
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val gap = 100f
            ink.strokeWidth = 2f; ink.color = Color.LTGRAY
            for (i in -30..30) {
                val x = i * gap + offsetX; val y = i * gap + offsetY
                canvas.drawLine(x, 0f, x, height.toFloat(), ink); canvas.drawLine(0f, y, width.toFloat(), y, ink)
            }
            ink.color = Color.DKGRAY
            canvas.drawText("方向与落点验证 · $mode", width * .2f + offsetX, height * .5f + offsetY, ink)
            if (mode == "drag") {
                ink.color = Color.rgb(62, 149, 101); ink.style = Paint.Style.STROKE; ink.strokeWidth = 5f
                canvas.drawCircle(width * .75f, height * .25f, 48f, ink)
                ink.style = Paint.Style.FILL; ink.color = Color.rgb(60, 98, 200)
                canvas.drawCircle(tokenX, tokenY, 38f, ink)
            }
        }
        override fun onDetachedFromWindow() { handler.removeCallbacksAndMessages(null); super.onDetachedFromWindow() }
    }
}

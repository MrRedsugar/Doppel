package dev.doppel.sdk

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Selection uses captured pixels/tree, never the changing app behind this activity. */
class ControlPickerSnapshotActivity : Activity() {
    private var level = 0
    private var selected = -1
    private var selectionLevel = 0
    private var ready = false
    private var frozen: AccessibilityControlPicker.Snapshot? = null
    private var preview: SnapshotView? = null
    private lateinit var info: TextView
    private lateinit var layer: Button
    private lateinit var confirm: Button
    private lateinit var cancel: Button
    private lateinit var reselect: Button
    private val token get() = intent.getStringExtra("picker_token").orEmpty()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!FirstUseConsent.allowEntry(this)) return
        UiTheme.init(this)
        // Frozen app content stays in memory and must not leak into recents/screenshots.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        AccessibilityControlPicker.attach(this)
        frozen = AccessibilityControlPicker.snapshot(token)
        level = state?.getInt("level", 0)?.coerceIn(0, 2) ?: 0
        selected = state?.getInt("selected", -1) ?: -1
        selectionLevel = state?.getInt("selection_level", level)?.coerceIn(0, 2) ?: level
        if (selected !in (frozen?.nodes?.indices ?: IntRange.EMPTY)) selected = -1
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        UiTheme.bind(page) { page.setBackgroundColor(UiTheme.background) }; UiTheme.window(this, page)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(UiTheme.text(this, "选择触发控件", 20f, UiTheme.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(UiTheme.command(this, "关闭") { AccessibilityControlPicker.stop(); finish() })
        page.addView(header)
        val image = SnapshotView(this).also { preview = it; it.contentDescription = "冻结的应用截图" }
        page.addView(image, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12); bottomMargin = dp(12) })
        info = UiTheme.text(this, "正在分析画面…", 14f, UiTheme.ink).apply { minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL }
        page.addView(info)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        layer = UiTheme.command(this, "分层 1/3") { selected = -1; level = (level + 1) % 3; update() }
        reselect = UiTheme.command(this, "重新选择") { AccessibilityControlPicker.reselect(this, intent.getStringExtra("picker_package").orEmpty()) }
        controls.addView(layer, LinearLayout.LayoutParams(0, -2, 1f)); controls.addView(reselect, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(controls)
        val selection = LinearLayout(this)
        cancel = UiTheme.command(this, "取消选择") { cancelSelection() }
        confirm = UiTheme.command(this, "确认", true) { if (selected >= 0) AccessibilityControlPicker.confirm(this, token, selected) }
        selection.addView(cancel, LinearLayout.LayoutParams(0, -2, 1f)); selection.addView(confirm, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(selection)
        layer.visibility = View.GONE; reselect.visibility = View.GONE; cancel.visibility = View.GONE; confirm.visibility = View.GONE
        setContentView(page)
        image.post {
            if (isFinishing || isDestroyed) return@post
            frozen?.layers
            ready = true
            update()
        }
    }
    private fun dp(value: Int) = UiTheme.dp(this, value)
    private fun update() {
        val capture = frozen
        layer.text = "分层 ${level + 1}/3"
        layer.visibility = if (ready && capture != null) View.VISIBLE else View.GONE
        reselect.visibility = if (ready) View.VISIBLE else View.GONE
        confirm.visibility = if (ready && capture != null) View.VISIBLE else View.GONE
        confirm.isEnabled = selected >= 0
        cancel.visibility = if (selected >= 0) View.VISIBLE else View.GONE
        info.text = when {
            !ready -> "正在分析画面…"
            capture == null -> "快照已失效，请重新选择"
            selected >= 0 -> capture.nodes[selected].let { it.text.ifBlank { it.id } }
            capture.layers[level].isEmpty() -> "本层没有可选控件，请切换分层"
            else -> "点击高亮控件选择；再次点击可选择其父控件"
        }
        preview?.invalidate()
    }
    private fun select(x: Float, y: Float) {
        val capture = frozen ?: return
        val previous = capture.nodes.getOrNull(selected)
        if (previous != null && previous.bounds.contains(x.toInt(), y.toInt())) {
            val parent = previous.parent
            val parentLayer = ((level + 1)..2).firstOrNull { parent != null && parent in capture.layers[it] }
            if (parent != null && parentLayer != null) { selected = parent; level = parentLayer; update() }
            return
        }
        val hit = capture.layers[level].filter { capture.nodes[it].bounds.contains(x.toInt(), y.toInt()) }
            .minByOrNull { capture.nodes[it].bounds.let { bounds -> bounds.width().toLong() * bounds.height() } } ?: return
        selectionLevel = level; selected = hit; update()
    }
    private fun cancelSelection() { selected = -1; level = selectionLevel; update() }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selected >= 0) cancelSelection() else { AccessibilityControlPicker.stop(); super.onBackPressed() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("level", level); outState.putInt("selected", selected); outState.putInt("selection_level", selectionLevel)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        preview = null; frozen = null
        AccessibilityControlPicker.detach(this, isChangingConfigurations)
        super.onDestroy()
    }

    private inner class SnapshotView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val imageRect = RectF()
        private var downX = 0f; private var downY = 0f
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        init { isClickable = true }
        private fun rect(bounds: android.graphics.Rect, capture: AccessibilityControlPicker.Snapshot) = RectF(
            imageRect.left + bounds.left * imageRect.width() / capture.displayWidth,
            imageRect.top + bounds.top * imageRect.height() / capture.displayHeight,
            imageRect.left + bounds.right * imageRect.width() / capture.displayWidth,
            imageRect.top + bounds.bottom * imageRect.height() / capture.displayHeight)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val capture = frozen ?: return
            val scale = minOf(width.toFloat() / capture.bitmap.width, height.toFloat() / capture.bitmap.height)
            val w = capture.bitmap.width * scale; val h = capture.bitmap.height * scale
            imageRect.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
            paint.color = Color.WHITE; paint.style = Paint.Style.FILL
            canvas.drawBitmap(capture.bitmap, null, imageRect, paint)
            if (!ready) return
            // Equal winding fills each covered pixel once, including overlapping sibling boxes.
            val union = Path()
            for (index in capture.layers[level]) union.addRect(rect(capture.nodes[index].bounds, capture), Path.Direction.CW)
            paint.color = 0x4037c5db; canvas.drawPath(union, paint)
            capture.nodes.getOrNull(selected)?.let { node ->
                val box = rect(node.bounds, capture)
                paint.color = 0x604ecde5; canvas.drawRect(box, paint)
                paint.color = UiTheme.blue; paint.style = Paint.Style.STROKE
                // View dp, independent of the captured image's resolution and scale.
                paint.strokeWidth = 2f * resources.displayMetrics.density
                canvas.drawRect(box, paint); paint.style = Paint.Style.FILL
            }
        }
        override fun performClick(): Boolean { super.performClick(); return true }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; return true }
                MotionEvent.ACTION_UP -> {
                    performClick()
                    val capture = frozen
                    if (ready && capture != null && imageRect.contains(event.x, event.y) &&
                        kotlin.math.abs(event.x - downX) <= slop && kotlin.math.abs(event.y - downY) <= slop) {
                        select((event.x - imageRect.left) * capture.displayWidth / imageRect.width(),
                            (event.y - imageRect.top) * capture.displayHeight / imageRect.height())
                    }
                    return true
                }
            }
            return true
        }
    }
}

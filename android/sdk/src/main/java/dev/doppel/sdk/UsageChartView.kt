package dev.doppel.sdk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

/** Lightweight line chart; deliberately uses Canvas so it adds no chart dependency. */
class UsageChartView(context: Context) : View(context) {
    private fun dp(value: Float) = value * resources.displayMetrics.density
    private val inputPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.blue; strokeWidth = dp(2.5f); style = Paint.Style.STROKE }
    private val outputPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE487B8.toInt(); strokeWidth = dp(2.5f); style = Paint.Style.STROKE }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x223B4563; strokeWidth = dp(1f) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.muted; textSize = 11f * resources.displayMetrics.scaledDensity }
    private var input = LongArray(0); private var output = LongArray(0)
    private var dates = emptyList<String>()
    fun setSeries(inputTokens: LongArray, outputTokens: LongArray, dateLabels: List<String> = emptyList()) {
        input = inputTokens.copyOf(); output = outputTokens.copyOf(); dates = dateLabels.toList()
        contentDescription = (0 until max(input.size, output.size)).joinToString("；") { i ->
            "${dates.getOrNull(i).orEmpty().ifBlank { "未标注日期" }}，输入 ${input.getOrElse(i) { 0 }} Token，输出 ${output.getOrElse(i) { 0 }} Token"
        }.ifEmpty { "暂无用量数据" }
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = dp(10f); val right = width - dp(10f); val top = dp(12f)
        val font = labelPaint.fontMetrics
        val bottom = height - dp(12f) - (font.bottom - font.top)
        if (right <= left || bottom <= top) return
        for (i in 0..3) { val y = top + (bottom - top) * i / 3f; canvas.drawLine(left, y, right, y, gridPaint) }
        val n = max(input.size, output.size); if (n == 0) return
        val maxValue = max(1L, max(input.maxOrNull() ?: 0, output.maxOrNull() ?: 0)).toFloat()
        fun x(index: Int) = if (n == 1) (left + right) / 2 else left + (right - left) * index / (n - 1).toFloat()
        fun y(value: Long) = bottom - (bottom - top) * value.coerceAtLeast(0) / maxValue
        fun series(values: LongArray, paint: Paint) {
            val path = Path()
            values.forEachIndexed { i, value -> if (i == 0) path.moveTo(x(i), y(value)) else path.lineTo(x(i), y(value)) }
            canvas.drawPath(path, paint)
            // A one-day path contains only moveTo and draws nothing; retain a visible sample.
            paint.style = Paint.Style.FILL
            values.forEachIndexed { i, value -> canvas.drawCircle(x(i), y(value), dp(3f), paint) }
            paint.style = Paint.Style.STROKE
        }
        series(input, inputPaint); series(output, outputPaint)
        val availableDates = dates.take(n)
        val labelWidth = availableDates.maxOfOrNull(labelPaint::measureText) ?: return
        if (labelWidth == 0f) return
        val slots = minOf(n, max(1, ((right - left) / (labelWidth + dp(12f))).toInt()))
        val indices = if (slots == 1) listOf((n - 1) / 2) else (0 until slots).map { (it * (n - 1).toFloat() / (slots - 1)).roundToInt() }
        for (i in indices) {
            val text = dates.getOrNull(i).orEmpty()
            val textWidth = labelPaint.measureText(text)
            val start = (x(i) - textWidth / 2).coerceIn(0f, (width - textWidth).coerceAtLeast(0f))
            canvas.drawText(text, start, height - dp(4f) - font.bottom, labelPaint)
        }
    }
}

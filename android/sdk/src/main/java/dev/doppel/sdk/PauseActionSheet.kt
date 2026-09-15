package dev.doppel.sdk

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/** Persistent interruption surface. Only explicit task actions can resolve it. */
internal class PauseActionSheet(context: Context, info: PausePresentation,
                               headingTitle: String = if (info.userInitiated) "任务待续" else info.category,
                               progressRun: org.json.JSONObject? = null,
                               action: (String) -> Unit) : LinearLayout(context) {
    private val end: Button
    private val continueTask: Button
    private val feedback = UiTheme.text(context, "", 13f, UiTheme.muted).apply {
        visibility = View.GONE
        setPadding(0, dp(12), 0, 0)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    init {
        orientation = VERTICAL
        background = UiTheme.glass(context, 28)
        elevation = dp(10).toFloat()
        setPadding(dp(22), dp(22), dp(22), dp(20))
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(ImageView(context).apply {
            setImageResource(UiIcons.pause)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            UiTheme.bind(this) { imageTintList = ColorStateList.valueOf(UiTheme.ink) }
        }, LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
        heading.addView(UiTheme.text(context, headingTitle, 14f, UiTheme.muted, true))
        addView(heading)
        progressRun?.let { run -> addView(TaskProgressView(context, true).apply { display(run, true) },
            LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
        val text = LinearLayout(context).apply { orientation = VERTICAL; setPadding(0, dp(16), 0, 0) }
        text.addView(UiTheme.text(context, info.surfaceReason, 19f, UiTheme.ink, true).apply { setLineSpacing(dp(4).toFloat(), 1f) })
        if (!info.userInitiated) text.addView(UiTheme.text(context, info.nextStep, 15f, UiTheme.muted).apply {
            setPadding(0, dp(12), 0, 0); setLineSpacing(dp(4).toFloat(), 1f)
        })
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = (resources.displayMetrics.heightPixels - dp(210)).coerceAtLeast(dp(72))
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(minOf(dp(330), available), View.MeasureSpec.AT_MOST))
            }
        }.apply { isFillViewport = false; clipToPadding = false; addView(text) }
        addView(scroll, LayoutParams(-1, -2, 1f))
        addView(feedback)
        val actions = LinearLayout(context).apply { setPadding(0, dp(20), 0, 0) }
        end = UiTheme.command(context, "结束") { action("cancel") }
        continueTask = UiTheme.command(context, "继续", true) { action("resume") }
        actions.addView(end, LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(10) })
        actions.addView(continueTask, LayoutParams(0, dp(52), 1f))
        addView(actions)
    }
    fun submitting(action: String) {
        end.isEnabled = false; continueTask.isEnabled = false
        if (action == "cancel") end.text = "结束中…" else continueTask.text = "继续中…"
        feedback.visibility = View.GONE
    }
    fun failed(message: String) {
        end.isEnabled = true; continueTask.isEnabled = true
        end.text = "结束"; continueTask.text = "继续"
        feedback.text = message; feedback.visibility = View.VISIBLE
    }
    private fun dp(value: Int) = UiTheme.dp(context, value)
}

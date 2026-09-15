package dev.doppel.sdk

import android.content.Context
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import org.json.JSONObject

/** The same persisted coarse plan on task cards and overlays; activity is shown separately. */
internal class TaskProgressView(context: Context, private val compact: Boolean = false) : LinearLayout(context) {
    private val label = UiTheme.text(context, "", if (compact) 11f else 12f, UiTheme.muted)
    private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val current = UiTheme.text(context, "", 13f, UiTheme.ink)
    private val plan = UiTheme.text(context, "", 12f, UiTheme.muted)
    private var shown: Pair<TaskProgress.Presentation, Boolean>? = null

    init {
        orientation = VERTICAL; visibility = GONE
        label.tag = "task_progress_label"; bar.tag = "task_progress_bar"
        current.tag = "task_progress_current"; plan.tag = "task_progress_plan"
        if (compact) { label.maxLines = 1; label.ellipsize = TextUtils.TruncateAt.END }
        addView(label, LayoutParams(-1, -2))
        addView(bar, LayoutParams(-1, UiTheme.dp(context, 4)).apply {
            topMargin = UiTheme.dp(context, if (compact) 1 else 5)
            bottomMargin = UiTheme.dp(context, if (compact) 0 else 6)
        })
        if (!compact) {
            current.setPadding(0, 0, 0, UiTheme.dp(context, 4))
            plan.setLineSpacing(UiTheme.dp(context, 3).toFloat(), 1f)
            addView(current, LayoutParams(-1, -2)); addView(plan, LayoutParams(-1, -2))
        }
        UiTheme.bind(bar) {
            bar.progressTintList = android.content.res.ColorStateList.valueOf(UiTheme.green)
            bar.indeterminateTintList = android.content.res.ColorStateList.valueOf(UiTheme.green)
            bar.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(UiTheme.line)
        }
    }

    fun display(run: JSONObject?, locallyPaused: Boolean = false) {
        if (run == null) { shown = null; visibility = GONE; bar.isIndeterminate = false; return }
        val progress = TaskProgress.presentation(run)
        val next = progress to locallyPaused
        visibility = VISIBLE
        if (shown == next) return
        shown = next
        label.text = if (compact) "${progress.label} · ${progress.current}" else progress.label
        label.contentDescription = "${progress.label}，${progress.current}"
        label.tooltipText = label.contentDescription
        bar.isIndeterminate = !progress.known && progress.running && !locallyPaused
        bar.visibility = if (progress.known || bar.isIndeterminate) VISIBLE else GONE
        bar.max = progress.plan.size.coerceAtLeast(1)
        bar.progress = progress.completed.coerceIn(0, bar.max)
        bar.contentDescription = progress.label
        current.text = progress.current
        plan.text = progress.plan.mapIndexed { index, step ->
            "${if (index < progress.completed) "✓" else "${index + 1}."} $step"
        }.joinToString("\n")
        plan.visibility = if (progress.plan.isEmpty()) GONE else VISIBLE
    }
}

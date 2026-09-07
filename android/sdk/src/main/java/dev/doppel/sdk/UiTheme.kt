package dev.doppel.sdk

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*

object UiTheme {
    val ink = Color.rgb(29, 35, 33)
    val muted = Color.rgb(111, 121, 116)
    val green = Color.rgb(22, 113, 83)
    val pale = Color.rgb(233, 245, 239)
    val background = Color.rgb(247, 249, 248)
    val line = Color.rgb(226, 231, 227)
    val blue = Color.rgb(57, 105, 154)
    val danger = Color.rgb(174, 67, 62)
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun surface(context: Context, color: Int = Color.WHITE, border: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(context, 8).toFloat()
        if (border) setStroke(dp(context, 1), line)
    }
    fun text(context: Context, value: String, size: Float = 15f, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); letterSpacing = 0f
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
    }
    fun field(context: Context, hint: String, initial: String = "", secret: Boolean = false) = EditText(context).apply {
        this.hint = hint; setText(initial); textSize = 15f; setTextColor(ink); setHintTextColor(muted)
        minHeight = dp(context, 48); setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
        background = surface(context, Color.WHITE, true); letterSpacing = 0f
        if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    fun command(context: Context, title: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = title; textSize = 14f; isAllCaps = false; letterSpacing = 0f; minHeight = dp(context, 44); maxLines = 2
        minimumWidth = 0; minWidth = 0; setPadding(dp(context, 16), 0, dp(context, 16), 0)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(muted, if (primary) Color.WHITE else green)))
        background = RippleDrawable(ColorStateList.valueOf(line), surface(context, if (primary) green else pale), null)
        setOnClickListener { action() }
    }
    fun icon(context: Context, drawable: Int, title: String, primary: Boolean = false, action: () -> Unit) = ImageButton(context).apply {
        setImageResource(drawable); imageTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(line, if (primary) Color.WHITE else green))
        contentDescription = title; tooltipText = title; setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
        background = RippleDrawable(ColorStateList.valueOf(line), surface(context, if (primary) green else Color.TRANSPARENT), null)
        setOnClickListener { action() }
    }
    fun divider(context: Context) = View(context).apply { setBackgroundColor(line); layoutParams = LinearLayout.LayoutParams(-1, dp(context, 1)) }
    fun row(context: Context, title: String, detail: String, icon: Int, action: () -> Unit): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(context, 64)
            background = RippleDrawable(ColorStateList.valueOf(pale), null, null)
            setPadding(0, dp(context, 10), 0, dp(context, 10)); isClickable = true; isFocusable = true
            addView(ImageView(context).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(green) }, LinearLayout.LayoutParams(dp(context, 22), dp(context, 22)).apply { marginEnd = dp(context, 14) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(context, title, 15f, ink, true).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END })
                if (detail.isNotBlank()) addView(text(context, detail, 12f, muted).apply { setPadding(0, dp(context, 5), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(context).apply { setImageResource(android.R.drawable.ic_media_next); imageTintList = ColorStateList.valueOf(muted) }, LinearLayout.LayoutParams(dp(context, 14), dp(context, 14)).apply { marginStart = dp(context, 10) })
            setOnClickListener { action() }
        }
    }
    fun window(activity: Activity, root: View) {
        activity.window.statusBarColor = Color.WHITE; activity.window.navigationBarColor = Color.WHITE
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        if (Build.VERSION.SDK_INT >= 35) root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars()); view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
    }
}

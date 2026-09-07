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
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.*

object UiTheme {
    val ink = Color.rgb(32, 33, 35)
    val muted = Color.rgb(112, 116, 123)
    val green = Color.rgb(18, 121, 104)
    val pale = Color.rgb(239, 242, 242)
    val background = Color.rgb(252, 252, 253)
    val line = Color.rgb(232, 234, 237)
    val blue = Color.rgb(67, 112, 164)
    val danger = Color.rgb(178, 66, 73)
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun surface(context: Context, color: Int = Color.WHITE, border: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(context, 8).toFloat()
        if (border) setStroke(dp(context, 1), line)
    }
    fun glass(context: Context, radius: Int = 24) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.argb(246, 255, 255, 255), Color.argb(226, 246, 248, 251), Color.argb(242, 255, 255, 255))
    ).apply {
        cornerRadius = dp(context, radius).toFloat()
        setStroke(dp(context, 1).coerceAtLeast(1), Color.argb(220, 255, 255, 255))
    }
    fun text(context: Context, value: String, size: Float = 15f, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); letterSpacing = 0f
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }
    fun field(context: Context, hint: String, initial: String = "", secret: Boolean = false) = EditText(context).apply {
        this.hint = hint; setText(initial); textSize = 15f; setTextColor(ink); setHintTextColor(muted)
        minHeight = dp(context, 48); setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
        background = surface(context, Color.rgb(247, 248, 249), true).apply { cornerRadius = dp(context, 16).toFloat() }; letterSpacing = 0f
        highlightColor = Color.argb(55, 18, 121, 104)
        if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    fun command(context: Context, title: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = title; textSize = 14f; isAllCaps = false; letterSpacing = 0f; minHeight = dp(context, 44); maxLines = 2
        minimumWidth = 0; minWidth = 0; setPadding(dp(context, 16), 0, dp(context, 16), 0)
        stateListAnimator = null
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(if (primary) Color.LTGRAY else muted, if (primary) Color.WHITE else ink)))
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(35, 128, 135, 145)), surface(context, if (primary) ink else pale).apply { cornerRadius = dp(context, 24).toFloat() }, null)
        setOnClickListener { action() }
    }
    fun icon(context: Context, drawable: Int, title: String, primary: Boolean = false, action: () -> Unit) = ImageButton(context).apply {
        setImageResource(UiIcons.resolve(drawable)); imageTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(if (primary) Color.LTGRAY else muted, if (primary) Color.WHITE else ink))
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = title; tooltipText = title; setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(30, 128, 135, 145)), surface(context, if (primary) ink else Color.TRANSPARENT).apply { cornerRadius = dp(context, 28).toFloat() }, null)
        setOnClickListener { action() }
    }
    fun divider(context: Context) = View(context).apply { setBackgroundColor(line); layoutParams = LinearLayout.LayoutParams(-1, dp(context, 1)) }
    fun row(context: Context, title: String, detail: String, icon: Int, action: () -> Unit): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(context, 64)
            background = RippleDrawable(ColorStateList.valueOf(pale), null, null)
            setPadding(0, dp(context, 10), 0, dp(context, 10)); isClickable = true; isFocusable = true
            addView(ImageView(context).apply { setImageResource(UiIcons.resolve(icon)); imageTintList = ColorStateList.valueOf(ink); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(context, 22), dp(context, 22)).apply { marginEnd = dp(context, 16) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(context, title, 15f, ink, true).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END })
                if (detail.isNotBlank()) addView(text(context, detail, 12f, muted).apply { setPadding(0, dp(context, 5), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(context).apply { setImageResource(UiIcons.resolve(android.R.drawable.ic_media_next)); imageTintList = ColorStateList.valueOf(muted); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(context, 16), dp(context, 16)).apply { marginStart = dp(context, 10) })
            setOnClickListener { action() }
        }
    }
    fun window(activity: Activity, root: View) {
        activity.window.statusBarColor = background; activity.window.navigationBarColor = background
        activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        if (Build.VERSION.SDK_INT >= 35) root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            WindowInsets.CONSUMED
        }
    }
    fun styleSheet(window: Window) {
        val backdrop = GradientDrawable().apply {
            setColor(background)
            cornerRadius = dp(window.context, 24).toFloat()
        }
        window.setBackgroundDrawable(backdrop)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.16f)
        if (Build.VERSION.SDK_INT >= 31) {
            // Window blur samples behind the sheet, leaving text and controls sharp.
            val manager = window.context.getSystemService(WindowManager::class.java)
            val listener = java.util.function.Consumer<Boolean> { enabled ->
                backdrop.setColor(if (enabled) Color.argb(20, 255, 255, 255) else background)
            }
            window.decorView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) { manager.addCrossWindowBlurEnabledListener(listener) }
                override fun onViewDetachedFromWindow(view: View) { manager.removeCrossWindowBlurEnabledListener(listener) }
            })
            window.setBackgroundBlurRadius(dp(window.context, 28))
        }
    }
}

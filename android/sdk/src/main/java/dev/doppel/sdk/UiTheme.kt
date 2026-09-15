package dev.doppel.sdk

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.*

object UiTheme {
    val ink get() = ThemeController.palette.ink
    val muted get() = ThemeController.palette.muted
    val green get() = ThemeController.palette.green
    val pale get() = ThemeController.palette.pale
    val background get() = ThemeController.palette.background
    val line get() = ThemeController.palette.line
    val blue get() = ThemeController.palette.blue
    val danger get() = ThemeController.palette.danger
    val spectrumBlue get() = ThemeController.palette.spectrumBlue
    val spectrumCyan get() = ThemeController.palette.spectrumCyan
    val spectrumPink get() = ThemeController.palette.spectrumPink
    val foregroundSurface get() = ThemeController.palette.surface
    val onPrimary get() = ThemeController.palette.onPrimary
    private val bindings = mutableListOf<java.lang.ref.WeakReference<Binding>>()
    private val drawables = mutableListOf<java.lang.ref.WeakReference<android.graphics.drawable.Drawable>>()
    private class Binding(val view: View, val update: () -> Unit)
    fun init(context: Context) = ThemeController.initialize(context)
    fun bind(view: View, update: () -> Unit) {
        val binding = Binding(view, update)
        bindings.add(java.lang.ref.WeakReference(binding))
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { binding.update() }
            override fun onViewDetachedFromWindow(view: View) {}
        })
        update()
    }
    internal fun refreshBindings() {
        bindings.removeAll { it.get() == null }
        bindings.toList().forEach { it.get()?.let { binding -> if (binding.view.isAttachedToWindow) binding.update() } }
        drawables.removeAll { it.get() == null }
        drawables.forEach { it.get()?.invalidateSelf() }
    }
    private fun dynamicColor(value: Int): () -> Int {
        val index = ThemeController.palette.values().indexOf(value).takeIf { it >= 0 }
            ?: ThemePalette.light.values().indexOf(value).takeIf { it >= 0 }
            ?: ThemePalette.dark.values().indexOf(value).takeIf { it >= 0 }
        return if (index == null) { { value } } else { { ThemeController.palette.colorAt(index) } }
    }
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun surface(context: Context, color: Int = foregroundSurface, border: Boolean = false): GradientDrawable {
        init(context)
        val source = dynamicColor(color)
        return object : GradientDrawable() {
            private var last = 0
            private var lastBorder = 0
            override fun draw(canvas: android.graphics.Canvas) {
                val current = source()
                if (last != current) { last = current; setColor(current) }
                if (border && lastBorder != line) { lastBorder = line; setStroke(dp(context, 1).coerceAtLeast(1), line) }
                super.draw(canvas)
            }
        }.apply {
            setColor(source()); cornerRadius = dp(context, 8).toFloat()
            if (border) setStroke(dp(context, 1).coerceAtLeast(1), line)
            drawables.add(java.lang.ref.WeakReference(this))
        }
    }
    fun glass(context: Context, radius: Int = 24): UiGlassDrawable {
        init(context)
        return UiGlassDrawable(dp(context, radius).toFloat(), dp(context, 1).coerceAtLeast(1).toFloat()).also { drawables.add(java.lang.ref.WeakReference(it)) }
    }
    fun text(context: Context, value: String, size: Float = 15f, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        init(context)
        val source = dynamicColor(color)
        text = value; textSize = size; letterSpacing = 0f
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
        bind(this) { setTextColor(source()); compoundDrawablesRelative.filterNotNull().forEach { it.setTint(source()) } }
    }
    fun field(context: Context, hint: String, initial: String = "", secret: Boolean = false) = EditText(context).apply {
        init(context)
        this.hint = hint; setText(initial); textSize = 15f; setTextColor(ink); setHintTextColor(muted)
        minHeight = dp(context, 48); setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
        val focused = surface(context, foregroundSurface).apply { cornerRadius = dp(context, 16).toFloat() }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), surface(context, pale, true).apply { cornerRadius = dp(context, 16).toFloat() })
        }; letterSpacing = 0f
        highlightColor = Color.argb(55, 75, 119, 235)
        imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        bind(this) { setTextColor(ink); setHintTextColor(muted); focused.setStroke(dp(context, 1).coerceAtLeast(1), spectrumBlue) }
    }
    fun command(context: Context, title: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        init(context)
        text = title; textSize = 14f; isAllCaps = false; letterSpacing = 0f; minHeight = dp(context, 44); maxLines = 2
        minimumWidth = 0; minWidth = 0; setPadding(dp(context, 16), 0, dp(context, 16), 0)
        stateListAnimator = null
        bind(this) {
            setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(muted, if (primary) onPrimary else ink)))
            compoundDrawablesRelative.filterNotNull().forEach { it.setTint(if (primary) onPrimary else ink) }
        }
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(35, 128, 135, 145)), surface(context, if (primary) ink else pale).apply { cornerRadius = dp(context, 24).toFloat() }, null)
        setOnClickListener { action() }
    }
    fun icon(context: Context, drawable: Int, title: String, primary: Boolean = false, action: () -> Unit) = ImageButton(context).apply {
        init(context)
        setImageResource(UiIcons.resolve(drawable))
        bind(this) { imageTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(muted, if (primary) onPrimary else ink)) }
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = title; tooltipText = title; setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(30, 128, 135, 145)), surface(context, if (primary) ink else Color.TRANSPARENT).apply { cornerRadius = dp(context, 28).toFloat() }, null)
        setOnClickListener { action() }
    }
    fun divider(context: Context) = View(context).apply { bind(this) { setBackgroundColor(line) }; layoutParams = LinearLayout.LayoutParams(-1, dp(context, 1)) }
    fun toggle(context: Context, title: String, checked: Boolean, changed: (Boolean) -> Unit = {}) = Switch(context).apply {
        init(context)
        text = title; textSize = 15f; setTextColor(ink); letterSpacing = 0f; minHeight = dp(context, 56)
        setPadding(0, dp(context, 8), 0, dp(context, 8)); switchPadding = dp(context, 18); splitTrack = false; showText = false
        thumbDrawable = InsetDrawable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setSize(dp(context, 22), dp(context, 22)) }, dp(context, 3))
        val selectedTrack = GradientDrawable().apply { cornerRadius = dp(context, 16).toFloat(); setSize(dp(context, 48), dp(context, 28)) }
        val idleTrack = GradientDrawable().apply { cornerRadius = dp(context, 16).toFloat(); setSize(dp(context, 48), dp(context, 28)) }
        trackDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), selectedTrack)
            addState(intArrayOf(), idleTrack)
        }
        bind(this) { setTextColor(ink); selectedTrack.setColor(green); idleTrack.setColor(line) }
        thumbTintList = null; trackTintList = null; isChecked = checked; setOnCheckedChangeListener { _, value -> changed(value) }
    }
    fun check(context: Context, title: String, checked: Boolean) = CheckBox(context).apply {
        init(context)
        text = title; textSize = 15f; setTextColor(ink); letterSpacing = 0f; minHeight = dp(context, 48)
        bind(this) { setTextColor(ink); buttonTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(ink, muted)) }
        isChecked = checked
    }
    fun selector(context: Context, title: String, options: List<String>, initial: Int, selected: (Int) -> Unit): TextView {
        var current = initial.coerceIn(0, (options.size - 1).coerceAtLeast(0))
        return text(context, options.getOrNull(current).orEmpty(), 15f, ink, true).apply {
            minHeight = dp(context, 52); gravity = Gravity.CENTER_VERTICAL; isFocusable = true; contentDescription = title
            background = glass(context, 16); setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, UiIcons.chevronDown, 0); compoundDrawablePadding = dp(context, 14)
            isEnabled = options.isNotEmpty()
            setOnClickListener {
                UiDialog.Builder(context).setTitle(title).setSingleChoiceItems(options.toTypedArray(), current) { dialog, index ->
                    current = index; text = options[index]; selected(index); dialog.dismiss()
                }.setNegativeButton("关闭", null).show()
            }
        }
    }
    fun row(context: Context, title: String, detail: String, icon: Int, action: () -> Unit): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(context, 64)
            background = RippleDrawable(ColorStateList.valueOf(pale), null, null)
            setPadding(0, dp(context, 10), 0, dp(context, 10)); isClickable = true; isFocusable = true
            val resolved = UiIcons.resolve(icon)
            addView(ImageView(context).apply {
                setImageResource(resolved); bind(this) { imageTintList = ColorStateList.valueOf(ink) }; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background = surface(context, pale)
                setPadding(dp(context, 7), dp(context, 7), dp(context, 7), dp(context, 7))
            }, LinearLayout.LayoutParams(dp(context, 36), dp(context, 36)).apply { marginEnd = dp(context, 14) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(context, title, 15f, ink, true).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END })
                if (detail.isNotBlank()) addView(text(context, detail, 12f, muted).apply { setPadding(0, dp(context, 5), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(context).apply { setImageResource(UiIcons.next); bind(this) { imageTintList = ColorStateList.valueOf(muted) }; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(context, 16), dp(context, 16)).apply { marginStart = dp(context, 10) })
            setOnClickListener { action() }
        }
    }
    fun navigationRow(context: Context, title: String, icon: Int, selected: Boolean, action: () -> Unit): LinearLayout =
        row(context, title, "", icon, action).apply {
            contentDescription = title; isSelected = selected; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setPaddingRelative(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            val fill = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_selected), surface(context, pale).apply { cornerRadius = dp(context, 16).toFloat() })
                addState(intArrayOf(), surface(context, Color.TRANSPARENT))
            }
            val mask = surface(context).apply { cornerRadius = dp(context, 16).toFloat() }
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(28, 75, 119, 235)), fill, mask)
        }
    fun window(activity: Activity, root: View) {
        init(activity)
        val solidBackground = root.background as? android.graphics.drawable.ColorDrawable
        val rootColor = solidBackground?.color?.let(::dynamicColor)
        bind(root) {
            if (root.background === solidBackground && rootColor != null) solidBackground?.color = rootColor()
            activity.window.statusBarColor = background; activity.window.navigationBarColor = background
            activity.window.decorView.systemUiVisibility = if (ThemeController.isDark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= 35) root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            WindowInsets.CONSUMED
        }
    }
    fun styleSheet(window: Window) {
        init(window.context)
        val backdrop = GradientDrawable().apply {
            setColor(background)
            cornerRadius = dp(window.context, 24).toFloat()
        }
        window.setBackgroundDrawable(backdrop)
        var blurEnabled = false
        bind(window.decorView) { backdrop.setColor(if (blurEnabled) Color.argb(20, Color.red(background), Color.green(background), Color.blue(background)) else background) }
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.16f)
        if (Build.VERSION.SDK_INT >= 31) {
            // Window blur samples behind the sheet, leaving text and controls sharp.
            val manager = window.context.getSystemService(WindowManager::class.java)
            val listener = java.util.function.Consumer<Boolean> { enabled ->
                blurEnabled = enabled
                backdrop.setColor(if (enabled) Color.argb(20, Color.red(background), Color.green(background), Color.blue(background)) else background)
                window.setDimAmount(if (enabled) 0.12f else 0.16f)
            }
            window.decorView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) { manager.addCrossWindowBlurEnabledListener(listener) }
                override fun onViewDetachedFromWindow(view: View) { manager.removeCrossWindowBlurEnabledListener(listener) }
            })
            window.setBackgroundBlurRadius(dp(window.context, 28))
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = dp(window.context, 16) }
        }
    }
}

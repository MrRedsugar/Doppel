package dev.doppel.sdk

import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView

/** Shared sheet presentation with ordinary Dialog dismissal and button semantics. */
class UiDialog private constructor(context: Context, private val spec: Builder) : Dialog(context, R.style.DoppelGlassDialog) {
    private val buttons = mutableMapOf<Int, Button>()
    private val choiceUpdates = mutableListOf<() -> Unit>()
    private var availableHeight = (context.resources.displayMetrics.heightPixels * 0.86f).toInt()
    private lateinit var sheet: LinearLayout
    private lateinit var handle: View
    private lateinit var headingText: android.widget.TextView
    private lateinit var headingRow: LinearLayout
    private lateinit var footer: LinearLayout
    private var compact = false
    private val geometryChanges = ViewTreeObserver.OnGlobalLayoutListener { updateGeometry() }

    fun getButton(which: Int): Button = requireNotNull(buttons[which])

    init {
        val root = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maximum = availableHeight
                val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maximum else minOf(maximum, MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
            }
        }.apply {
            orientation = LinearLayout.VERTICAL; background = UiTheme.glass(context, 28); clipToOutline = true
            setPadding(dp(22), dp(12), dp(22), dp(16))
        }
        sheet = root
        handle = View(context).apply { background = UiTheme.surface(context, UiTheme.line); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        root.addView(handle, LinearLayout.LayoutParams(dp(32), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        headingRow = heading
        headingText = UiTheme.text(context, spec.title, 19f, UiTheme.ink, true).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }
        heading.addView(headingText, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(UiTheme.icon(context, UiIcons.close, "关闭") { dismiss() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(heading)
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        spec.message?.takeIf { it.isNotBlank() }?.let { body.addView(UiTheme.text(context, it, 15f, UiTheme.muted).apply { setLineSpacing(dp(4).toFloat(), 1f); setTextIsSelectable(true); setPadding(0, dp(12), 0, dp(18)) }) }
        spec.content?.let { body.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(12) }) }
        spec.items?.forEachIndexed { index, label ->
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(58); isFocusable = true; isClickable = true
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(UiTheme.pale), null, null)
                setPadding(dp(2), dp(12), dp(2), dp(12))
            }
            val labelView = UiTheme.text(context, label, 15f, UiTheme.ink, index == spec.checked)
            row.addView(labelView, LinearLayout.LayoutParams(0, -2, 1f))
            if (spec.singleChoice) {
                row.isSelected = index == spec.checked
                row.accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = android.widget.RadioButton::class.java.name; info.isCheckable = true; info.isChecked = index == spec.checked
                    }
                }
                val marker = View(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                row.addView(marker, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(16) })
                val update = {
                    val selected = index == spec.checked
                    row.isSelected = selected
                    labelView.typeface = android.graphics.Typeface.create(if (selected) "sans-serif-medium" else "sans-serif", android.graphics.Typeface.NORMAL)
                    marker.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL; setColor(if (selected) UiTheme.ink else Color.TRANSPARENT)
                        setStroke(dp(if (selected) 5 else 1), if (selected) UiTheme.pale else UiTheme.line)
                    }
                }
                choiceUpdates.add(update); UiTheme.bind(marker, update)
            } else {
                row.addView(android.widget.ImageView(context).apply { setImageResource(UiIcons.next); UiTheme.bind(this) { imageTintList = android.content.res.ColorStateList.valueOf(UiTheme.muted) }; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(16) })
            }
            row.setOnClickListener {
                if (spec.singleChoice) { spec.checked = index; choiceUpdates.forEach { update -> update() } }
                spec.onItem?.invoke(this, index)
                if (!spec.singleChoice) dismiss()
            }
            body.addView(row); if (index < spec.items!!.lastIndex) body.addView(UiTheme.divider(context))
        }
        root.addView(ScrollView(context).apply { isFillViewport = false; isVerticalScrollBarEnabled = false; addView(body) }, LinearLayout.LayoutParams(-1, -2, 1f))
        footer = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, 0) }
        listOf(DialogInterface.BUTTON_NEUTRAL, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_POSITIVE).forEach { which ->
            spec.actions[which]?.let { (label, callback) ->
                val button = UiTheme.command(context, label, which == DialogInterface.BUTTON_POSITIVE) { callback?.invoke(this, which); dismiss() }
                footer.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (footer.childCount > 0) marginStart = dp(10) })
                buttons[which] = button
            }
        }
        if (footer.childCount > 0) root.addView(footer)
        setContentView(root)
        setCanceledOnTouchOutside(true)
        window?.let {
            UiTheme.styleSheet(it); it.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL); it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            if (android.os.Build.VERSION.SDK_INT >= 30) it.setDecorFitsSystemWindows(true)
            it.attributes = it.attributes.apply { y = dp(10) }
            if ((owningActivity(context)?.window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE != 0) it.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun show() {
        super.show()
        window?.decorView?.viewTreeObserver?.addOnGlobalLayoutListener(geometryChanges)
        updateGeometry()
    }

    override fun dismiss() {
        window?.decorView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(geometryChanges)
        super.dismiss()
    }

    private fun updateGeometry() {
        val currentWindow = window ?: return
        val display = context.resources.displayMetrics
        val screen = if (android.os.Build.VERSION.SDK_INT >= 30) context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds else Rect(0, 0, display.widthPixels, display.heightPixels)
        val safe = Rect(screen)
        if (android.os.Build.VERSION.SDK_INT >= 30) currentWindow.decorView.rootWindowInsets?.let { insets ->
            val occupied = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout() or android.view.WindowInsets.Type.ime())
            safe.set(screen.left + occupied.left, screen.top + occupied.top, screen.right - occupied.right, screen.bottom - occupied.bottom)
        }
        val visible = Rect()
        currentWindow.decorView.getWindowVisibleDisplayFrame(visible)
        if (!visible.isEmpty) safe.intersect(visible)
        if (safe.width() <= dp(40) || safe.height() <= dp(80)) return
        val width = minOf(dp(560), safe.width() - dp(20))
        val height = minOf((screen.height() * 0.86f).toInt(), safe.height() - dp(20))
        val compactNow = height < dp(260)
        if (compactNow != compact) {
            compact = compactNow
            handle.visibility = if (compact) View.GONE else View.VISIBLE
            headingText.maxLines = if (compact) 1 else 3
            sheet.setPadding(dp(22), dp(if (compact) 4 else 12), dp(22), dp(if (compact) 4 else 16))
            footer.setPadding(0, dp(if (compact) 4 else 14), 0, 0)
        }
        headingRow.visibility = if (height < dp(180) && footer.childCount > 0 && sheet.findFocus() is android.widget.EditText) View.GONE else View.VISIBLE
        if (availableHeight != height) {
            availableHeight = height; sheet.requestLayout()
            sheet.post { sheet.findFocus()?.let { focused -> focused.requestRectangleOnScreen(Rect(0, 0, focused.width, focused.height), true) } }
        }
        if (currentWindow.attributes.width != width) currentWindow.setLayout(width, -2)
    }

    private fun dp(value: Int) = UiTheme.dp(context, value)

    private fun owningActivity(context: Context): android.app.Activity? {
        var current = context
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Context, Boolean>())
        repeat(16) {
            if (!visited.add(current)) return null
            if (current is android.app.Activity) return current as android.app.Activity
            current = (current as? ContextWrapper)?.baseContext ?: return null
        }
        return null
    }

    class Builder(private val context: Context) {
        internal var title = ""
        internal var message: String? = null
        internal var content: View? = null
        internal var items: Array<String>? = null
        internal var checked = -1
        internal var singleChoice = false
        internal var onItem: ((DialogInterface, Int) -> Unit)? = null
        internal val actions = mutableMapOf<Int, Pair<String, ((DialogInterface, Int) -> Unit)?>>()
        fun setTitle(value: String) = apply { title = value }
        fun setMessage(value: String) = apply { message = value }
        fun setView(value: View) = apply { content = value }
        fun setItems(values: Array<String>, action: (DialogInterface, Int) -> Unit) = apply { items = values; onItem = action }
        fun setSingleChoiceItems(values: Array<String>, selected: Int, action: (DialogInterface, Int) -> Unit) = apply { items = values; checked = selected; singleChoice = true; onItem = action }
        fun setPositiveButton(value: String, action: ((DialogInterface, Int) -> Unit)?) = apply { actions[DialogInterface.BUTTON_POSITIVE] = value to action }
        fun setNegativeButton(value: String, action: ((DialogInterface, Int) -> Unit)?) = apply { actions[DialogInterface.BUTTON_NEGATIVE] = value to action }
        fun setNeutralButton(value: String, action: ((DialogInterface, Int) -> Unit)?) = apply { actions[DialogInterface.BUTTON_NEUTRAL] = value to action }
        fun create() = UiDialog(context, this)
        fun show() = create().also { it.show() }
    }
}

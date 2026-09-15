package dev.doppel.sdk

import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.ActionMode
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** Accidental-touch protection for an automatic run. This is not an Android keyguard. */
internal object AutomaticRunShield {
    private val handler = Handler(Looper.getMainLooper())
    private var manager: WindowManager? = null
    private var root: FrameLayout? = null
    private var runPanel: View? = null
    private var message: TextView? = null
    private var taskProgress: TaskProgressView? = null
    private var params: WindowManager.LayoutParams? = null
    private var holding: View? = null
    private var hold: Runnable? = null
    private var holdProgress: ProgressBar? = null
    private var holdMessage: TextView? = null
    private var holdStartedAt = 0L
    private var holdLabel = ""
    private var passing = false
    private var authenticationPanel: View? = null
    private var authenticationInput: EditText? = null
    private var authenticationMessage: TextView? = null
    private var authenticationCountdown: TextView? = null
    private var authenticationActivity: (() -> Unit)? = null
    private var authenticationCancel: (() -> Unit)? = null
    private var changingInput = false
    val visible: Boolean get() = root?.isAttachedToWindow == true
    val isHolding: Boolean get() = holding != null
    val isAuthenticating: Boolean get() = authenticationPanel != null

    fun show(service: DoppelAccessibilityService, onHold: (String) -> Unit): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        hide()
        val screen = object : FrameLayout(service) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (isAuthenticating && event.actionMasked == MotionEvent.ACTION_DOWN) authenticationActivity?.invoke()
                return super.dispatchTouchEvent(event)
            }
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (isAuthenticating && event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) authenticationCancel?.invoke()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(0x08000000)
            isClickable = true
            setOnTouchListener { _, _ -> true }
        }
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; background = UiTheme.glass(service)
            setPadding(UiTheme.dp(service, 18), UiTheme.dp(service, 14), UiTheme.dp(service, 18), UiTheme.dp(service, 12))
        }
        panel.addView(UiTheme.text(service, "自动任务", 12f, UiTheme.muted, true))
        message = UiTheme.text(service, "正在自动执行", 16f, UiTheme.ink, true).also { panel.addView(it) }
        taskProgress = TaskProgressView(service, true).also { panel.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiTheme.dp(service, 4); bottomMargin = UiTheme.dp(service, 4) }) }
        panel.addView(UiTheme.text(service, "长按接管或停止 3 秒，再输入已设置的锁屏密码进行本机验证。", 12f, UiTheme.muted))
        holdMessage = UiTheme.text(service, "", 12f, UiTheme.muted).also { it.visibility = View.GONE; panel.addView(it) }
        holdProgress = ProgressBar(service, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 3000; visibility = View.GONE
        }.also { panel.addView(it, LinearLayout.LayoutParams(-1, UiTheme.dp(service, 6))) }
        val row = LinearLayout(service)
        for ((label, action) in listOf("接管" to "pause", "停止" to "cancel")) {
            val button = UiTheme.command(service, label) {}.apply {
                contentDescription = "$label，长按 3 秒后进行本机密码验证"
            }
            button.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        cancelHold()
                        if (!passing && !isAuthenticating) {
                            holding = view; view.isPressed = true
                            holdStartedAt = SystemClock.elapsedRealtime(); holdLabel = label
                            holdProgress?.visibility = View.VISIBLE; holdMessage?.visibility = View.VISIBLE
                            refreshHold.run()
                            val pending = Runnable {
                                if (holding === view && visible && !passing && !isAuthenticating) {
                                    cancelHold()
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                    onHold(action)
                                }
                            }
                            hold = pending; handler.postDelayed(pending, 3000L)
                        }
                    }
                    MotionEvent.ACTION_MOVE -> if (event.x < 0 || event.y < 0 || event.x >= view.width || event.y >= view.height) cancelHold()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN,
                    MotionEvent.ACTION_POINTER_UP -> cancelHold()
                }
                true
            }
            row.addView(button, LinearLayout.LayoutParams(0, UiTheme.dp(service, 52), 1f))
        }
        panel.addView(row)
        screen.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = UiTheme.dp(service, 16); rightMargin = UiTheme.dp(service, 16)
            bottomMargin = UiTheme.dp(service, 40)
        })
        val layout = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= 30) {
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        manager = service.getSystemService(WindowManager::class.java)
        root = screen; runPanel = panel; params = layout; passing = false
        if (Build.VERSION.SDK_INT >= 30) screen.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsets.Type.ime())
            if (isAuthenticating) view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            else view.setPadding(0, 0, 0, 0)
            insets
        }
        return try { manager!!.addView(screen, layout); true } catch (_: Exception) { hide(); false }
    }

    /** App-local verification. The same touch shield stays attached, including after Home. */
    fun showAuthentication(
        kind: AutomaticUnlockCredentials.Kind,
        action: String,
        onSubmit: (CharArray) -> Unit,
        onActivity: () -> Unit,
        onCancel: () -> Unit,
    ): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val screen = root?.takeIf { it.isAttachedToWindow } ?: return false
        val layout = params ?: return false
        if (isAuthenticating || !setPassing(false)) return false
        cancelHold()
        val context = screen.context
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; background = UiTheme.glass(context)
            setPadding(UiTheme.dp(context, 18), UiTheme.dp(context, 18), UiTheme.dp(context, 18), UiTheme.dp(context, 14))
        }
        val stopping = action == "cancel"
        panel.addView(UiTheme.text(context, if (stopping) "验证后结束任务" else "验证后接管", 18f, UiTheme.ink, true))
        panel.addView(UiTheme.text(context, "请输入自动解锁中保存的锁屏密码。5 秒无操作或连续 3 次错误后继续任务。", 12f, UiTheme.muted).apply {
            setPadding(0, UiTheme.dp(context, 10), 0, UiTheme.dp(context, 12))
        })
        val input = object : EditText(context) {
            override fun onTextContextMenuItem(id: Int) = false
            override fun onDragEvent(event: android.view.DragEvent) = false
            override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(info)
                // Services may request otherwise unimportant views; never expose the editable buffer.
                info.text = null; info.hintText = null; info.contentDescription = "锁屏密码"
            }
            override fun onPopulateAccessibilityEvent(event: AccessibilityEvent) {
                super.onPopulateAccessibilityEvent(event)
                event.text.clear(); event.beforeText = null; event.contentDescription = "锁屏密码"
            }
            override fun sendAccessibilityEventUnchecked(event: AccessibilityEvent) {
                event.text.clear(); event.beforeText = null; event.contentDescription = "锁屏密码"
                super.sendAccessibilityEventUnchecked(event)
            }
        }.apply {
            hint = "锁屏密码"; contentDescription = "锁屏密码"
            textSize = 18f; setTextColor(UiTheme.ink); setHintTextColor(UiTheme.muted)
            background = UiTheme.surface(context, UiTheme.pale, true)
            setPadding(UiTheme.dp(context, 12), UiTheme.dp(context, 12), UiTheme.dp(context, 12), UiTheme.dp(context, 12))
            isSingleLine = true; isSaveEnabled = false; isSaveFromParentEnabled = false; isLongClickable = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            filterTouchesWhenObscured = true
            inputType = if (kind == AutomaticUnlockCredentials.Kind.PIN)
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            if (kind == AutomaticUnlockCredentials.Kind.PIN) {
                showSoftInputOnFocus = false; isFocusable = false
            }
            // Do not briefly reveal the last typed character, even if the system enables it.
            transformationMethod = object : PasswordTransformationMethod() {
                override fun getTransformation(source: CharSequence, view: View): CharSequence = object : CharSequence {
                    override val length get() = source.length
                    override fun get(index: Int) = '\u2022'
                    override fun subSequence(startIndex: Int, endIndex: Int) = "\u2022".repeat(endIndex - startIndex)
                    override fun toString() = "\u2022".repeat(length)
                }
            }
            filters = arrayOf(InputFilter.LengthFilter(if (kind == AutomaticUnlockCredentials.Kind.PIN) 16 else 64))
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            val noClipboard = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode?, menu: Menu?) = false
                override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false
                override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = false
                override fun onDestroyActionMode(mode: ActionMode?) {}
            }
            customSelectionActionModeCallback = noClipboard; customInsertionActionModeCallback = noClipboard
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!changingInput && isAuthenticating) authenticationActivity?.invoke()
                }
                override fun afterTextChanged(value: Editable?) {}
            })
        }
        panel.addView(input, LinearLayout.LayoutParams(-1, UiTheme.dp(context, 54)))
        authenticationMessage = UiTheme.text(context, "密码仅在本机核对，不会发送给 AI。", 12f, UiTheme.muted).also {
            it.setPadding(0, UiTheme.dp(context, 10), 0, UiTheme.dp(context, 8)); panel.addView(it)
        }
        authenticationCountdown = UiTheme.text(context, "5 秒无操作后继续任务", 12f, UiTheme.muted).also { panel.addView(it) }
        if (kind == AutomaticUnlockCredentials.Kind.PIN) {
            // Local PIN keys keep the keyboard window from changing the captured app's navigation state.
            for (labels in listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("退格", "0", "清空"))) {
                val row = LinearLayout(context)
                for (label in labels) row.addView(UiTheme.command(context, label) {
                    if (authenticationInput !== input) return@command
                    when (label) {
                        "退格" -> if (input.text.isNotEmpty()) input.text.delete(input.text.length - 1, input.text.length)
                        "清空" -> input.text.clear()
                        else -> input.text.append(label)
                    }
                }.apply { contentDescription = if (label.length == 1) "数字 $label" else label },
                    LinearLayout.LayoutParams(0, UiTheme.dp(context, 48), 1f))
                panel.addView(row)
            }
        }
        fun submit() {
            if (!isAuthenticating || authenticationInput !== input || input.text.isEmpty()) return
            onActivity()
            val value = CharArray(input.text.length) { input.text[it] }
            clearInput()
            // Ownership transfers to the session, which must wipe the array after verification.
            try { onSubmit(value) } catch (failure: Throwable) { value.fill('\u0000'); throw failure }
        }
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) {
                submit(); true
            } else false
        }
        val buttons = LinearLayout(context)
        buttons.addView(UiTheme.command(context, "继续任务") { onCancel() }, LinearLayout.LayoutParams(0, UiTheme.dp(context, 48), 1f))
        buttons.addView(UiTheme.command(context, if (stopping) "确认并结束" else "确认并接管", true) { submit() }, LinearLayout.LayoutParams(0, UiTheme.dp(context, 48), 1f))
        panel.addView(buttons)
        val card = ScrollView(context).apply { addView(panel) }
        authenticationPanel = card; authenticationInput = input
        authenticationActivity = onActivity; authenticationCancel = onCancel
        runPanel?.visibility = View.GONE
        screen.alpha = 1f; screen.setBackgroundColor(0xBB000000.toInt())
        screen.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
            leftMargin = UiTheme.dp(context, 24); rightMargin = UiTheme.dp(context, 24)
            topMargin = UiTheme.dp(context, 16); bottomMargin = UiTheme.dp(context, 16)
        })
        layout.flags = (if (kind == AutomaticUnlockCredentials.Kind.PASSWORD)
            layout.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else layout.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or WindowManager.LayoutParams.FLAG_SECURE
        layout.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        return try {
            manager!!.updateViewLayout(screen, layout)
            screen.requestApplyInsets()
            if (kind == AutomaticUnlockCredentials.Kind.PASSWORD) {
                input.requestFocus()
                input.post {
                    if (authenticationInput === input && isAuthenticating)
                        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            true
        } catch (_: Exception) { hideAuthentication(); false }
    }

    fun authenticationError(value: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        authenticationMessage?.apply { text = value.take(120); setTextColor(UiTheme.danger) }
    }

    fun authenticationCountdown(seconds: Int) {
        check(Looper.myLooper() == Looper.getMainLooper())
        authenticationCountdown?.text = "${seconds.coerceAtLeast(0)} 秒无操作后继续任务"
    }

    fun hideAuthentication(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val screen = root?.takeIf { it.isAttachedToWindow } ?: return false
        val layout = params ?: return false
        if (!isAuthenticating) return true
        clearInput()
        screen.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(screen.windowToken, 0)
        layout.flags = (layout.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and WindowManager.LayoutParams.FLAG_SECURE.inv()
        layout.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        return try {
            manager!!.updateViewLayout(screen, layout)
            authenticationPanel?.let(screen::removeView)
            authenticationPanel = null; authenticationInput = null; authenticationMessage = null; authenticationCountdown = null
            authenticationActivity = null; authenticationCancel = null
            runPanel?.visibility = View.VISIBLE
            screen.setBackgroundColor(0x08000000); screen.setPadding(0, 0, 0, 0); screen.requestApplyInsets()
            true
        } catch (_: Exception) { false }
    }

    private fun clearInput() {
        changingInput = true
        try { authenticationInput?.text?.clear() } finally { changingInput = false }
    }

    /** The owner must stop dispatching if yielding or restoring the input window fails. */
    fun setPassing(value: Boolean): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val screen = root?.takeIf { it.isAttachedToWindow } ?: return false
        val layout = params ?: return false
        if (value && (isAuthenticating || isHolding)) return false
        layout.flags = if (value) layout.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else layout.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        return try {
            manager!!.updateViewLayout(screen, layout)
            passing = value
            true
        } catch (_: Exception) { false }
    }

    fun update(value: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        message?.text = value.take(100).ifBlank { "正在自动执行" }
    }

    fun updateProgress(run: org.json.JSONObject, paused: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        taskProgress?.display(run, paused)
    }

    /** Whole-display screenshot fallback: hide pixels while keeping the touch window installed. */
    fun setCaptureHidden(hidden: Boolean): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val screen = root?.takeIf { it.isAttachedToWindow } ?: return false
        if (hidden && isAuthenticating) return false
        screen.alpha = if (hidden) 0f else 1f
        return true
    }

    private fun cancelHold() {
        hold?.let(handler::removeCallbacks); hold = null
        handler.removeCallbacks(refreshHold)
        holding?.isPressed = false; holding = null
        holdProgress?.visibility = View.GONE; holdMessage?.visibility = View.GONE
    }

    private val refreshHold = object : Runnable {
        override fun run() {
            if (!isHolding) return
            val elapsed = (SystemClock.elapsedRealtime() - holdStartedAt).coerceIn(0, 3000).toInt()
            holdProgress?.progress = elapsed
            holdMessage?.text = "$holdLabel ${elapsed / 1000}.${elapsed % 1000 / 100} / 3 秒"
            handler.postDelayed(this, 100)
        }
    }

    fun hide() {
        check(Looper.myLooper() == Looper.getMainLooper())
        cancelHold()
        clearInput()
        root?.let { screen -> screen.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(screen.windowToken, 0) }
        root?.let { runCatching { manager?.removeViewImmediate(it) } }
        root = null; runPanel = null; params = null; manager = null; message = null; passing = false
        holdProgress = null; holdMessage = null; taskProgress = null
        authenticationPanel = null; authenticationInput = null; authenticationMessage = null; authenticationCountdown = null
        authenticationActivity = null; authenticationCancel = null
    }
}

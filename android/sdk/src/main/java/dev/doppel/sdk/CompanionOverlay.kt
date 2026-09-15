package dev.doppel.sdk

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A bounded touch window; it never intercepts the rest of the app. */
class CompanionOverlay(private val context: Context, private val pause: () -> Unit) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val handler = androidx.core.os.HandlerCompat.createAsync(Looper.getMainLooper())
    private val prefs = Gateway(context).prefs
    private var attached = false
    private var captureHidden = false
    private val gestureTouchPass = CompanionTouchPassState()
    private val touchPassDiagnostic = java.util.concurrent.atomic.AtomicReference<TouchHandoffDiagnostic?>()
    @Volatile private var editorHidden = false
    private var edge: SpectrumSurface? = null
    private val edgeWindows=ArrayList<SpectrumSurface>()
    private var edgeState: AssistantVisualState = AssistantVisualState.RUNNING
    private val edgeTick=object:Runnable {
        override fun run() {
            if(edgeWindows.isEmpty() || captureHidden || editorHidden) return
            val time=android.os.SystemClock.uptimeMillis()
            edgeWindows.forEach {it.perimeterFrameTime=time;it.invalidate()}
            handler.postDelayed(this,33)
        }
    }
    private var edgeManager: WindowManager? = null
    private var displayedTitle: String? = null
    private var displayedVisual: AssistantVisualState? = null
    private val notice = CompanionNotice()
    private var lastDisplayedRun: JSONObject? = null
    private var lastWorkerState = ""
    private var scheduledNotice = ""
    private val collapseNotice = Runnable { display(lastDisplayedRun, lastWorkerState) }
    private var dockAnimation: ValueAnimator? = null
    private var rightEdge = prefs.getBoolean("companion_right_edge", true)
    private var voiceId: Long? = null
    private var dragging = false
    private var touching = false
    private var downX = 0f
    private var downY = 0f
    private var initialX = 0
    private var initialY = 0
    private var desiredWidth = dp(58)
    private var desiredHeight = dp(58)
    private val params = WindowManager.LayoutParams(dp(58), dp(58), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        y = prefs.getInt("companion_y", dp(180))
    }
    private val icon = ImageView(context).apply {
        setImageResource(UiIcons.brand); imageTintList = ColorStateList.valueOf(UiTheme.ink)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val label = UiTheme.text(context, "", 12f, UiTheme.ink, true).apply {
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        visibility = View.GONE
    }
    private val taskProgress = TaskProgressView(context, true)
    private val textStack = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(label); addView(taskProgress, LinearLayout.LayoutParams(-1, -2))
    }
    private val root = SpectrumSurface(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(17), dp(12), dp(17), dp(12))
        // Concave shoulders fit within the usual 58 dp touch window.
        background = EdgeDockDrawable(resources.displayMetrics.density)
        elevation = 0f
        contentDescription = "Doppel 输入任务"; tooltipText = "点击输入，长按说话"
        isClickable = true; isFocusable = true
        addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(textStack, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        setOnClickListener { openComposer() }
    }
    private val hold = Runnable {
        if (touching && !dragging) {
            pause()
            root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val id = VoiceGestureSession.begin(context)
            voiceId = id
            try {
                context.startActivity(Intent(context, VoiceActivity::class.java)
                    .putExtra(VoiceGestureSession.EXTRA_SESSION_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            } catch (_: Exception) { VoiceGestureSession.cancel(id); voiceId = null; openComposer() }
        }
    }

    init {
        UiTheme.bind(root) {
            root.background.invalidateSelf()
            icon.imageTintList = ColorStateList.valueOf(UiTheme.ink)
        }
        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dockAnimation?.cancel(); touching = true; dragging = false; voiceId = null
                    downX = event.rawX; downY = event.rawY; initialX = params.x; initialY = params.y
                    if (!captureHidden && !editorHidden) root.alpha = 1f
                    handler.postDelayed(hold, 400)
                }
                MotionEvent.ACTION_MOVE -> {
                    val id = voiceId
                    if (id != null) {
                        if (HorizontalHoldCancel.crossed(downX, event.rawX, context.resources.displayMetrics.widthPixels.toFloat(),
                            dp(72).toFloat(), ViewConfiguration.get(context).scaledTouchSlop.toFloat())) {
                            VoiceGestureSession.cancel(id, (event.rawX - downX).compareTo(0f))
                        }
                    } else {
                        val moved = kotlin.math.abs(event.rawX - downX) + kotlin.math.abs(event.rawY - downY)
                        if (moved > ViewConfiguration.get(context).scaledTouchSlop) { dragging = true; handler.removeCallbacks(hold) }
                        if (dragging) {
                            params.x = (initialX + event.rawX - downX).toInt().coerceIn(0, maxX())
                            params.y = (initialY + event.rawY - downY).toInt().coerceIn(dp(32), maxY())
                            updateLayout()
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    touching = false; handler.removeCallbacks(hold)
                    val id = voiceId
                    if (id != null) { VoiceGestureSession.finish(id); voiceId = null }
                    else if (!dragging) root.performClick()
                    if (dragging) rightEdge = params.x + params.width / 2 > context.resources.displayMetrics.widthPixels / 2
                    dock()
                }
                MotionEvent.ACTION_CANCEL -> {
                    touching = false; handler.removeCallbacks(hold)
                    voiceId?.let { VoiceGestureSession.cancel(it) }; voiceId = null; dock()
                }
            }
            true
        }
    }

    fun show() {
        if (!Settings.canDrawOverlays(context) || attached) return
        params.x = if (rightEdge) maxX() else 0
        params.y = params.y.coerceIn(dp(32), maxY())
        updateDockShape()
        try { manager.addView(root, params); attached = true } catch (_: Exception) { attached = false }
    }

    fun display(run: JSONObject?, workerState: String) {
        handler.post {
            if (!attached) return@post
            lastDisplayedRun = run; lastWorkerState = workerState
            val displayedRun = run?.let { PauseDetails.resolve(context, it, DeviceWorkerService.instance?.isPaused == true) }
            val state = displayedRun?.optString("status").orEmpty()
            val pauseInfo = displayedRun?.let(PausePresentation::from)
            val noticeStatus = if (workerState in setOf("正在创建任务", "创建未确认，草稿已保留")) "" else state
            val collapsed = notice.collapsed(run?.optString("id").orEmpty(), noticeStatus, android.os.SystemClock.uptimeMillis())
            val visual = if (collapsed) AssistantVisualState.IDLE else AssistantVisualState.resolve(state, workerState, DeviceWorkerService.instance?.isPaused ?: (workerState == "已暂停"))
            val noticeKey = if (TaskPresentation.terminal(noticeStatus)) "${run?.optString("id")}:$noticeStatus" else ""
            if (noticeKey != scheduledNotice) {
                handler.removeCallbacks(collapseNotice); scheduledNotice = noticeKey
                if (noticeKey.isNotBlank() && !collapsed) handler.postDelayed(collapseNotice, 4000)
            }
            root.state = visual
            edgeState = visual
            // Keep the perimeter alive for both active phases. Its colour and
            // motion are driven by the same visual state as the floating entry.
            updateEdge(visual == AssistantVisualState.RUNNING || visual == AssistantVisualState.THINKING)
            val stateTitle = if (collapsed) "" else when (workerState) {
                "正在创建任务" -> "正在创建任务"
                "创建未确认，草稿已保留" -> "创建未确认"
                else -> when {
                    state == "cancelled" -> "已停止"
                    visual == AssistantVisualState.PAUSED -> pauseInfo?.compact.orEmpty()
                    visual == AssistantVisualState.THINKING -> "思考中"
                    visual == AssistantVisualState.RUNNING -> (if (workerState.startsWith("正在")) workerState else TaskPresentation.message(run?.optString("message").orEmpty()))
                        .lineSequence().firstOrNull().orEmpty().ifBlank { "执行中" }.let { if (it.length > 15) it.take(14) + "…" else it }
                    else -> visual.label
                }
            }
            val title = if (collapsed || workerState in setOf("正在创建任务", "创建未确认，草稿已保留")) stateTitle
                else TaskPresentation.withSourceLabel(run, stateTitle)
            val entry = if (prefs.getString("active_run", "").isNullOrBlank()) "输入任务" else "当前任务"
            root.contentDescription = if (title.isEmpty()) "Doppel $entry" else "Doppel $entry，$title" + (pauseInfo?.let { "，${it.reason}" } ?: "")
            root.tooltipText = pauseInfo?.reason ?: if (entry == "当前任务") "查看进度与任务控制" else "点击输入，长按说话"
            val showProgress = !collapsed && run != null && workerState !in setOf("正在创建任务", "创建未确认，草稿已保留")
            taskProgress.display(if (showProgress) run else null, DeviceWorkerService.instance?.isPaused == true)
            val stateChanged = displayedTitle != title || displayedVisual != visual
            if (stateChanged) { label.text = title; label.visibility = if (title.isEmpty()) View.GONE else View.VISIBLE }
            desiredWidth = dp(if (title.isEmpty() && !showProgress) 58 else if (showProgress) 254 else 196)
                .coerceAtMost((context.resources.displayMetrics.widthPixels - dp(12)).coerceAtLeast(dp(58)))
            // Two Chinese text lines and the bar need more than the previous 34 dp
            // content height. Keep the usual 58 dp window, growing only for larger fonts.
            val verticalPadding = dp(if (showProgress) 8 else 12)
            root.setPadding(dp(17), verticalPadding, dp(17), verticalPadding)
            textStack.measure(View.MeasureSpec.makeMeasureSpec((desiredWidth - dp(68)).coerceAtLeast(0), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            desiredHeight = maxOf(dp(58), textStack.measuredHeight + verticalPadding * 2)
            if (!touching && (params.width != desiredWidth || params.height != desiredHeight)) dock(false)
            if (!stateChanged) return@post
            displayedTitle = title; displayedVisual = visual
            icon.setImageResource(when (visual) {
                AssistantVisualState.PAUSED -> UiIcons.pause
                AssistantVisualState.WAITING -> UiIcons.lock
                AssistantVisualState.THINKING -> UiIcons.sparkles
                else -> UiIcons.brand
            })
            icon.imageTintList = ColorStateList.valueOf(UiTheme.ink)
        }
    }

    private fun updateEdge(running: Boolean) {
        if (!running) {
            handler.removeCallbacks(edgeTick)
            edgeWindows.toList().forEach {view ->
                try {edgeManager?.removeViewImmediate(view)} catch (_:Exception) {}
                if(!view.isAttachedToWindow) edgeWindows.remove(view)
            }
            edge=edgeWindows.firstOrNull();if(edgeWindows.isEmpty()) edgeManager=null
            return
        }
        if (edgeWindows.size==4 && edgeWindows.all {it.isAttachedToWindow}) {
            edgeWindows.forEach { it.state = edgeState }
            return
        }
        updateEdge(false)
        if(edgeWindows.isNotEmpty()) return
        // Trusted accessibility overlays do not obscure touches in the application below.
        val service = DoppelAccessibilityService.instance ?: return
        val windowManager = service.getSystemService(WindowManager::class.java)
        val display=android.graphics.Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(display)
        edgeManager=windowManager
        val time=android.os.SystemClock.uptimeMillis()
        try {
            FeedbackMotion.edgeRegions(display.x,display.y,service.resources.displayMetrics.density).forEach {rect ->
                val view=SpectrumSurface(service,true).apply {
                    perimeterViewport=android.graphics.Rect(rect[0],rect[1],rect[2],rect[3])
                    perimeterDisplayWidth=display.x;perimeterDisplayHeight=display.y;perimeterFrameTime=time
                    state=edgeState
                    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    isClickable=false;isFocusable=false
                    visibility=if(captureHidden || editorHidden) View.INVISIBLE else View.VISIBLE
                }
                val layout=WindowManager.LayoutParams(rect[2]-rect[0],rect[3]-rect[1],WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT).apply {
                    gravity=Gravity.TOP or Gravity.LEFT;x=rect[0];y=rect[1]
                    alpha=if(captureHidden || editorHidden) 0f else 1f
                    if(android.os.Build.VERSION.SDK_INT>=30) {
                        setFitInsetsTypes(0)
                        layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else if(android.os.Build.VERSION.SDK_INT>=28) {
                        layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                windowManager.addView(view,layout);edgeWindows.add(view)
            }
            edge=edgeWindows.firstOrNull();handler.removeCallbacks(edgeTick);handler.post(edgeTick)
        } catch (_:Exception) {updateEdge(false)}
    }

    fun hideForScreenshot(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return false
        val ready = CountDownLatch(1)
        handler.post {
            // Retain the entry's touch region while omitting all of its rendered pixels.
            captureHidden = true
            if (!applyVisibility()) return@post
            // Window alpha is applied by the compositor even when this app's
            // software Canvas / Choreographer is not producing visible frames.
            handler.postDelayed({ ready.countDown() }, 80)
        }
        return ready.await(1, TimeUnit.SECONDS)
    }

    fun restoreAfterScreenshot() {
        handler.post {
            captureHidden = false
            if (attached) applyVisibility()
        }
    }

    /** Called off the UI thread. The input window must yield before a host gesture is submitted. */
    internal fun beginGestureTouchPass(generation: Long, durationMs: Long, isCurrent: () -> Boolean): AutoCloseable? {
        val diagnostic=TouchHandoffDiagnostic(android.os.SystemClock::elapsedRealtime)
        touchPassDiagnostic.set(diagnostic)
        fun finish(outcome:String) {
            diagnostic.finish(outcome);android.util.Log.i("DoppelTouchPass",diagnostic.snapshot().toString())
            if(outcome=="timeout") android.util.Log.w("DoppelTouchPassMain",Looper.getMainLooper().thread.stackTrace.take(12).joinToString("\n"))
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {finish("main_thread");return null}
        val gate = TouchHandoffGate()
        val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
        val ticket = java.util.concurrent.atomic.AtomicReference<CompanionTouchPassState.Ticket?>()
        val traversal = java.util.concurrent.atomic.AtomicReference<android.view.ViewTreeObserver.OnPreDrawListener?>()
        fun removeTraversalListener() {
            traversal.getAndSet(null)?.let { listener ->
                root.viewTreeObserver.takeIf {it.isAlive}?.removeOnPreDrawListener(listener)
            }
        }
        fun release(owner: CompanionTouchPassState.Ticket) {
            removeTraversalListener()
            if (gestureTouchPass.release(owner)) {
                applyVisibility()
                // If a WindowManager update fails, remove/recreate rather than leave a dead entry.
                if (!updateLayout()) { close(); show() }
                diagnostic.mark("released")
            }
        }
        val queued=handler.post {
            diagnostic.mark("main_entered")
            fun reject(outcome:String) {diagnostic.finish(outcome);gate.reject()}
            if (abandoned.get() || !isCurrent()) {reject("stale_host");return@post}
            diagnostic.mark("host_checked")
            if(touching) {reject("user_touch");return@post}
            if(voiceId!=null) {reject("voice_input");return@post}
            if(editorHidden) {reject("editor_visible");return@post}
            val owner = gestureTouchPass.acquire(generation, generation) ?: run {reject("ticket_busy");return@post}
            ticket.set(owner)
            diagnostic.mark("ticket_acquired")
            dockAnimation?.cancel()
            fun acknowledge() {
                removeTraversalListener()
                if(abandoned.get() || !isCurrent() || !gestureTouchPass.owns(owner)) {
                    reject("ack_expired");release(owner)
                } else {diagnostic.mark("main_acknowledged");gate.applied()}
            }
            if(attached && root.isAttachedToWindow) {
                val listener=android.view.ViewTreeObserver.OnPreDrawListener {
                    // ViewRoot has performed the pending relayout before pre-draw.
                    // Signal before software drawing; LayoutParams mutation alone
                    // does not prove the input window flags reached WindowManager.
                    diagnostic.mark("window_traversed");acknowledge();true
                }
                traversal.set(listener);root.viewTreeObserver.addOnPreDrawListener(listener)
            }
            diagnostic.mark("layout_started")
            applyVisibility()
            if (!updateLayout()) {diagnostic.finish("layout_failed");release(owner);gate.reject();return@post}
            diagnostic.mark("layout_applied")
            // Covers interruption before the caller can enter its finally block.
            handler.postDelayed({ release(owner) }, durationMs.coerceIn(3000, 6000))
            // No attached input surface requires a traversal acknowledgement.
            if(traversal.get()==null) {diagnostic.mark("window_absent");acknowledge()}
            // The 80 ms compositor allowance after acknowledgement is paid by the
            // background caller, never queued behind another software draw.
        }
        if(!queued) {finish("queue_rejected");return null}
        fun stillCurrent()= !abandoned.get() && isCurrent() && ticket.get()?.let(gestureTouchPass::owns)!=false
        val acquired = try {gate.await(::stillCurrent) {diagnostic.mark("compositor_wait_started")}}
            catch (_: InterruptedException) {diagnostic.finish("interrupted");Thread.currentThread().interrupt();TouchHandoffGate.Result.CANCELLED}
        if (acquired!=TouchHandoffGate.Result.READY || !stillCurrent()) {
            abandoned.set(true)
            finish(if(acquired==TouchHandoffGate.Result.TIMED_OUT) "timeout" else "stale_host")
            ticket.get()?.let { owner -> diagnostic.mark("release_queued");handler.post { release(owner) } }
            return null
        }
        diagnostic.mark("compositor_wait_finished");diagnostic.mark("ready")
        finish("ready")
        return AutoCloseable {
            abandoned.set(true)
            ticket.get()?.let { owner -> diagnostic.mark("release_queued");handler.post { release(owner) } }
        }
    }

    internal fun gestureTouchPassDiagnostic(): JSONObject = touchPassDiagnostic.get()?.snapshot() ?: JSONObject()

    internal fun stopGestureTouchPass(invalidatedGeneration: Long) {
        handler.post {
            if (!gestureTouchPass.clearBefore(invalidatedGeneration)) return@post
            applyVisibility()
            if (!updateLayout()) { close(); show() }
        }
    }

    internal fun setEditorVisible(visible: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { setEditorVisible(visible) }; return }
        if (editorHidden == visible) return
        editorHidden = visible
        applyVisibility(); updateLayout()
    }

    private fun applyVisibility(): Boolean {
        // Screenshot capture only removes rendered pixels; host injection also yields input.
        root.visibility = if (editorHidden) View.INVISIBLE else View.VISIBLE
        root.alpha = if (captureHidden) 0f else 1f
        params.flags = if (editorHidden || gestureTouchPass.passing) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        // Android 12+ blocks touches through opaque, untrusted APPLICATION_OVERLAY windows.
        // Window alpha (not View alpha) makes the temporary non-touchable window transparent.
        params.alpha = if (gestureTouchPass.passing) 0f else 1f
        if (captureHidden || editorHidden) params.alpha = 0f
        var applied = !attached || updateLayout()
        edgeWindows.forEach { view ->
            view.visibility=if(captureHidden || editorHidden) View.INVISIBLE else View.VISIBLE
            val layout = view.layoutParams as? WindowManager.LayoutParams
            if (layout != null) {
                layout.alpha = if (captureHidden || editorHidden) 0f else 1f
                try { edgeManager?.updateViewLayout(view, layout) } catch (_: Exception) { applied = false }
            }
        }
        handler.removeCallbacks(edgeTick)
        if(!captureHidden && !editorHidden && edgeWindows.isNotEmpty()) handler.post(edgeTick)
        root.invalidate()
        return applied
    }

    fun onConfigurationChanged() { handler.post {
        if (attached) dock(false)
        if(edgeWindows.isNotEmpty()) {updateEdge(false);updateEdge(true)}
    } }

    private fun openComposer() {
        if (DeviceWorkerService.instance?.revealPauseControls() == true) return
        if (lastDisplayedRun?.optString("status") != "paused" && DeviceWorkerService.instance?.isPaused != true) pause()
        val id = prefs.getString("active_run", "").orEmpty()
        val intent = if (id.isNotBlank()) Intent(context, TaskPanelActivity::class.java).putExtra("run_id", id)
            else Intent(context, VoiceActivity::class.java).putExtra(VoiceActivity.EXTRA_OPEN_KEYBOARD, true)
        context.startActivity(intent
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun dock(animated: Boolean = true) {
        if (!attached) return
        params.width = desiredWidth
        params.height = desiredHeight
        params.y = params.y.coerceIn(dp(32), maxY())
        val target = if (rightEdge) maxX() else 0
        prefs.edit().putBoolean("companion_right_edge", rightEdge).putInt("companion_y", params.y).apply()
        dockAnimation?.cancel()
        if (animated && ValueAnimator.areAnimatorsEnabled()) {
            dockAnimation = ValueAnimator.ofInt(params.x, target).apply {
                duration = 220; interpolator = DecelerateInterpolator()
                addUpdateListener { params.x = it.animatedValue as Int; updateLayout() }; start()
            }
        } else { params.x = target; updateLayout() }
    }

    private fun updateLayout(): Boolean {
        if (!attached) return true
        updateDockShape()
        return try { manager.updateViewLayout(root, params); true } catch (_: Exception) { false }
    }
    private fun updateDockShape() {
        val screenWidth = context.resources.displayMetrics.widthPixels.toFloat()
        val onRight = if (touching && dragging) params.x + params.width / 2f > screenWidth / 2f else rightEdge
        (root.background as? EdgeDockDrawable)?.setAttachment(onRight,
            EdgeDockGeometry.attachment(params.x.toFloat(), params.width.toFloat(), screenWidth, onRight, dp(24).toFloat()))
    }
    private fun dp(value: Int) = UiTheme.dp(context, value)
    fun bounds(): android.graphics.Rect? {
        if (!attached || editorHidden) return null
        val position = IntArray(2)
        root.getLocationOnScreen(position)
        return android.graphics.Rect(position[0], position[1], position[0] + root.width, position[1] + root.height)
    }
    private fun maxX() = (context.resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0)
    private fun maxY() = (context.resources.displayMetrics.heightPixels - params.height - dp(40)).coerceAtLeast(dp(32))
    fun close() {
        gestureTouchPass.clear(); applyVisibility()
        handler.removeCallbacksAndMessages(null); dockAnimation?.cancel()
        updateEdge(false)
        voiceId?.let { VoiceGestureSession.cancel(it) }; voiceId = null
        if (attached) try { manager.removeView(root) } catch (_: Exception) {}
        attached = false
    }
}

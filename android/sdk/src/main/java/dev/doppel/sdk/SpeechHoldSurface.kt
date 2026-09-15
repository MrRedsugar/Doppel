package dev.doppel.sdk

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Presentation only. The host owns the hold gesture, haptics, recognition and task submission. */
internal class SpeechHoldSurface(context: Context) : FrameLayout(context) {
    private val clock = Handler(Looper.getMainLooper())
    private val power = context.getSystemService(PowerManager::class.java)
    private val cancelWash = View(context).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(255, 224, 237), Color.rgb(255, 244, 248), Color.rgb(250, 218, 235))).apply {
            cornerRadius = dp(28).toFloat()
            setStroke(dp(1).coerceAtLeast(1), Color.rgb(225, 128, 162))
        }
        alpha = 0f
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val statusText = UiTheme.text(context, "正在听", 13f, UiTheme.muted, true).apply {
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val transcriptText = UiTheme.text(context, "", 20f, UiTheme.ink, true).apply {
        maxLines = 4; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL
        setHorizontallyScrolling(false)
        setLineSpacing(dp(4).toFloat(), 1f)
        tag = "speech_hold_transcript"
    }
    private val signalFrame = FrameLayout(context)
    private val waveform = UiActivitySignal(context, UiActivitySignal.Form.VOICE)
    private val processing = UiActivitySignal(context, UiActivitySignal.Form.ORBIT).apply {
        active = false; visibility = INVISIBLE
    }
    private var cancelling = false
    private var cancelCompletion: (() -> Unit)? = null
    private val finishCancellation = Runnable {
        val completion = cancelCompletion
        cancelCompletion = null
        completion?.invoke()
    }

    var transcript: String = ""
        set(value) {
            field = value
            if (!cancelling) transcriptText.text = value
        }
    var status: String = "正在听"
        set(value) {
            field = value
            if (!cancelling) statusText.text = value
        }

    init {
        background = UiTheme.glass(context, 28)
        clipToOutline = true
        isClickable = false; isFocusable = false
        addView(cancelWash, LayoutParams(-1, -1))
        addView(body, LayoutParams(-1, -1))
        body.addView(statusText, LinearLayout.LayoutParams(-1, dp(24)))
        body.addView(transcriptText, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(signalFrame, LinearLayout.LayoutParams(-1, dp(32)))
        signalFrame.addView(waveform, LayoutParams(-1, -1))
        signalFrame.addView(processing, LayoutParams(dp(24), dp(24), Gravity.CENTER))
    }

    fun setListening() {
        stopExit()
        cancelling = false
        alpha = 1f; scaleX = 1f; scaleY = 1f; translationX = 0f
        cancelWash.alpha = 0f
        statusText.setTextColor(UiTheme.muted); transcriptText.setTextColor(UiTheme.ink)
        transcriptText.text = transcript
        status = "正在听"
        waveform.visibility = VISIBLE; waveform.active = true
        processing.visibility = INVISIBLE; processing.active = false
    }

    fun setProcessing() {
        if (cancelling) return
        status = "正在识别"
        waveform.visibility = INVISIBLE; waveform.active = false
        processing.visibility = VISIBLE; processing.active = true
    }

    /** direction: negative = left, positive = right, zero = centered dismissal. Main thread only. */
    fun animateCancel(direction: Int, onEnd: () -> Unit) {
        if (cancelling) return
        status = "已取消"
        cancelling = true
        cancelCompletion = onEnd
        statusText.setTextColor(UiTheme.danger)
        transcriptText.setTextColor(Color.rgb(137, 75, 100))
        waveform.active = false; processing.active = false
        waveform.visibility = INVISIBLE; processing.visibility = INVISIBLE
        cancelWash.alpha = 1f
        val reducedMotion = !ValueAnimator.areAnimatorsEnabled() || power?.isPowerSaveMode == true || power?.isInteractive == false
        if (reducedMotion || !isAttachedToWindow) {
            clock.postDelayed(finishCancellation, 180)
            return
        }
        val side = direction.compareTo(0)
        val travel = maxOf(width * 0.72f, dp(140).toFloat()) * side
        pivotX = width / 2f; pivotY = height / 2f
        animate().setStartDelay(140).setDuration(260).setInterpolator(AccelerateInterpolator(1.1f))
            .scaleX(0.72f).scaleY(0.82f).translationX(travel).alpha(0f)
            .withEndAction { finishCancellation.run() }.start()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val desiredHeight = dp(if (landscape) 164 else 224)
        val measuredHeight = resolveSize(desiredHeight, heightMeasureSpec)
        val measuredWidth = resolveSize(dp(480), widthMeasureSpec)
        val contentHeight = (measuredHeight - paddingTop - paddingBottom).coerceAtLeast(0)
        val contentWidth = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0)
        val compact = contentHeight < dp(190) || contentWidth < dp(300)
        val verticalPadding = dp(if (compact) 14 else 20)
        val horizontalPadding = dp(if (compact) 16 else 24)
        body.setPaddingRelative(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        val statusHeight = maxOf(dp(24), statusText.lineHeight)
        val signalHeight = dp(if (compact) 28 else 32)
        signalFrame.visibility = if (contentHeight < dp(128)) GONE else VISIBLE
        (statusText.layoutParams as LinearLayout.LayoutParams).height = statusHeight
        (signalFrame.layoutParams as LinearLayout.LayoutParams).height = signalHeight
        val availableTextHeight = contentHeight - verticalPadding * 2 - statusHeight - (if (signalFrame.visibility == GONE) 0 else signalHeight)
        transcriptText.maxLines = (availableTextHeight / transcriptText.lineHeight.coerceAtLeast(1)).coerceIn(1, 4)
        super.onMeasure(MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY))
    }

    private fun stopExit() {
        cancelCompletion = null
        clock.removeCallbacks(finishCancellation)
        animate().withEndAction(null).cancel()
    }

    override fun onDetachedFromWindow() {
        stopExit()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int) = UiTheme.dp(context, value)
}

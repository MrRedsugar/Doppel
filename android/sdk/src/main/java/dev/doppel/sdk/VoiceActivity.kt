package dev.doppel.sdk

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONObject
import java.util.concurrent.Executors

class VoiceActivity : Activity() {
    companion object {
        const val EXTRA_OPEN_KEYBOARD = "open_keyboard"
        private val taskCreation = TaskSubmissionGate.creating
        private var resumedVoice = java.lang.ref.WeakReference<VoiceActivity>(null)
        internal val keyboardVisible: Boolean
            get() = resumedVoice.get()?.let { it.visible && !it.holdMode && !it.isFinishing && !it.isDestroyed } == true
        @Volatile var isVisible = false
            private set
    }
    private var text: EditText? = null
    private var status: TextView? = null
    private var confirm: Button? = null
    private var listeningSignal: UiActivitySignal? = null
    private var holdSurface: SpeechHoldSurface? = null
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var gateway: Gateway
    private lateinit var capture: SpeechCapture
    private var visible = false
    private var listening = false
    private var gesture: VoiceGestureSession.Session? = null
    private var gestureAttached = false
    private var holdMode = false
    private var closingHold = false
    private var entryRevision = 0L
    private var keyboardDraft = ""
    private var heldTranscript = ""
    private var microphonePermissionPending = false
    private var micHeld = false
    private var micStartX = 0f
    private var micWindowLeft = 0f
    private var micWindowWidth = 0f
    private var submitted = false
    private var restoringWorker = false
    private var keyboardPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gateway = Gateway(this)
        capture = SpeechCapture(this, object : SpeechCapture.Listener {
            override fun onState(message: String) { if (visible && !closingHold) showStatus(message) }
            override fun onText(value: String, final: Boolean) {
                if (!visible || closingHold || isFinishing) return
                if (holdMode) {
                    if (value.isNotBlank()) { heldTranscript = value; holdSurface?.transcript = value }
                    if (final) {
                        listening = false
                        val session = gesture ?: return
                        session.state.finalText(value)
                        if (session.state.phase == VoiceHoldState.Phase.HOLDING) {
                            holdSurface?.setProcessing(); showStatus("已识别")
                        }
                        handleGestureChange()
                    }
                } else {
                    if (value.isNotBlank()) { keyboardDraft = value; text?.setText(value) }
                    if (final) {
                        listening = false; listeningSignal?.active = false
                        showStatus(if (value.isBlank()) "未听清，请重试或输入文字" else "确认任务")
                    }
                }
            }
            override fun onFailure(message: String) {
                if (!visible || closingHold) return
                if (holdMode) finishHoldWithStatus(message)
                else { detachGesture(); stopRecognition(true); showStatus(message) }
            }
        })
        if (!FirstUseConsent.allowEntry(this)) return
        keyboardDraft = savedInstanceState?.getString("voice_text") ?: intent.getStringExtra("initial_text")
            ?: gateway.prefs.getString("draft_goal", "").orEmpty()
        heldTranscript = savedInstanceState?.getString("hold_transcript").orEmpty()
        val gestureId = intent.getLongExtra(VoiceGestureSession.EXTRA_SESSION_ID, 0)
        gesture = VoiceGestureSession.find(gestureId)
        holdMode = gesture?.state?.phase == VoiceHoldState.Phase.HOLDING || savedInstanceState?.getBoolean("hold_surface") == true
        keyboardPending = !holdMode && gestureId == 0L && (savedInstanceState?.getBoolean("keyboard_pending")
            ?: intent.getBooleanExtra(EXTRA_OPEN_KEYBOARD, false))
        if (!holdMode) detachGesture()
        renderSurface()
        UiTheme.styleSheet(window)
        configureWindow()
        if (!holdMode && gestureId != 0L) showStatus("语音手势已结束，请重新按住或输入文字")
    }

    private fun renderSurface() {
        text = null; status = null; confirm = null; listeningSignal = null; holdSurface = null
        if (holdMode) {
            val surface = SpeechHoldSurface(this).apply { transcript = heldTranscript; setListening(); status = "正在开启麦克风" }
            holdSurface = surface
            UiTheme.window(this, surface); setContentView(surface)
            if (gesture?.state?.phase == VoiceHoldState.Phase.HOLDING) window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        } else {
            renderEditor()
        }
        configureWindow()
        DeviceWorkerService.instance?.voiceEditorVisibilityChanged()
    }

    private fun configureWindow() {
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    }

    private fun renderEditor() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = UiTheme.glass(this@VoiceActivity, 24); clipToOutline = true }
        UiTheme.window(this, root)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(10), dp(24), dp(24)) }
        root.addView(ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(layout) })
        setContentView(root)
        layout.addView(android.view.View(this).apply {
            background = UiTheme.surface(this@VoiceActivity, UiTheme.line)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(32), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) })
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(UiIcons.sparkles); imageTintList = android.content.res.ColorStateList.valueOf(UiTheme.spectrumBlue) }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })
        header.addView(UiTheme.text(this, if (gateway.selectedConversationRun().isNullOrBlank()) "Doppel" else "继续说", 18f, UiTheme.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (!gateway.prefs.getString("active_run", "").isNullOrBlank()) {
            header.addView(UiTheme.icon(this, UiIcons.history, "当前任务") { openCurrentTask(keyboardValue()) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        header.addView(UiTheme.icon(this, UiIcons.close, "关闭") { finishVoice() }, LinearLayout.LayoutParams(dp(44), dp(44))); layout.addView(header)
        status = UiTheme.text(this, if (gateway.prefs.getString("active_run", "").isNullOrBlank()) "说出你的想法，或直接输入" else "新任务将加入队列，当前任务继续执行", 13f, UiTheme.muted).apply { setPadding(0, dp(2), 0, dp(16)) }.also { layout.addView(it) }
        val editor = UiTheme.field(this, "输入任务").apply {
            val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            minLines = if (landscape) 1 else 3; maxLines = if (landscape) 3 else 6
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP; textSize = 18f
            setBackgroundColor(Color.TRANSPARENT); setPadding(0, dp(8), 0, dp(12))
            setLineSpacing(dp(4).toFloat(), 1f)
            setText(keyboardDraft); setSelection(length())
        }
        text = editor; layout.addView(editor, LinearLayout.LayoutParams(-1, -2))
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, dp(18)) }
        val mic = UiTheme.icon(this, UiIcons.microphone, "按住说话", true) {}
        tools.addView(mic, LinearLayout.LayoutParams(dp(52), dp(52)))
        listeningSignal = UiActivitySignal(this, UiActivitySignal.Form.VOICE).apply { active = false }.also {
            tools.addView(it, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(14); marginEnd = dp(14) })
        }
        layout.addView(tools)
        mic.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    detachGesture(); micHeld = true
                    val position = IntArray(2); window.decorView.getLocationOnScreen(position)
                    micWindowLeft = position[0].toFloat(); micWindowWidth = window.decorView.width.toFloat()
                    micStartX = event.rawX - micWindowLeft
                    mic.parent.requestDisallowInterceptTouchEvent(true)
                    startRecognition()
                }
                MotionEvent.ACTION_MOVE -> if (micHeld && HorizontalHoldCancel.crossed(micStartX, event.rawX - micWindowLeft,
                    micWindowWidth, dp(72).toFloat(), android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat())) {
                    micHeld = false; stopRecognition(true); showStatus("已取消")
                }
                MotionEvent.ACTION_UP -> {
                    if (micHeld && listening) stopRecognition(false)
                    micHeld = false; mic.parent.requestDisallowInterceptTouchEvent(false)
                }
                MotionEvent.ACTION_CANCEL -> {
                    micHeld = false; stopRecognition(true); showStatus("已取消")
                    mic.parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            true
        }
        confirm = UiTheme.command(this, "开始任务", true) { submitTask() }.apply { isEnabled = !taskCreation.get() }
            .also { layout.addView(it, LinearLayout.LayoutParams(-1, dp(50))) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val editor = text ?: return
        if (!hasFocus || holdMode || !keyboardPending) return
        editor.requestFocus()
        val revision = entryRevision
        editor.post {
            if (visible && !holdMode && editor === text && editor.hasWindowFocus() && keyboardPending && !isFinishing && revision == entryRevision) {
                keyboardPending = false
                getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun keyboardValue(): String = text?.text?.toString() ?: keyboardDraft
    private fun showStatus(message: String) { status?.text = message; holdSurface?.status = message }
    private fun openCurrentTask(value: String) {
        stopRecognition(true); detachGesture()
        if (value.isNotBlank()) gateway.prefs.edit().putString("draft_goal", value).apply()
        startActivity(Intent(this, TaskPanelActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finishVoice()
    }
    private fun finishVoice() {
        if (isTaskRoot) finishAndRemoveTask() else finish()
        DeviceWorkerService.instance?.voiceEditorVisibilityChanged()
    }

    private fun submitHold(value: String) {
        if (!FirstUseConsent.allowEntry(this)) return
        if (submitted || closingHold || !visible || isFinishing) return
        try { gateway.prepareUserConnection() }
        catch (error: Exception) { failHoldSubmission(value, ConversationIntent.failureMessage(error) + "，语音已保留为草稿"); return }
        if (DoppelAccessibilityService.instance == null) {
            failHoldSubmission(value, "请先启用无障碍服务，语音已保留为草稿"); return
        }
        if (!taskCreation.compareAndSet(false, true)) {
            failHoldSubmission(value, "已有任务正在创建，语音已保留为草稿"); return
        }
        submitted = true
        try {
            check(gateway.prefs.edit().putString("draft_goal", value).commit())
            checkNotNull(startForegroundService(Intent(this, DeviceWorkerService::class.java)
                .setAction(DeviceWorkerService.SUBMIT_VOICE).putExtra("goal", value)
                .putExtra(TaskControl.EXTRA_GENERATION, TaskControl.currentGeneration())))
            finishVoice()
        } catch (_: Exception) {
            taskCreation.set(false); submitted = false
            failHoldSubmission(value, "任务未启动，语音已保留为草稿")
        }
    }

    private fun failHoldSubmission(value: String, message: String) {
        val saved = gateway.prefs.edit().putString("draft_goal", value).commit()
        Toast.makeText(this, if (saved) message else "任务未启动，草稿保存失败", Toast.LENGTH_LONG).show()
        finishVoice()
    }

    private fun submitTask() {
        if (!FirstUseConsent.allowEntry(this)) return
        if (submitted || taskCreation.get()) return
        val value = keyboardValue().trim()
        if (value.isBlank()) { showStatus("请说出或输入任务"); return }
        val device = try { gateway.prepareUserConnection() } catch (error: Exception) {
            gateway.prefs.edit().putString("draft_goal", value).apply()
            showStatus(ConversationIntent.failureMessage(error)); return
        }
        if (DoppelAccessibilityService.instance == null) { showStatus("请先启用无障碍服务"); return }
        if (!taskCreation.compareAndSet(false, true)) return
        val creationGeneration = TaskControl.currentGeneration()
        val connection = gateway.captureReviewConnection()
        submitted = true
        gateway.prefs.edit().putString("draft_goal", value).commit()
        stopRecognition(true); detachGesture(); confirm?.isEnabled = false; showStatus("正在创建任务")
        val mode = listOf("ask", "assist", "full")[gateway.prefs.getInt("mode_index", 1).coerceIn(0, 2)]
        io.execute {
            try {
                val run = gateway.createConversationRun(JSONObject().put("device_id", device).put("goal", value).put("mode", mode), connection)
                TaskControl.reconcileCreatedRun(this, run, creationGeneration, connection) { reconciled, error ->
                    taskCreation.set(false)
                    if (connection.scope != gateway.reviewScope()) return@reconcileCreatedRun
                    if (reconciled != null) {
                        if (gateway.prefs.getString("draft_goal", "").orEmpty().trim() == value) gateway.prefs.edit().remove("draft_goal").commit()
                        runCatching { TaskControl.wakeQueue(this) }
                        resumedVoice.get()?.takeIf { it.keyboardValue().trim() == value }?.let { screen ->
                            Toast.makeText(screen, if (reconciled.optString("status") == "queued") TaskPresentation.queueLabel(reconciled) else "任务已创建", Toast.LENGTH_SHORT).show()
                            screen.finishVoice()
                        }
                    } else resumedVoice.get()?.let { screen ->
                        screen.submitted = false; screen.showStatus(error ?: "创建未确认，草稿已保留"); screen.confirm?.isEnabled = true
                    }
                }

            } catch (_: Exception) { taskCreation.set(false); runOnUiThread {
                if (connection.scope != gateway.reviewScope()) return@runOnUiThread
                resumedVoice.get()?.takeIf { !it.holdMode }?.let {
                    it.submitted = false; it.showStatus("任务未创建，请检查连接后重试"); it.confirm?.isEnabled = true
                }
            } }
        }
    }

    private fun startCreatedWorker() {
        val id = gateway.prefs.getString("voice_pending_worker_run", "").orEmpty()
        if (!visible || holdMode || isFinishing || isDestroyed || restoringWorker || id.isBlank()) return
        val generation = gateway.prefs.getLong("voice_pending_worker_generation", TaskControl.currentGeneration())
        if (!TaskControl.isCurrent(generation)) {
            gateway.prefs.edit().remove("voice_pending_worker_run").remove("voice_pending_worker_generation").apply()
            submitted = false; confirm?.isEnabled = true; confirm?.text = "查看任务"; showStatus("任务已创建，执行保持暂停")
            return
        }
        val active = gateway.prefs.getString("active_run", "").orEmpty()
        if (active.isNotBlank() && active != id) {
            gateway.prefs.edit().remove("voice_pending_worker_run").remove("voice_pending_worker_generation").apply()
            return
        }
        restoringWorker = true
        io.execute {
            val run = try { gateway.request("GET", "/runs/$id") } catch (_: Exception) { null }
            runOnUiThread {
                restoringWorker = false
                if (!visible || holdMode || isFinishing || isDestroyed || gateway.prefs.getString("voice_pending_worker_run", "") != id) return@runOnUiThread
                if (!TaskControl.isCurrent(generation)) { startCreatedWorker(); return@runOnUiThread }
                val current = gateway.prefs.getString("active_run", "").orEmpty()
                if (current.isNotBlank() && current != id) return@runOnUiThread
                submitted = false; confirm?.isEnabled = true; confirm?.text = "查看任务"
                when (run?.takeIf { it.optString("id") == id }?.optString("status")) {
                    "running" -> try {
                        if (current != id) { showStatus("任务状态已更新，请查看任务"); return@runOnUiThread }
                        if (!TaskControl.wakeQueue(this, generation)) return@runOnUiThread
                        gateway.prefs.edit().remove("voice_pending_worker_run").remove("voice_pending_worker_generation").commit()
                        finishVoice()
                    } catch (_: Exception) { showStatus("任务已创建，请返回任务页继续") }
                    "completed", "failed", "cancelled" -> {
                        gateway.prefs.edit().remove("voice_pending_worker_run").remove("voice_pending_worker_generation").apply()
                        finishVoice()
                    }
                    "queued" -> {
                        gateway.prefs.edit().remove("voice_pending_worker_run").remove("voice_pending_worker_generation").commit()
                        TaskControl.wakeQueue(this, generation)
                        Toast.makeText(this, TaskPresentation.queueLabel(requireNotNull(run)), Toast.LENGTH_SHORT).show()
                        finishVoice()
                    }
                    else -> showStatus("任务已创建，请返回任务页继续")
                }
            }
        }
    }

    private fun startRecognition() {
        if (!FirstUseConsent.allowEntry(this)) return
        if (!visible || listening || submitted || closingHold) return
        keyboardPending = false
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(window.decorView.windowToken, 0)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micHeld = false; detachGesture(); microphonePermissionPending = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
            return
        }
        val current = gesture
        if (current != null && !current.state.startRecording()) { handleGestureChange(); return }
        listening = true; listeningSignal?.active = true
        holdSurface?.setListening()
        if (holdMode) showStatus("正在开启麦克风")
        capture.start()
    }
    private fun stopRecognition(cancel: Boolean) {
        listening = false; listeningSignal?.active = false
        if (cancel) capture.cancel() else { holdSurface?.setProcessing(); capture.finish() }
    }
    private fun detachGesture() {
        gesture?.let { VoiceGestureSession.detach(it.id) }
        gesture = null; gestureAttached = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    }

    private fun handleGestureChange() {
        val session = gesture ?: return
        when (session.state.phase) {
            VoiceHoldState.Phase.HOLDING -> Unit
            VoiceHoldState.Phase.CANCELLED -> cancelHold(session.cancelDirection)
            VoiceHoldState.Phase.RELEASED -> {
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                if (listening) stopRecognition(false)
                if (gesture !== session) return
                val result = session.state.takeResult()
                if (result != null) {
                    heldTranscript = result; holdSurface?.transcript = result
                    detachGesture()
                    if (visible && !isFinishing && !isDestroyed) submitHold(result)
                } else if (session.state.hasFinal) {
                    detachGesture(); finishHoldWithStatus("没有识别到内容")
                }
            }
        }
    }

    private fun cancelHold(direction: Int) {
        if (!holdMode || closingHold) return
        closingHold = true
        stopRecognition(true); detachGesture()
        val revision = entryRevision
        val surface = holdSurface ?: return finishVoice()
        surface.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
        surface.animateCancel(direction) {
            if (revision == entryRevision && !isFinishing && !isDestroyed) finishVoice()
        }
    }

    private fun finishHoldWithStatus(message: String) {
        if (!holdMode || closingHold || isFinishing || isDestroyed) return
        closingHold = true
        stopRecognition(true); detachGesture()
        holdSurface?.setProcessing(); showStatus(message)
        val revision = entryRevision
        handler.postDelayed({ if (revision == entryRevision && !isFinishing && !isDestroyed) finishVoice() }, 2400)
    }

    private fun attachGesture() {
        if (!visible || !holdMode || closingHold || isFinishing || microphonePermissionPending) return
        val session = gesture
        if (session == null) { finishHoldWithStatus("录音已中断"); return }
        if (taskCreation.get()) { finishHoldWithStatus("已有任务正在创建"); return }
        if (gestureAttached) return
        gestureAttached = true
        session.onChanged = { if (visible && gesture === session) handleGestureChange() }
        val revision = entryRevision
        handler.post {
            if (!visible || isFinishing || revision != entryRevision || gesture !== session) return@post
            if (session.state.phase == VoiceHoldState.Phase.HOLDING) startRecognition() else handleGestureChange()
        }
        handler.postDelayed({
            if (visible && revision == entryRevision && gesture === session && session.state.phase == VoiceHoldState.Phase.HOLDING) {
                VoiceGestureSession.cancel(session.id)
            }
        }, 35000)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        keyboardDraft = keyboardValue()
        stopRecognition(true); detachGesture()
        handler.removeCallbacksAndMessages(null); entryRevision++; closingHold = false
        if (!FirstUseConsent.allowEntry(this)) return
        setIntent(intent)
        val gestureId = intent.getLongExtra(VoiceGestureSession.EXTRA_SESSION_ID, 0)
        gesture = VoiceGestureSession.find(gestureId)
        holdMode = gesture?.state?.phase == VoiceHoldState.Phase.HOLDING
        heldTranscript = ""
        keyboardPending = !holdMode && gestureId == 0L && intent.getBooleanExtra(EXTRA_OPEN_KEYBOARD, false)
        intent.getStringExtra("initial_text")?.let { keyboardDraft = it }
        if (!holdMode) detachGesture()
        renderSurface()
        if (!holdMode && gestureId != 0L) showStatus("语音手势已结束，请重新按住或输入文字")
        if (visible) {
            submitted = taskCreation.get()
            if (holdMode) attachGesture() else startCreatedWorker()
        }
        if (hasWindowFocus()) onWindowFocusChanged(true)
    }

    override fun onResume() {
        super.onResume()
        if (!FirstUseConsent.allowEntry(this)) return
        visible = true; isVisible = true; resumedVoice = java.lang.ref.WeakReference(this)
        DeviceWorkerService.instance?.voiceEditorVisibilityChanged()
        submitted = taskCreation.get()
        confirm?.isEnabled = !submitted
        if (holdMode) attachGesture() else startCreatedWorker()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) {
            microphonePermissionPending = false
            val message = if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) "麦克风已授权，请重新按住说话" else "麦克风未授权"
            if (holdMode) finishHoldWithStatus(message) else showStatus(message)
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        keyboardDraft = keyboardValue()
        outState.putString("voice_text", keyboardDraft)
        outState.putString("hold_transcript", heldTranscript)
        outState.putBoolean("hold_surface", holdMode)
        outState.putBoolean("keyboard_pending", keyboardPending)
        super.onSaveInstanceState(outState)
    }
    override fun onPause() {
        keyboardDraft = keyboardValue()
        visible = false; isVisible = false
        if (resumedVoice.get() === this) resumedVoice.clear()
        DeviceWorkerService.instance?.voiceEditorVisibilityChanged()
        micHeld = false; stopRecognition(true); detachGesture(); super.onPause()
    }
    override fun onDestroy() {
        if (resumedVoice.get() === this) resumedVoice.clear()
        DeviceWorkerService.instance?.voiceEditorVisibilityChanged()
        handler.removeCallbacksAndMessages(null)
        capture.cancel(); detachGesture(); io.shutdown(); super.onDestroy()
    }
    private fun dp(value: Int) = UiTheme.dp(this, value)
}

package dev.doppel.sdk

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.*
import android.view.MotionEvent
import android.view.Gravity
import android.graphics.Color
import android.widget.*
import org.json.JSONObject
import java.util.concurrent.Executors

class VoiceActivity : Activity(), RecognitionListener {
    private var recognizer: SpeechRecognizer? = null
    private lateinit var text: EditText
    private lateinit var status: TextView
    private var autoStarted = false
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var gateway: Gateway
    private lateinit var confirm: Button
    private var local: LocalDictation? = null
    private var visible = false
    private var listening = false
    private var useLocal = false
    private var permissionStartPending = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); gateway = Gateway(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        UiTheme.window(this, root)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(UiTheme.dp(this@VoiceActivity, 20), UiTheme.dp(this@VoiceActivity, 16), UiTheme.dp(this@VoiceActivity, 20), UiTheme.dp(this@VoiceActivity, 20)) }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(layout) })
        setContentView(root)
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(UiTheme.text(this, "语音任务", 21f, UiTheme.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(UiTheme.icon(this, android.R.drawable.ic_menu_close_clear_cancel, "关闭") { finishVoice() }, LinearLayout.LayoutParams(UiTheme.dp(this, 44), UiTheme.dp(this, 44))); layout.addView(header)
        status = UiTheme.text(this, "等待输入", 13f, UiTheme.muted).apply { setPadding(0, UiTheme.dp(this@VoiceActivity, 4), 0, UiTheme.dp(this@VoiceActivity, 18)) }; layout.addView(status)
        text = UiTheme.field(this, "输入任务").apply { minLines = 4; maxLines = 7; gravity = Gravity.TOP }; layout.addView(text, LinearLayout.LayoutParams(-1, -2))
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, UiTheme.dp(this@VoiceActivity, 12), 0, UiTheme.dp(this@VoiceActivity, 14)) }
        val mic = UiTheme.icon(this, android.R.drawable.ic_btn_speak_now, "按住说话", true) {}; tools.addView(mic, LinearLayout.LayoutParams(UiTheme.dp(this, 56), UiTheme.dp(this, 56)))
        tools.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        tools.addView(UiTheme.icon(this, android.R.drawable.ic_menu_manage, "语音设置与中文模型") { startActivity(Intent(this, SpeechSettingsActivity::class.java)) }, LinearLayout.LayoutParams(UiTheme.dp(this, 44), UiTheme.dp(this, 44))); layout.addView(tools)
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        }
        local = LocalDictation(this, object : LocalDictation.Listener {
            override fun onListening() { if (visible && listening) status.text = "本地正在听" }
            override fun onText(value: String, final: Boolean) {
                if (!visible) return
                if (value.isNotBlank()) text.setText(value)
                if (final) { listening = false; status.text = if (value.isBlank()) "未听清，请重试或输入文字" else "确认任务" }
            }
            override fun onFailure() { if (visible) { listening = false; status.text = "本地识别未启动，请检查模型和麦克风" } }
        })
        mic.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    startRecognition()
                }
                if (event.action == MotionEvent.ACTION_UP && listening) stopRecognition(false)
                if (event.action == MotionEvent.ACTION_CANCEL) stopRecognition(true)
                true
        }
        if (recognizer == null && !SpeechModels.installed(this)) status.text = "中文模型未安装，可下载或输入文字"
        confirm = UiTheme.command(this, "开始任务", true) { submitTask() }; layout.addView(confirm, LinearLayout.LayoutParams(-1, UiTheme.dp(this, 48)))
    }
    private fun finishVoice() { if (isTaskRoot) finishAndRemoveTask() else finish() }
    private fun submitTask() {
        val value = text.text.toString().trim()
        if (value.isBlank()) { status.text = "请说出或输入任务"; return }
        val device = gateway.prefs.getString("device_id", "").orEmpty()
        if (device.isBlank() || gateway.prefs.getString("token", "").isNullOrBlank()) {
            gateway.prefs.edit().putString("draft_goal", value).apply()
            startActivity(packageManager.getLaunchIntentForPackage(packageName)); finishVoice(); return
        }
        if (DoppelAccessibilityService.instance == null) { status.text = "请先启用无障碍服务"; return }
        stopRecognition(true); confirm.isEnabled = false; status.text = "正在创建任务"
        val mode = listOf("ask", "assist", "full")[gateway.prefs.getInt("mode_index", 1).coerceIn(0, 2)]
        io.execute {
            try {
                val run = gateway.request("POST", "/runs", JSONObject().put("device_id", device).put("goal", value).put("mode", mode))
                if (isFinishing || isDestroyed) { gateway.request("POST", "/runs/${run.getString("id")}/cancel", JSONObject()); return@execute }
                gateway.prefs.edit().putString("active_run", run.getString("id")).putString("draft_goal", "").commit()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) { startForegroundService(Intent(this, DeviceWorkerService::class.java)); finishVoice() }
                }
            } catch (_: Exception) { runOnUiThread { if (!isDestroyed) { status.text = "任务未创建，请检查连接后重试"; confirm.isEnabled = true } } }
        }
    }
    private fun startRecognition() {
        if (!visible || listening) return
        val provider = gateway.prefs.getString("speech_provider", "auto")
        useLocal = provider == "local" || (provider != "system" && SpeechModels.installed(this))
        if (useLocal && !SpeechModels.installed(this)) { status.text = "中文模型未安装，请先下载"; return }
        if (!useLocal && recognizer == null) { status.text = "系统识别不可用，请下载中文模型"; return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
        else {
            listening = true
            if (useLocal) { status.text = "正在加载中文模型"; local?.start() }
            else { status.text = "正在听"; recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")) }
        }
    }
    private fun stopRecognition(cancel: Boolean) {
        listening = false
        if (useLocal) local?.stop(cancel) else if (cancel) recognizer?.cancel() else recognizer?.stopListening()
        if (!cancel) status.text = "正在识别"
    }
    override fun onResume() {
        super.onResume(); visible = true
        if (permissionStartPending) { permissionStartPending = false; window.decorView.post { startRecognition() } }
        if (intent.getBooleanExtra("auto_listen", false) && !autoStarted) { autoStarted = true; window.decorView.post { if (!isFinishing) startRecognition() } }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (visible) startRecognition() else permissionStartPending = true
        }
        else if (requestCode == 7) status.text = "麦克风未授权，请输入文字"
    }
    override fun onPause() { visible = false; stopRecognition(true); local?.stop(true); recognizer?.cancel(); super.onPause() }
    override fun onDestroy() { local?.destroy(); recognizer?.destroy(); io.shutdown(); super.onDestroy() }
    override fun onResults(results: Bundle?) { if (visible && !useLocal) { listening = false; text.setText(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()); status.text = "确认任务" } }
    override fun onError(error: Int) { if (visible && !useLocal) { listening = false; status.text = "系统识别不可用，可在语音设置安装本地中文模型" } }
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() { if (visible && !useLocal) status.text = "正在识别" }
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}

package dev.doppel.sdk

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.*
import android.view.MotionEvent
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
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 40, 24, 24) }
        setContentView(layout)
        window.setGravity(android.view.Gravity.BOTTOM)
        window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        status = TextView(this).apply { textSize = 20f; text = "语音任务" }; layout.addView(status)
        text = EditText(this).apply { hint = "输入任务"; minLines = 3 }; layout.addView(text)
        val mic = ImageButton(this).apply { setImageResource(android.R.drawable.ic_btn_speak_now); contentDescription = "按住说话" }; layout.addView(mic, LinearLayout.LayoutParams((56 * resources.displayMetrics.density).toInt(), (52 * resources.displayMetrics.density).toInt()))
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
        layout.addView(Button(this).apply { this.text = "语音设置与中文模型"; setOnClickListener { startActivity(Intent(this@VoiceActivity, SpeechSettingsActivity::class.java)) } })
        confirm = Button(this).apply { this.text = "开始任务"; setOnClickListener { submitTask() } }; layout.addView(confirm)
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

package dev.doppel.sdk

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.*

class SpeechSettingsActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var install: Button
    private lateinit var cancel: Button
    private lateinit var remove: Button
    private val observer: (SpeechModels.State) -> Unit = { state ->
        status.text = state.message; progress.progress = state.progress
        progress.visibility = if (state.busy) View.VISIBLE else View.GONE
        install.isEnabled = !state.busy; cancel.isEnabled = state.busy; remove.isEnabled = !state.busy && SpeechModels.installed(this)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "语音识别"
        val pad = (20 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        setContentView(ScrollView(this).apply { addView(layout) })
        layout.addView(TextView(this).apply { text = "识别方式"; textSize = 20f })
        val prefs = getSharedPreferences("doppel", MODE_PRIVATE)
        val values = listOf("auto", "local", "system")
        layout.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@SpeechSettingsActivity, android.R.layout.simple_spinner_dropdown_item, listOf("自动", "本地中文", "系统识别"))
            setSelection(values.indexOf(prefs.getString("speech_provider", "auto")).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { prefs.edit().putString("speech_provider", values[position]).apply() }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        })
        status = TextView(this).apply { textSize = 17f }; layout.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }; layout.addView(progress)
        install = Button(this).apply { text = "下载中文模型 · 42 MiB"; setOnClickListener { SpeechModels.install(this@SpeechSettingsActivity) } }; layout.addView(install)
        cancel = Button(this).apply { text = "取消下载"; setOnClickListener { SpeechModels.cancel() } }; layout.addView(cancel)
        remove = Button(this).apply { text = "删除本地模型"; setOnClickListener { SpeechModels.delete(this@SpeechSettingsActivity) } }; layout.addView(remove)
        layout.addView(TextView(this).apply { text = "Vosk 0.3.75 · 中文模型 0.22 · Apache 2.0"; textSize = 14f })
    }
    override fun onStart() { super.onStart(); SpeechModels.observe(this, observer) }
    override fun onStop() { SpeechModels.removeObserver(observer); super.onStop() }
}

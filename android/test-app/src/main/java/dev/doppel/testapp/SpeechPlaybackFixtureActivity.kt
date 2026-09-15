package dev.doppel.testapp

import android.app.Activity
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import kotlin.math.roundToInt

/** Synthetic acoustic source. Playback deliberately continues behind the speech capture Activity. */
class SpeechPlaybackFixtureActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var audioManager: AudioManager
    private var player: MediaPlayer? = null
    private var originalVolume: Int? = null
    private var attempted = false
    private var finished = false
    private var destroyed = false
    private val startPlayback = Runnable { playOnce() }
    private val timeout = Runnable { complete("播放超时，声学测试已停止") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(AudioManager::class.java)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
            setBackgroundColor(Color.rgb(247, 249, 251))
        }
        page.addView(TextView(this).apply {
            text = "合成语音声学测试源"
            textSize = 23f
            setTextColor(Color.rgb(24, 30, 40))
        })
        page.addView(TextView(this).apply {
            text = "本地已知中文 WAV，仅用于扬声器到麦克风的声学通路测试。"
            textSize = 16f
            setTextColor(Color.rgb(78, 88, 100))
            setPadding(0, dp(16), 0, dp(16))
        })
        status = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.rgb(24, 30, 40))
        }
        page.addView(status)
        setContentView(page)
        if (savedInstanceState != null) {
            attempted = true
            finished = true
            status.text = "界面已重建，本次音频不会重复播放"
        } else {
            status.text = "等待 5 秒后播放一次"
            handler.postDelayed(startPlayback, 5_000)
        }
    }

    private fun playOnce() {
        if (attempted || finished || destroyed) return
        attempted = true
        try {
            val file = File(filesDir, "speech-qa.wav")
            check(file.canonicalFile.parentFile == filesDir.canonicalFile && file.isFile)
            val source = MediaPlayer()
            player = source
            source.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            source.isLooping = false
            source.setOnCompletionListener { complete("播放完成，媒体音量已恢复") }
            source.setOnErrorListener { _, _, _ ->
                complete("播放失败，声学测试已停止")
                true
            }
            source.setOnPreparedListener { prepared ->
                if (destroyed || finished || player !== prepared) return@setOnPreparedListener
                handler.removeCallbacks(timeout)
                try {
                    check(!audioManager.isVolumeFixed)
                    originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (maximum * 0.5f).roundToInt(), 0)
                    val speakerPreferred = if (Build.VERSION.SDK_INT >= 28) {
                        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                            ?.let { prepared.setPreferredDevice(it) } ?: false
                    } else false
                    status.text = if (speakerPreferred) "正在播放合成语音，已请求内置扬声器" else "正在播放合成语音，使用系统音频输出"
                    prepared.start()
                    handler.postDelayed(timeout, 45_000)
                } catch (_: Exception) {
                    complete("无法启动播放，声学测试已停止")
                }
            }
            FileInputStream(file).use { input ->
                val length = input.channel.size()
                check(length in 44L..1_000_000L)
                val header = ByteArray(12)
                check(input.read(header) == header.size)
                check(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WAVE")
                source.setDataSource(input.fd, 0, length)
            }
            status.text = "正在准备本地合成音频"
            handler.postDelayed(timeout, 10_000)
            source.prepareAsync()
        } catch (_: Exception) {
            complete("测试音频缺失、超限或无效")
        }
    }

    private fun complete(message: String) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        val restored = releaseAndRestore()
        if (!destroyed) status.text = if (restored) message else "$message；系统未允许恢复媒体音量"
    }

    private fun releaseAndRestore(): Boolean {
        val current = player
        player = null
        runCatching { current?.release() }
        val volume = originalVolume ?: return true
        return runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
            originalVolume = null
            true
        }.getOrDefault(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("speech_source_created", true)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        releaseAndRestore()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}

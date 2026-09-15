package dev.doppel.sdk

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

internal class CompletionSpeech(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val worker = Executors.newSingleThreadExecutor()
    private val epoch = AtomicLong()
    private var tts: TextToSpeech? = null
    private var ready = false
    private var initialized = false
    private var pending: Pair<Long, String>? = null
    private var fallbackEpoch = -1L
    private var closed = false
    private val prefs = context.getSharedPreferences("doppel", Context.MODE_PRIVATE)
    private val changed = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "completion_speech" && !prefs.getBoolean(key, true)) handler.post { stop() }
    }
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { change -> if (change < 0) handler.post { stop() } }.build()

    init { prefs.registerOnSharedPreferenceChangeListener(changed) }
    private fun initialize() {
        if (initialized) return
        initialized = true
        tts = TextToSpeech(context) { status -> handler.post {
            if (closed) return@post
            val language = if (status == TextToSpeech.SUCCESS) tts?.setLanguage(Locale.SIMPLIFIED_CHINESE) ?: -2 else -2
            ready = language >= TextToSpeech.LANG_AVAILABLE
            tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { }
                override fun onDone(id: String?) { handler.post { if (id == epoch.get().toString()) audio.abandonAudioFocusRequest(focus) } }
                @Deprecated("Android callback") override fun onError(id: String?) { handler.post { pending?.takeIf { it.first.toString() == id }?.let { fallback(it.first, it.second) } } }
            })
            pending?.let { play(it.first, it.second) }
        } }
    }
    fun speak(value: String) {
        if (closed) return
        stop()
        if (!canSpeak()) return
        val id = epoch.get()
        val text = value.take(1000).let { if (value.length > 1000) "$it。完整结果已显示在屏幕上。" else it }
        pending = id to text
        if (!initialized) {
            initialize()
            handler.postDelayed({ if (pending?.first == id && !ready) fallback(id, text) }, 3500)
        } else play(id, text)
    }
    private fun canSpeak() = prefs.getBoolean("completion_speech", true) &&
        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
        audio.mode !in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE)
    private fun play(id: Long, text: String) {
        if (closed || id != epoch.get() || fallbackEpoch == id || !canSpeak()) return
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        if (!ready || tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id.toString()) != TextToSpeech.SUCCESS) fallback(id, text)
    }
    private fun fallback(id: Long, text: String) {
        if (closed || id != epoch.get() || fallbackEpoch == id || !canSpeak()) return
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        fallbackEpoch = id
        worker.execute {
            try { EmbeddedChineseTts(context).use { it.speak(text) { !closed && id == epoch.get() && canSpeak() } } }
            catch (_: Exception) { audioUnavailable(id) }
            catch (_: LinkageError) { audioUnavailable(id) }
            catch (_: OutOfMemoryError) { audioUnavailable(id) }
            finally { handler.post { if (id == epoch.get()) audio.abandonAudioFocusRequest(focus) } }
        }
    }
    private fun audioUnavailable(id: Long) {
        handler.post { if (!closed && id == epoch.get()) android.widget.Toast.makeText(context, "播报暂不可用，结果已保留在屏幕上", android.widget.Toast.LENGTH_SHORT).show() }
    }
    fun stop() { epoch.incrementAndGet(); pending = null; tts?.stop(); audio.abandonAudioFocusRequest(focus) }
    fun close() { closed = true; stop(); prefs.unregisterOnSharedPreferenceChangeListener(changed); tts?.shutdown(); tts = null; worker.shutdown() }
}

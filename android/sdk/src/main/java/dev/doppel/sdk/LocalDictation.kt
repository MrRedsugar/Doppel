package dev.doppel.sdk

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.InputStream
import java.util.concurrent.Executors

class LocalDictation(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onListening()
        fun onText(text: String, final: Boolean)
        fun onFailure()
    }
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var requested = false
    @Volatile private var generation = 0
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null

    fun start() {
        stop(true)
        requested = true
        val session = ++generation
        io.execute {
            try {
                if (!requested || session != generation) return@execute
                val loaded = Model(SpeechModels.directory(context).absolutePath)
                if (!requested || session != generation) { loaded.close(); return@execute }
                model = loaded
                recognizer = Recognizer(loaded, 16000f)
                val service = SpeechService(recognizer, 16000f)
                speech = service
                val segments = mutableListOf<String>()
                var latestText = ""
                fun deliver(json: String, final: Boolean, partial: Boolean = false) {
                    if (session != generation) return
                    val value = JSONObject(json).optString(if (partial) "partial" else "text").trim()
                    if (!partial && value.isNotBlank()) segments.add(value)
                    latestText = (segments + if (partial) listOf(value) else emptyList()).joinToString(" ").trim()
                    listener.onText(latestText, final)
                }
                if (!requested || session != generation) { close(); return@execute }
                service.startListening(object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String) { deliver(hypothesis, false, true) }
                    override fun onResult(hypothesis: String) { deliver(hypothesis, false) }
                    override fun onFinalResult(hypothesis: String) { deliver(hypothesis, true) }
                    override fun onError(exception: Exception) { if (session == generation) { stop(true); listener.onFailure() } }
                    override fun onTimeout() { if (session == generation) { stop(true); listener.onText(latestText, true) } }
                }, 30000)
                main.post { if (session == generation && requested) listener.onListening() }
            } catch (_: Throwable) {
                close()
                main.post { if (session == generation) listener.onFailure() }
            }
        }
    }
    fun stop(cancel: Boolean) {
        requested = false
        if (cancel) generation++
        val session = generation
        io.execute {
            if (cancel) speech?.cancel() else if (speech != null) speech?.stop() else main.post { if (session == generation) listener.onText("", true) }
            close()
        }
    }
    fun destroy() { stop(true); io.shutdown() }
    private fun close() {
        speech?.shutdown(); speech = null
        recognizer?.close(); recognizer = null
        model?.close(); model = null
    }

    companion object {
        /** Decodes caller-owned PCM16 mono at 16 kHz without opening or storing microphone audio. */
        fun transcribePcm(context: Context, input: InputStream): String {
            Model(SpeechModels.directory(context).absolutePath).use { model ->
                Recognizer(model, 16000f).use { recognizer ->
                    val parts = mutableListOf<String>()
                    val buffer = ByteArray(6400)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 16000 * 2 * 60) { "Audio exceeds one minute" }
                        if (recognizer.acceptWaveForm(buffer, count)) parts.add(JSONObject(recognizer.result).optString("text"))
                    }
                    parts.add(JSONObject(recognizer.finalResult).optString("text"))
                    return parts.filter { it.isNotBlank() }.joinToString(" ")
                }
            }
        }
    }
}

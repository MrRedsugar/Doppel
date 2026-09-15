package dev.doppel.sdk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File

/** Main-thread controls with gesture-owned recording and bounded local recognition. */
internal class SpeechCapture(context: Context, private val listener: Listener) {
    companion object {
        private const val MICROPHONE_PERMISSION_MESSAGE = "麦克风权限未开启或已被撤回，请在系统设置中允许后重试，或输入文字"
        private val diagnosticSequence = java.util.concurrent.atomic.AtomicLong()
        private val diagnostics = java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.ArrayBlockingQueue<Runnable>(1),
            java.util.concurrent.ThreadFactory { work -> Thread(work, "doppel-speech-diagnostics").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy())
    }
    interface Listener {
        fun onState(message: String)
        fun onText(text: String, final: Boolean)
        fun onFailure(message: String)
    }

    private val consentContext = context.applicationContext
    private val recognizer = EmbeddedAsr(consentContext)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0L
    @Volatile private var current: Session? = null

    fun start() {
        cancel()
        if (!FirstUseConsent.isAccepted(consentContext)) { listener.onFailure(FirstUseConsent.REQUIRED_MESSAGE); return }
        val session = Session(generation)
        current = session
        main.postDelayed(session.startupTimeout, 6000)
        session.recordingThread.start()
    }

    fun finish() {
        val session = current ?: return
        if (!session.state.finish()) return
        main.removeCallbacks(session.previewTick)
        main.removeCallbacks(session.startupTimeout)
        main.postDelayed(session.finalTimeout, 30000)
        session.recordingThread.interrupt()
        if (session.recordingStopped.get()) session.queueFinal()
        listener.onState("正在识别")
    }

    fun cancel() {
        generation++
        val session = current
        current = null
        session?.state?.cancel()
        session?.close()
    }

    private inner class Session(val id: Long) {
        val state = CloudSpeechState()
        private val audio = CloudSpeechAudio()
        val recordingStopped = AtomicBoolean(false)
        private val recordingFailure = AtomicReference<String?>()
        private val recordingReady = AtomicBoolean(false)
        private val finalQueued = AtomicBoolean(false)
        private var previewFailed = false
        private val startedAt = SystemClock.elapsedRealtime()
        @Volatile private var readyAfterMs = -1L
        @Volatile private var routedDeviceType = 0
        @Volatile private var microphoneMuted = false
        @Volatile private var clientSilenced = false
        @Volatile private var previewRequests = 0
        @Volatile private var finalRequests = 0
        private val audioManager = consentContext.getSystemService(AudioManager::class.java)
        private val requests = Executors.newFixedThreadPool(2) { work ->
            Thread(work, "doppel-speech-asr-$id").apply { isDaemon = true }
        }
        val recordingThread = Thread({ record() }, "doppel-speech-record-$id").apply { isDaemon = true }
        val startupTimeout = Runnable {
            if (owned() && !recordingReady.get() && state.phase == CloudSpeechState.Phase.RECORDING)
                fail(SpeechFailure.MICROPHONE_STARTING.message, SpeechFailure.MICROPHONE_STARTING.name)
        }
        val finalTimeout = Runnable { if (owned()) fail("本地识别用时较长，请重新说一遍或输入文字", SpeechFailure.TIMEOUT.name) }
        val previewTick = object : Runnable {
            override fun run() {
                if (!owned() || state.phase != CloudSpeechState.Phase.RECORDING || recordingStopped.get() || previewFailed) return
                preview()
                main.postDelayed(this, 1200)
            }
        }

        private fun owned(): Boolean = current === this && generation == id

        private fun record() {
            var recorder: AudioRecord? = null
            var problem: String? = null
            try {
                if (!owned() || state.phase != CloudSpeechState.Phase.RECORDING) return
                val minimum = AudioRecord.getMinBufferSize(CloudSpeechAudio.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                if (consentContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    throw SecurityException()
                recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, CloudSpeechAudio.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 2560))
                check(recorder.state == AudioRecord.STATE_INITIALIZED)
                if (!owned() || state.phase != CloudSpeechState.Phase.RECORDING) return
                FirstUseConsent.requireAccepted(consentContext)
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                recordingReady.set(true)
                readyAfterMs = SystemClock.elapsedRealtime() - startedAt
                main.post {
                    if (owned() && state.phase == CloudSpeechState.Phase.RECORDING) {
                        main.removeCallbacks(startupTimeout)
                        main.postDelayed(previewTick, 1200)
                        listener.onState("正在听")
                    }
                }
                val started = SystemClock.elapsedRealtime()
                val buffer = ShortArray(320)
                while (owned() && state.phase == CloudSpeechState.Phase.RECORDING && !audio.full &&
                    SystemClock.elapsedRealtime() - started < CloudSpeechAudio.MAX_DURATION_MS) {
                    val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                    check(count >= 0)
                    if (count > 0) audio.append(buffer, count)
                    if (count == 0) Thread.sleep(10)
                }
            } catch (_: SecurityException) {
                if (owned()) problem = MICROPHONE_PERMISSION_MESSAGE
            } catch (_: InterruptedException) {
                if (owned() && state.phase == CloudSpeechState.Phase.RECORDING)
                    problem = "录音已中断，请重新按住或输入文字"
            } catch (_: Exception) {
                if (owned()) problem = "麦克风未能完成录音，请重新按住或输入文字"
            } finally {
                microphoneMuted = runCatching { audioManager.isMicrophoneMute }.getOrDefault(false)
                routedDeviceType = runCatching { recorder?.routedDevice?.type ?: 0 }.getOrDefault(0)
                if (Build.VERSION.SDK_INT >= 29) clientSilenced = runCatching {
                    recorder?.activeRecordingConfiguration?.isClientSilenced == true
                }.getOrDefault(false)
                // Preserve already captured tail samples on release without waiting for more audio.
                if (problem == null && owned() && state.phase == CloudSpeechState.Phase.FINALIZING && recordingReady.get()) {
                    try {
                        val tail = ShortArray(320)
                        for (attempt in 0 until 8) {
                            if (audio.full || !owned() || state.phase != CloudSpeechState.Phase.FINALIZING) break
                            val count = recorder?.read(tail, 0, tail.size, AudioRecord.READ_NON_BLOCKING) ?: 0
                            check(count >= 0)
                            if (count == 0) break
                            audio.append(tail, count)
                        }
                    } catch (_: SecurityException) {
                        if (owned()) problem = MICROPHONE_PERMISSION_MESSAGE
                    } catch (_: Exception) {
                        if (owned()) problem = "麦克风未能完成录音，请重新按住或输入文字"
                    }
                }
                // Only the recording thread owns AudioRecord stop/release, including early cancellation.
                try { if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() } catch (_: Exception) { }
                try { recorder?.release() } catch (_: Exception) { }
                recordingFailure.set(problem)
                recordingStopped.set(true)
                if (problem != null) {
                    val message = problem
                    main.post { fail(message) }
                } else if (owned()) {
                    if (state.phase == CloudSpeechState.Phase.FINALIZING) queueFinal()
                    else if (state.phase == CloudSpeechState.Phase.RECORDING) main.post {
                        if (owned() && state.phase == CloudSpeechState.Phase.RECORDING) {
                            main.removeCallbacks(previewTick)
                            listener.onState("已达 30 秒，松手发送")
                        }
                    }
                }
            }
        }

        private fun preview() {
            val token = state.beginPreview() ?: return
            execute {
                try {
                    val clip = audio.snapshot()
                    if (!clip.canPreview) {
                        report("preview_skipped")
                        state.completePreview(token)
                        return@execute
                    }
                    previewRequests++
                    report("preview_request")
                    val text = transcribe(clip.wav, false)
                    main.post {
                        if (owned() && state.completePreview(token) && text.isNotBlank()) listener.onText(text, false)
                    }
                } catch (error: Exception) {
                    main.post {
                        if (owned() && state.completePreview(token)) {
                            previewFailed = true
                            main.removeCallbacks(previewTick)
                            val failure = SpeechFailure.from(error, true)
                            report("preview_failed", failure.name)
                            listener.onState("正在听，松手后完成本地识别")
                        }
                    }
                }
            }
        }

        fun queueFinal() {
            if (!owned() || state.phase != CloudSpeechState.Phase.FINALIZING || !recordingStopped.get() ||
                !finalQueued.compareAndSet(false, true)) return
            recordingFailure.get()?.let { message ->
                main.post { fail(message) }
                return
            }
            execute {
                try {
                    // The second slot queues release promptly; EmbeddedAsr prioritizes final requests.
                    if (!owned() || state.phase != CloudSpeechState.Phase.FINALIZING) return@execute
                    val clip = audio.snapshot()
                    val recordingIssue = SpeechFailure.forRecording(recordingReady.get(), microphoneMuted,
                        clientSilenced, clip.sampleCount, clip.durationMs, clip.hasSpeech)
                    if (recordingIssue != null) {
                        main.post { fail(recordingIssue.message, recordingIssue.name) }
                        return@execute
                    }
                    finalRequests++
                    report("final_request")
                    val text = transcribe(clip.wav, true)
                    main.post {
                        if (owned() && state.completeFinal()) {
                            report("completed")
                            current = null
                            close()
                            listener.onText(text, true)
                        }
                    }
                } catch (error: Exception) {
                    val failure = SpeechFailure.from(error, true)
                    main.post { fail(if (failure == SpeechFailure.EMPTY_RESULT) "没有识别清楚，请重新说一遍或输入文字" else "本地语音识别未完成，请重新说一遍或输入文字", failure.name) }
                }
            }
        }

        private fun transcribe(wav: ByteArray, final: Boolean): String {
            val expected = if (final) CloudSpeechState.Phase.FINALIZING else CloudSpeechState.Phase.RECORDING
            if (!owned() || state.phase != expected) throw InterruptedException()
            val response = recognizer.transcribe(wav, final)
            if (!owned() || state.phase != expected) throw InterruptedException()
            return (response.opt("text") as? String ?: error("Missing transcript")).trim()
                .also { if (final) check(it.isNotBlank() && !it.contains("<OOV>") && !it.contains("<unk>")) { "Missing transcript" } }
        }

        private fun execute(work: () -> Unit) {
            try { requests.execute(work) } catch (_: RejectedExecutionException) {
                if (owned()) main.post { fail("语音识别未能启动，请重新按住或输入文字") }
            }
        }

        private fun fail(message: String, code: String = "CAPTURE_FAILED") {
            if (!owned() || !state.fail()) return
            report("failed", code)
            current = null
            close()
            listener.onFailure(message)
        }

        /** Only timing, signal statistics and locally selected codes; never audio, transcript or credentials. */
        private fun report(stage: String, failure: String? = null) {
            if (!owned()) return
            val clip = audio.statistics()
            val record = JSONObject().put("at", System.currentTimeMillis()).put("stage", stage)
                .put("connection", "embedded_offline").put("asr_model", SpeechModels.EMBEDDED_NAME)
                .put("capture_ready", recordingReady.get()).put("ready_after_ms", readyAfterMs)
                .put("duration_ms", clip.durationMs).put("sample_count", clip.sampleCount)
                .put("peak", clip.peak).put("rms", clip.rms).put("final_signal", clip.hasSpeech)
                .put("preview_signal", clip.canPreview).put("input_device_type", routedDeviceType)
                .put("microphone_muted", microphoneMuted).put("client_silenced", clientSilenced)
                .put("preview_requests", previewRequests).put("final_requests", finalRequests)
                .put("failure_code", failure ?: JSONObject.NULL)
            Log.i("DoppelSpeech", record.toString())
            val ticket = diagnosticSequence.incrementAndGet()
            diagnostics.execute {
                runCatching {
                    if (diagnosticSequence.get() != ticket) return@runCatching
                    val file = AtomicFile(File(consentContext.filesDir, "speech-diagnostic.json"))
                    val stream = file.startWrite()
                    try { stream.write(record.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
                    catch (error: Exception) { file.failWrite(stream); throw error }
                }
            }
        }

        fun close() {
            main.removeCallbacks(previewTick)
            main.removeCallbacks(startupTimeout)
            main.removeCallbacks(finalTimeout)
            recordingThread.interrupt()
            requests.shutdownNow()
        }
    }

}

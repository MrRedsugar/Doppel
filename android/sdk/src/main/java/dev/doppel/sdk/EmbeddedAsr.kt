package dev.doppel.sdk

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Bundled Mandarin ASR. Audio and inference never leave this process.
 * Hold previews decode bounded cumulative clips. Only release requests are final;
 * the capture owner decides whether a returned result still belongs to its gesture.
 */
class EmbeddedAsr(context: Context) {
    private val app = context.applicationContext

    fun transcribe(wav: ByteArray, final: Boolean): JSONObject {
        val started = SystemClock.elapsedRealtime()
        val duration = DirectPayload.wavDuration(wav)
        if (duration < 300) throw IllegalArgumentException("录音太短")
        val samples = ShortArray((wav.size - 44) / 2)
        ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        // Silence must not be passed to a language decoder, which could invent text.
        var energy = 0.0
        for (sample in samples) energy += sample.toDouble() * sample
        if (energy < samples.size * 4.0) return response("", final, duration, started)
        if (final) waitingFinals.incrementAndGet()
        try {
            decoderLock.lockInterruptibly()
            try {
                checkInterrupted()
                if (!final && waitingFinals.get() > 0) return response("", false, duration, started)
                val loaded = load(app)
                lastUse = SystemClock.elapsedRealtime()
                val bank = EmbeddedAsrFrontend.filterbank(samples)
                val features = EmbeddedAsrFrontend.stackAndNormalize(bank, loaded.mean, loaded.inverseStd)
                if (features.isEmpty()) return response("", final, duration, started)
                checkInterrupted()
                val env = OrtEnvironment.getEnvironment()
                val frames = features.size / 560
                val text = OnnxTensor.createTensor(env, FloatBuffer.wrap(features), longArrayOf(1, frames.toLong(), 560)).use { speech ->
                    OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(frames)), longArrayOf(1)).use { lengths ->
                        loaded.session.run(mapOf("speech" to speech, "speech_lengths" to lengths)).use { result ->
                            checkInterrupted()
                            val logits = result.get("logits").orElseThrow() as OnnxTensor
                            val count = (result.get("token_num").orElseThrow() as OnnxTensor).intBuffer.get()
                            val shape = logits.info.shape
                            check(shape.size == 3 && shape[0] == 1L && shape[2] == loaded.tokens.size.toLong()) { "离线模型输出异常" }
                            check(count in 0..shape[1].toInt()) { "离线模型输出异常" }
                            val values = logits.floatBuffer
                            val words = ArrayList<String>(count)
                            for (row in 0 until count) {
                                var best = 0
                                var score = Float.NEGATIVE_INFINITY
                                for (column in loaded.tokens.indices) {
                                    val value = values.get(row * loaded.tokens.size + column)
                                    if (value > score) { best = column; score = value }
                                }
                                words.add(loaded.tokens[best])
                            }
                            EmbeddedAsrFrontend.text(words)
                        }
                    }
                }
                checkInterrupted()
                return response(text, final, duration, started)
            } finally {
                lastUse = SystemClock.elapsedRealtime()
                decoderLock.unlock()
                scheduleIdleRelease()
            }
        } finally { if (final) waitingFinals.decrementAndGet() }
    }

    private fun response(text: String, final: Boolean, duration: Int, started: Long) = JSONObject()
        .put("text", text).put("model", SpeechModels.EMBEDDED_NAME).put("duration_ms", duration)
        .put("elapsed_ms", SystemClock.elapsedRealtime() - started).put("final", final).put("offline", true)

    companion object {
        private const val MODEL_SHA256 = "3ef6c19369b912f7caf3cef8e545c5ccd1a33d9d7ec792a46668dc41c4b229ec"
        private val decoderLock = ReentrantLock(true)
        private val waitingFinals = AtomicInteger()
        private val cleanup = Executors.newSingleThreadScheduledExecutor { work -> Thread(work, "doppel-asr-idle").apply { isDaemon = true } }
        private var idleRelease: java.util.concurrent.ScheduledFuture<*>? = null
        private var engine: Engine? = null
        @Volatile private var lastUse = 0L
        private data class Engine(val session: OrtSession, val mean: FloatArray, val inverseStd: FloatArray, val tokens: List<String>)

        private fun checkInterrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedException() }

        private fun load(context: Context): Engine {
            engine?.let { return it }
            val root = File(context.noBackupFilesDir, "embedded-asr/${MODEL_SHA256.take(16)}").apply { check(isDirectory || mkdirs()) }
            val file = File(root, "model.int8.onnx")
            if (!file.isFile || file.length() != 81828675L || sha256(file) != MODEL_SHA256) {
                val partial = File(root, "model.part")
                try {
                    context.assets.open("asr/model.int8.onnx").use { source -> partial.outputStream().use { source.copyTo(it) } }
                    check(sha256(partial) == MODEL_SHA256) { "内置中文模型校验失败" }
                    check(partial.renameTo(file)) { "内置中文模型无法准备" }
                } finally { partial.delete() }
            }
            checkInterrupted()
            val session = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                options.setInterOpNumThreads(1)
                // Variable clip lengths should not keep the largest arena allocation alive.
                options.setCPUArenaAllocator(false)
                options.setMemoryPatternOptimization(false)
                OrtEnvironment.getEnvironment().createSession(file.absolutePath, options)
            }
            try {
                val metadata = session.metadata.customMetadata
                check(metadata["model_type"] == "paraformer" && metadata["lfr_window_size"] == "7" && metadata["lfr_window_shift"] == "6")
                fun vector(name: String) = metadata.getValue(name).split(',').map { it.toFloat() }.toFloatArray().also {
                    check(it.size == 560 && it.all { value -> value.isFinite() })
                }
                val tokens = context.assets.open("asr/tokens.txt").bufferedReader().use { reader ->
                    reader.lineSequence().filter { it.isNotBlank() }.mapIndexed { index, line ->
                        val split = line.lastIndexOf(' ')
                        check(split > 0 && line.substring(split + 1).toInt() == index)
                        line.substring(0, split)
                    }.toList()
                }
                check(tokens.size == 8359)
                return Engine(session, vector("neg_mean"), vector("inv_stddev"), tokens).also { engine = it }
            } catch (failure: Throwable) { session.close(); throw failure }
        }

        private fun sha256(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(128 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun scheduleIdleRelease() = synchronized(cleanup) {
            idleRelease?.cancel(false)
            idleRelease = cleanup.schedule({
                decoderLock.withLock {
                    if (SystemClock.elapsedRealtime() - lastUse >= 90000) { engine?.session?.close(); engine = null }
                }
            }, 91, TimeUnit.SECONDS)
        }
    }
}

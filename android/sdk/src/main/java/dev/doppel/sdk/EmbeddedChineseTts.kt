package dev.doppel.sdk

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.icu.text.MessageFormat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import java.io.Closeable
import java.io.DataInputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.channels.FileChannel
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Offline fallback only. The caller owns audio focus; all user text/audio stays in memory. */
internal class EmbeddedChineseTts(context: Context) : Closeable {
    private val assets = context.applicationContext.assets
    private val inUse = AtomicBoolean(false)
    private val runLock = Any()
    private val resourcesLock = Any()
    @Volatile private var closed = false
    private var session: OrtSession? = null
    private var mappedModel: ByteBuffer? = null
    private var voice: FloatArray? = null
    private var frontend: ChineseSpeechText? = null
    private var activeRun: OrtSession.RunOptions? = null
    @Volatile private var runDeadline = 0L

    fun speak(text: String, current: () -> Boolean) {
        if (closed || !current() || text.isBlank()) return
        check(inUse.compareAndSet(false, true)) { "Speech is already active" }
        val stopped = AtomicBoolean(false)
        val produced = AtomicBoolean(false)
        val failure = AtomicReference<Exception?>()
        val queue = ArrayBlockingQueue<ShortArray>(1)
        fun valid() = !closed && !stopped.get() && current()
        var playback: Thread? = null
        var watchdog: Thread? = null
        try {
            load()
            if (!valid()) return
            val chunks = requireNotNull(frontend).chunks(normalize(text.take(1200)))
            if (chunks.isEmpty()) return
            playback = Thread({
                try {
                    while (valid() && (!produced.get() || queue.isNotEmpty())) {
                        val pcm = queue.poll(40, TimeUnit.MILLISECONDS) ?: continue
                        play(pcm, ::valid)
                    }
                } catch (error: Exception) { failure.set(error); stopped.set(true) }
            }, "doppel-embedded-tts-play").apply { isDaemon = true; start() }
            watchdog = Thread({
                try {
                    while (!stopped.get()) {
                        val deadline = runDeadline
                        if (!valid() || (deadline != 0L && SystemClock.elapsedRealtime() >= deadline)) {
                            stopped.set(true); terminateRun(); break
                        }
                        Thread.sleep(40)
                    }
                } catch (_: InterruptedException) { }
                catch (_: Exception) { stopped.set(true); terminateRun() }
            }, "doppel-embedded-tts-cancel").apply { isDaemon = true; start() }
            for (chunk in chunks) {
                if (!valid()) break
                val pcm = synthesize(chunk, ::valid)
                while (valid() && !queue.offer(pcm, 40, TimeUnit.MILLISECONDS)) { }
            }
            produced.set(true)
            while (playback.isAlive) {
                if (!valid()) stopped.set(true)
                playback.join(100)
            }
            failure.get()?.let { throw it }
        } finally {
            produced.set(true); stopped.set(true); terminateRun()
            watchdog?.interrupt()
            playback?.interrupt()
            playback?.join(500)
            watchdog?.join(500)
            queue.clear()
            inUse.set(false)
            if (closed) releaseResources()
        }
    }

    private fun load() = synchronized(resourcesLock) {
        check(!closed)
        if (session != null) return@synchronized
        val map = HashMap<String, IntArray>(90000)
        DataInputStream(assets.open("$ASSETS/lexicon.bin").buffered()).use { input ->
            val magic = ByteArray(5); input.readFully(magic)
            check(String(magic, Charsets.US_ASCII) == "DPLX1")
            val count = input.readInt(); check(count in 1..100000)
            repeat(count) {
                val size = input.readUnsignedShort(); check(size in 1..512)
                val key = ByteArray(size); input.readFully(key)
                val phones = input.readUnsignedShort(); check(phones in 1..256)
                map[String(key, Charsets.UTF_8)] = IntArray(phones) { input.readUnsignedShort().also { check(it in 1..255) } }
            }
            check(input.read() == -1)
        }
        val style = assets.open("$ASSETS/voice.bin").use { it.readBytes() }
        check(style.size == 510 * 256 * 4)
        val styles = FloatArray(510 * 256)
        ByteBuffer.wrap(style).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(styles)
        check(styles.all { it.isFinite() })
        val model = assets.openFd("$ASSETS/model.int8.onnx").use { descriptor ->
            check(descriptor.declaredLength == 114299010L)
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
            }
        }
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
            options.setMemoryPatternOptimization(false)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            val loaded = OrtEnvironment.getEnvironment().createSession(model, options)
            try {
                check(loaded.inputNames == setOf("tokens", "style", "speed") && "audio" in loaded.outputNames)
                check(loaded.metadata.customMetadata["sample_rate"] == "24000")
                session = loaded; mappedModel = model; voice = styles; frontend = ChineseSpeechText(map)
            } catch (error: Exception) { loaded.close(); throw error }
        }
    }

    private fun synthesize(ids: LongArray, current: () -> Boolean): ShortArray {
        val length = ids.size - 2
        check(length in 1..508)
        val environment = OrtEnvironment.getEnvironment()
        val style = FloatBuffer.wrap(requireNotNull(voice), length * 256, 256)
        val options = OrtSession.RunOptions()
        try {
            synchronized(runLock) {
                if (!current()) throw InterruptedException()
                activeRun = options; runDeadline = SystemClock.elapsedRealtime() + 30000
            }
            OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { tokens ->
                OnnxTensor.createTensor(environment, style, longArrayOf(1, 256)).use { speaker ->
                    OnnxTensor.createTensor(environment, FloatBuffer.wrap(floatArrayOf(1f)), longArrayOf(1)).use { speed ->
                        requireNotNull(session).run(mapOf("tokens" to tokens, "style" to speaker, "speed" to speed), setOf("audio"), options).use { result ->
                            val audio = (result[0] as OnnxTensor).floatBuffer
                            check(audio.remaining() in 1..24000 * 60)
                            return ShortArray(audio.remaining()) {
                                val value = audio.get(); check(value.isFinite())
                                (value.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                            }
                        }
                    }
                }
            }
        } finally {
            synchronized(runLock) { activeRun = null; runDeadline = 0; options.close() }
        }
    }

    private fun play(pcm: ShortArray, current: () -> Boolean) {
        if (!current()) return
        val minimum = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, 4800)).build()
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED)
            track.play()
            var offset = 0
            val deadline = SystemClock.elapsedRealtime() + pcm.size * 1000L / 24000 + 5000
            while (offset < pcm.size && current() && SystemClock.elapsedRealtime() < deadline) {
                val written = track.write(pcm, offset, minOf(2400, pcm.size - offset), AudioTrack.WRITE_NON_BLOCKING)
                check(written >= 0)
                offset += written
                if (written == 0) Thread.sleep(5)
            }
            while (track.playbackHeadPosition < offset && current() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
            check(!current() || (offset == pcm.size && track.playbackHeadPosition >= offset)) { "Audio playback stalled" }
        } finally {
            try { track.pause(); track.flush() } finally { track.release() }
        }
    }

    private fun terminateRun() = synchronized(runLock) { try { activeRun?.setTerminate(true) } catch (_: Exception) { } }

    override fun close() {
        closed = true; terminateRun()
        if (!inUse.get()) releaseResources()
    }

    private fun releaseResources() = synchronized(resourcesLock) {
        if (!inUse.get()) {
            session?.close(); session = null; mappedModel = null; voice = null; frontend = null
        }
    }

    private fun normalize(value: String): String {
        val numbers = MessageFormat("{0,spellout}", Locale.SIMPLIFIED_CHINESE)
        fun number(value: String): String {
            val parts = value.split('.', limit = 2)
            val whole = parts[0]
            val spoken = if (whole.length <= 8 && (whole == "0" || !whole.startsWith('0'))) {
                try { numbers.format(arrayOf<Any>(whole.toLong())) } catch (_: IllegalArgumentException) { ChineseSpeechText.digits(whole) }
            } else ChineseSpeechText.digits(whole)
            return spoken + if (parts.size == 2) "点" + ChineseSpeechText.digits(parts[1]) else ""
        }
        var text = Normalizer.normalize(value, Normalizer.Form.NFKC)
        text = Regex("([0-9]+(?:\\.[0-9]+)?)%").replace(text) { "百分之" + number(it.groupValues[1]) }
        text = Regex("[0-9]+(?:\\.[0-9]+)?").replace(text) { number(it.value) }
        val letters = listOf("诶", "比", "西", "迪", "伊", "艾弗", "吉", "艾尺", "爱", "杰", "开", "艾勒", "艾姆", "恩", "欧", "批", "丘", "阿尔", "艾斯", "提", "优", "维", "达布柳", "艾克斯", "歪", "泽德")
        return buildString { for (character in text) append(if (character in 'A'..'Z' || character in 'a'..'z') letters[character.uppercaseChar() - 'A'] else character.toString()) }
    }

    companion object { private const val ASSETS = "tts/kokoro-zh" }
}

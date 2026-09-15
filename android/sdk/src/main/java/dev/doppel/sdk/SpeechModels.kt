package dev.doppel.sdk

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object SpeechModels {
    const val EMBEDDED_NAME = "paraformer-zh-small-int8-2024-03-09"
    const val EMBEDDED_BYTES = 81904027L
    fun embeddedAvailable(context: Context): Boolean = runCatching {
        context.assets.openFd("asr/model.int8.onnx").use { it.length == 81828675L } &&
            context.assets.open("asr/tokens.txt").use { it.read() >= 0 }
    }.getOrDefault(false)
    // Legacy Vosk installer retained for the old public LocalDictation API only.
    const val NAME = "vosk-model-small-cn-0.22"
    const val URL = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
    const val SHA256 = "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba"
    const val DOWNLOAD_BYTES = 43898754L
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val observers = CopyOnWriteArraySet<(State) -> Unit>()
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var state = State("", 0, false)
    data class State(val message: String, val progress: Int, val busy: Boolean)

    fun directory(context: Context) = File(context.noBackupFilesDir, "speech/$NAME")
    fun installed(context: Context) = File(directory(context), ".verified").readTextOrEmpty() == SHA256 && File(directory(context), "am/final.mdl").isFile
    fun observe(context: Context, listener: (State) -> Unit) {
        observers.add(listener)
        listener(if (state.message.isEmpty()) State(if (installed(context)) "中文模型已安装" else "中文模型未安装 · 下载 42 MiB", 0, false) else state)
    }
    fun removeObserver(listener: (State) -> Unit) { observers.remove(listener) }
    private fun publish(message: String, progress: Int = 0, running: Boolean = busy.get()) {
        state = State(message, progress, running)
        main.post { observers.forEach { it(state) } }
    }
    fun cancel() { cancelled.set(true); connection?.disconnect() }
    fun install(context: Context) {
        if (!busy.compareAndSet(false, true)) return
        val app = context.applicationContext
        cancelled.set(false)
        publish("正在下载中文模型", 0, true)
        io.execute {
            val root = File(app.noBackupFilesDir, "speech").apply { mkdirs() }
            val archive = File(root, "download.part")
            val staging = File(root, "install.part")
            try {
                require(root.usableSpace > 160L * 1024 * 1024) { "Insufficient free storage" }
                staging.deleteRecursively()
                val http = URL(URL).openConnection() as HttpURLConnection
                connection = http
                http.connectTimeout = 15000; http.readTimeout = 15000; http.instanceFollowRedirects = false
                require(http.responseCode == 200) { "Model download failed" }
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L; var percentage = -1
                val deadline = System.nanoTime() + 10L * 60 * 1_000_000_000
                http.inputStream.use { input -> archive.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        if (cancelled.get()) throw InterruptedException()
                        require(System.nanoTime() < deadline) { "Model download timed out" }
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= DOWNLOAD_BYTES) { "Unexpected download size" }
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                        val next = (total * 100 / DOWNLOAD_BYTES).toInt()
                        if (next != percentage) { percentage = next; publish("正在下载中文模型 · $next%", next) }
                    }
                } }
                require(total == DOWNLOAD_BYTES && digest.digest().joinToString("") { "%02x".format(it) } == SHA256) { "Model checksum mismatch" }
                publish("正在安装中文模型", 100)
                archive.inputStream().use { SpeechModelArchive.extract(it, staging, 100L * 1024 * 1024) { cancelled.get() } }
                if (cancelled.get()) throw InterruptedException()
                val extracted = File(staging, NAME)
                require(File(extracted, "am/final.mdl").isFile && File(extracted, "conf/model.conf").isFile) { "Incomplete model" }
                File(extracted, ".verified").writeText(SHA256)
                val target = directory(app)
                target.deleteRecursively()
                check(extracted.renameTo(target)) { "Cannot finish model installation" }
                publish("中文模型已安装", 100, false)
            } catch (_: Exception) {
                publish(if (cancelled.get()) "下载已取消" else "安装失败，请检查网络与存储后重试", 0, false)
            } finally {
                connection?.disconnect(); connection = null
                archive.delete(); staging.deleteRecursively(); busy.set(false)
            }
        }
    }
    fun delete(context: Context) {
        cancel()
        val app = context.applicationContext
        io.execute { directory(app).deleteRecursively(); publish("中文模型已删除", 0, false) }
    }
    private fun File.readTextOrEmpty(): String = try { if (isFile) readText() else "" } catch (_: Exception) { "" }
}

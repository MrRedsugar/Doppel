package dev.doppel.sdk

import android.util.AtomicFile
import java.io.File

/** Shared atomic read/write boundary; reads may restore the last committed backup. */
internal class DirectRunStateFile(root: File) {
    private val file = AtomicFile(File(root, "direct-runs-v1.json"))
    companion object { private val lock = Any(); private const val MAX_BYTES = 2 * 1024 * 1024 }

    fun read(): String? = synchronized(lock) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized null
        file.openRead().use { input ->
            check(input.channel.size() <= MAX_BYTES) { "本机任务记录超过上限，请先导出并检查" }
            input.bufferedReader(Charsets.UTF_8).readText()
        }
    }

    fun write(value: String) = synchronized(lock) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_BYTES) { "本机任务记录空间不足" }
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (failure: Exception) { file.failWrite(output); throw failure }
    }
}

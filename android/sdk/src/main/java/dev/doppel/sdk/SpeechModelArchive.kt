package dev.doppel.sdk

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

object SpeechModelArchive {
    fun extract(input: InputStream, directory: File, limit: Long, cancelled: () -> Boolean) {
        val root = directory.canonicalFile
        root.mkdirs()
        var total = 0L
        var entries = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                if (cancelled()) throw InterruptedException("Installation cancelled")
                val entry = zip.nextEntry ?: break
                require(++entries <= 1000) { "Too many model files" }
                require(!entry.name.contains('\\')) { "Invalid archive path" }
                val target = File(root, entry.name).canonicalFile
                require(target.path.startsWith(root.path + File.separator)) { "Archive path escapes model directory" }
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            if (cancelled()) throw InterruptedException("Installation cancelled")
                            val count = zip.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= limit) { "Model exceeds extraction limit" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }
}

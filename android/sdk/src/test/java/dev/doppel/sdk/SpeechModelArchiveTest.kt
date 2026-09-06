package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SpeechModelArchiveTest {
    private fun archive(name: String, text: String): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { it.putNextEntry(ZipEntry(name)); it.write(text.toByteArray()); it.closeEntry() }
    }.toByteArray()

    @Test fun rejectsTraversalAndOversizedExpansion() {
        val root = Files.createTempDirectory("speech-model-test").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) { SpeechModelArchive.extract(ByteArrayInputStream(archive("../escape", "bad")), root, 100) { false } }
            assertThrows(IllegalArgumentException::class.java) { SpeechModelArchive.extract(ByteArrayInputStream(archive("model/data", "12345")), root, 4) { false } }
        } finally { root.deleteRecursively() }
    }

    @Test fun installsOnlyExpectedBytesAndHonorsCancellation() {
        val root = Files.createTempDirectory("speech-model-test").toFile()
        try {
            SpeechModelArchive.extract(ByteArrayInputStream(archive("model/data", "hello")), root, 100) { false }
            assertEquals("hello", root.resolve("model/data").readText())
            assertThrows(InterruptedException::class.java) { SpeechModelArchive.extract(ByteArrayInputStream(archive("other", "never")), root, 100) { true } }
            assertFalse(root.resolve("other").exists())
        } finally { root.deleteRecursively() }
    }
}

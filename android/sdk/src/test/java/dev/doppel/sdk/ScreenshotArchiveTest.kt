package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class ScreenshotArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZxkAAAAASUVORK5CYII=")
    private val run = "direct-run-00000000-0000-0000-0000-000000000001"
    @Test fun savedScreenshotSurvivesReopeningAndDeletesWithRun() {
        val dir = temp.newFolder()
        val item = ScreenshotArchive(dir).save(run, "command-1", png, JSONObject())!!
        val reopened = ScreenshotArchive(dir)
        assertEquals(1, reopened.list(run).getJSONArray("items").length())
        assertArrayEquals(png, reopened.read(run, item.getString("id")))
        reopened.delete(run)
        assertEquals(0, reopened.list(run).getJSONArray("items").length())
        assertThrows(IllegalStateException::class.java) { reopened.read(run, item.getString("id")) }
    }
    @Test fun differentRunsCannotReadEachOthersScreenshot() {
        val archive = ScreenshotArchive(temp.newFolder())
        val item = archive.save(run, "command-1", png, JSONObject())!!
        assertThrows(IllegalStateException::class.java) { archive.read("direct-run-other", item.getString("id")) }
        listOf("../secret", "..", "a/b", "a\\b").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { archive.list(bad) }
            assertThrows(IllegalArgumentException::class.java) { archive.read(run, bad) }
        }
    }
    @Test fun repeatedCommandDoesNotDuplicateEvidence() {
        val archive = ScreenshotArchive(temp.newFolder())
        val a = archive.save(run, "command-1", png, JSONObject())!!
        val b = archive.save(run, "command-1", png, JSONObject())!!
        assertEquals(a.getString("id"), b.getString("id"))
        assertEquals(1, archive.list(run).getJSONArray("items").length())
    }
    @Test fun countSpaceAndAgeAreBounded() {
        var time = 100000L
        val archive = ScreenshotArchive(temp.newFolder(), { time }, maxBytes = 4096, maxPerRun = 2)
        repeat(3) { archive.save(run, "command-$it", png, JSONObject()); time++ }
        val items = archive.list(run).getJSONArray("items")
        assertEquals(2, items.length())
        assertEquals("command-1", items.getJSONObject(0).getString("command_id"))
        time += 86400001
        archive.prune(1)
        assertEquals(0, archive.list(run).getJSONArray("items").length())
    }
    @Test fun corruptImageFailsClearlyAndUnrelatedFilesRemain() {
        val dir = temp.newFolder()
        val marker = File(dir, "unrelated.txt").apply { writeText("retain") }
        val archive = ScreenshotArchive(dir)
        assertThrows(IllegalArgumentException::class.java) { archive.save(run, "bad", byteArrayOf(1,2,3), JSONObject()) }
        val item = archive.save(run, "command-1", png, JSONObject())!!
        File(File(dir, run), item.getString("id") + ".png").writeBytes(byteArrayOf(1))
        assertThrows(IllegalStateException::class.java) { archive.read(run, item.getString("id")) }
        archive.prune(7, emptySet())
        assertEquals("retain", marker.readText())
    }

    @Test fun missingCreatedAtDoesNotBreakOtherRecordsAndItsFilesArePruned() {
        val dir = temp.newFolder()
        val archive = ScreenshotArchive(dir)
        val damaged = archive.save(run, "command-1", png, JSONObject())
        val good = archive.save(run, "command-2", png, JSONObject())
        val record = File(File(dir, run), damaged.getString("id") + ".json")
        val invalid = JSONObject(record.readText()).apply { remove("created_at") }
        record.writeText(invalid.toString())
        assertEquals(1, archive.list(run).getJSONArray("items").length())
        assertTrue(assertThrows(IllegalStateException::class.java) { archive.read(run, damaged.getString("id")) }.message.orEmpty().contains("损坏"))
        archive.prune(7)
        assertFalse(record.exists())
        assertFalse(File(File(dir, run), damaged.getString("id") + ".png").exists())
        assertArrayEquals(png, archive.read(run, good.getString("id")))
    }

    @Test fun invalidMetadataTypesAreIsolatedAndCannotHideOrphanImages() {
        val dir = temp.newFolder()
        val archive = ScreenshotArchive(dir)
        listOf("created_at" to "broken", "size" to -1, "sha256" to "broken", "width" to 0).forEachIndexed { index, (field, value) ->
            val item = archive.save(run, "command-$index", png, JSONObject())
            val record = File(File(dir, run), item.getString("id") + ".json")
            record.writeText(JSONObject(record.readText()).put(field, value).toString())
            assertEquals(0, archive.list(run).getJSONArray("items").length())
            archive.prune(7)
            assertFalse(record.exists())
            assertFalse(File(File(dir, run), item.getString("id") + ".png").exists())
        }
    }

    @Test fun interruptedAndUnpairedFilesAreRemovedWithinTheQuotaWithOneArchiveScan() {
        val dir = temp.newFolder()
        var scans = 0
        val countedRoot = object : File(dir.path) {
            override fun listFiles(): Array<File>? { scans++; return super.listFiles() }
        }
        val folder = File(dir, run).apply { mkdirs() }
        val orphan = File(folder, "orphan.png").apply { writeBytes(ByteArray(3000)) }
        val partial = File(folder, "capture-interrupted.part").apply { writeBytes(ByteArray(3000)) }
        val metadataOnly = File(folder, "metadata-only.json").apply { writeText("{}") }
        val marker = File(folder, "notes.txt").apply { writeText("retain") }
        val archive = ScreenshotArchive(countedRoot, maxBytes = 1024)
        val item = archive.save(run, "command-1", png, JSONObject())
        assertEquals(1, scans)
        assertFalse(orphan.exists()); assertFalse(partial.exists()); assertFalse(metadataOnly.exists())
        assertTrue(ownedBytes(dir) <= 1024)
        assertArrayEquals(png, archive.read(run, item.getString("id")))
        assertEquals("retain", marker.readText())
    }

    @Test fun globalQuotaCountsActualImagesAndMetadataAcrossRuns() {
        val dir = temp.newFolder()
        var time = 100000L
        val archive = ScreenshotArchive(dir, { time++ })
        val first = archive.save("run-a", "command-1", png, JSONObject())
        val oneRecordBytes = ownedBytes(dir)
        val second = archive.save("run-b", "command-2", png, JSONObject())
        val record = File(File(dir, "run-a"), first.getString("id") + ".json")
        record.writeText(JSONObject(record.readText()).put("size", 33).toString())
        val bounded = ScreenshotArchive(dir, { time }, maxBytes = oneRecordBytes + 32)
        bounded.prune(0)
        assertTrue(ownedBytes(dir) <= oneRecordBytes + 32)
        assertEquals(0, bounded.list("run-a").getJSONArray("items").length())
        assertArrayEquals(png, bounded.read("run-b", second.getString("id")))
    }

    @Test fun quotaAppliesToNewEntriesAndDeletionKeepsTheOtherRunIntact() {
        val dir = temp.newFolder()
        var time = 100000L
        val initial = ScreenshotArchive(dir, { time++ })
        initial.save("run-a", "command-1", png, JSONObject())
        initial.save("run-b", "command-2", png, JSONObject())
        val limit = ownedBytes(dir)
        val bounded = ScreenshotArchive(dir, { time++ }, maxBytes = limit)
        val latest = bounded.save("run-c", "command-3", png, JSONObject())
        assertTrue(ownedBytes(dir) <= limit)
        assertEquals(0, bounded.list("run-a").getJSONArray("items").length())
        bounded.delete("run-b")
        assertArrayEquals(png, bounded.read("run-c", latest.getString("id")))
        assertThrows(IllegalArgumentException::class.java) { bounded.delete("../run-c") }
        assertArrayEquals(png, bounded.read("run-c", latest.getString("id")))
    }

    @Test fun anOversizeRecordDoesNotDeletePreviouslySavedEvidence() {
        val dir = temp.newFolder()
        val initial = ScreenshotArchive(dir)
        val item = initial.save(run, "command-1", png, JSONObject())
        val tooSmall = ScreenshotArchive(dir, maxBytes = png.size.toLong())
        assertThrows(IllegalArgumentException::class.java) { tooSmall.save(run, "command-2", png, JSONObject()) }
        assertArrayEquals(png, initial.read(run, item.getString("id")))
    }

    @Test fun sevenDayRetentionPreservesItsBoundaryAndRemovesExpiredEvidence() {
        var time = 1_000_000_000L
        val archive = ScreenshotArchive(temp.newFolder(), { time })
        val item = archive.save(run, "command-1", png, JSONObject())
        time += 7 * 86400000L
        archive.prune(7)
        assertArrayEquals(png, archive.read(run, item.getString("id")))
        time++
        archive.prune(7)
        assertEquals(0, archive.list(run).getJSONArray("items").length())
    }

    @Test fun saveAppliesRetentionAndValidRunsTogetherIncludingDuplicateCommands() {
        var time = 1_000_000_000L
        val archive = ScreenshotArchive(temp.newFolder(), { time })
        archive.save("run-deleted", "command-deleted", png, JSONObject())
        archive.save(run, "command-old", png, JSONObject())
        time++
        val boundary = archive.save(run, "command-current", png, JSONObject())
        time += 7 * 86400000L
        val duplicate = archive.save(run, "command-current", png, JSONObject(), 7, setOf(run))
        assertEquals(boundary.toString(), duplicate.toString())
        assertEquals(0, archive.list("run-deleted").getJSONArray("items").length())
        assertEquals(1, archive.list(run).getJSONArray("items").length())
        assertArrayEquals(png, archive.read(run, boundary.getString("id")))
        time++
        val renewed = archive.save(run, "command-current", png, JSONObject(), 7, setOf(run))
        assertEquals(time, renewed.getLong("created_at"))
        assertEquals(1, archive.list(run).getJSONArray("items").length())
        assertThrows(IllegalArgumentException::class.java) {
            archive.save("run-deleted", "command-new", png, JSONObject(), 7, setOf(run))
        }
        assertArrayEquals(png, archive.read(run, renewed.getString("id")))
    }

    @Test fun failedMetadataWriteKeepsPreviousEvidenceAndNextSaveCanRecover() {
        val dir = temp.newFolder()
        val archive = ScreenshotArchive(dir)
        val previous = archive.save(run, "command-1", png, JSONObject())
        val id = java.security.MessageDigest.getInstance("SHA-256").digest("command-2".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
        val blocked = File(File(dir, run), "$id.json").apply { mkdir() }
        assertThrows(Exception::class.java) { archive.save(run, "command-2", png, JSONObject()) }
        assertFalse(File(File(dir, run), "$id.png").exists())
        assertArrayEquals(png, archive.read(run, previous.getString("id")))
        assertTrue(blocked.delete())
        val recovered = archive.save(run, "command-2", png, JSONObject())
        assertArrayEquals(png, archive.read(run, recovered.getString("id")))
        assertEquals(2, archive.list(run).getJSONArray("items").length())
    }

    private fun ownedBytes(dir: File) = dir.walkTopDown().filter { it.isFile && it.extension in setOf("png", "json", "part") }.sumOf { it.length() }
}

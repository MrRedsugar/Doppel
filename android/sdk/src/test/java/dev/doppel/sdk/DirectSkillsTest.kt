package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DirectSkillsTest {
    private fun skill(name: String = "test-skill", body: String = "Read references/guide.md only when needed.") =
        "---\nname: $name\ndescription: >\n  Learn fixture knowledge\n  when testing skills.\nplatforms: [android]\nversion: '2026.09.08.1'\n---\n$body"
    private fun store(builtins: Map<String, Map<String, ByteArray>> = emptyMap()) =
        DirectSkillStore(Files.createTempDirectory("direct-skills-").toFile(), builtins)
    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { stream -> files.forEach { (name, text) -> stream.putNextEntry(ZipEntry(name)); stream.write(text.toByteArray()); stream.closeEntry() } }
        return bytes.toByteArray()
    }
    @Test fun catalogueOnlyDescribesAndLoadingIsVersionedUntrustedData() {
        val store = store()
        val imported = store.importPackage(skill().toByteArray(), "markdown")
        val item = store.list().getJSONArray("items").getJSONObject(0)
        assertFalse(item.has("instructions")); assertFalse(item.getBoolean("trusted"))
        assertTrue(item.getString("description").length <= 200)
        val loaded = store.read("test-skill", item.getString("revision"))
        assertTrue(loaded.getBoolean("found")); assertTrue(loaded.getString("instructions").contains("references/guide.md"))
        assertEquals(imported.getString("revision"), loaded.getString("revision"))
        assertFalse(store.read("test-skill", "stale-revision").getBoolean("found"))
        assertFalse(store.read("../outside").getBoolean("found"))
    }
    @Test fun appScopeAndAliasesSurviveCatalogueParsingWithoutGrantingPermissions() {
        val store = store()
        store.importPackage("---\nname: scoped\ndescription: sheet\npackages: [cn.wps.moffice_eng]\napp_aliases: [WPS]\naliases: [WPS, 表格]\npermissions: [full]\n---\nCheck the current sheet.".toByteArray(), "markdown")
        val item = store.list().getJSONArray("items").getJSONObject(0)
        assertEquals("cn.wps.moffice_eng", item.optJSONArray("packages")?.optString(0))
        assertEquals("表格", item.optJSONArray("aliases")?.optString(1))
        assertEquals("WPS", item.optJSONArray("app_aliases")?.optString(0))
        for (invalid in listOf("['']", "[7]", "WPS")) {
            assertThrows(IllegalArgumentException::class.java) {
                store().importPackage("---\nname: identity-check\ndescription: example\napp_aliases: $invalid\n---\nBody".toByteArray(), "markdown")
            }
        }
        assertFalse(item.getBoolean("trusted")); assertFalse(item.has("permissions"))
        for (packages in listOf("['']", "[com.example.*]")) {
            assertThrows(IllegalArgumentException::class.java) {
                store().importPackage("---\nname: bad-scope\ndescription: example\npackages: $packages\n---\nBody".toByteArray(), "markdown")
            }
        }
    }
    @Test fun searchableCatalogueReachesItemsBeyondTheFirstTwenty() {
        val builtins = (0..24).associate { index ->
            val name = "fixture-%02d".format(index)
            name to mapOf("SKILL.md" to skill(name).replace("platforms: [android]", "aliases: [topic$index]\nplatforms: [android]").toByteArray())
        }
        val store = store(builtins)
        assertEquals(25, store.list().getJSONArray("items").length())
        val first = store.list("", 0, 20)
        assertEquals(20, first.getJSONArray("items").length())
        val last = store.list("", first.getInt("next_offset"), 20)
        assertEquals("fixture-24", last.getJSONArray("items").getJSONObject(4).getString("name"))
        assertTrue(last.isNull("next_offset"))
        assertEquals("fixture-24", store.list("topic24").getJSONArray("items").getJSONObject(0).getString("name"))
        assertEquals(0, store.list("no-such-subject").getInt("total"))
    }
    @Test fun bodyAndResourcesCanBeContinuedWithoutLosingRevisionChecks() {
        val store = store()
        val text = "a".repeat(5000) + "目标在这里" + "b".repeat(8000)
        store.importPackage(zip("SKILL.md" to skill(body = text), "references/guide.md" to text), "zip")
        val revision = store.list().getJSONArray("items").getJSONObject(0).getString("revision")
        assertEquals(5000, store.read("test-skill", revision, 0, 5000).getInt("next_offset"))
        assertEquals("目标在这里", store.read("test-skill", revision, 5000, 5).getString("instructions"))
        val resource = store.resource("test-skill", "references/guide.md", revision, 5000, 5)
        assertEquals("目标在这里", resource.getString("content")); assertEquals(13005, resource.getInt("total_chars"))
        assertEquals("references/guide.md", resource.getString("path")); assertEquals("imported", resource.getString("source"))
        assertEquals("", store.read("test-skill", revision, 99999, 10).getString("instructions"))
        assertFalse(store.resource("test-skill", "references/guide.md", "old", 5000, 5).getBoolean("found"))
        assertEquals("invalid_skill_range", store.read("test-skill", revision, -1, 5).getString("error"))
        assertEquals("invalid_skill_range", store.resource("test-skill", "references/guide.md", revision, 0, 0).getString("error"))
    }
    @Test fun zipResourcesAreLazyBoundedAndNeverExecuted() {
        val store = store()
        store.importPackage(zip("bundle/SKILL.md" to skill(), "bundle/references/guide.md" to "fixture detail", "bundle/scripts/echo.sh" to "echo never-executed"), "zip")
        assertEquals("fixture detail", store.resource("test-skill", "references/guide.md").getString("content"))
        assertFalse(store.resource("test-skill", "scripts/echo.sh").getBoolean("found"))
        assertFalse(store.resource("test-skill", "../../outside").getBoolean("found"))
        assertFalse(store.resource("test-skill", "missing.md").getBoolean("found"))
    }
    @Test fun invalidYamlAndPermissionDeclarationsCannotGrantAuthority() {
        for (meta in listOf("name: test-skill\nname: duplicate\ndescription: x", "name: test-skill\ndescription: &anchor value\nextra: *anchor", "!!java.lang.Runtime {}")) {
            assertThrows(IllegalArgumentException::class.java) { store().importPackage("---\n$meta\n---\nbody".toByteArray(), "markdown") }
        }
        val store = store()
        store.importPackage("---\nname: test-skill\ndescription: fixture\npermissions: [full, payment]\n---\nInstructions are data.".toByteArray(), "markdown")
        val item = store.read("test-skill")
        assertFalse(item.getBoolean("trusted")); assertFalse(item.has("permissions"))
    }
    @Test fun traversalDuplicateFilesOversizeAndConflictsFailWithoutPartialPublication() {
        val store = store()
        for (bytes in listOf(zip("SKILL.md" to skill(), "../escape.md" to "bad"),
                zip("SKILL.md" to skill(), "guide.md" to "one", "GUIDE.md" to "two"),
                zip("SKILL.md" to skill(), "large.md" to "x".repeat(65537)))) {
            assertThrows(IllegalArgumentException::class.java) { store.importPackage(bytes, "zip") }
        }
        assertEquals(0, store.list().getJSONArray("items").length())
        store.importPackage(skill().toByteArray(), "markdown")
        assertThrows(IllegalArgumentException::class.java) { store.importPackage(skill().toByteArray(), "markdown") }
        store.delete("test-skill")
        assertEquals(0, store.list().getJSONArray("items").length())
    }
    @Test fun builtinCannotBeReplacedAndLongBodyReportsTruncation() {
        val store = store(mapOf("test-skill" to mapOf("SKILL.md" to skill(body = "x".repeat(13000)).toByteArray())))
        val loaded = store.read("test-skill")
        assertEquals("bundled", loaded.getString("source")); assertTrue(loaded.getBoolean("truncated"))
        assertEquals(12000, loaded.getString("instructions").length)
        assertEquals("2026.09.08.1", loaded.getString("included_source_version"))
        assertThrows(IllegalArgumentException::class.java) { store.delete("test-skill") }
        assertThrows(IllegalArgumentException::class.java) { store.importPackage(skill().toByteArray(), "markdown") }
    }
    @Test fun invalidUtf8AndOverlongNamesCannotBecomeSkills() {
        val store = store()
        assertThrows(IllegalArgumentException::class.java) { store.importPackage(byteArrayOf(0xc3.toByte(), 0x28), "markdown") }
        assertThrows(IllegalArgumentException::class.java) { store.importPackage(skill(name = "A".repeat(51)).toByteArray(), "markdown") }
    }
    @Test fun zipLinksSpecialFilesAndEncryptionAreRejectedBeforePublication() {
        val store = store()
        for (kind in listOf("symlink", "fifo", "encrypted")) {
            val bytes = zip("SKILL.md" to skill())
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val central = (0..bytes.size - 46).first { buffer.getInt(it) == 0x02014b50 }
            when (kind) {
                "symlink" -> buffer.putInt(central + 38, 0xa0000000.toInt())
                "fifo" -> buffer.putInt(central + 38, 0x10000000)
                "encrypted" -> buffer.putShort(central + 8, (buffer.getShort(central + 8).toInt() or 1).toShort())
            }
            assertThrows(IllegalArgumentException::class.java) { store.importPackage(bytes, "zip") }
            assertEquals("Unsafe archive must not publish a package", 0, store.list().getJSONArray("items").length())
        }
        store.importPackage(skill().toByteArray(), "markdown")
        assertTrue("Rejected archives must leave the store usable", store.read("test-skill").getBoolean("found"))
    }
}

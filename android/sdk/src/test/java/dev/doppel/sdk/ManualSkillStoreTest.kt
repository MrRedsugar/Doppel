package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipInputStream

class ManualSkillStoreTest {
    private fun draft() = JSONObject("""{"title":"进入设置","description":"需要进入示例应用设置时参考","steps":[{"instruction":"找到设置入口并打开","expected":"设置页标题可见","evidence_ids":["ev-1"]}],"limitations":["只观察过一次"]}""")
    private fun evidence() = JSONObject("""{"events":[{"id":"ev-1","kind":"observed_click","label":"设置"}],"screenshots":[{"image_base64":"private-pixels"}]}""")
    @Test fun savedSkillSurvivesRestartAndExportsWithoutImagePayload() {
        val root = Files.createTempDirectory("manual-skill-").toFile()
        val store = ManualSkillStore(root); val name = store.save(draft(), evidence()).getString("name")
        val read = ManualSkillStore(root).read(name)
        assertTrue(read.getBoolean("found")); assertFalse(read.getJSONObject("evidence").has("screenshots"))
        val files = mutableMapOf<String, String>()
        ZipInputStream(store.export(name).inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) { files[entry.name] = zip.readBytes().toString(Charsets.UTF_8); entry = zip.nextEntry }
        }
        assertEquals(setOf("SKILL.md", "references/evidence.json"), files.keys)
        assertTrue(files.getValue("SKILL.md").startsWith("---\nname: $name\ndescription:"))
        assertFalse(files.values.any { it.contains("private-pixels") })
    }
    @Test fun staleEditCannotOverwriteNewReviewAndDeleteRemovesCatalogueEntry() {
        val store = ManualSkillStore(Files.createTempDirectory("manual-skill-").toFile())
        val original = store.save(draft(), evidence()); val name = original.getString("name")
        store.save(draft().put("title", "更新标题"), evidence(), name, original.getString("revision"))
        assertThrows(IllegalArgumentException::class.java) { store.save(draft(), evidence(), name, original.getString("revision")) }
        assertEquals("更新标题", store.read(name).getString("title"))
        store.delete(name); assertEquals(0, store.list().getJSONArray("items").length()); assertFalse(store.read(name).getBoolean("found"))
    }
    @Test fun oversizedExportIsRejectedBeforePublishing() {
        val store = ManualSkillStore(Files.createTempDirectory("manual-skill-").toFile())
        val value = draft(); val steps = value.getJSONArray("steps")
        repeat(23) { steps.put(JSONObject(steps.getJSONObject(0).toString()).put("instruction", "界面".repeat(900))) }
        assertThrows(IllegalArgumentException::class.java) { store.save(value, evidence()) }
        assertEquals(0, store.list().getJSONArray("items").length())
    }
    @Test fun disabledSkillStaysInspectableButCannotLoadForModel() {
        val store = ManualSkillStore(Files.createTempDirectory("manual-skill-").toFile())
        val name = store.save(draft(), evidence()).getString("name")
        store.setEnabled(name, false)
        assertFalse(store.read(name).getBoolean("found"))
        assertTrue(store.read(name, inspect = true).getBoolean("found"))
        store.setEnabled(name, true)
        assertTrue(store.read(name).getBoolean("found"))
    }
}

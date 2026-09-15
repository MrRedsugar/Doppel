package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Local human-reviewed semantic Skills. Pending drafts never appear in the model catalogue. */
class ManualSkillStore(root: File) {
    companion object { private val locks = ConcurrentHashMap<String, Any>() }
    private val root = root.canonicalFile
    private val lock = locks.computeIfAbsent(this.root.path) { Any() }
    init { require(!Files.isSymbolicLink(root.toPath()) && (this.root.isDirectory || this.root.mkdirs())) }
    private fun file(name: String): File {
        require(name.matches(Regex("manual-[a-f0-9-]{36}")))
        return File(root, "$name.json").also { require(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile == it.absoluteFile) }
    }
    private fun load(name: String): JSONObject {
        val f = file(name); require(f.length() in 1..524288)
        return JSONObject(f.readText()).also { SkillDraft.validate(it.getJSONObject("draft"), SkillDraft.evidenceIds(it.getJSONObject("evidence"))) }
    }
    private fun describe(value: JSONObject): JSONObject = JSONObject().put("name", value.getString("name"))
        .put("title", value.getJSONObject("draft").getString("title")).put("description", value.getJSONObject("draft").getString("description"))
        .put("source", "manual").put("trusted", false).put("enabled", value.optBoolean("enabled", true)).put("revision", LearningTrace.hash(value.toString()))
        .put("resources", JSONArray(listOf("references/evidence.json")))
    fun save(draft: JSONObject, evidence: JSONObject, existing: String? = null, expectedRevision: String? = null): JSONObject = synchronized(lock) {
        val checked = SkillDraft.validate(draft, SkillDraft.evidenceIds(evidence)); val name = existing ?: "manual-${UUID.randomUUID()}"
        if (existing != null) require(describe(load(name)).getString("revision") == expectedRevision) { "这份 Skill 已变化，请重新打开" }
        else require(root.listFiles().orEmpty().count { it.name.endsWith(".json") } < 50) { "最多保存 50 份手动 Skill" }
        val safeEvidence = JSONObject(evidence.toString()).apply { remove("screenshots"); remove("generated_draft") }
        require(SkillDraft.markdown(name, checked).toByteArray(Charsets.UTF_8).size <= 65536 &&
            safeEvidence.toString(2).toByteArray(Charsets.UTF_8).size <= 65536) { "Skill 或来源超过 64 KB，请缩短描述以便兼容导出" }
        val value = JSONObject().put("name", name).put("draft", checked).put("evidence", safeEvidence).put("saved_at_ms", System.currentTimeMillis())
            .put("enabled", if (existing != null) load(existing).optBoolean("enabled", true) else true)
        write(value)
        describe(value)
    }
    private fun write(value: JSONObject) {
        val bytes = value.toString().toByteArray(); require(bytes.size <= 524288)
        val target = file(value.getString("name")); val stage = Files.createTempFile(root.toPath(), ".manual-", ".tmp").toFile()
        try { stage.outputStream().use { it.write(bytes); it.fd.sync() }; Files.move(stage.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        finally { if (stage.exists()) stage.delete() }
    }
    fun setEnabled(name: String, enabled: Boolean): JSONObject = synchronized(lock) {
        val value = load(name).put("enabled", enabled); write(value); describe(value)
    }
    fun list(): JSONObject = synchronized(lock) {
        val items = JSONArray(); val errors = JSONArray()
        root.listFiles().orEmpty().filter { it.name.endsWith(".json") }.forEach { file ->
            runCatching { describe(load(file.name.removeSuffix(".json"))) }.fold({ items.put(it) }, { errors.put(file.name) })
        }
        JSONObject().put("items", items).put("errors", errors)
    }
    fun read(name: String, revision: String? = null, inspect: Boolean = false): JSONObject = synchronized(lock) {
        try {
            val value = load(name); val item = describe(value)
            if (!inspect && !value.optBoolean("enabled", true)) JSONObject().put("found", false).put("error", "skill_disabled")
            else if (revision != null && item.getString("revision") != revision) JSONObject().put("found", false).put("error", "skill_changed_refresh_catalogue")
            else item.put("found", true).put("instructions", SkillDraft.markdown(name, value.getJSONObject("draft"))).put("draft", value.getJSONObject("draft")).put("evidence", value.getJSONObject("evidence"))
        } catch (_: Exception) { JSONObject().put("found", false).put("error", "skill_missing_or_invalid") }
    }
    fun delete(name: String) = synchronized(lock) { require(file(name).delete()) }
    fun export(name: String): ByteArray = synchronized(lock) {
        val item = read(name, inspect = true); require(item.optBoolean("found")); val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            mapOf("SKILL.md" to item.getString("instructions"), "references/evidence.json" to item.getJSONObject("evidence").toString(2)).forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }; out.toByteArray()
    }
}

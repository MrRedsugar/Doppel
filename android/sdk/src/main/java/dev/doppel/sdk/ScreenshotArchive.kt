package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Bounded private screenshots. Files are separate from task JSON and never backed up. */
internal class ScreenshotArchive(
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = 64L * 1024 * 1024,
    private val maxPerRun: Int = 30
) {
    init { require(maxBytes > 0 && maxPerRun > 0) }
    private val safeId = Regex("[A-Za-z0-9_-]{1,120}")
    private val sha256 = Regex("[0-9a-f]{64}")
    private val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    private fun checked(value: String): String { require(safeId.matches(value)) { "截图标识无效" }; return value }
    private fun folder(run: String) = File(root, checked(run)).also {
        require(it.canonicalFile == File(root.canonicalFile, run)) { "截图路径无效" }
    }
    private fun file(run: String, id: String, extension: String) = File(folder(run), checked(id) + extension).also {
        require(it.canonicalFile == File(folder(run).canonicalFile, id + extension)) { "截图路径无效" }
    }
    private fun dimensions(bytes: ByteArray): Pair<Int, Int> {
        require(bytes.size in 33..(5 * 1024 * 1024) && bytes.copyOfRange(0, 8).contentEquals(signature) &&
            String(bytes, 12, 4, Charsets.US_ASCII) == "IHDR") { "截图图像无效" }
        val width = ByteBuffer.wrap(bytes, 16, 4).int
        val height = ByteBuffer.wrap(bytes, 20, 4).int
        require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 32_000_000) { "截图尺寸无效" }
        return width to height
    }
    private fun digest(bytes: ByteArray): String {
        val hashed = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = "0123456789abcdef"
        return CharArray(hashed.size * 2) { index ->
            val value = hashed[index / 2].toInt() and 255
            hex[if (index % 2 == 0) value ushr 4 else value and 15]
        }.concatToString()
    }
    private fun atomic(target: File, bytes: ByteArray) {
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "截图目录不可用" }
        val temporary = File.createTempFile("capture-", ".part", target.parentFile)
        try {
            temporary.outputStream().use { out -> out.write(bytes); out.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }
    private fun ownedFiles(run: String): List<File> = folder(run).listFiles().orEmpty().filter {
        it.extension in setOf("png", "json", "part") && safeId.matches(it.nameWithoutExtension) &&
            runCatching { file(run, it.nameWithoutExtension, "." + it.extension).isFile }.getOrDefault(false)
    }
    private fun metadata(run: String, entry: File): JSONObject? = runCatching {
            check(entry.isFile && entry.length() in 1L..4096L)
            check(entry.canonicalFile == file(run, entry.nameWithoutExtension, ".json").canonicalFile)
            val value = JSONObject(entry.readText())
            fun number(key: String): Long {
                val raw = value.opt(key)
                check(raw is Long || raw is Int)
                return (raw as Number).toLong()
            }
            val id = value.getString("id")
            val command = value.getString("command_id")
            check(id == entry.nameWithoutExtension && value.getString("run_id") == run)
            check(safeId.matches(command) && id == digest(command.toByteArray(Charsets.UTF_8)).take(32))
            check(number("created_at") >= 0 && number("size") in 33..5L * 1024 * 1024)
            val width = number("width"); val height = number("height")
            check(width in 1L..8192L && height in 1L..8192L && width * height <= 32_000_000)
            check(sha256.matches(value.getString("sha256")) && value.getString("mime_type") == "image/png")
            check(value.getString("url") == "/runs/$run/screenshots/$id")
            check(file(run, id, ".png").isFile)
            value
        }.getOrNull()
    private fun entries(run: String): List<JSONObject> = ownedFiles(run).filter { it.extension == "json" }
        .mapNotNull { metadata(run, it) }.sortedBy { it.getLong("created_at") }
    private fun runFolders() = root.listFiles().orEmpty().filter {
        it.isDirectory && safeId.matches(it.name) && runCatching { folder(it.name).canonicalFile == it.canonicalFile }.getOrDefault(false)
    }
    @Synchronized fun save(run: String, command: String, bytes: ByteArray, metadata: JSONObject): JSONObject {
        checked(command)
        val (width, height) = dimensions(bytes)
        require(bytes.size <= maxBytes) { "截图超过存储上限" }
        val id = digest(command.toByteArray(Charsets.UTF_8)).take(32)
        val item = JSONObject().put("id", id).put("run_id", run).put("command_id", command)
            .put("created_at", now()).put("size", bytes.size).put("mime_type", "image/png")
            .put("width", width).put("height", height).put("sha256", digest(bytes))
            .put("url", "/runs/$run/screenshots/$id")
        metadata.optString("capture_id").takeIf { safeId.matches(it) }?.let { item.put("capture_id", it) }
        val recordBytes = item.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() + recordBytes.size <= maxBytes) { "截图及记录超过存储上限" }
        // Interrupted writes cannot accumulate outside the quota between successful saves.
        maintain(0, null)
        entries(run).firstOrNull { it.getString("id") == id }?.let { return JSONObject(it.toString()) }
        try {
            atomic(file(run, id, ".png"), bytes)
            atomic(file(run, id, ".json"), recordBytes)
        } catch (failure: Exception) {
            runCatching { delete(run, id) }
            throw failure
        }
        maintain(0, null, run to id)
        return JSONObject(item.toString())
    }
    @Synchronized fun list(run: String): JSONObject = JSONObject().put("items", JSONArray(entries(run)))
        .put("retention", "bounded_local").put("max_per_run", maxPerRun).put("max_bytes", maxBytes)
    /** Aggregate metadata only; image bytes are never opened. */
    @Synchronized fun usageSummary(): JSONObject {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getDefault() }
        val byDay = linkedMapOf<String, Long>(); var total = 0L
        runFolders().forEach { folder -> entries(folder.name).forEach { item ->
            val day = format.format(java.util.Date(item.optLong("created_at", 0L))); byDay[day] = (byDay[day] ?: 0L) + 1; total++
        } }
        val daily = JSONArray(byDay.toSortedMap().entries.toList().takeLast(30).map { JSONObject().put("date", it.key).put("screenshots", it.value) })
        return JSONObject().put("screenshots", total).put("daily", daily)
    }
    @Synchronized fun read(run: String, id: String): ByteArray {
        checked(id)
        val record = file(run, id, ".json")
        val image = file(run, id, ".png")
        check(record.exists() || image.exists()) { "截图不存在或已过期" }
        val item = metadata(run, record) ?: error("截图文件或记录损坏")
        check(image.length() in 33..5L * 1024 * 1024 && image.length() == item.getLong("size")) { "截图文件损坏" }
        val bytes = image.readBytes()
        check(digest(bytes) == item.getString("sha256")) { "截图文件损坏" }
        dimensions(bytes)
        return bytes
    }
    @Synchronized fun delete(run: String, id: String? = null) {
        val files = if (id == null) ownedFiles(run) else listOf(file(run, id, ".png"), file(run, id, ".json"))
        files.forEach { removeOwned(run, it) }
        if (folder(run).listFiles()?.isEmpty() == true) folder(run).delete()
    }
    @Synchronized fun prune(days: Int, validRuns: Set<String>? = null) {
        maintain(days, validRuns)
    }
    private fun removeOwned(run: String, value: File) {
        check(!value.exists() || (value.isFile && value.canonicalFile == file(run, value.nameWithoutExtension, "." + value.extension).canonicalFile && value.delete())) { "截图删除失败" }
    }
    private fun footprint(item: JSONObject): Long = file(item.getString("run_id"), item.getString("id"), ".png").length() +
        file(item.getString("run_id"), item.getString("id"), ".json").length()

    private fun maintain(days: Int, validRuns: Set<String>?, preserve: Pair<String, String>? = null) {
        require(days in setOf(0, 1, 7, 30, 90)) { "截图保留时间无效" }
        val cutoff = if (days == 0) Long.MIN_VALUE else now() - days * 86400000L
        val order = compareBy<JSONObject> { (it.getString("run_id") to it.getString("id")) == preserve }
            .thenBy { it.getLong("created_at") }
        val retained = mutableListOf<JSONObject>()
        for (folder in runFolders()) {
            if (validRuns != null && folder.name !in validRuns) { delete(folder.name); continue }
            val records = entries(folder.name).filter { it.getLong("created_at") >= cutoff }.sortedWith(order)
            val keep = records.takeLast(maxPerRun)
            val ids = keep.map { it.getString("id") }.toSet()
            // A JSON filename alone is not evidence of a complete, valid committed pair.
            ownedFiles(folder.name).filter { it.extension == "part" || it.nameWithoutExtension !in ids }
                .forEach { removeOwned(folder.name, it) }
            retained.addAll(keep)
            if (folder.listFiles()?.isEmpty() == true) folder.delete()
        }
        // The limit covers actual PNG + JSON files, including a damaged image's actual size.
        var total = retained.sumOf { footprint(it) }
        for (old in retained.sortedWith(order)) {
            if (total <= maxBytes) break
            val size = footprint(old)
            delete(old.getString("run_id"), old.getString("id")); total -= size
        }
    }
}

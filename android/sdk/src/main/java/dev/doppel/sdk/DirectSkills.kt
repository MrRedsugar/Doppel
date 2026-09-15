package dev.doppel.sdk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.events.AliasEvent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/** Public local knowledge adapter. It cannot execute code or grant host capabilities. */
class DirectSkills(context: Context) {
    companion object { private val stores = ConcurrentHashMap<String, DirectSkillStore>() }
    private val context = context.applicationContext
    private val store: DirectSkillStore = run {
        val folder = File(this.context.noBackupFilesDir, "direct-skills-v1")
        stores.computeIfAbsent(folder.absolutePath) {
            val bundled = linkedMapOf<String, Map<String, ByteArray>>()
            val assets = this.context.assets
            for (name in assets.list("skills").orEmpty().sorted()) {
                val files = linkedMapOf<String, ByteArray>()
                fun visit(relative: String, depth: Int) {
                    require(depth <= 8 && files.size <= 100)
                    val assetPath = "skills/$name" + if (relative.isBlank()) "" else "/$relative"
                    val children = assets.list(assetPath).orEmpty()
                    if (children.isNotEmpty()) children.sorted().forEach { visit(if (relative.isBlank()) it else "$relative/$it", depth + 1) }
                    else if (relative.isNotBlank()) files[relative] = assets.open(assetPath).use { DirectSkillStore.readBounded(it, 65536) }
                }
                visit("", 0); bundled[name] = files
            }
            DirectSkillStore(folder, bundled)
        }
    }
    // Application learning was retired. Bundled/imported Skills remain available;
    // legacy learned files are left on disk until the user explicitly clears app data.
    private fun learningRemoved(name: String) = name.startsWith("learned-") || name.startsWith("manual-")
    private val switches = this.context.getSharedPreferences("doppel_skill_switches", Context.MODE_PRIVATE)
    private fun disabledNames(): Set<String> = switches.getStringSet("disabled_names", emptySet()).orEmpty().toSet()
    private fun unavailable(name: String): JSONObject? = if (name in disabledNames())
        JSONObject().put("found", false).put("trusted", false).put("name", name).put("error", "skill_disabled") else null
    /** Adapter switches are separate from package contents. */
    fun enabledState(): Map<String, Boolean> {
        val items = store.list().getJSONArray("items")
        val disabled = disabledNames()
        return (0 until items.length()).associate { items.getJSONObject(it).getString("name").let { name -> name to (name !in disabled) } }
    }
    fun setEnabled(name: String, enabled: Boolean) {
        require(name in enabledState()) { "Skill 不存在" }
        synchronized(switches) {
            val next = disabledNames().toMutableSet()
            if (enabled) next.remove(name) else next.add(name)
            check(switches.edit().putStringSet("disabled_names", next).commit()) { "Skill 启用状态未保存" }
        }
    }
    fun list(): JSONObject = store.list().also { result ->
        val items = result.getJSONArray("items"); val disabled = disabledNames()
        result.put("items", JSONArray((0 until items.length()).map { items.getJSONObject(it) }
            .filter { it.getString("name") !in disabled }))
    }
    fun list(query: String, offset: Int = 0, limit: Int = 20): JSONObject = SkillKnowledgeResolver.cataloguePage(list(), query, offset, limit)
    fun read(name: String, revision: String? = null, offset: Int = 0, maxChars: Int = 12000): JSONObject {
        unavailable(name)?.let { return it }
        if (learningRemoved(name)) return JSONObject().put("found", false).put("reason", "application_learning_removed")
        return store.read(name, revision, offset, maxChars)
    }
    fun resource(name: String, path: String, revision: String? = null, offset: Int = 0, maxChars: Int = 12000): JSONObject {
        unavailable(name)?.let { return it }
        if (learningRemoved(name)) return JSONObject().put("found", false).put("reason", "application_learning_removed")
        return store.resource(name, path, revision, offset, maxChars)
    }
    fun relevant(goal: String, packageName: String, screen: String): JSONObject = SkillKnowledgeResolver(
        { list() }, { name, revision, offset, limit -> read(name, revision, offset, limit) },
        { name, path, revision, offset, limit -> resource(name, path, revision, offset, limit) }
    ).relevant(goal, packageName, screen)
    fun importPackage(bytes: ByteArray, kind: String): JSONObject {
        FirstUseConsent.requireAccepted(context)
        check(DirectMode.isEnabled(context)) { "请先开启本机直连模式" }
        return store.importPackage(bytes, kind)
    }
    fun delete(name: String): JSONObject {
        if (learningRemoved(name)) return JSONObject().put("ok", false).put("reason", "application_learning_removed")
        return store.delete(name)
    }
    /** Root Gateway delegates the direct /skills family after ordinary consent checks. */
    fun request(method: String, path: String): JSONObject {
        val parts = path.substringBefore('?').trim('/').split('/').map { URLDecoder.decode(it, "UTF-8") }
        require(parts.firstOrNull() == "skills")
        val query = linkedMapOf<String, String>()
        path.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.forEach { pair ->
            val fields = pair.split('=', limit = 2)
            val key = URLDecoder.decode(fields[0], "UTF-8")
            require(!query.containsKey(key)) { "Skills 查询参数重复" }
            query[key] = URLDecoder.decode(fields.getOrElse(1) { "" }, "UTF-8")
        }
        fun number(key: String, default: Int) = query[key]?.let { it.toIntOrNull() ?: error("Skills 查询范围无效") } ?: default
        return when {
            parts.size == 1 && method == "GET" -> if (query.isEmpty()) list() else list(query["query"].orEmpty(), number("offset", 0), number("limit", 20))
            parts.size == 2 && method == "GET" -> read(parts[1], query["revision"], number("offset", 0), number("max_chars", 12000))
            parts.size == 2 && method == "DELETE" -> delete(parts[1])
            parts.size == 3 && parts[2] == "resources" && method == "GET" -> {
                resource(parts[1], query["path"] ?: error("缺少资源路径"), query["revision"], number("offset", 0), number("max_chars", 12000))
            }
            else -> error("不支持的本机 Skills 请求")
        }
    }
}

/** Pure JVM file/catalogue implementation. Host owns root; imported files are never loaded as code. */
internal class DirectSkillStore(root: File, private val bundled: Map<String, Map<String, ByteArray>> = emptyMap()) {
    companion object {
        private const val TEXT_BYTES = 65536
        private const val PACKAGE_BYTES = 2 * 1024 * 1024
        private val namePattern = Regex("[A-Za-z0-9_-]{1,50}")
        private val textTypes = setOf("md", "txt", "json", "yaml", "yml", "csv")
        internal fun readBounded(input: java.io.InputStream, maximum: Int): ByteArray {
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(count > 0 && output.size() + count <= maximum) { "Skills 文件超过大小限制" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
        private fun decode(bytes: ByteArray): String = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: Exception) { throw IllegalArgumentException("Skills 必须使用有效 UTF-8 文本") }
        private fun safePath(path: String): List<String> {
            require(path.isNotBlank() && path.length <= 240 && path.none { it == '\\' || it == ':' || it.code < 32 }) { "Skills 资源路径无效" }
            val parts = path.split('/')
            require(parts.size <= 8 && parts.all { it.isNotBlank() && it !in setOf(".", "..") && !it.endsWith('.') && !it.endsWith(' ') &&
                !Regex("(?i)(CON|PRN|AUX|NUL|CLOCK\\$|COM[1-9]|LPT[1-9])(?:\\..*)?").matches(it) }) { "Skills 资源不能越出目录" }
            return parts
        }
        private fun parse(bytes: ByteArray, expected: String? = null): JSONObject {
            require(bytes.size <= TEXT_BYTES)
            val content = decode(bytes).replace("\r\n", "\n")
            val lines = content.split('\n')
            require(lines.firstOrNull() == "---") { "SKILL.md 需要 YAML frontmatter" }
            val end = (1 until lines.size).firstOrNull { lines[it] == "---" } ?: throw IllegalArgumentException("SKILL.md 元数据没有结束标记")
            val header = lines.subList(1, end).joinToString("\n")
            val meta = try {
                val options = LoaderOptions().apply { setAllowDuplicateKeys(false); setMaxAliasesForCollections(0); setNestingDepthLimit(32); setCodePointLimit(TEXT_BYTES) }
                val yaml = Yaml(SafeConstructor(options))
                require(yaml.parse(StringReader(header)).none { it is AliasEvent })
                yaml.load<Any>(header) as? Map<*, *> ?: throw IllegalArgumentException()
            } catch (_: Exception) { throw IllegalArgumentException("Skills YAML 无效：不支持对象标签、别名、重复键或过深结构") }
            val name = meta["name"]; val description = meta["description"]
            require(name is String && namePattern.matches(name) && (expected == null || expected == name)) { "Skill 名称需与目录一致，限 1 至 50 位字母、数字、短横线或下划线" }
            require(description is String && description.isNotBlank() && description.length <= 4096) { "Skill 需要简短说明" }
            fun strings(value: Any?): JSONArray {
                if (value == null) return JSONArray()
                require(value is List<*> && value.size <= 32 && value.all { it is String && it.length <= 256 }) { "Skills 元数据列表无效" }
                return JSONArray(value)
            }
            val dependencies = meta["dependencies"] ?: emptyMap<String, Any>()
            require(dependencies is Map<*, *> && dependencies.keys.all { it in setOf("tools", "runtimes") }) { "Skills 依赖只接受 tools 与 runtimes" }
            val instructions = lines.drop(end + 1).joinToString("\n").trim()
            require(instructions.isNotBlank()) { "SKILL.md 正文不能为空" }
            val result = JSONObject().put("name", name).put("description", description.trim().take(200))
                .put("platforms", strings(meta["platforms"]))
                .put("packages", strings(meta["packages"]))
                .put("aliases", strings(meta["aliases"]))
                .put("app_aliases", strings(meta["app_aliases"]))
                .put("dependencies", JSONObject().put("tools", strings(dependencies["tools"])).put("runtimes", strings(dependencies["runtimes"])))
                .put("runtime_status", "unverified").put("trusted", false).put("instructions", instructions)
            val packages = result.getJSONArray("packages")
            require((0 until packages.length()).all { Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+").matches(packages.getString(it)) }) { "Skills packages 需要明确的 Android 包名" }
            val appAliases = result.getJSONArray("app_aliases")
            require((0 until appAliases.length()).all { appAliases.getString(it).trim().length >= 2 }) { "Skills app_aliases 需要明确的应用身份名" }
            (meta["version"] as? String)?.takeIf { it.length in 1..100 }?.let { result.put("included_source_version", it) }
            return result
        }
        private fun revision(files: Map<String, ByteArray>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            for ((path, bytes) in files.toSortedMap()) {
                digest.update(path.toByteArray(Charsets.UTF_8)); digest.update(0.toByte())
                digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII)); digest.update(0.toByte()); digest.update(bytes)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        private fun zip(data: ByteArray): Map<String, ByteArray> {
            // Inspect central entry types before extraction: reject links, encryption and ZIP64.
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val end = (data.size - 22 downTo maxOf(0, data.size - 65557)).firstOrNull { buffer.getInt(it) == 0x06054b50 }
                ?: throw IllegalArgumentException("Skills ZIP 目录无效")
            val count = buffer.getShort(end + 10).toInt() and 0xffff
            var position = buffer.getInt(end + 16)
            require(count in 1..128 && position in 0 until end && buffer.getShort(end + 4).toInt() == 0 && buffer.getShort(end + 6).toInt() == 0)
            repeat(count) {
                require(position >= 0 && position + 46 <= end && buffer.getInt(position) == 0x02014b50) { "Skills ZIP 目录无效" }
                val flags = buffer.getShort(position + 8).toInt() and 0xffff
                val type = (buffer.getInt(position + 38) ushr 16) and 0xf000
                require(flags and 1 == 0 && type in setOf(0, 0x8000, 0x4000)) { "Skills ZIP 不接受加密文件或链接" }
                val name = buffer.getShort(position + 28).toInt() and 0xffff
                val extra = buffer.getShort(position + 30).toInt() and 0xffff
                val comment = buffer.getShort(position + 32).toInt() and 0xffff
                position += 46 + name + extra + comment; require(position <= end)
            }
            val files = linkedMapOf<String, ByteArray>(); val seen = mutableSetOf<String>(); var total = 0; var members = 0
            ZipInputStream(ByteArrayInputStream(data)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    members++; require(members <= 128)
                    val name = entry.name.removeSuffix("/"); safePath(name)
                    require(seen.add(name.lowercase(java.util.Locale.ROOT))) { "Skills ZIP 有重复路径" }
                    if (!entry.isDirectory) {
                        require(files.size < 100)
                        val bytes = readBounded(input, TEXT_BYTES); total += bytes.size
                        require(total <= PACKAGE_BYTES) { "Skills ZIP 解压总量超过限制" }; files[name] = bytes
                    }
                    input.closeEntry()
                }
            }
            require(members == count && files.isNotEmpty()) { "Skills ZIP 文件记录不一致" }
            if (files.containsKey("SKILL.md")) return files
            val prefixes = files.keys.map { it.substringBefore('/') }.toSet()
            require(prefixes.size == 1 && files.keys.all { it.contains('/') }) { "每次只能导入一个 Skill" }
            return files.mapKeys { it.key.substringAfter('/') }.also { require(it.containsKey("SKILL.md")) { "ZIP 缺少 SKILL.md" } }
        }
    }
    private val suppliedRoot = root.absoluteFile
    private val root = root.canonicalFile
    init {
        require(!Files.isSymbolicLink(suppliedRoot.toPath())) { "Skills 根目录不能为链接" }
        require(this.root.isDirectory || this.root.mkdirs()) { "本机 Skills 目录不可用" }
        require(bundled.size <= 50)
    }
    private fun folder(name: String): File {
        require(namePattern.matches(name)) { "Skill 名称无效" }
        return contained(root, name)
    }
    private fun contained(base: File, relative: String): File {
        var current = base
        for (part in safePath(relative)) {
            current = File(current, part)
            require(!Files.isSymbolicLink(current.toPath()) && current.canonicalFile == current.absoluteFile) { "Skills 路径不接受链接" }
        }
        require(current.toPath().normalize().startsWith(root.toPath()) && current != root)
        return current
    }
    private fun importedNames() = root.listFiles().orEmpty().filter { !it.name.startsWith('.') }.map {
        require(it.isDirectory && !Files.isSymbolicLink(it.toPath()) && namePattern.matches(it.name)) { "本机 Skills 目录格式无效" }; it.name
    }.also { require(it.size + bundled.size <= 50) { "最多导入 50 个 Skills（含内置）" } }
    private fun files(name: String): Pair<String, Map<String, ByteArray>> {
        require(namePattern.matches(name))
        bundled[name]?.let { return "bundled" to it }
        val folder = folder(name); require(folder.isDirectory) { "Skill 不存在" }
        val result = linkedMapOf<String, ByteArray>(); var total = 0
        fun visit(directory: File, prefix: String, depth: Int) {
            require(depth <= 8)
            for (item in directory.listFiles().orEmpty().sortedBy { it.name }) {
                val relative = if (prefix.isEmpty()) item.name else "$prefix/${item.name}"
                val safe = contained(folder, relative)
                if (safe.isDirectory) visit(safe, relative, depth + 1)
                else {
                    require(safe.isFile && result.size < 100)
                    val bytes = safe.inputStream().use { readBounded(it, TEXT_BYTES) }; total += bytes.size
                    require(total <= PACKAGE_BYTES); result[relative] = bytes
                }
            }
        }
        visit(folder, "", 0); return "imported" to result
    }
    private fun metadata(name: String, source: String, files: Map<String, ByteArray>): JSONObject =
        parse(files["SKILL.md"] ?: throw IllegalArgumentException("SKILL.md 不存在"), name)
            .put("source", source).put("revision", revision(files))
            .put("resources", JSONArray(files.keys.filter { it != "SKILL.md" && it.substringAfterLast('.', "").lowercase() in textTypes && !it.startsWith("scripts/") }.sorted()))
    @Synchronized fun list(): JSONObject {
        val names = bundled.keys.toList() + importedNames()
        require(names.map { it.lowercase(java.util.Locale.ROOT) }.toSet().size == names.size) { "存在同名 Skills，请检查本机目录" }
        val items = JSONArray(); val errors = JSONArray()
        for (name in names.sorted()) {
            try {
                val (source, files) = files(name)
                val item = metadata(name, source, files); item.remove("instructions"); item.remove("resources"); items.put(item)
            } catch (_: Exception) { errors.put(JSONObject().put("name", name).put("error", "invalid_skill")) }
        }
        return JSONObject().put("items", items).put("errors", errors)
    }
    private fun failure(code: String) = JSONObject().put("found", false).put("error", code).put("trusted", false)
    @Synchronized fun list(query: String, offset: Int = 0, limit: Int = 20): JSONObject = SkillKnowledgeResolver.cataloguePage(list(), query, offset, limit)
    @Synchronized fun read(name: String, expectedRevision: String? = null, offset: Int = 0, maxChars: Int = 12000): JSONObject {
        return try {
            val (source, files) = files(name); val item = metadata(name, source, files)
            if (expectedRevision != null && item.getString("revision") != expectedRevision) return failure("skill_changed_refresh_catalogue")
            val text = item.getString("instructions")
            SkillKnowledgeResolver.textPage(item.put("found", true).put("instruction_chars", text.length), "instructions", offset, maxChars)
        } catch (_: Exception) { failure("skill_missing_or_invalid") }
    }
    @Synchronized fun resource(name: String, path: String, expectedRevision: String? = null, offset: Int = 0, maxChars: Int = 12000): JSONObject {
        return try {
            safePath(path)
            require(path.substringAfterLast('.', "").lowercase() in textTypes && !path.startsWith("scripts/"))
            val (source, files) = files(name); val revision = revision(files)
            if (expectedRevision != null && revision != expectedRevision) return failure("skill_changed_refresh_catalogue")
            val text = decode(files[path] ?: return failure("resource_not_found"))
            SkillKnowledgeResolver.textPage(JSONObject().put("found", true).put("content", text).put("revision", revision).put("trusted", false)
                .put("name", name).put("source", source).put("path", path), "content", offset, maxChars)
        } catch (_: Exception) { failure("resource_missing_or_invalid") }
    }
    @Synchronized fun importPackage(data: ByteArray, kind: String): JSONObject {
        require(data.size in 1..PACKAGE_BYTES) { "Skills 导入大小无效" }
        val files = when (kind) { "markdown" -> mapOf("SKILL.md" to data); "zip" -> zip(data); else -> throw IllegalArgumentException("只支持 SKILL.md 或 ZIP") }
        val parsed = parse(files["SKILL.md"] ?: throw IllegalArgumentException("缺少 SKILL.md")); val name = parsed.getString("name")
        val names = bundled.keys + importedNames()
        require(names.size < 50 && names.none { it.equals(name, ignoreCase = true) }) { "同名 Skill 已存在或数量已满，请先移除旧导入" }
        val target = folder(name); require(!target.exists()) { "同名 Skill 目录已存在" }
        val stage = File(root, ".stage-${UUID.randomUUID()}"); require(stage.mkdir())
        try {
            for ((relative, bytes) in files) {
                require(bytes.size <= TEXT_BYTES); val destination = contained(stage, relative)
                require(destination.parentFile.isDirectory || destination.parentFile.mkdirs())
                destination.outputStream().use { it.write(bytes); it.fd.sync() }
            }
            require(stage.renameTo(target)) { "Skill 原子发布未完成" }
        } finally { if (stage.exists()) removeFolder(stage) }
        return metadata(name, "imported", files).apply { remove("instructions"); put("found", true) }
    }
    @Synchronized fun delete(name: String): JSONObject {
        require(name !in bundled) { "内置 Skill 不能删除" }
        val folder = folder(name); require(folder.isDirectory && File(folder, "SKILL.md").isFile) { "导入 Skill 不存在" }
        removeFolder(folder); return JSONObject().put("ok", true)
    }
    private fun removeFolder(folder: File) {
        require(folder != root && folder.canonicalFile == folder.absoluteFile && folder.toPath().startsWith(root.toPath()))
        for (entry in folder.listFiles().orEmpty()) {
            require(!Files.isSymbolicLink(entry.toPath()) && entry.canonicalFile == entry.absoluteFile)
            if (entry.isDirectory) removeFolder(entry) else require(entry.delete())
        }
        require(folder.delete())
    }
}

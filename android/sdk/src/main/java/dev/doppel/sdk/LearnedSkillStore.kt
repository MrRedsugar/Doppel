package dev.doppel.sdk

import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Local, version-bound knowledge; imported instructions never acquire execution authority. */
class LearnedSkillStore(root: File, private val currentIdentity: (String) -> JSONObject?) {
    companion object { private val locks = ConcurrentHashMap<String, Any>() }
    private val suppliedRoot = root.absoluteFile
    // Android's host-owned /data/user/0 is an alias of /data/data on some devices.
    // Resolve the host root once, while still rejecting links introduced at/below it.
    private val root = root.canonicalFile
    private val lock = locks.computeIfAbsent(this.root.path) { Any() }
    init { checkRoot() }
    private fun checkRoot() {
        require(!Files.isSymbolicLink(suppliedRoot.toPath()) && suppliedRoot.canonicalFile == root)
        require(!Files.isSymbolicLink(root.toPath()) && root.canonicalFile == root)
        require(root.isDirectory || root.mkdirs())
    }
    private fun file(name: String): File {
        checkRoot(); require(name.matches(Regex("learned-[a-f0-9]{40}"))) { "学习文档名称无效" }
        return File(root, "$name.json").also { require(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile == it) }
    }
    private fun keys(value: JSONObject, allowed: Set<String>) { require(value.keys().asSequence().all { it in allowed }) { "学习证据含未知字段" } }
    private fun validate(trace: JSONObject) {
        keys(trace, setOf("app", "steps", "source_id", "origin", "proves_business_success"))
        require(trace.opt("proves_business_success") == false && trace.optString("origin") in setOf("completed_task", "demonstration"))
        require(trace.optString("source_id").matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val app = trace.getJSONObject("app")
        keys(app, setOf("package_name", "version_code", "version_name", "system", "locale"))
        val identity = requireNotNull(LearningTrace.identity(app))
        require(identity.keys().asSequence().all { app.opt(it) is String && identity.getString(it) == app.getString(it) })
        val steps = trace.getJSONArray("steps"); require(steps.length() in 1..24)
        var previous: String? = null
        repeat(steps.length()) { index ->
            val step = steps.getJSONObject(index)
            keys(step, setOf("kind", "label", "before", "after", "evidence_id", "direction", "desired_checked"))
            require(step.getString("kind") in setOf("tap", "long_press", "scroll", "back"))
            require(step.getString("label").isNotBlank() && LearningTrace.label(step.getString("label")) == step.getString("label"))
            require(step.getString("evidence_id").matches(Regex("[A-Za-z0-9_-]{1,128}")))
            for (key in listOf("before", "after")) {
                val page = step.getJSONObject(key); keys(page, setOf("screen_id", "labels"))
                require(page.getString("screen_id").matches(Regex("[A-Za-z0-9_-]{1,128}")))
                val labels = page.getJSONArray("labels"); require(labels.length() <= 6)
                repeat(labels.length()) { require(labels.getString(it).isNotBlank() && LearningTrace.label(labels.getString(it)) == labels.getString(it)) }
            }
            val before = step.getJSONObject("before").getString("screen_id"); val after = step.getJSONObject("after").getString("screen_id")
            require(before != after && (previous == null || previous == before)); previous = after
            if (step.has("desired_checked")) require(step.get("desired_checked") is Boolean)
            if (step.getString("kind") == "scroll") require(step.optString("direction") in setOf("up", "down", "left", "right"))
        }
    }
    private fun load(name: String): JSONObject {
        val file = file(name); require(file.isFile && file.length() <= 65536)
        val value = JSONObject(file.readText(Charsets.UTF_8)); validate(value.getJSONObject("trace"))
        require(value.getString("name") == name && value.getJSONArray("sources").length() in 1..100 && value.get("enabled") is Boolean)
        return value
    }
    private fun names(): List<String> {
        checkRoot()
        return root.listFiles().orEmpty().filter { !it.name.startsWith('.') }.map {
            require(it.name.endsWith(".json")); val name = it.name.removeSuffix(".json"); file(name); name
        }.also { require(it.size <= 50) { "学习文档最多50份，请清理后继续" } }
    }
    private fun write(value: JSONObject) {
        val target = file(value.getString("name")); val bytes = value.toString().toByteArray(Charsets.UTF_8); require(bytes.size <= 65536)
        val stage = Files.createTempFile(root.toPath(), ".learning-", ".tmp").toFile()
        try {
            stage.outputStream().use { it.write(bytes); it.fd.sync() }
            Files.move(stage.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { if (stage.exists()) require(stage.delete()) }
    }
    fun save(trace: JSONObject): JSONObject = synchronized(lock) {
        validate(trace)
        val reusable = JSONObject().put("app", LearningTrace.identity(trace.getJSONObject("app"))).put("steps", JSONArray().apply {
            val steps = trace.getJSONArray("steps")
            repeat(steps.length()) { i ->
                val step = steps.getJSONObject(i)
                put(JSONObject().put("kind", step.getString("kind")).put("label", step.getString("label"))
                    .put("before_labels", step.getJSONObject("before").getJSONArray("labels"))
                    .put("after_labels", step.getJSONObject("after").getJSONArray("labels"))
                    .put("direction", step.opt("direction")).put("desired_checked", step.opt("desired_checked")))
            }
        })
        val name = "learned-" + LearningTrace.hash(reusable.toString()).take(40)
        val existed = file(name).exists()
        if (!existed) require(names().size < 50) { "学习文档已满，请先清理" }
        val value = if (existed) load(name) else JSONObject().put("name", name).put("trace", JSONObject(trace.toString()))
            .put("enabled", true).put("sources", JSONArray()).put("created_at", System.currentTimeMillis())
        val sources = value.getJSONArray("sources"); val sourceHash = LearningTrace.hash(trace.getString("source_id"))
        if ((0 until sources.length()).none { sources.getString(it) == sourceHash }) {
            if (sources.length() >= 100) return@synchronized describe(value)
            sources.put(sourceHash); value.put("updated_at", System.currentTimeMillis()); write(value)
        }
        describe(value)
    }
    private fun title(value: JSONObject): String {
        val steps = value.getJSONObject("trace").getJSONArray("steps")
        return (0 until steps.length()).map { steps.getJSONObject(it).getString("label") }.distinct().take(3).joinToString(" → ").take(180)
    }
    private fun description(value: JSONObject) = "${value.getJSONObject("trace").getJSONObject("app").getString("package_name")}：${title(value)}".take(200)
    private fun availability(value: JSONObject): String {
        if (!value.getBoolean("enabled")) return "disabled"
        val app = value.getJSONObject("trace").getJSONObject("app")
        return if (LearningTrace.signature(app) == LearningTrace.signature(currentIdentity(app.getString("package_name")))) "available" else "version_changed"
    }
    private fun describe(value: JSONObject) = JSONObject().put("name", value.getString("name")).put("title", title(value))
        .put("description", description(value))
        .put("source", "learned").put("trusted", false).put("enabled", value.getBoolean("enabled"))
        .put("availability", availability(value)).put("observations", value.getJSONArray("sources").length())
        .put("origin", value.getJSONObject("trace").getString("origin")).put("app", value.getJSONObject("trace").getJSONObject("app"))
        .put("revision", LearningTrace.hash(value.toString())).put("resources", JSONArray(listOf("references/evidence.json")))
    fun list(): JSONObject = synchronized(lock) {
        val items = JSONArray(); val errors = JSONArray()
        for (name in names().sorted()) try { items.put(describe(load(name))) } catch (_: Exception) { errors.put(JSONObject().put("name", name).put("error", "invalid_learning_record")) }
        JSONObject().put("items", items).put("errors", errors)
    }
    private fun markdown(value: JSONObject): String {
        val trace = value.getJSONObject("trace"); val app = trace.getJSONObject("app"); val steps = trace.getJSONArray("steps")
        return buildString {
            append("---\nname: ${value.getString("name")}\ndescription: ${JSONObject.quote(description(value))}\nplatforms: [android]\nversion: ${JSONObject.quote(app.getString("version_code"))}\n---\n\n")
            append("# ${title(value)}\n\n## 适用范围\n\n应用 ${app.getString("package_name")}，版本 ${app.getString("version_name")} (${app.getString("version_code")})，系统 ${app.getString("system")}，语言 ${app.getString("locale")}。版本或页面不符时停止套用，重新观察。\n\n")
            append("## 来源与限制\n\n本机${if (trace.getString("origin") == "demonstration") "显式人类演示" else "已完成任务"}中的观察路线，已有 ${value.getJSONArray("sources").length()} 个独立会话样本。它不是业务成功证明或执行授权，不继承旧批准、账号、坐标或输入。每一步重新查找当前目标并核对结果；变化不符时回到正常规划。详细来源见 references/evidence.json。\n\n## 操作经验\n\n")
            repeat(steps.length()) { i ->
                val step = steps.getJSONObject(i)
                val verb = when (step.getString("kind")) { "tap" -> "点击"; "long_press" -> "长按"; "scroll" -> "滚动"; else -> "返回" }
                append("${i + 1}. $verb“${step.getString("label")}”")
                if (step.has("direction")) append("，方向 ${step.getString("direction")}")
                if (step.has("desired_checked")) append("，目标状态 ${if (step.getBoolean("desired_checked")) "开启" else "关闭"}")
                val labels = step.getJSONObject("after").getJSONArray("labels")
                append("。观察到的后续界面文字：${(0 until labels.length()).joinToString("、") { labels.getString(it) }.ifBlank { "无可复用文字，需重新核对" }}。\n")
            }
        }
    }
    fun read(name: String, revision: String? = null, inspect: Boolean = false): JSONObject = synchronized(lock) {
        try {
            val value = load(name); val item = describe(value)
            if (!inspect && (item.getString("availability") != "available" || revision != null && revision != item.getString("revision")))
                JSONObject().put("found", false).put("reason", "learning_changed_or_unavailable")
            else item.put("found", true).put("instructions", markdown(value)).put("evidence", JSONObject(value.getJSONObject("trace").toString()))
        } catch (_: Exception) { JSONObject().put("found", false).put("reason", "learning_missing_or_invalid") }
    }
    fun setEnabled(name: String, enabled: Boolean): JSONObject = synchronized(lock) {
        val value = load(name); value.put("enabled", enabled); write(value); describe(value)
    }
    fun delete(name: String) = synchronized(lock) { require(file(name).delete()) { "学习文档不存在或无法删除" } }
    fun export(name: String): ByteArray = synchronized(lock) {
        val value = load(name); val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((path, content) in mapOf("SKILL.md" to markdown(value), "references/evidence.json" to value.getJSONObject("trace").toString(2))) {
                zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
        }; output.toByteArray()
    }
    fun relevant(goal: String, packageName: String): JSONObject? = synchronized(lock) {
        val items = list().getJSONArray("items")
        val selected = (0 until items.length()).map { items.getJSONObject(it) }.filter {
            it.getString("availability") == "available" && it.getJSONObject("app").getString("package_name") == packageName
        }.map { item ->
            val title = item.getString("title"); val tokens = title.split(Regex("[ →·、，,]+"))
                .filter { it.length >= 2 && it !in setOf("设置", "返回", "确定", "取消") }
            item to tokens.count { goal.contains(it, ignoreCase = true) }
        }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
        selected?.let { read(it.getString("name"), it.getString("revision")).takeIf { result -> result.optBoolean("found") } }
    }
}

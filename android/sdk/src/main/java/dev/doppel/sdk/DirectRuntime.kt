package dev.doppel.sdk

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DirectRuntime private constructor(context: Context) {
    companion object {
        const val DEVICE_ID = "direct-this-phone"
        @Volatile private var instance: DirectRuntime? = null
        fun get(context: Context): DirectRuntime = instance ?: synchronized(this) {
            instance ?: DirectRuntime(context.applicationContext).also { instance = it }
        }
        fun interrupt(context: Context, reason: String = "设备执行已暂停，请核对后继续") {
            if (DirectMode.isEnabled(context)) instance?.let { it.engine.interrupt(reason); it.cancelCalls() }
        }
    }
    private val context = context.applicationContext
    private val file = AtomicFile(File(context.noBackupFilesDir, "direct-runs-v1.json"))
    // Migrate before the engine can increment calls for the first new request.
    private val usageLedger = ModelUsageLedger(context.noBackupFilesDir).also { ledger -> runCatching { ledger.initialize() } }
    private val skills = DirectSkills(context)
    private val gui = GuiGroundingClient(context)
    private val reviewMemory = TaskReviewMemory(File(context.noBackupFilesDir, "task-review-memory-v1.json"))
    private val engine = SplitTaskEngine(readState(), ::saveState, skillCatalog = skills::list, skillReference = skills::relevant,
        enhancementEnabled = { ModelProviders(context).routing().enhancementEnabled },
        reviewMemory = { reviewMemory.list() })
    private val provider = ModelApi(context)
    private val reviewAssistant = TaskReviewAssistant(provider, reviewMemory)
    private val providerConnection = java.util.concurrent.atomic.AtomicReference<java.net.HttpURLConnection?>()
    private fun cancelCalls() { web.cancel(); gui.cancel(); runCatching { providerConnection.getAndSet(null)?.disconnect() } }
    private val web = AndroidWebResearch()
    private val screenshots = ScreenshotArchive(File(context.noBackupFilesDir, "direct-screenshots-v1"))
    private fun retentionDays() = context.getSharedPreferences("doppel", Context.MODE_PRIVATE).getInt("screenshot_retention_days", 7)
    private fun pruneScreenshots() {
        val items = engine.list().getJSONArray("items")
        screenshots.prune(retentionDays(), (0 until items.length()).map { items.getJSONObject(it).getString("id") }.toSet())
    }
    fun image(path: String): ByteArray {
        check(DirectMode.isEnabled(context)) { "本机直连模式未启用" }
        val route = path.trim('/').split('/')
        require(route.size == 4 && route[0] == "runs" && route[2] == "screenshots") { "截图地址无效" }
        engine.get(route[1]); pruneScreenshots()
        return screenshots.read(route[1], route[3])
    }
    private fun acceptResult(body: JSONObject): JSONObject {
        val accepted = engine.result(body)
        val data = body.optJSONObject("data")
        if (accepted.optBoolean("accepted") && body.optString("status") == "ok" && data?.has("image_base64") == true) {
            try {
                require(data.optString("mime_type") == "image/png")
                val encoded = data.getString("image_base64")
                require(encoded.length <= 7 * 1024 * 1024)
                screenshots.save(body.getString("run_id"), body.getString("command_id"),
                    java.util.Base64.getDecoder().decode(encoded), data.optJSONObject("visual_frame") ?: JSONObject())
                pruneScreenshots()
            } catch (_: Exception) {
                // The device command is already acknowledged: never replay it for an archive failure.
                android.util.Log.w("DoppelArchive", "Screenshot archive unavailable for an acknowledged result")
                accepted.put("screenshot_saved", false)
            }
        }
        return accepted
    }
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "direct-planner").apply { isDaemon = true } }
    private val pumping = AtomicBoolean(false)
    private fun conversationIntent(body: JSONObject): JSONObject {
        val history = body.optJSONArray("history") ?: JSONArray()
        val messages = JSONArray()
        for (i in 0 until history.length()) history.optJSONObject(i)?.let { messages.put(it) }
        messages.put(JSONObject().put("role", "user").put("content", body.optString("message").trim()))
        val prompt = ConversationIntent.request(messages)
        prompt.getJSONObject(0).put("content", ConversationIntent.SYSTEM_PROMPT +
            "\n若为 conversation，增加 reply 字段给出简洁中文回答；task/uncertain 时 reply 为空。")
        val response = provider.complete(JSONObject().put("_doppel_role", "primary").put("stream", false)
            .put("max_completion_tokens", 700).put("messages", prompt))
        val content = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        val decision = ConversationIntent.parse(content)
        val raw = runCatching { JSONObject(content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()) }.getOrNull()
        val result = JSONObject().put("intent", when (decision.kind) {
            ConversationIntent.Kind.TASK -> "task"; ConversationIntent.Kind.CONVERSATION -> "conversation"; else -> "uncertain"
        }).put("confidence", decision.confidence).put("task_goal", decision.goal).put("title", decision.title)
            .put("question", decision.question).put("reason", "model_router")
        result.put("reply", raw?.optString("reply").orEmpty().take(4000))
        if (decision.kind == ConversationIntent.Kind.TASK && !body.optBoolean("device_available", false)) {
            result.put("intent", "task").put("message", "这是手机任务，但当前还没有绑定设备。")
        }
        return result
    }
    private fun readState(): String? {
        if (!file.baseFile.exists()) return null
        check(file.baseFile.length() <= 2 * 1024 * 1024) { "本机任务记录超过上限，请先导出并检查" }
        return file.openRead().use { it.bufferedReader(Charsets.UTF_8).readText() }
    }
    private fun saveState(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        check(bytes.size <= 2 * 1024 * 1024) { "本机任务记录空间不足" }
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }
    fun hasUnfinishedRun() = engine.hasUnfinished()
    /** Status polling must not enter request(), whose completion can wake the planner. */
    internal fun statusOrNull(id: String): String? {
        check(DirectMode.isEnabled(context)) { "本机直连模式未启用" }
        return engine.statusOrNull(id)
    }
    private fun isCurrent(work: SplitTaskEngine.Work) = DirectMode.isEnabled(context) &&
        FirstUseConsent.isAccepted(context) && engine.isCurrent(work)
    private fun pump() {
        if (!engine.readyForWork()) return
        if (!pumping.compareAndSet(false, true)) return
        executor.execute {
            try {
                while (DirectMode.isEnabled(context)) {
                    val work = engine.takeWork() ?: break
                    if (work.localTool != null) {
                        try {
                            FirstUseConsent.requireAccepted(context)
                            val result = when (work.localTool) {
                                "locate_ui" -> gui.locate(work.payload) { isCurrent(work) }
                                "search_web" -> web.search(work.payload.getString("query")) { isCurrent(work) }
                                "read_web" -> web.read(work.payload.getString("url")) { isCurrent(work) }
                                "list_skills" -> skills.list(work.payload.optString("query"), work.payload.optInt("offset", 0), work.payload.optInt("limit", 20))
                                "load_skill" -> skills.read(work.payload.getString("name"), work.payload.optString("revision").ifBlank { null }, work.payload.optInt("offset", 0), work.payload.optInt("max_chars", 4500))
                                "read_skill_resource" -> skills.resource(work.payload.getString("name"), work.payload.getString("path"), work.payload.optString("revision").ifBlank { null }, work.payload.optInt("offset", 0), work.payload.optInt("max_chars", 4500))
                                else -> error("资料工具尚不可用")
                            }
                            engine.acceptLocal(work, result)
                        } catch (error: Exception) {
                            val diagnostic = if (work.localTool == "locate_ui")
                                JSONObject().put("transport_diagnostic", GuiGroundingFailure.from(error)) else null
                            engine.acceptLocal(work, diagnostic, true)
                        }
                    } else {
                        var owned: java.net.HttpURLConnection? = null
                        try { engine.accept(work, provider.complete(work.payload, onConnection = { connection ->
                            owned = connection; providerConnection.set(connection)
                            if (!isCurrent(work)) {
                                providerConnection.compareAndSet(connection,null); connection.disconnect(); error("任务已取消")
                            }
                        })) }
                        catch (failure: Exception) { engine.accept(work, null, failure.message ?: "本机模型请求失败，已暂停") }
                        finally { owned?.let { providerConnection.compareAndSet(it,null) } }
                    }
                }
            } catch (_: Exception) { runCatching { engine.interrupt("本机记录保存或规划未完成，请检查后继续") } }
            finally {
                pumping.set(false)
                // A result may arrive between the last takeWork and releasing this flag.
                if (DirectMode.isEnabled(context) && engine.readyForWork()) pump()
            }
        }
    }
    fun request(method: String, path: String, body: JSONObject?): JSONObject {
        check(DirectMode.isEnabled(context)) { "本机直连模式未启用" }
        val route = path.substringBefore('?').trim('/').split('/')
        if (route == listOf("conversation", "intent") && method == "POST")
            return conversationIntent(body ?: error("缺少消息"))
        val readOnlyReview = route.size == 3 && route[0] == "runs" && route[2] == "review"
        // Reading/managing knowledge never wakes a planner or makes a paid model call.
        if (route.firstOrNull() == "skills") return skills.request(method, path)
        val result = when {
            route == listOf("devices") && method == "POST" -> JSONObject().put("id", DEVICE_ID).put("name", "本机直连设备")
            route == listOf("devices") && method == "GET" -> JSONObject().put("items", JSONArray().put(JSONObject().put("id", DEVICE_ID)))
            route == listOf("devices", DEVICE_ID, "data-cleanup") && method == "GET" -> JSONObject().put("items", JSONArray())
            route == listOf("devices", DEVICE_ID, "commands") && method == "GET" -> engine.poll().also {
                if (it.isNull("command")) Thread.sleep(200)
            }
            route == listOf("devices", DEVICE_ID, "results") && method == "POST" -> acceptResult(body ?: error("缺少设备结果"))
            route == listOf("data-retention") && method == "GET" -> JSONObject().put("days", retentionDays())
            route == listOf("data-retention") && method in setOf("PATCH", "PUT", "POST") -> {
                val days = body?.getInt("days") ?: error("缺少保留天数")
                require(days in setOf(0, 1, 7, 30, 90)) { "截图保留时间无效" }
                check(context.getSharedPreferences("doppel", Context.MODE_PRIVATE).edit().putInt("screenshot_retention_days", days).commit())
                pruneScreenshots(); JSONObject().put("days", days)
            }
            route == listOf("runs") && method == "POST" -> {
                check(ModelProviders(context).isReady()) { "请先配置并检测支持视觉的模型" }; engine.create(body ?: error("缺少任务"))
            }
            route == listOf("runs") && method == "GET" -> engine.list()
            route == listOf("usage") && method == "GET" -> usageLedger.snapshot().also { usage ->
                pruneScreenshots()
                val archive = screenshots.usageSummary(); usage.put("screenshots", archive.optLong("screenshots"))
                val byDate = linkedMapOf<String, Long>()
                archive.optJSONArray("daily")?.let { rows -> for (i in 0 until rows.length()) rows.optJSONObject(i)?.let { byDate[it.optString("date")] = it.optLong("screenshots") } }
                usage.optJSONArray("daily")?.let { rows -> for (i in 0 until rows.length()) rows.optJSONObject(i)?.let { it.put("screenshots", byDate[it.optString("date")] ?: 0L) } }
            }
            route.size == 2 && route[0] == "runs" && method == "GET" -> engine.get(route[1])
            route.size == 2 && route[0] == "runs" && method == "DELETE" -> {
                val id = route[1]
                check(engine.get(id).optString("status") in setOf("completed", "failed", "cancelled")) { "请先结束任务再删除" }
                screenshots.delete(id)
                val deleted = engine.delete(id)
                val prefs = context.getSharedPreferences("doppel", Context.MODE_PRIVATE)
                synchronized(prefs) {
                    if (prefs.getString("active_run", "") == id)
                        check(prefs.edit().remove("active_run").commit()) { "已删除任务的本机引用清理失败" }
                }
                deleted
            }
            route.size == 3 && route[0] == "runs" && route[2] == "events" && method == "GET" -> engine.events(route[1])
            route.size == 3 && route[0] == "runs" && route[2] == "conversation" && method == "GET" -> engine.conversation(route[1])
            route.size == 3 && route[0] == "runs" && route[2] == "review" && method == "GET" ->
                reviewMemory.review(engine.internalRun(route[1]))
            route.size == 3 && route[0] == "runs" && route[2] == "review" && method == "POST" -> {
                // Read-only diagnostic call. It deliberately bypasses pump(): no
                // planner state, screenshot, command, or authorization is touched.
                val run = engine.internalRun(route[1])
                require(run.optString("status") in setOf("paused", "completed", "failed", "cancelled")) { "任务执行中时不可复盘，请先暂停任务" }
                reviewAssistant.analyze(run, body?.optString("question"))
            }
            route.size == 3 && route[0] == "runs" && route[2] == "review-memory" && method == "POST" ->
                reviewMemory.save(engine.internalRun(route[1]), body ?: JSONObject())
            route.size == 3 && route[0] == "runs" && route[2] == "screenshots" && method == "GET" -> {
                engine.get(route[1]); pruneScreenshots(); screenshots.list(route[1])
            }
            route.size == 4 && route[0] == "runs" && route[2] == "screenshots" && method == "DELETE" -> {
                engine.get(route[1]); screenshots.delete(route[1], route[3]); JSONObject().put("deleted", true)
            }
            route.firstOrNull() == "review-memory" && method == "GET" ->
                reviewMemory.list(path.substringAfter("package_name=", "").takeIf { it.isNotBlank() })
            route.size == 2 && route[0] == "review-memory" && method == "DELETE" -> reviewMemory.delete(route[1])
            route.size == 3 && route[0] == "runs" && route[2] in setOf("pause", "resume", "cancel", "answer") && method == "POST" -> {
                val wasRunning = engine.get(route[1]).optString("status") == "running"
                val controlled = engine.control(route[1], route[2], body ?: JSONObject())
                if (wasRunning && route[2] in setOf("pause", "cancel")) { cancelCalls() }
                controlled
            }
            else -> error("此功能需要网关服务，本机直连模式暂不支持")
        }
        // A review is deliberately isolated from the execution pump. Even if
        // a caller asks about a currently running run, diagnostics cannot wake
        // or advance its planner.
        if (!readOnlyReview) pump()
        return result
    }

}

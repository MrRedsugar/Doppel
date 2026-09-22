package dev.doppel.sdk

import android.content.Context
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
            if (DirectMode.isEnabled(context)) instance?.let { try { it.engine.interrupt(reason) } finally { it.cancelCalls() } }
        }
        internal fun allowsLoginVerification(context: Context, runId: String, pkg: String, permit: String): Boolean =
            DirectMode.isEnabled(context) && instance?.engine?.allowsLoginVerification(runId, pkg, permit) == true
    }
    private val context = context.applicationContext
    private val stateFile = DirectRunStateFile(context.noBackupFilesDir)
    // Migrate before the engine can increment calls for the first new request.
    private val usageLedger = ModelUsageLedger(context.noBackupFilesDir).also { ledger -> runCatching { ledger.initialize() } }
    private val gui = GuiGroundingClient(context)
    private val reviewMemory = TaskReviewMemory(File(context.noBackupFilesDir, "task-review-memory-v1.json"))
    private val engine = SplitTaskEngine(stateFile.read(), stateFile::write,
        enhancementEnabled = { ModelProviders(context).routing().enhancementEnabled },
        reviewMemory = { packageName -> reviewMemory.context(packageName) })
    private val attachments = ChatAttachmentStore(context)
    private val provider = ModelApi(context)
    private val reviewAssistant = TaskReviewAssistant(provider, reviewMemory)
    private val providerConnection = java.util.concurrent.atomic.AtomicReference<java.net.HttpURLConnection?>()
    private fun cancelCalls() { web.cancel(); gui.cancel(); runCatching { providerConnection.getAndSet(null)?.disconnect() } }
    private val web = AndroidWebResearch(context)
    private var webRunId: String? = null
    private val screenshots = ScreenshotArchive(File(context.noBackupFilesDir, "direct-screenshots-v1"))
    private fun retentionDays() = context.getSharedPreferences("doppel", Context.MODE_PRIVATE).getInt("screenshot_retention_days", 7)
    private fun screenshotRunIds(): Set<String> {
        val items = engine.list().getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it).getString("id") }.toSet()
    }
    private fun pruneScreenshots() = screenshots.prune(retentionDays(), screenshotRunIds())
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
                    java.util.Base64.getDecoder().decode(encoded), data.optJSONObject("visual_frame") ?: JSONObject(),
                    retentionDays(), screenshotRunIds())
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
    private val chatLock = Any()
    private fun conversationIntent(body: JSONObject): JSONObject = synchronized(chatLock) {
        val conversationId = body.optString("conversation_id")
        val requestId = body.optString("request_id")
        val runId = body.optString("run_id")
        require(body.optString("message").trim().length in 1..12000) { "消息长度无效" }
        require(conversationId.length <= 200 && requestId.length <= 160 && runId.length <= 160) { "对话标识无效" }
        val canSave = conversationId.isNotBlank() && requestId.isNotBlank()
        if (canSave) reviewMemory.responseFor(conversationId, requestId)?.let { return@synchronized it }
        val run = runId.takeIf { it.isNotBlank() }?.let { engine.internalRun(it) }
        val packageName = run?.optString("last_observed_package").orEmpty().ifBlank { run?.optJSONObject("last_receipt")?.let {
            it.optString("foreground_package").ifBlank { it.optString("package_name") }
        }.orEmpty() }
        val memory = reviewMemory.context(packageName)
        val history = body.optJSONArray("history") ?: JSONArray()
        require(!body.has("attachments") || body.opt("attachments") is JSONArray) { "附件列表格式无效" }
        val currentAttachments = attachments.validate(body.optJSONArray("attachments") ?: JSONArray())
        val selectedAttachments = ChatAttachmentContext.select(currentAttachments, history)
        val refs = attachments.validate(selectedAttachments.getJSONArray("items"))
        val messages = JSONArray()
        for (i in 0 until history.length()) history.optJSONObject(i)?.let { messages.put(it) }
        messages.put(JSONObject().put("role", "user").put("content", body.optString("message").trim()).put("attachments", currentAttachments))
        val prompt = ConversationIntent.request(messages)
        prompt.getJSONObject(0).put("content", ConversationIntent.SYSTEM_PROMPT + ConversationIntent.CHAT_MEMORY_PROMPT + ConversationIntent.WEB_PROMPT +
            if (canSave) "" else "\n本次客户端不支持写入记忆，memory_changes 必须为空。")
        if (refs.length() > 0) prompt.getJSONObject(0).put("content", prompt.getJSONObject(0).getString("content") + ChatAttachmentContext.PROMPT +
            "\n需要读取文档时先只输出 {\"intent\":\"read_attachment\",\"attachment_id\":\"附件id\",\"operation\":\"info|search|read\",\"query\":\"搜索词或空\",\"offset\":0,\"limit\":4000,\"context_summary\":\"此前已确认且与问题相关的事实及位置，不超过2000字\"}。工具结果按实际体积保留，小文件不用重复总结；仅当预计下一段读取超过remaining_context_chars时，在context_summary中保留即将被裁剪且与问题相关的已确认事实，不需要额外总结请求。收到工具结果后继续判断或回答。最多调用${ChatAttachmentContext.MAX_READS}次，尽量直接读取相关片段避免重复info/search；预算耗尽后必须用已读资料回答并标注缺失，不得谎称已读未提供的部分；无需内容就直接回答。")
        val input = JSONObject(prompt.getJSONObject(1).getString("content")).put("long_term_memory", memory)
        if (refs.length() > 0) input.put("attachments", selectedAttachments.put("items", refs))
        if (run != null) input.put("task_evidence", TaskReviewAssistant.chatEvidence(reviewMemory.review(run)).put("package_name", packageName))
        prompt.getJSONObject(1).put("content", input.toString())
        // Chat reading has its own transport: pausing a device task must not cancel this reply.
        val chatWeb by lazy { AndroidWebResearch(context) }
        val response = ChatAttachmentContext.chat(prompt, refs, attachments::readText, webTool = { args ->
            when (args.getString("intent")) {
                "read_web" -> chatWeb.readPage(args)
                "search_web" -> chatWeb.search(args.optString("query"))
                else -> error("未知资料工具")
            }
        }) { next ->
            provider.complete(ChatAttachmentContext.withImages(JSONObject().put("_doppel_role", "primary").put("stream", false)
                .put("max_completion_tokens", 1400).put("messages", next), refs, attachments::imageDataUrl))
        }
        val content = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        val decision = ConversationIntent.parse(content)
        val raw = runCatching { JSONObject(content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()) }.getOrNull()
        val result = JSONObject().put("intent", when (decision.kind) {
            ConversationIntent.Kind.TASK -> "task"; ConversationIntent.Kind.CONVERSATION -> "conversation"; else -> "uncertain"
        }).put("confidence", decision.confidence).put("task_goal", decision.goal).put("title", decision.title)
            .put("question", decision.question).put("reason", "model_router")
        result.put("reply", raw?.optString("reply").orEmpty().take(4000))
        response.optJSONObject("_doppel_attachment_reads")?.let { result.put("attachment_diagnostics", it) }
        response.optJSONObject("_doppel_reference_reads")?.let { result.put("reference_diagnostics", it) }
        if (decision.kind == ConversationIntent.Kind.TASK && !body.optBoolean("device_available", false)) {
            result.put("intent", "task").put("message", "这是手机任务，但当前还没有绑定设备。")
        }
        if (canSave) {
            try {
                val changes = ConversationIntent.memoryChanges(raw ?: JSONObject(), decision, memory, packageName)
                val receipt = reviewMemory.applyChanges(changes, conversationId, requestId, runId, run?.optString("goal").orEmpty(), result)
                result.put("memory_result", JSONObject().put("applied", receipt.optInt("applied"))
                    .put("saved", receipt.optInt("saved")).put("deleted", receipt.optInt("deleted")))
            } catch (error: Exception) {
                // Keep the useful answer, but never disguise a failed write as remembered knowledge.
                result.put("memory_result", JSONObject().put("error", error.message?.take(180) ?: "长期记忆未保存"))
            }
        }
        result
    }
    fun hasUnfinishedRun() = engine.hasUnfinished()
    internal fun serverRuns(accountId: String, sessionId: String, remoteOnly: Boolean = false) = engine.serverRuns(accountId, sessionId, remoteOnly)
    internal fun serverTask(accountId: String, sessionId: String) = engine.serverTask(accountId, sessionId)
    internal fun serverTaskEvents(accountId: String, sessionId: String) = engine.serverTaskEvents(accountId, sessionId)
    internal fun serverCreate(body: JSONObject, id: String, owner: SplitTaskEngine.ServerOwner) = engine.createOwned(body, id, owner)
    internal fun serverControls(run: JSONObject) = engine.serverControls(run)
    internal fun serverControl(id: String, accountId: String, sessionId: String, revision: Long, action: String, current: () -> Boolean): JSONObject {
        synchronized(engine) {
            var changed = false
            val queued = engine.get(id).optString("status") == "queued"
            try {
                return engine.serverControl(id, accountId, sessionId, revision, action) {
                    check(current()) { "operation_expired" }
                    changed = true
                    if (!queued) {
                        TaskControl.invalidate()
                        DoppelAccessibilityService.instance?.stopActionFeedback()
                    }
                }
            } finally { if (changed && !queued && action != "resume") cancelCalls() }
        }
    }
    internal fun serverStop(id: String): JSONObject {
        synchronized(engine) {
            var changed = false
            try {
                if (engine.get(id).optString("status") !in setOf("queued", "completed", "failed", "cancelled")) {
                    changed = true
                    TaskControl.invalidate()
                    DoppelAccessibilityService.instance?.stopActionFeedback()
                }
                val result = engine.control(id, "cancel", JSONObject())
                engine.checkpoint() // A previous failed save must not turn an in-memory terminal state into a successful ACK.
                return result
            } finally { if (changed) cancelCalls() }
        }
    }
    internal fun checkpoint() = engine.checkpoint()
    private fun localOwner(): SplitTaskEngine.ServerOwner? = dev.doppel.sdk.cloud.CloudSessionStore(context).load()?.let {
        SplitTaskEngine.ServerOwner(it.accountId, it.sessionId, false)
    }
    /** No request()/pump(): a connected desktop cannot start or advance model work. */
    internal fun companionState(): JSONObject? {
        check(DirectMode.isEnabled(context)) { "本机直连模式未启用" }
        return engine.companionState()
    }
    internal fun companionTaskEvents(): JSONArray {
        check(DirectMode.isEnabled(context)) { "本机直连模式未启用" }
        val owner = localOwner()
        return engine.companionTaskEvents(owner?.accountId, owner?.sessionId)
    }
    /** Status polling must not enter request(), whose completion can wake the planner. */
    internal fun statusOrNull(id: String): String? {
        check(DirectMode.isEnabled(context)) { "本机直连模式未启用" }
        return engine.statusOrNull(id)
    }
    private fun isCurrent(work: SplitTaskEngine.Work) = DirectMode.isEnabled(context) &&
        FirstUseConsent.isAccepted(context) && engine.isCurrent(work) &&
        TaskControl.captureExecutionPermit(work.runId, TaskControl.currentGeneration())()
    private fun pump() {
        if (!engine.readyForWork()) return
        if (!pumping.compareAndSet(false, true)) return
        executor.execute {
            try {
                while (DirectMode.isEnabled(context)) {
                    val work = engine.takeWork() ?: break
                    if (!isCurrent(work)) { engine.interrupt("执行授权已失效，请重新发起任务"); break }
                    if (work.localTool != null) {
                        try {
                            FirstUseConsent.requireAccepted(context)
                            if (work.localTool in setOf("read_web", "search_web") && webRunId != work.runId) {
                                web.cancel(); webRunId = work.runId
                            }
                            val result = when (work.localTool) {
                                "locate_ui" -> gui.locate(work.payload) { isCurrent(work) }
                                "search_web" -> web.search(work.payload.getString("query")) { isCurrent(work) }
                                "read_web" -> web.readPage(work.payload) { isCurrent(work) }
                                "read_attachment" -> {
                                    check(isCurrent(work)) { "任务已暂停" }
                                    ChatAttachmentContext.page(work.payload, work.payload.getJSONArray("allowed_attachments"), attachments::readText)
                                }
                                else -> error("资料工具尚不可用")
                            }
                            engine.acceptLocal(work, result)
                        } catch (error: Exception) {
                            val diagnostic = when (work.localTool) {
                                "locate_ui" -> JSONObject().put("transport_diagnostic", GuiGroundingFailure.from(error))
                                "read_attachment" -> JSONObject().put("attachment_id", work.payload.optString("attachment_id")).put("error", error.message?.take(200) ?: "附件读取失败")
                                else -> null
                            }
                            engine.acceptLocal(work, diagnostic, true)
                        }
                    } else {
                        var owned: java.net.HttpURLConnection? = null
                        try { engine.accept(work, provider.complete(ChatAttachmentContext.withImages(work.payload, work.payload.optJSONArray("_doppel_attachments") ?: JSONArray(), attachments::imageDataUrl), onConnection = { connection ->
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
        // Reading/managing memory never wakes a planner or makes a paid model call.
        if (route.firstOrNull() == "memories") return when {
            route.size == 1 && method == "GET" -> reviewMemory.listAll()
            route.size == 1 && method == "POST" -> reviewMemory.create(body ?: error("缺少记忆内容"))
            route.size == 2 && method in setOf("PATCH", "PUT") -> reviewMemory.update(route[1], body ?: error("缺少记忆内容"),
                body.optLong("expected_revision").takeIf { body.has("expected_revision") })
            route.size == 2 && method == "DELETE" -> reviewMemory.delete(route[1], body?.optLong("expected_revision")?.takeIf { body.has("expected_revision") })
            else -> error("记忆操作无效")
        }
        val result = when {
            route == listOf("devices") && method == "POST" -> JSONObject().put("id", DEVICE_ID).put("name", "本机直连设备")
            route == listOf("devices") && method == "GET" -> JSONObject().put("items", JSONArray().put(JSONObject().put("id", DEVICE_ID)))
            route == listOf("devices", DEVICE_ID, "queue") && method == "GET" -> engine.queueSnapshot()
            route == listOf("devices", DEVICE_ID, "data-cleanup") && method == "GET" -> JSONObject().put("items", JSONArray())
            route == listOf("devices", DEVICE_ID, "commands") && method == "GET" -> engine.poll().also {
                if (it.isNull("command")) Thread.sleep(200)
                pump()
            }
            route == listOf("devices", DEVICE_ID, "results") && method == "POST" -> acceptResult(body ?: error("缺少设备结果")).also { pump() }
            route == listOf("data-retention") && method == "GET" -> JSONObject().put("days", retentionDays())
            route == listOf("data-retention") && method in setOf("PATCH", "PUT", "POST") -> {
                val days = body?.getInt("days") ?: error("缺少保留天数")
                require(days in setOf(0, 1, 7, 30, 90)) { "截图保留时间无效" }
                check(context.getSharedPreferences("doppel", Context.MODE_PRIVATE).edit().putInt("screenshot_retention_days", days).commit())
                pruneScreenshots(); JSONObject().put("days", days)
            }
            route == listOf("runs") && method == "POST" -> {
                check(ModelProviders(context).isReady()) { "请先配置并检测支持视觉的模型" }
                val input = JSONObject((body ?: error("缺少任务")).toString())
                for (key in listOf("attachments", "reference_attachments")) {
                    require(!input.has(key) || input.opt(key) is JSONArray) { "附件列表格式无效" }
                    input.optJSONArray(key)?.let { input.put(key, attachments.validate(it)) }
                }
                engine.createOwned(input, null, localOwner()).also { pump() }
            }
            route.size == 3 && route[0] == "runs" && route[2] == "start" && method == "POST" -> {
                check(ModelProviders(context).isReady()) { "请先配置并检测支持视觉的模型" }
                check(TaskControl.captureExecutionPermit(route[1],TaskControl.currentGeneration())()) { "执行授权已失效" }
                engine.start(route[1]).also { pump() }
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
                pump()
                controlled
            }
            else -> error("此功能需要网关服务，本机直连模式暂不支持")
        }
        // Only execution routes above advance the planner; history/settings reads do not.
        return result
    }

}

package dev.doppel.sdk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

internal class GatewayHttpException(val statusCode: Int, message: String) : IllegalStateException(message)

class Gateway(private val context: Context) {
    companion object { private val conversationSubmissions = Any() }
    val prefs = SelfCoreRollback.preferences(context)
    fun isDirectMode() = DirectMode.isEnabled(context)
    fun isConnected(): Boolean = if (isDirectMode()) ModelProviders(context).isReady() && prefs.getString("device_id", "") == DirectRuntime.DEVICE_ID
        else !prefs.getString("token", "").isNullOrBlank() && !prefs.getString("device_id", "").isNullOrBlank()
    /** Explicit foreground submission only; status reads and automatic tasks never switch connections. */
    internal fun prepareUserConnection(): String {
        FirstUseConsent.requireAccepted(context)
        DirectMode.enableConfiguredDefault(context)
        check(isConnected()) {
            if (isDirectMode()) "请先在模型设置中验证所选模型的视觉能力"
            else if (DirectMode.available(context)) "请在模型设置中验证并启用本机连接，或完成网关连接"
            else "请先连接网关并绑定设备"
        }
        return prefs.getString("device_id", "").orEmpty()
    }
    private fun conversationScope(): String {
        val value = if (isDirectMode()) "direct:${context.packageName}" else
            "${prefs.getString("base_url", "")}|${prefs.getString("device_id", "")}|${prefs.getString("token", "")}"
        return java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    internal fun conversationKey(): String = "${conversationScope()}:${prefs.getString("conversation_epoch", "").orEmpty().ifBlank { "legacy" }}"
    internal fun reviewScope(): String = captureReviewConnection().scope
    /** The review and its credential must come from one preferences snapshot. */
    internal fun captureReviewConnection(): ReviewConnection {
        val values = prefs.all
        val direct = DirectMode.available(context) && values["direct_mode"] == true
        val base = values["base_url"] as? String
        val token = values["token"] as? String ?: ""
        val device = values["device_id"] as? String ?: ""
        val value = if (direct) "direct:${context.packageName}" else "${base.orEmpty()}|$device|$token"
        val scope = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        return ReviewConnection(scope, direct, base ?: "http://10.0.2.2:8765", token, device)
    }
    internal inner class ReviewConnection internal constructor(
        val scope: String, val direct: Boolean, private val base: String, private val token: String, val deviceId: String
    ) {
        fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
            FirstUseConsent.requireRequest(context, method, path)
            return if (direct) DirectRuntime.get(context).request(method, path, body)
            else remoteRequest(method, path, body, base, token, deviceId)
        }
    }
    private fun chatRecord(key: String): JSONObject = prefs.getString("conversation_chat_$key", null)?.let {
        try { JSONObject(it) } catch (_: Exception) { error("聊天记录无法读取，请检查本机存储") }
    } ?: JSONObject().put("epoch", key.substringAfter(':')).put("messages", JSONArray()).put("run_ids", JSONArray())
    private fun currentChat(): JSONObject = synchronized(prefs) {
        val key = conversationKey()
        val record = chatRecord(key)
        if (!prefs.contains("conversation_chat_$key") && prefs.contains("chat_messages") &&
            (!prefs.contains("conversation_scope") || prefs.getString("conversation_scope", "") == conversationScope())) {
            record.put("messages", JSONArray(prefs.getString("chat_messages", "[]")))
                .put("title", prefs.getString("chat_title", "")).put("tail", selectedConversationRun().orEmpty())
            record.getJSONArray("run_ids").apply { selectedConversationRun()?.takeIf { it.isNotBlank() }?.let { put(it) } }
            check(prefs.edit().putString("conversation_chat_$key", record.toString()).remove("chat_messages").remove("chat_title").commit()) { "聊天记录迁移未保存" }
        }
        record
    }
    /** Bind a delayed usage response to the connection that actually supplied it. */
    fun readUsage(path: String = "/usage", fallbackPoints: Boolean = false): JSONObject {
        val scope = conversationScope()
        val response = try { request("GET", path) } catch (error: Exception) {
            if (!fallbackPoints || scope != conversationScope()) throw error
            request("GET", "/points")
        }
        check(scope == conversationScope()) { "连接已更改，请重新读取用量" }
        return response.put("_doppel_usage_scope", scope)
    }
    fun usageSnapshot(response: JSONObject): JSONObject {
        val scope = response.optString("_doppel_usage_scope").ifBlank { conversationScope() }
        check(scope == conversationScope()) { "连接已更改，请重新读取用量" }
        return UsageBalance.snapshot(response).put("scope", scope)
    }
    /** Refresh and recharge use one transaction, so neither can overwrite the other's balance. */
    fun updateUsageBalance(response: JSONObject, baselineKey: String,
                           update: (JSONObject?, JSONObject, android.content.SharedPreferences.Editor) -> Unit): JSONObject = synchronized(prefs) {
        val previous = prefs.getString(baselineKey, null)?.let { JSONObject(it) }
        val snapshot = UsageBalance.advance(previous, usageSnapshot(response))
        val editor = prefs.edit()
        update(previous, snapshot, editor)
        check(editor.putString(baselineKey, snapshot.toString()).commit()) { "余额记录未保存" }
        snapshot
    }
    internal fun conversationMessages(): JSONArray = ConversationVersions.chats(ConversationVersions.repository(currentChat()).getMessages())
    internal fun conversationTitle(): String = currentChat().optString("title")
    internal fun appendConversationReply(key: String, user: String, reply: String, title: String?) = synchronized(prefs) {
        check(key == conversationKey()) { "对话已切换，请在当前对话重新发送" }
        currentChat()
        appendCapturedConversationReply(key, selectedConversationRun().orEmpty(), user, reply, title)
    }
    /** A completed chat belongs to its request's record even after its Activity/selection changes. */
    internal fun appendCapturedConversationReply(key: String, afterRunId: String, user: String, reply: String, title: String?, requestId: String = "", attachments: JSONArray = JSONArray()) = synchronized(prefs) {
        require(key.substringBefore(':').matches(Regex("[a-f0-9]{64}")) && key.substringAfter(':', "").isNotBlank() && key.length <= 200)
        val record = chatRecord(key)
        val refs = ChatAttachmentContext.metadata(attachments)
        val repository = ConversationVersions.repository(record)
        val nodeId = "chat:" + requestId.ifBlank { java.util.UUID.randomUUID().toString() }
        if (repository.contains(nodeId)) return@synchronized
        val pending = pendingChat(key)?.takeIf { it.optString("request_id") == requestId }
        val parent = if (pending?.has("head_id") == true) pending.opt("head_id") as? String else repository.headId
        val activeParent = repository.headId == parent
        repository.addOrUpdateMessage(parent, ConversationVersions.chat(nodeId, user, reply, afterRunId, requestId, refs))
        if (activeParent) repository.switchToBranch(nodeId)
        ConversationVersions.save(record, repository)
        if (record.optString("title").isBlank()) record.put("title", title?.takeIf { it.isNotBlank() }?.take(120) ?: user.take(36))
        record.put("updated_at", System.currentTimeMillis())
        val edit = stageConversationRecord(key, record, prefs.edit())
        if (requestId.isNotBlank() && pendingChat(key)?.optString("request_id") == requestId) edit.remove("conversation_intent_$key")
        clearMatchingAttachmentDraft(edit, key, refs)
        // The reply, replay marker and matching draft must commit together across process death.
        if (key == conversationKey() && afterRunId == selectedConversationRun().orEmpty() && prefs.getString("draft_goal", "").orEmpty().trim() == user)
            edit.putString("draft_goal", "")
        check(edit.commit()) { "聊天记录未保存，请检查本机存储" }
    }
    private fun stageConversationRecord(key: String, record: JSONObject, edit: android.content.SharedPreferences.Editor): android.content.SharedPreferences.Editor {
        val serialized = record.toString()
        // Model context is bounded independently; saved branches are never silently truncated.
        val size = serialized.toByteArray(Charsets.UTF_8).size
        check(size <= 2 * 1024 * 1024) { "本段对话已达容量上限，历史与当前输入均已保留，请新建对话" }
        val otherBytes = prefs.all.entries.asSequence()
            .filter { it.key.startsWith("conversation_chat_") && it.key != "conversation_chat_$key" }
            .sumOf { (it.value as? String).orEmpty().toByteArray(Charsets.UTF_8).size.toLong() }
        check(otherBytes + size <= 8 * 1024 * 1024) { "聊天记录已达本机容量上限，历史与当前输入均已保留" }
        return edit.putString("conversation_chat_$key", serialized)
    }
    private fun clearMatchingAttachmentDraft(edit: android.content.SharedPreferences.Editor, key: String, sent: JSONArray) {
        val draft = prefs.getString("attachment_draft_$key", null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return
        if (ChatAttachmentContext.ids(draft) == ChatAttachmentContext.ids(sent)) edit.remove("attachment_draft_$key")
    }
    private fun pendingChat(key: String) = prefs.getString("conversation_intent_$key", null)?.let { JSONObject(it) }
    internal fun conversationRequestId(key: String, tail: String, message: String, attachments: JSONArray = JSONArray()): String = synchronized(prefs) {
        require(key == conversationKey()) { "对话已切换，请重新发送" }
        val head = ConversationVersions.repository(currentChat()).headId
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(JSONArray().put(tail).put(head).put(message).put(JSONArray(ChatAttachmentContext.ids(ChatAttachmentContext.metadata(attachments)))).toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        pendingChat(key)?.takeIf { it.optString("fingerprint") == fingerprint }?.let { return@synchronized it.getString("request_id") }
        val id = java.util.UUID.randomUUID().toString()
        check(prefs.edit().putString("conversation_intent_$key", JSONObject().put("fingerprint", fingerprint).put("request_id", id).put("head_id", head ?: JSONObject.NULL).toString()).commit()) { "消息提交记录未保存" }
        id
    }
    internal fun finishConversationRequest(key: String, requestId: String) = synchronized(prefs) {
        if (pendingChat(key)?.optString("request_id") == requestId)
            check(prefs.edit().remove("conversation_intent_$key").commit()) { "消息提交状态未保存" }
    }
    internal fun conversationContext(expectedKey: String = conversationKey(), expectedTail: String = selectedConversationRun().orEmpty(), connection: ReviewConnection = captureReviewConnection()): JSONArray {
        val chats = synchronized(prefs) {
            check(expectedKey == conversationKey() && expectedTail == selectedConversationRun().orEmpty()) { "对话或关联任务已变化，请重新发送" }
            conversationMessages()
        }
        val tasks = if (expectedTail.isBlank()) JSONArray() else connection.request("GET", "/runs/$expectedTail/conversation").optJSONArray("items") ?: JSONArray()
        check(expectedKey == conversationKey() && expectedTail == selectedConversationRun().orEmpty()) { "对话或关联任务已变化，请重新发送" }
        return ConversationChatContext.merge(tasks, chats)
    }
    /** Only the selected path's last user turn can be edited. Task records stay immutable. */
    internal fun revisionTarget(connection: ReviewConnection = captureReviewConnection()): JSONObject? {
        val target = synchronized(prefs) {
            val key = conversationKey()
            check(connection.scope == key.substringBefore(':')) { "连接已变化，请重新读取消息" }
            val node = ConversationVersions.repository(currentChat()).getMessages().lastOrNull() ?: return null
            if (node.optBoolean("deleted")) return null
            JSONObject(node.toString()).put("key", key).put("tail", selectedConversationRun().orEmpty()).put("node_id", node.getString("id"))
        }
        if (target.getString("kind") == "chat") {
            val rows = target.getJSONArray("messages")
            val user = (0 until rows.length()).mapNotNull(rows::optJSONObject).firstOrNull { it.optString("role") == "user" } ?: return null
            target.put("text", user.optString("content")).put("attachments", ChatAttachmentContext.metadata(user.optJSONArray("attachments")))
        } else {
            val run = connection.request("GET", "/runs/${target.getString("run_id")}")
            check(run.optString("status") in ConversationHistory.terminal) { "请先结束当前任务，再修改这条消息；旧任务不会重放" }
            target.put("text", run.optString("goal")).put("attachments", ChatAttachmentContext.metadata(run.optJSONArray("attachments")))
        }
        synchronized(prefs) { requireRevisionTarget(target) }
        return target
    }
    private fun requireRevisionTarget(target: JSONObject): ConversationMessageRepository {
        check(target.getString("key") == conversationKey()) { "对话已切换，请重新编辑" }
        val repository = ConversationVersions.repository(currentChat())
        requireRevisionHead(repository, target.getString("node_id"))
        return repository
    }
    private fun requireRevisionHead(repository: ConversationMessageRepository, nodeId: String) {
        check(repository.headId == nodeId && !repository.getMessage(nodeId).getJSONObject("message").optBoolean("deleted")) {
            "消息版本已变化或已删除，原消息与编辑草稿已保留，请重新编辑"
        }
    }
    /** Preparation captures context and a durable retry id; it never hides the old question/answer. */
    internal fun prepareConversationRevision(target: JSONObject, text: String, refs: JSONArray,
                                             connection: ReviewConnection = captureReviewConnection()): JSONObject {
        require(text.trim().isNotEmpty() && text.length <= 12000) { "消息内容为空或过长" }
        val revision = synchronized(prefs) {
            val repository = requireRevisionTarget(target)
            val key = target.getString("key")
            check(connection.scope == key.substringBefore(':')) { "连接已变化，请重新编辑" }
            val parent = repository.getMessage(target.getString("node_id")).opt("parentId") as? String
            val path = if (parent == null) emptyList() else repository.getMessages(parent)
            val attachments = ChatAttachmentContext.metadata(refs)
            val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(JSONArray().put(target.getString("node_id"))
                .put(text).put(JSONArray(ChatAttachmentContext.ids(attachments))).toString().toByteArray()).joinToString("") { "%02x".format(it) }
            val pendingKey = "conversation_revision_$key"
            val pending = prefs.getString(pendingKey, null)?.let { JSONObject(it) }
            val id = pending?.takeIf { it.optString("fingerprint") == fingerprint }?.getString("request_id") ?: TaskSubmissionKey.create()
            check(prefs.edit().putString(pendingKey, JSONObject().put("fingerprint", fingerprint).put("request_id", id).toString()).commit()) { "编辑提交记录未保存" }
            JSONObject().put("key", key).put("tail", ConversationVersions.tail(path)).put("original_tail", target.optString("tail"))
                .put("target_node_id", target.getString("node_id")).put("parent_node_id", parent ?: JSONObject.NULL)
                .put("request_id", id).put("text", text).put("attachments", attachments).put("history_chats", ConversationVersions.chats(path))
        }
        val tail = revision.getString("tail")
        val tasks = if (tail.isBlank()) JSONArray() else connection.request("GET", "/runs/$tail/conversation").optJSONArray("items") ?: JSONArray()
        revision.put("history", ConversationChatContext.merge(tasks, revision.getJSONArray("history_chats"))).remove("history_chats")
        synchronized(prefs) { requireRevisionTarget(target) }
        return revision
    }
    internal fun commitConversationRevision(revision: JSONObject, reply: String, title: String? = null) = synchronized(prefs) {
        require(reply.isNotBlank()) { "模型未返回回答，原消息已保留" }
        val id = "chat:${revision.getString("request_id")}"
        val node = ConversationVersions.chat(id, revision.getString("text"), reply, revision.getString("tail"),
            revision.getString("request_id"), ChatAttachmentContext.metadata(revision.optJSONArray("attachments")))
        saveConversationRevision(revision, node, title)
    }
    private fun saveConversationRevision(revision: JSONObject, node: JSONObject, title: String?) {
        val key = revision.getString("key")
        check(key == conversationKey()) { "对话已切换，原消息与编辑草稿已保留" }
        val record = currentChat()
        val repository = ConversationVersions.repository(record)
        if (repository.contains(node.getString("id"))) return
        requireRevisionHead(repository, revision.getString("target_node_id"))
        repository.addOrUpdateMessage(revision.opt("parent_node_id") as? String, node)
        repository.switchToBranch(node.getString("id"))
        ConversationVersions.save(record, repository).put("updated_at", System.currentTimeMillis())
        if (record.optString("title").isBlank()) record.put("title", title?.takeIf { it.isNotBlank() }?.take(120) ?: revision.getString("text").take(36))
        val edit = stageConversationRecord(key, record, prefs.edit()).putString("conversation_scope", key.substringBefore(':'))
            .putString("conversation_tail", record.optString("tail")).remove("conversation_revision_$key")
        val markerKey = "conversation_edit_$key"
        val marker = prefs.getString(markerKey, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (marker?.optJSONObject("target")?.optString("node_id") == revision.getString("target_node_id")) edit.remove(markerKey)
        clearMatchingAttachmentDraft(edit, key, revision.getJSONArray("attachments"))
        if (prefs.getString("draft_goal", "").orEmpty().trim() == revision.getString("text").trim()) edit.putString("draft_goal", "")
        check(edit.commit()) { "编辑结果未保存，原消息与编辑草稿已保留" }
    }
    internal fun createConversationRevisionRun(revision: JSONObject, body: JSONObject,
                                                connection: ReviewConnection = captureReviewConnection()): JSONObject = synchronized(conversationSubmissions) {
        synchronized(prefs) {
            check(revision.getString("key") == conversationKey() && connection.scope == conversationScope()) { "对话或连接已变化，请重新发送" }
            val repository = ConversationVersions.repository(currentChat())
            requireRevisionHead(repository, revision.getString("target_node_id"))
        }
        val input = JSONObject(body.toString()).put("defer_start", true).put("request_id", revision.getString("request_id"))
        val parent = revision.getString("tail")
        input.remove("parent_run_id")
        if (parent.isNotBlank()) input.put("parent_run_id", parent)
        val attachments = ChatAttachmentContext.metadata(revision.optJSONArray("attachments"))
        val prior = ChatAttachmentContext.select(JSONArray(), revision.getJSONArray("history")).getJSONArray("items")
        if (attachments.length() > 0 || prior.length() > 0) {
            check(connection.direct) { "当前连接不支持本机聊天附件，请使用本机模型连接" }
            input.put("attachments", attachments).put("reference_attachments", prior)
        }
        val run = connection.request("POST", "/runs", input)
        synchronized(prefs) { saveConversationRevision(revision, ConversationVersions.task(run.getString("id")), body.optString("title")) }
        run
    }
    internal fun conversationVersionGroups(): JSONArray = synchronized(prefs) {
        ConversationVersions.groups(ConversationVersions.repository(currentChat()))
    }
    /** Browsing calls no model, run creation, resume or task-control endpoint. */
    internal fun selectConversationVersion(group: JSONObject, versionId: String) = synchronized(prefs) {
        val key = conversationKey(); val record = currentChat(); val repository = ConversationVersions.repository(record)
        val anchor = group.getJSONObject("anchor")
        val selected = repository.getMessages().firstOrNull {
            it.optString("kind") == anchor.optString("kind") && it.optString("turn_id") == anchor.optString("turn_id")
        } ?: error("消息版本已变化，请重新读取")
        require(versionId in repository.getBranches(selected.getString("id"))) { "版本不属于此问题" }
        repository.switchToBranch(versionId)
        ConversationVersions.save(record, repository)
        check(stageConversationRecord(key, record, prefs.edit()).putString("conversation_scope", key.substringBefore(':'))
            .putString("conversation_tail", record.optString("tail")).commit()) { "消息版本选择未保存" }
    }
    internal fun savedConversations(): JSONArray = synchronized(prefs) {
        currentChat()
        val prefix = "conversation_chat_${conversationScope()}:"
        JSONArray(prefs.all.keys.filter { it.startsWith(prefix) }.mapNotNull { key ->
            val record = chatRecord(key.removePrefix("conversation_chat_"))
            if (!ConversationVersions.hasSavedChats(record) && (record.optJSONArray("run_ids")?.length() ?: 0) == 0) return@mapNotNull null
            JSONObject().put("key", key.removePrefix("conversation_chat_")).put("title", record.optString("title"))
                .put("tail", record.optString("tail")).put("updated_at", record.optLong("updated_at"))
        }.sortedByDescending { it.optLong("updated_at") })
    }
    internal fun conversationMessagesForRun(runId: String): JSONArray = synchronized(prefs) {
        val prefix = "conversation_chat_${conversationScope()}:"
        val record = prefs.all.keys.asSequence().filter { it.startsWith(prefix) }.map { chatRecord(it.removePrefix("conversation_chat_")) }
            .firstOrNull { record -> record.optJSONArray("run_ids")?.let { ids -> (0 until ids.length()).any { ids.optString(it) == runId } } == true }
            ?: return@synchronized JSONArray()
        val repository = ConversationVersions.repository(record)
        if (repository.getMessages().none { it.optString("run_id") == runId } && repository.contains("task:$runId"))
            repository.switchToBranch("task:$runId")
        ConversationVersions.chats(repository.getMessages())
    }
    internal fun selectConversation(key: String) = synchronized(prefs) {
        require(key.startsWith("${conversationScope()}:") && prefs.contains("conversation_chat_$key")) { "对话记录不属于当前连接" }
        val record = chatRecord(key)
        check(prefs.edit().putString("conversation_scope", conversationScope()).putString("conversation_epoch", record.getString("epoch"))
            .putString("conversation_tail", record.optString("tail")).commit()) { "对话选择未保存" }
    }
    /** Only an explicit user visit opens a discussion for an independent/automatic task. */
    internal fun selectTaskConversation(run: JSONObject) = synchronized(prefs) {
        val id = run.getString("id")
        val prefix = "conversation_chat_${conversationScope()}:"
        val saved = prefs.all.keys.firstOrNull { name -> name.startsWith(prefix) &&
            chatRecord(name.removePrefix("conversation_chat_")).optJSONArray("run_ids")?.let { ids ->
                (0 until ids.length()).any { ids.optString(it) == id }
            } == true }
        if (saved != null) selectConversation(saved.removePrefix("conversation_chat_")) else startNewConversation()
        val key = conversationKey(); val record = chatRecord(key)
        val repository = ConversationVersions.repository(record)
        if (!repository.contains("task:$id")) repository.addOrUpdateMessage(repository.headId, ConversationVersions.task(id))
        repository.switchToBranch("task:$id")
        ConversationVersions.save(record, repository).put("updated_at", System.currentTimeMillis())
        if (record.optString("title").isBlank()) record.put("title", run.optString("title").ifBlank { run.optString("goal").take(36) })
        check(stageConversationRecord(key, record, prefs.edit()).putString("conversation_tail", record.optString("tail")).commit()) { "对话关联未保存" }
    }
    fun startNewConversation() {
        synchronized(prefs) {
            currentChat()
            check(prefs.edit().remove("conversation_tail").putString("conversation_scope", conversationScope())
                .putString("conversation_epoch", java.util.UUID.randomUUID().toString()).commit()) { "新对话未保存" }
        }
    }
    /** null: legacy selection; empty: explicit new conversation; otherwise the selected tail. */
    fun selectedConversationRun(): String? = when {
        !prefs.contains("conversation_scope") -> null
        prefs.getString("conversation_scope", "") == conversationScope() -> prefs.getString("conversation_tail", "").orEmpty()
        else -> ""
    }
    /** Account memory helpers. Saving is deliberately explicit; callers should
     * only invoke saveMemory after showing the review and receiving confirmation. */
    fun listMemories(): JSONObject {
        check(!isDirectMode()) { "账号记忆在本机直连模式使用本机复盘记忆接口" }
        return request("GET", "/memories")
    }
    fun saveMemory(content: String): JSONObject {
        require(content.trim().length in 2..10000) { "记忆内容长度无效" }
        check(!isDirectMode()) { "账号记忆在本机直连模式使用本机复盘记忆接口" }
        return request("POST", "/memories", JSONObject().put("content", content.trim()))
    }
    fun deleteMemory(id: String): JSONObject {
        require(id.matches(Regex("[A-Za-z0-9_-]{8,160}"))) { "记忆编号无效" }
        check(!isDirectMode()) { "账号记忆在本机直连模式使用本机复盘记忆接口" }
        return request("DELETE", "/memories/$id")
    }
    /** Ask for a read-only analysis of a recorded run; this never resumes or dispatches it. */
    fun reviewRun(runId: String, question: String? = null): JSONObject {
        require(runId.matches(Regex("[A-Za-z0-9_-]{8,160}"))) { "任务编号无效" }
        val body = JSONObject().apply { question?.trim()?.takeIf { it.isNotEmpty() }?.let { put("question", it) } }
        return request("POST", "/runs/$runId/review", body)
    }
    /** User composer entry only. Scheduled and SDK-created independent runs keep their explicit scope. */
    fun createConversationRun(body: JSONObject): JSONObject = createConversationRun(body, captureReviewConnection())
    internal fun createConversationRun(body: JSONObject, connection: ReviewConnection): JSONObject = synchronized(conversationSubmissions) {
        val scope = conversationScope()
        val epoch = prefs.getString("conversation_epoch", "")
        val key = conversationKey()
        currentChat()
        val parent = if (prefs.getString("conversation_scope", "") == scope) prefs.getString("conversation_tail", "").orEmpty() else ""
        val input = JSONObject(body.toString()).put("defer_start", true).apply { if (parent.isNotBlank()) put("parent_run_id", parent) }
        val attachments = ChatAttachmentContext.metadata(body.optJSONArray("attachments"))
        val prior = ChatAttachmentContext.select(JSONArray(), conversationMessages()).getJSONArray("items")
        if (attachments.length() > 0 || prior.length() > 0) {
            check(connection.direct) { "当前连接不支持本机聊天附件，请使用本机模型连接" }
            input.put("attachments", attachments).put("reference_attachments", prior)
        }
        val pendingKey = "conversation_submission_$key"
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(input.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val pending = prefs.getString(pendingKey, null)?.let { JSONObject(it) }
        val requestId = pending?.takeIf { it.optString("fingerprint") == fingerprint }?.getString("request_id")
            ?: TaskSubmissionKey.create()
        check(prefs.edit().putString(pendingKey, JSONObject().put("fingerprint", fingerprint).put("request_id", requestId).toString()).commit()) {
            "任务提交记录未保存，请检查本机存储"
        }
        input.put("request_id", requestId)
        check(connection.scope == scope) { "连接已改变，请重新发送" }
        val run = connection.request("POST", "/runs", input)
        synchronized(prefs) {
                val chats = chatRecord(key)
                val repository = ConversationVersions.repository(chats)
                val runNode = ConversationVersions.task(run.getString("id"))
                if (!repository.contains(runNode.getString("id"))) repository.addOrUpdateMessage(repository.headId, runNode)
                repository.switchToBranch(runNode.getString("id"))
                ConversationVersions.save(chats, repository).put("updated_at", System.currentTimeMillis())
                if (chats.optString("title").isBlank()) chats.put("title", body.optString("title").ifBlank { run.optString("title").ifBlank { body.optString("goal").take(36) } })
                val editor = stageConversationRecord(key, chats, prefs.edit()).remove(pendingKey)
                clearMatchingAttachmentDraft(editor, key, attachments)
                if (scope == conversationScope() && epoch == prefs.getString("conversation_epoch", ""))
                    editor.putString("conversation_scope", scope).putString("conversation_tail", run.getString("id"))
                if (!editor.commit())
                    run.put("conversation_warning", "执行已创建，会话关联暂未写入磁盘")
        }
        run
    }
    /** Create background work without changing the user's conversation tail. */
    fun createAutomaticRun(body: JSONObject): JSONObject {
        val input = JSONObject(body.toString()).put("conversation_enabled", false).put("source", body.optString("source", "trigger"))
        return request("POST", "/runs", input)
    }
    /** Only an absent local record or HTTP 404 means missing; failures remain retryable. */
    internal fun runStatus(runId: String): String {
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,160}"))) { "任务编号无效" }
        val path = "/runs/$runId"
        FirstUseConsent.requireRequest(context, "GET", path)
        if (isDirectMode()) return DirectRuntime.get(context).statusOrNull(runId) ?: "missing"
        return try {
            remoteRequest("GET", path, null).getString("status").also {
                check(it.isNotBlank() && it != "missing") { "任务状态不可用" }
            }
        } catch (error: GatewayHttpException) {
            if (error.statusCode == 404) "missing" else throw error
        }
    }
    fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        FirstUseConsent.requireRequest(context, method, path)
        val family = path.substringBefore('?').trim('/').split('/').firstOrNull()
        if (family == "schedules")
            return ScheduleManager.get(context).request(method, path, body)
        if (isDirectMode()) return DirectRuntime.get(context).request(method, path, body).also { clearDeletedConversation(method, path) }
        return remoteRequest(method, path, body)
    }
    private fun remoteRequest(method: String, path: String, body: JSONObject?,
                              baseUrl: String = prefs.getString("base_url", "http://10.0.2.2:8765")!!,
                              token: String = prefs.getString("token", "").orEmpty(),
                              deviceId: String = prefs.getString("device_id", "").orEmpty()): JSONObject {
        require(body?.optJSONArray("attachments")?.length().let { it == null || it == 0 } &&
            body?.optJSONArray("reference_attachments")?.length().let { it == null || it == 0 }) { "当前连接不支持本机聊天附件，请使用本机模型连接" }
        val requestScope = java.security.MessageDigest.getInstance("SHA-256").digest("$baseUrl|$deviceId|$token".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val base = baseUrl.trimEnd('/')
        val uri = URI(base)
        require(uri.scheme in setOf("http", "https") && uri.userInfo == null && uri.host != null) { "服务地址无效" }
        val connection = URI(base + "/v1" + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10000; connection.readTimeout = 25000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer " + token)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                FirstUseConsent.requireRequest(context, method, path)
                connection.outputStream.use { FirstUseConsent.requireRequest(context, method, path); it.write(body.toString().toByteArray()) }
            }
            FirstUseConsent.requireRequest(context, method, path)
            val code = connection.responseCode
            if (code !in 200..299) throw GatewayHttpException(code, when(code) { 401 -> "身份验证无效，请检查凭据"; 402 -> "额度不足"; 403 -> "没有操作权限"; 501 -> "服务尚未启用"; else -> "服务请求失败 ($code)" })
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).also { result ->
                clearDeletedConversation(method, path)
                if (GatewayTaskEvents.isTaskMutation(method, path) && result.optString("device_id") == deviceId && deviceId.isNotBlank() &&
                    captureReviewConnection().scope == requestScope) {
                    DeviceWorkerService.instance?.observeCompanionRun(requestScope, result)
                    runCatching { GatewayTaskEvents.observe(context, requestScope, result) }
                        .onFailure { android.util.Log.w("DoppelCompanion", "gateway_event_save_failed") }
                }
            }
        } finally { connection.disconnect() }
    }
    private fun clearDeletedConversation(method: String, path: String) {
        val route = path.trim('/').split('/')
        if (method != "DELETE" || route.size != 2 || route[0] != "runs") return
        synchronized(prefs) {
            val edit = prefs.edit()
            if (prefs.getString("conversation_tail", "") == route[1]) edit.remove("conversation_tail")
            val prefix = "conversation_chat_${conversationScope()}:"
            for (key in prefs.all.keys.filter { it.startsWith(prefix) }) {
                val record = chatRecord(key.removePrefix("conversation_chat_"))
                val ids = record.optJSONArray("run_ids") ?: continue
                if ((0 until ids.length()).none { ids.optString(it) == route[1] }) continue
                val retained = (0 until ids.length()).map(ids::getString).filter { it != route[1] }
                val repository = ConversationVersions.repository(record)
                val deletedNodeId = "task:${route[1]}"
                if (repository.contains(deletedNodeId)) {
                    ConversationVersions.removeTaskReference(repository, route[1])
                    ConversationVersions.save(record, repository)
                } else record.put("run_ids", JSONArray(retained))
                if (key == "conversation_chat_${conversationKey()}") edit.putString("conversation_tail", record.optString("tail"))
                if (retained.isEmpty() && !ConversationVersions.hasSavedChats(record)) edit.remove(key)
                else edit.putString(key, record.toString())
            }
            check(edit.commit()) { "任务已删除，对话关联暂未保存" }
        }
    }
    private fun binaryConnection(path: String): HttpURLConnection {
        check(!isDirectMode()) { "此功能需要网关服务，本机直连模式暂不支持" }
        val base = prefs.getString("base_url", "http://10.0.2.2:8765")!!.trimEnd('/')
        val uri = URI(base)
        require(uri.scheme in setOf("http", "https") && uri.userInfo == null && uri.host != null) { "服务地址无效" }
        return (URI(base + "/v1" + path).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 30000; instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer " + prefs.getString("token", ""))
        }
    }
    private fun documentName(value: String): String {
        require(value.length in 6..120 && value.endsWith(".xlsx", true) && !value.contains("..") && value.none { it == '/' || it == '\\' || it == '"' || it.code < 32 }) { "仅支持安全命名的 .xlsx 文件" }
        return value
    }
    internal fun transcribeSpeech(wav: ByteArray, final: Boolean = false,
                                  onConnection: (HttpURLConnection) -> Unit = {}): JSONObject {
        FirstUseConsent.requireAccepted(context)
        require(wav.size in 44..960044) { "录音不得超过 30 秒" }
        return EmbeddedAsr(context).transcribe(wav, final)
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            check(out.size() + count <= limit) { "语音响应过大" }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
    fun image(path: String): ByteArray {
        if (isDirectMode()) return DirectRuntime.get(context).image(path)
        val connection = binaryConnection(path)
        try {
            check(connection.responseCode == 200) { "图片不可用 (${connection.responseCode})" }
            require(connection.contentType?.substringBefore(';') in setOf("image/png", "image/jpeg", "image/webp")) { "图片格式不受支持" }
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0
                while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 8 * 1024 * 1024) { "图片过大" }; output.write(buffer, 0, count) }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
    }
    fun clearDocumentCache(name: String? = null) {
        val folder = java.io.File(context.cacheDir, "documents")
        val files = if (name == null) folder.listFiles()?.toList().orEmpty() else listOf(java.io.File(folder, documentName(name)))
        for (file in files) if (file.isFile) {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.documents", file)
            context.revokeUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            check(file.delete()) { "本机文档缓存清理失败" }
        }
    }
    fun uploadDocument(uri: android.net.Uri, name: String): JSONObject {
        FirstUseConsent.requireAccepted(context)
        val filename = documentName(name)
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0
            while (true) { FirstUseConsent.requireAccepted(context); val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 20 * 1024 * 1024) { "文件不得超过 20 MB" }; output.write(buffer, 0, count) }
            output.toByteArray()
        } ?: error("文件不可读取")
        val boundary = "Doppel" + java.util.UUID.randomUUID().toString().replace("-", "")
        FirstUseConsent.requireAccepted(context)
        val connection = binaryConnection("/documents")
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            FirstUseConsent.requireAccepted(context)
            connection.outputStream.use { out ->
                FirstUseConsent.requireAccepted(context)
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\nContent-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n".toByteArray())
                FirstUseConsent.requireAccepted(context)
                out.write(bytes); out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            check(connection.responseCode in 200..299) { "文件上传失败 (${connection.responseCode})" }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    fun openDocument(value: String, packageName: String? = null, isCurrent: () -> Boolean = { true }) {
        check(isCurrent()) { "操作已失效" }
        val uri = android.net.Uri.parse(value)
        require(uri.scheme == "doppel-document" && uri.query == null && uri.fragment == null) { "文档地址无效" }
        val filename = documentName(uri.authority.orEmpty())
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "文档地址无效" }
        val path = java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
        val connection = binaryConnection("/documents/$path")
        val folder = java.io.File(context.cacheDir, "documents").apply { mkdirs() }
        val target = java.io.File(folder, filename)
        val temporary = java.io.File.createTempFile("download-", ".part", folder)
        try {
            check(connection.responseCode == 200) { "文件下载失败 (${connection.responseCode})" }
            require(connection.contentLengthLong <= 20 * 1024 * 1024) { "文件不得超过 20 MB" }
            connection.inputStream.use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0
                while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 20 * 1024 * 1024) { "文件不得超过 20 MB" }; output.write(buffer, 0, count) }
            } }
            check(temporary.renameTo(target)) { "文件缓存失败" }
        } finally { connection.disconnect(); temporary.delete() }
        val content = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.documents", target)
        val open = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(content, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (packageName != null) {
            require(packageName.matches(Regex("[A-Za-z0-9_.]+"))) { "目标应用无效" }
            open.setPackage(packageName)
            check(open.resolveActivity(context.packageManager) != null) { "指定应用不可打开此文档" }
        } else if (context.packageManager.getLaunchIntentForPackage("cn.wps.moffice_eng") != null) open.setPackage("cn.wps.moffice_eng")
        check(isCurrent()) { "操作已失效" }
        context.startActivity(open)
    }
}

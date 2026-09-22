package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

/**
 * Kotlin source adaptation of assistant-ui's MessageRepository, MIT.
 * Copyright (c) 2025 AgentbaseAI Inc. See third_party/assistant-ui-LICENSE.txt.
 * Source: assistant-ui/assistant-ui@5f21b37bee60458cf721b68369b251636cd939e3,
 * packages/core/src/runtime/utils/message-repository.ts.
 *
 * Keeps the upstream parent/children/remembered-next/head algorithms and export
 * format. Doppel stores a completed conversation turn as the opaque message.
 * No React, optimistic messages, deletion or reparenting is needed here; saved
 * parents are immutable. Import accepts only leaf heads, so it never invokes
 * upstream's destructive resetHead. JSON values are copied at the boundary.
 */
internal class ConversationMessageRepository {
    private open class Parent {
        val children = mutableListOf<String>()
        var next: Node? = null
    }
    private class Node(val prev: Node?, var current: JSONObject) : Parent() {
        val level: Int = (prev?.level ?: -1) + 1
        val id: String get() = current.getString("id")
    }
    private val messages = linkedMapOf<String, Node>()
    private val root = Parent()
    private var head: Node? = null
    val headId: String? get() = head?.id

    private fun findHead(message: Parent): Node? {
        var current = message
        while (current.next != null) current = current.next!!
        return current as? Node
    }
    private fun selectPathTo(message: Node) {
        var current: Node? = message
        while (current != null) {
            (current.prev ?: root).next = current
            current = current.prev
        }
    }
    fun addOrUpdateMessage(parentId: String?, message: JSONObject) {
        val id = message.getString("id")
        require(id.isNotBlank()) { "Message id is empty" }
        val prev = parentId?.let { messages[it] ?: error("Parent message not found") }
        val existing = messages[id]
        if (existing != null) {
            require(existing.prev === prev) { "Saved message parents are immutable" }
            existing.current = JSONObject(message.toString())
            return
        }
        val item = Node(prev, JSONObject(message.toString()))
        messages[id] = item
        val parent = prev ?: root
        parent.children += id
        if (findHead(item) === head) selectPathTo(item)
        else if (parent.next == null) {
            parent.next = item
            if (head === parent) head = findHead(item)
        }
        if (head === prev) head = item
    }
    fun getMessage(id: String): JSONObject {
        val item = messages[id] ?: error("Message not found")
        return JSONObject().put("parentId", item.prev?.id ?: JSONObject.NULL)
            .put("message", JSONObject(item.current.toString())).put("index", item.level)
    }
    fun contains(id: String) = messages.containsKey(id)
    fun getMessages(headId: String? = this.headId): List<JSONObject> {
        var current = headId?.let { messages[it] ?: error("Head message not found") }
        val path = mutableListOf<JSONObject>()
        while (current != null) {
            path += JSONObject(current.current.toString())
            current = current.prev
        }
        return path.asReversed()
    }
    fun getBranches(messageId: String): List<String> {
        val message = messages[messageId] ?: error("Message not found")
        return (message.prev ?: root).children.toList()
    }
    fun switchToBranch(messageId: String) {
        val message = messages[messageId] ?: error("Branch not found")
        selectPathTo(message)
        head = findHead(message)
    }
    fun export(): JSONObject {
        val out = JSONArray()
        val pending = java.util.ArrayDeque<String>()
        root.children.asReversed().forEach(pending::addLast)
        while (pending.isNotEmpty()) {
            val message = messages.getValue(pending.removeLast())
            message.children.asReversed().forEach(pending::addLast)
            out.put(JSONObject().put("parentId", message.prev?.id ?: JSONObject.NULL)
                .put("message", JSONObject(message.current.toString())))
        }
        return JSONObject().put("headId", headId ?: JSONObject.NULL).put("messages", out)
    }
    companion object {
        fun fromExport(value: JSONObject): ConversationMessageRepository {
            val result = ConversationMessageRepository()
            val items = value.getJSONArray("messages")
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                result.addOrUpdateMessage(item.opt("parentId") as? String, item.getJSONObject("message"))
            }
            val headId = value.opt("headId") as? String ?: items.optJSONObject(items.length() - 1)?.getJSONObject("message")?.getString("id")
            if (headId != null) {
                val node = result.messages[headId] ?: error("Head message not found")
                require(node.children.isEmpty()) { "Persisted head must be a leaf" }
                result.switchToBranch(headId)
            }
            return result
        }
    }
}

/** Thin adapter from Doppel's existing chat rows and immutable task references. */
internal object ConversationVersions {
    const val FIELD = "message_repository"
    fun repository(record: JSONObject): ConversationMessageRepository {
        record.optJSONObject(FIELD)?.let { return ConversationMessageRepository.fromExport(it) }
        val result = ConversationMessageRepository()
        val rows = record.optJSONArray("messages") ?: JSONArray()
        val consumed = mutableSetOf<Int>()
        fun discussion(after: String) {
            var turn: JSONObject? = null
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                if (row.optString("after_run_id") != after || !consumed.add(i)) continue
                if (row.optString("role") == "user" || turn == null) {
                    val id = "chat:" + row.optString("request_id").ifBlank { "legacy-$i" }
                    turn = JSONObject().put("id", id).put("kind", "chat").put("turn_id", id)
                        .put("after_run_id", after).put("messages", JSONArray())
                    result.addOrUpdateMessage(result.headId, turn)
                }
                turn.getJSONArray("messages").put(JSONObject(row.toString()).put("turn_id", turn.getString("id")))
                val parent = result.getMessage(turn.getString("id")).opt("parentId") as? String
                result.addOrUpdateMessage(parent, turn)
            }
        }
        discussion("")
        val ids = record.optJSONArray("run_ids") ?: JSONArray()
        for (i in 0 until ids.length()) {
            val runId = ids.optString(i)
            if (runId.isBlank()) continue
            val nodeId = "task:$runId"
            if (!result.contains(nodeId)) result.addOrUpdateMessage(result.headId, task(runId))
            discussion(runId)
        }
        for (i in 0 until rows.length()) if (i !in consumed) discussion(rows.optJSONObject(i)?.optString("after_run_id").orEmpty())
        return result
    }
    fun task(runId: String) = JSONObject().put("id", "task:$runId").put("kind", "task").put("turn_id", runId).put("run_id", runId)
    fun chat(id: String, user: String, reply: String, after: String, requestId: String, refs: JSONArray): JSONObject {
        val rows = JSONArray()
        for ((role, text) in listOf("user" to user, "assistant" to reply)) if (text.isNotBlank()) {
            rows.put(JSONObject().put("role", role).put("content", text.take(12000)).put("after_run_id", after)
                .put("request_id", requestId).put("turn_id", id).put("created_at", System.currentTimeMillis())
                .apply { if (role == "user" && refs.length() > 0) put("attachments", refs) })
        }
        return JSONObject().put("id", id).put("kind", "chat").put("turn_id", id).put("after_run_id", after).put("messages", rows)
    }
    fun chats(path: List<JSONObject>): JSONArray = JSONArray().apply {
        var after = ""
        for (node in path) {
            if (node.optString("kind") == "task") {
                if (node.optBoolean("deleted")) put(JSONObject().put("role", "system").put("kind", "task")
                    .put("turn_id", node.getString("turn_id")).put("deleted", true)
                    .put("content", "任务记录已删除").put("after_run_id", after))
                else after = node.getString("run_id")
            }
            node.optJSONArray("messages")?.let { rows ->
                for (i in 0 until rows.length()) put(JSONObject(rows.getJSONObject(i).toString()).put("after_run_id", after))
            }
        }
    }
    fun tail(path: List<JSONObject>): String = path.lastOrNull { it.optString("kind") == "task" && !it.optBoolean("deleted") }?.optString("run_id").orEmpty()
    fun save(record: JSONObject, repository: ConversationMessageRepository): JSONObject {
        val exported = repository.export()
        val all = exported.getJSONArray("messages")
        val runIds = linkedSetOf<String>()
        for (i in 0 until all.length()) all.getJSONObject(i).getJSONObject("message").let {
            if (it.optString("kind") == "task" && !it.optBoolean("deleted")) runIds += it.getString("run_id")
        }
        val path = repository.getMessages()
        return record.put(FIELD, exported).put("messages", chats(path)).put("run_ids", JSONArray(runIds))
            .put("tail", tail(path))
    }
    fun groups(repository: ConversationMessageRepository): JSONArray = JSONArray().apply {
        for (node in repository.getMessages()) {
            val branches = repository.getBranches(node.getString("id"))
            if (branches.size < 2) continue
            put(JSONObject().put("anchor", JSONObject().put("kind", node.getString("kind")).put("turn_id", node.getString("turn_id")))
                .put("index", branches.indexOf(node.getString("id"))).put("items", JSONArray(branches.map { id ->
                    repository.getMessage(id).getJSONObject("message").let { JSONObject().put("id", id)
                        .put("kind", it.getString("kind")).put("turn_id", it.getString("turn_id")) }
                })))
        }
    }
    fun removeTaskReference(repository: ConversationMessageRepository, runId: String) {
        val nodeId = "task:$runId"
        if (!repository.contains(nodeId)) return
        val node = repository.getMessage(nodeId)
        repository.addOrUpdateMessage(node.opt("parentId") as? String, node.getJSONObject("message").put("deleted", true))
        if (repository.headId == nodeId) repository.getBranches(nodeId).lastOrNull {
            !repository.getMessage(it).getJSONObject("message").optBoolean("deleted")
        }?.let(repository::switchToBranch)
    }
    fun hasSavedChats(record: JSONObject): Boolean {
        val rows = record.optJSONArray("messages") ?: JSONArray()
        if ((0 until rows.length()).any { rows.optJSONObject(it)?.let { row -> !row.optBoolean("deleted") } == true }) return true
        val nodes = record.optJSONObject(FIELD)?.optJSONArray("messages") ?: return false
        return (0 until nodes.length()).any { (nodes.optJSONObject(it)?.optJSONObject("message")?.optJSONArray("messages")?.length() ?: 0) > 0 }
    }
}

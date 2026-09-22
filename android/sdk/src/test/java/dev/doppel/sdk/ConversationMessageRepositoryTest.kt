package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Behavioral cases adapted from assistant-ui's MIT MessageRepository.test.ts at
 * 5f21b37bee60458cf721b68369b251636cd939e3. Copyright (c) 2025 AgentbaseAI Inc.
 * Doppel adds paired turn, attachment and immutable task-reference cases. */
class ConversationMessageRepositoryTest {
    private fun message(id: String) = JSONObject().put("id", id)
    private fun ids(repository: ConversationMessageRepository) = repository.getMessages().map { it.getString("id") }
    private fun chat(id: String, question: String = "问题 $id", answer: String = "回答 $id", after: String = "", refs: JSONArray = JSONArray()) =
        ConversationVersions.chat(id, question, answer, after, id, refs)

    @Test fun upstreamSiblingBranchesSelectOnlyTheirOwnPath() {
        val repository = ConversationMessageRepository()
        repository.addOrUpdateMessage(null, message("parent"))
        repository.addOrUpdateMessage("parent", message("branch1"))
        repository.addOrUpdateMessage("parent", message("branch2"))
        assertEquals(listOf("branch1", "branch2"), repository.getBranches("branch1"))
        repository.switchToBranch("branch1")
        assertEquals(listOf("parent", "branch1"), ids(repository))
        repository.switchToBranch("branch2")
        assertEquals(listOf("parent", "branch2"), ids(repository))
        assertThrows(IllegalStateException::class.java) { repository.switchToBranch("missing") }
    }

    @Test fun upstreamNestedBranchSelectionIsRememberedWhenRevisitingAncestor() {
        val repository = ConversationMessageRepository()
        repository.addOrUpdateMessage(null, message("r"))
        repository.addOrUpdateMessage("r", message("a"))
        repository.addOrUpdateMessage("a", message("x"))
        repository.addOrUpdateMessage("r", message("b"))
        repository.addOrUpdateMessage("b", message("y"))
        repository.switchToBranch("y"); repository.switchToBranch("r")
        assertEquals(listOf("r", "b", "y"), ids(repository))
        repository.switchToBranch("x"); repository.switchToBranch("r")
        assertEquals(listOf("r", "a", "x"), ids(repository))
    }

    @Test fun upstreamExportImportRetainsSiblingsAndSelectedLeafWithoutDeletingHistory() {
        val original = ConversationMessageRepository()
        original.addOrUpdateMessage(null, message("root"))
        original.addOrUpdateMessage("root", message("one"))
        original.addOrUpdateMessage("one", message("answer1"))
        original.addOrUpdateMessage("root", message("two"))
        original.addOrUpdateMessage("two", message("answer2"))
        original.switchToBranch("answer2")
        val restored = ConversationMessageRepository.fromExport(original.export())
        assertEquals("answer2", restored.headId)
        restored.switchToBranch("one")
        assertEquals(listOf("root", "one", "answer1"), ids(restored))
        val again = ConversationMessageRepository.fromExport(restored.export())
        again.switchToBranch("two")
        assertEquals(listOf("root", "two", "answer2"), ids(again))
        assertEquals(5, again.export().getJSONArray("messages").length())
        assertThrows(IllegalArgumentException::class.java) {
            ConversationMessageRepository.fromExport(original.export().put("headId", "root"))
        }
    }

    @Test fun upstreamLongHistoryTraversalsDoNotUseRecursion() {
        val repository = ConversationMessageRepository()
        repository.addOrUpdateMessage(null, message("root"))
        repeat(30000) { i -> repository.addOrUpdateMessage(if (i == 0) "root" else "branch-${i - 1}", message("branch-$i")) }
        repository.addOrUpdateMessage("root", message("alternate"))
        repository.switchToBranch("alternate")
        val restored = ConversationMessageRepository.fromExport(repository.export())
        restored.switchToBranch("branch-0")
        assertEquals("branch-29999", restored.headId)
        assertEquals(30001, restored.getMessages().size)
    }

    @Test fun savedParentsAndJsonPayloadCannotBeMutatedByCallers() {
        val repository = ConversationMessageRepository()
        val first = message("first").put("content", "original")
        repository.addOrUpdateMessage(null, first)
        first.put("content", "caller mutation")
        assertEquals("original", repository.getMessages().single().getString("content"))
        repository.getMessages().single().put("content", "reader mutation")
        assertEquals("original", repository.getMessages().single().getString("content"))
        repository.addOrUpdateMessage("first", message("second"))
        assertThrows(IllegalArgumentException::class.java) { repository.addOrUpdateMessage("second", message("first")) }
        assertThrows(IllegalStateException::class.java) { repository.addOrUpdateMessage("absent", message("new")) }
        assertEquals(listOf("first", "second"), ids(repository))
    }

    @Test fun editedQuestionsSwitchWithTheirAnswersAndKeepAttachmentsAfterRestart() {
        val repository = ConversationMessageRepository()
        val refs = JSONArray().put(JSONObject().put("id", "attachment-one").put("name", "报告.docx"))
        repository.addOrUpdateMessage(null, chat("first", "原问题", "原回答", refs = refs))
        repository.addOrUpdateMessage(null, chat("second", "修改的问题", "对应的新回答"))
        repository.switchToBranch("second")
        val record = ConversationVersions.save(JSONObject(), repository)
        assertEquals("修改的问题", record.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals("对应的新回答", record.getJSONArray("messages").getJSONObject(1).getString("content"))
        val restored = ConversationVersions.repository(JSONObject(record.toString()))
        restored.switchToBranch("first")
        val rows = ConversationVersions.chats(restored.getMessages())
        assertEquals("原问题", rows.getJSONObject(0).getString("content"))
        assertEquals("原回答", rows.getJSONObject(1).getString("content"))
        assertEquals(refs.toString(), rows.getJSONObject(0).getJSONArray("attachments").toString())
        assertEquals(0, ConversationVersions.groups(restored).getJSONObject(0).getInt("index"))
    }

    @Test fun taskVersusChatVersionsRetainTaskReferenceAndFollowupsStayOnTheirBranch() {
        val repository = ConversationMessageRepository()
        repository.addOrUpdateMessage(null, chat("prefix"))
        repository.addOrUpdateMessage("prefix", ConversationVersions.task("old-run"))
        repository.addOrUpdateMessage("prefix", chat("revision"))
        repository.switchToBranch("revision")
        repository.addOrUpdateMessage("revision", chat("followup"))
        val record = ConversationVersions.save(JSONObject(), repository)
        assertEquals("", record.getString("tail"))
        assertEquals("old-run", record.getJSONArray("run_ids").getString(0))
        repository.switchToBranch("task:old-run")
        assertEquals("old-run", ConversationVersions.tail(repository.getMessages()))
        assertEquals(listOf("prefix", "task:old-run"), ids(repository))
        repository.switchToBranch("revision")
        assertEquals(listOf("prefix", "revision", "followup"), ids(repository))
    }

    @Test fun legacyChatTaskChronologyMigratesWithoutDiscardingRows() {
        val rows = JSONArray()
        for ((after, user) in listOf("" to "开始前", "run-one" to "任务之后", "deleted-run" to "保留讨论")) {
            rows.put(JSONObject().put("role", "user").put("content", user).put("after_run_id", after))
                .put(JSONObject().put("role", "assistant").put("content", "答：$user").put("after_run_id", after))
        }
        val record = JSONObject().put("messages", rows).put("run_ids", JSONArray().put("run-one"))
        val repo = ConversationVersions.repository(record)
        assertEquals(listOf("chat:legacy-0", "task:run-one", "chat:legacy-2", "chat:legacy-4"), ids(repo))
        ConversationVersions.save(record, repo)
        assertEquals(6, record.getJSONArray("messages").length())
        assertEquals("chat:legacy-0", record.getJSONArray("messages").getJSONObject(0).getString("turn_id"))
        assertEquals("run-one", record.getString("tail"))
        assertEquals(0, ConversationVersions.groups(repo).length())
    }

    @Test fun deletingSelectedTaskVersionReturnsToChatAndRetainsOnlyReadonlyDeletedAnchor() {
        val repository = ConversationMessageRepository()
        repository.addOrUpdateMessage(null, chat("original", "原问题", "原回答"))
        repository.addOrUpdateMessage(null, ConversationVersions.task("deleted-run"))
        repository.switchToBranch("task:deleted-run")
        ConversationVersions.removeTaskReference(repository, "deleted-run")
        val record = ConversationVersions.save(JSONObject(), repository)
        assertEquals("original", repository.headId)
        assertEquals("", record.getString("tail"))
        assertEquals(0, record.getJSONArray("run_ids").length())
        assertEquals("原问题", record.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertTrue(ConversationVersions.hasSavedChats(record))
        assertEquals(1, ConversationVersions.groups(repository).length())
        val reopened = ConversationVersions.repository(JSONObject(record.toString()))
        assertEquals("原回答", ConversationVersions.chats(reopened.getMessages()).getJSONObject(1).getString("content"))
        reopened.switchToBranch("task:deleted-run")
        assertEquals("", ConversationVersions.tail(reopened.getMessages()))
        val placeholder = ConversationVersions.chats(reopened.getMessages()).getJSONObject(0)
        assertTrue(placeholder.getBoolean("deleted"))
        assertEquals("system", placeholder.getString("role"))
        assertEquals("task", placeholder.getString("kind"))
        assertEquals("deleted-run", placeholder.getString("turn_id"))
        assertEquals(0, ConversationChatContext.merge(JSONArray(), JSONArray().put(placeholder)).length())
    }

    @Test fun deletingTaskReferenceRetainsDiscussionAfterTheDeletedTask() {
        val repository = ConversationMessageRepository()
        repository.addOrUpdateMessage(null, ConversationVersions.task("deleted-run"))
        repository.addOrUpdateMessage("task:deleted-run", chat("discussion", "复盘问题", "复盘回答", "deleted-run"))
        ConversationVersions.removeTaskReference(repository, "deleted-run")
        val record = ConversationVersions.save(JSONObject(), repository)
        assertEquals("discussion", repository.headId)
        assertEquals("", record.getString("tail"))
        assertEquals(0, record.getJSONArray("run_ids").length())
        assertEquals(3, record.getJSONArray("messages").length())
        assertTrue(record.getJSONArray("messages").getJSONObject(0).getBoolean("deleted"))
        assertEquals("", record.getJSONArray("messages").getJSONObject(1).getString("after_run_id"))
        assertTrue(ConversationVersions.hasSavedChats(record))

        repository.addOrUpdateMessage(null, chat("alternative"))
        repository.switchToBranch("alternative")
        val reopened = ConversationVersions.repository(ConversationVersions.save(JSONObject(), repository))
        val versions = ConversationVersions.groups(reopened).getJSONObject(0).getJSONArray("items")
        assertEquals(2, versions.length())
        reopened.switchToBranch("task:deleted-run")
        assertEquals("discussion", reopened.headId)
        val history = ConversationChatContext.merge(JSONArray(), ConversationVersions.chats(reopened.getMessages()))
        assertEquals(2, history.length())
        assertEquals("复盘问题", history.getJSONObject(0).getString("content"))
    }
}

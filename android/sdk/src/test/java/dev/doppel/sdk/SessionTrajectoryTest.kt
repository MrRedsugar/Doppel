package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionTrajectoryTest {
    private fun sameJson(left: Any?, right: Any?): Boolean = when {
        left is JSONObject && right is JSONObject -> {
            val keys=left.keys().asSequence().toSet()
            keys==right.keys().asSequence().toSet() && keys.all { sameJson(left.opt(it),right.opt(it)) }
        }
        left is JSONArray && right is JSONArray -> left.length()==right.length() && (0 until left.length()).all { sameJson(left.opt(it),right.opt(it)) }
        else -> left==right
    }
    private fun run() = JSONObject().put("id", "run-1").put("status", "running")
    private fun current(text: String = "current observation") = JSONArray()
        .put(JSONObject().put("role", "system").put("content", "current instructions"))
        .put(JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", text))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/png;base64,PRIVATE_IMAGE")))))
    private fun assistant(id: String = "call-1", reasoning: String = "complete private thought") = JSONObject()
        .put("role", "assistant").put("content", "I will inspect this target.").put("reasoning_content", reasoning)
        .put("tool_calls", JSONArray().put(JSONObject().put("id", id).put("type", "function")
            .put("function", JSONObject().put("name", "action").put("arguments", "{ \"kind\": \"tap\", \"target\": \"n1\" }"))))
    private fun command(id: String = "cmd-1", kind: String = "tap") = JSONObject().put("id", id).put("run_id", "run-1").put("kind", kind)
    private fun result(id: String = "cmd-1", status: String = "ok", noOp: Boolean = false) = JSONObject()
        .put("run_id", "run-1").put("command_id", id).put("status", status).put("message", "actual host outcome")
        .put("data", JSONObject().put("action_state", if (status == "ok") "accepted" else "unconfirmed").put("no_op", noOp))
    private fun roles(messages: JSONArray) = (0 until messages.length()).map { messages.getJSONObject(it).getString("role") }
    private fun tool(messages: JSONArray) = (0 until messages.length()).map { messages.getJSONObject(it) }.first { it.optString("role") == "tool" }
    private fun complete(run: JSONObject, id: String = "call-1", reasoning: String = "complete private thought") {
        SessionTrajectory.proposed(run, current("old observation"), assistant(id, reasoning))
        SessionTrajectory.receipt(run, command(), result())
        SessionTrajectory.settle(run)
    }
    @Test fun originalAssistantAndMatchingToolReceiptPrecedeCurrentObservation() {
        val r=run();val original=assistant();complete(r)
        val messages=SessionTrajectory.messages(r,current("latest observation"))
        assertEquals(listOf("system","user","assistant","tool","user"),roles(messages))
        assertTrue(sameJson(original,messages.getJSONObject(2)))
        assertEquals("call-1",tool(messages).getString("tool_call_id"))
        val content=JSONObject(tool(messages).getString("content"))
        assertFalse(content.getBoolean("proves_business_success"))
        assertEquals("ok",content.getJSONArray("receipts").getJSONObject(0).getJSONObject("result").getString("status"))
        assertTrue(messages.getJSONObject(messages.length()-1).toString().contains("latest observation"))
    }
    @Test fun originalArgumentsThoughtAndOtherAssistantFieldsAreNotRewritten() {
        val r=run();val a=assistant(reasoning="first\nsecond\n第三段完整思考").put("custom_model_field",JSONObject().put("keep",true))
        SessionTrajectory.proposed(r,current(),a);SessionTrajectory.receipt(r,command(),result());SessionTrajectory.settle(r)
        assertTrue(sameJson(a,SessionTrajectory.messages(r,current()).getJSONObject(2)))
    }
    @Test fun historicalImagesAreNotPersistedAndOnlyCurrentImagesAreReused() {
        val r=run();complete(r)
        assertFalse(r.toString().contains("PRIVATE_IMAGE"));assertFalse(r.toString().contains("image_url"))
        val messages=SessionTrajectory.messages(r,current())
        assertEquals(1,Regex("PRIVATE_IMAGE").findAll(messages.toString()).count())
    }
    @Test fun receiptImagePayloadsAreRemovedRecursively() {
        val r=run();SessionTrajectory.proposed(r,current(),assistant())
        val outcome=result().put("message","SECRET_INPUT");outcome.getJSONObject("data").put("image_base64","SECRET_BITMAP")
            .put("action_diagnostic",JSONObject().put("screenshot_base64","ANOTHER_BITMAP").put("node_present",true).put("text","SECRET_INPUT"))
        SessionTrajectory.receipt(r,command(),outcome);SessionTrajectory.settle(r)
        assertFalse(r.toString().contains("BITMAP"));assertFalse(r.toString().contains("SECRET_INPUT"));assertTrue(r.toString().contains("node_present"))
    }
    @Test fun assistantWithoutReasoningIsPreservedWithoutInventingIt() {
        val r=run();val a=assistant();a.remove("reasoning_content");a.put("content",JSONObject.NULL)
        SessionTrajectory.proposed(r,current(),a);SessionTrajectory.settle(r)
        assertTrue(sameJson(a,SessionTrajectory.messages(r,current()).getJSONObject(2)))
    }
    @Test fun pendingAssistantIsNeverReusedBeforeSettlement() {
        val r=run();SessionTrajectory.proposed(r,current(),assistant())
        assertEquals(listOf("system","user"),roles(SessionTrajectory.messages(r,current())))
    }
    @Test fun errorsAndNoOpsRetainActualHostOutcomeWithoutSuccessClaim() {
        for ((status,noOp) in listOf("error" to false,"ok" to true,"stale" to false)) {
            val r=run();SessionTrajectory.proposed(r,current(),assistant())
            SessionTrajectory.receipt(r,command(),result(status=status,noOp=noOp));SessionTrajectory.settle(r)
            val summary=JSONObject(tool(SessionTrajectory.messages(r,current())).getString("content"))
            val receipt=summary.getJSONArray("receipts").getJSONObject(0).getJSONObject("result")
            assertEquals(status,receipt.getString("status"));assertEquals(noOp,receipt.getJSONObject("data").getBoolean("no_op"))
            assertFalse(summary.getBoolean("proves_business_success"))
        }
    }
    @Test fun wrongRunOrCommandAndDuplicateReceiptAreNotAttached() {
        val r=run();SessionTrajectory.proposed(r,current(),assistant())
        SessionTrajectory.receipt(r,command(),result("wrong"))
        SessionTrajectory.receipt(r,command(),result().put("run_id","other-run"))
        SessionTrajectory.receipt(r,command().put("run_id","other-run"),result())
        SessionTrajectory.receipt(r,command(),result());SessionTrajectory.receipt(r,command(),result())
        SessionTrajectory.settle(r)
        assertEquals(1,JSONObject(tool(SessionTrajectory.messages(r,current())).getString("content")).getJSONArray("receipts").length())
    }
    @Test fun localPlanMayContainSeveralActualCommandReceipts() {
        val r=run();SessionTrajectory.proposed(r,current(),assistant())
        SessionTrajectory.receipt(r,command("step-1"),result("step-1"))
        SessionTrajectory.receipt(r,command("step-2"),result("step-2",noOp=true))
        r.put("local_visual_plan",JSONObject().put("state","verify").put("steps",2).put("accepted_steps",1))
        SessionTrajectory.settle(r)
        val summary=JSONObject(tool(SessionTrajectory.messages(r,current())).getString("content"))
        assertEquals(2,summary.getJSONArray("receipts").length());assertEquals("verify",summary.getJSONObject("local_visual_plan").getString("state"))
    }
    @Test fun deferredGuiAndQueuedPlanWithoutReceiptsNeverBecomeExecuted() {
        for (name in listOf("locate_ui","execute_visual_plan")) {
            val r=run();val a=assistant();a.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").put("name",name)
            SessionTrajectory.proposed(r,current(),a)
            r.put("local_visual_plan",JSONObject().put("state","prepared").put("steps",3).put("accepted_steps",0))
            SessionTrajectory.settle(r)
            val summary=JSONObject(tool(SessionTrajectory.messages(r,current())).getString("content"))
            assertEquals("unconfirmed",summary.getString("execution_status"));assertEquals(0,summary.getJSONArray("receipts").length())
            assertFalse(summary.getBoolean("commands_replayable"))
        }
    }
    @Test fun hostValidationFeedbackIsReturnedWithoutADeviceReceipt() {
        val r=run();SessionTrajectory.proposed(r,current(),assistant())
        r.put("recovery_feedback",JSONObject().put("code","stale_stage").put("action_executed",false))
        SessionTrajectory.settle(r)
        val summary=JSONObject(tool(SessionTrajectory.messages(r,current())).getString("content"))
        assertEquals("stale_stage",summary.getJSONObject("recovery_feedback").getString("code"))
        assertEquals(0,summary.getJSONArray("receipts").length())
    }
    @Test fun interruptedPendingBecomesRevokedHistoryWithoutRestoringCommands() {
        val r=run();SessionTrajectory.proposed(r,current(),assistant());SessionTrajectory.invalidate(r,"user paused")
        val summary=JSONObject(tool(SessionTrajectory.messages(r,current())).getString("content"))
        assertEquals("revoked",summary.getString("pending_disposition"));assertEquals("unconfirmed",summary.getString("execution_status"))
        assertEquals("user paused",summary.getString("reason"));assertFalse(r.has("pending_command"))
        SessionTrajectory.receipt(r,command(),result());SessionTrajectory.settle(r)
        assertEquals(1,roles(SessionTrajectory.messages(r,current())).count{it=="tool"})
    }
    @Test fun restartPreservesCompletePairsAndDoesNotReplayPendingWork() {
        val r=run();complete(r);SessionTrajectory.proposed(r,current(),assistant("pending"))
        val restored=JSONObject(r.toString())
        assertEquals(1,roles(SessionTrajectory.messages(restored,current())).count{it=="assistant"})
        SessionTrajectory.invalidate(restored,"process restored")
        assertEquals(2,roles(SessionTrajectory.messages(restored,current())).count{it=="assistant"})
        assertFalse(restored.has("pending_command"))
    }
    @Test fun maximumEightWholeTransactionsAndNoDanglingToolCalls() {
        val r=run();repeat(12){complete(r,"call-$it")}
        val messages=SessionTrajectory.messages(r,current());assertEquals(8,roles(messages).count{it=="assistant"})
        assertEquals(8,roles(messages).count{it=="tool"});assertFalse(messages.toString().contains("call-0\""))
    }
    @Test fun utf8BudgetDropsWholeTransactionsNeverThoughtSuffixes() {
        val r=run();val thought="中".repeat(20000)
        repeat(8){complete(r,"call-$it",thought)}
        assertTrue(r.getJSONObject("_session_trajectory").toString().toByteArray(Charsets.UTF_8).size<=256*1024)
        val messages=SessionTrajectory.messages(r,current())
        for(index in 0 until messages.length()) if(messages.getJSONObject(index).optString("role")=="assistant")
            assertEquals(thought,messages.getJSONObject(index).getString("reasoning_content"))
        assertTrue(roles(messages).count{it=="assistant"} in 1..4)
    }
    @Test fun oversizeAssistantIsDiscardedWholeWithoutDestroyingEarlierPair() {
        val r=run();complete(r)
        SessionTrajectory.proposed(r,current(),assistant("large","中".repeat(100000)));SessionTrajectory.settle(r)
        val messages=SessionTrajectory.messages(r,current());assertEquals(1,roles(messages).count{it=="assistant"})
        assertFalse(messages.toString().contains("large"));assertTrue(r.getJSONObject("_session_trajectory").getInt("discarded_transactions")>=1)
    }
    @Test fun malformedOrMultiCallAssistantIsNotRepairedOrReplayed() {
        val missing=assistant().apply{remove("tool_calls")}
        val multiple=assistant().apply{getJSONArray("tool_calls").put(JSONObject(getJSONArray("tool_calls").getJSONObject(0).toString()))}
        val noId=assistant().apply{getJSONArray("tool_calls").getJSONObject(0).remove("id")}
        for(a in listOf(missing,multiple,noId)) {
            val r=run();SessionTrajectory.proposed(r,current(),a);SessionTrajectory.settle(r)
            assertEquals(listOf("system","user"),roles(SessionTrajectory.messages(r,current())))
        }
    }
    @Test fun clearRemovesAllPrivateReasoningPendingAndHistoricalObservations() {
        val r=run();complete(r);SessionTrajectory.proposed(r,current(),assistant("pending"));SessionTrajectory.clear(r)
        assertFalse(r.has("_session_trajectory"));assertFalse(r.toString().contains("private thought"))
        assertEquals(listOf("system","user"),roles(SessionTrajectory.messages(r,current())))
    }
    @Test fun inputsOutputsAndPersistedHistoryAreDeepCopies() {
        val r=run();val a=assistant();val c=current();SessionTrajectory.proposed(r,c,a)
        a.put("reasoning_content","tampered");c.getJSONObject(1).put("content","tampered")
        SessionTrajectory.receipt(r,command(),result());SessionTrajectory.settle(r)
        val output=SessionTrajectory.messages(r,current());output.getJSONObject(2).put("reasoning_content","tampered")
        assertEquals("complete private thought",SessionTrajectory.messages(r,current()).getJSONObject(2).getString("reasoning_content"))
    }
    @Test fun unchangedFeedbackAndPlanFromPreviousTransactionAreNotAttributedToNewCall() {
        val r=run().put("recovery_feedback",JSONObject().put("code","old_feedback").put("action_executed",false))
            .put("local_visual_plan",JSONObject().put("state","verify").put("steps",2).put("accepted_steps",2))
        complete(r)
        val summary=JSONObject(tool(SessionTrajectory.messages(r,current())).getString("content"))
        assertFalse(summary.has("recovery_feedback"));assertFalse(summary.has("local_visual_plan"))
    }
    @Test fun observeOnlyReceiptCannotProveRequestedMutationRan() {
        val r=run();SessionTrajectory.proposed(r,current(),assistant())
        SessionTrajectory.receipt(r,command(kind="observe"),result());SessionTrajectory.settle(r)
        val summary=JSONObject(tool(SessionTrajectory.messages(r,current())).getString("content"))
        assertEquals("unconfirmed",summary.getString("execution_status"));assertEquals(1,summary.getJSONArray("receipts").length())
    }
    @Test fun clearThenLateReceiptSettleAndInvalidateCannotRebuildPrivateStore() {
        val r=run();SessionTrajectory.proposed(r,current(),assistant());SessionTrajectory.clear(r)
        SessionTrajectory.receipt(r,command(),result());SessionTrajectory.settle(r);SessionTrajectory.invalidate(r,"late")
        assertFalse(r.has("_session_trajectory"))
    }
    @Test fun archivingReusedContextKeepsOnlyNewestUserObservationWithoutRecursiveHistory() {
        val r=run();complete(r)
        val payload=SessionTrajectory.messages(r,current("newest"));SessionTrajectory.proposed(r,payload,assistant("call-2"))
        SessionTrajectory.settle(r)
        assertEquals(listOf("system","user","assistant","tool","user","assistant","tool","user"),
            roles(SessionTrajectory.messages(r,current("third"))))
    }
    @Test fun historicalObservationTextMayBeExplicitlyTruncatedWhileCurrentAndThoughtRemainComplete() {
        val r=run();val text="observation:"+"x".repeat(15000)+"END";val c=current(text)
        SessionTrajectory.proposed(r,c,assistant());SessionTrajectory.settle(r)
        val messages=SessionTrajectory.messages(r,c)
        val old=messages.getJSONObject(1).getString("content")
        assertTrue(old.length<13000);assertTrue(old.contains("truncated"));assertFalse(old.endsWith("END"))
        assertTrue(messages.getJSONObject(messages.length()-1).toString().contains(text))
    }
    @Test fun oversizedReceiptDropsItsWholePendingTransactionAndRetainsEarlierHistory() {
        val r=run();complete(r)
        SessionTrajectory.proposed(r,current(),assistant("oversize-result"))
        // An excessive set of actual host receipts must not split the original assistant transaction.
        repeat(1500){val id="cmd-$it-"+"x".repeat(150);SessionTrajectory.receipt(r,command(id),result(id))}
        SessionTrajectory.settle(r)
        val messages=SessionTrajectory.messages(r,current())
        assertEquals(1,roles(messages).count{it=="assistant"});assertFalse(messages.toString().contains("oversize-result"))
        assertTrue(r.getJSONObject("_session_trajectory").toString().toByteArray(Charsets.UTF_8).size<=256*1024)
    }
}

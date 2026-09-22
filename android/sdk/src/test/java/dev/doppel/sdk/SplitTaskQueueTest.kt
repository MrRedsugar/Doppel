package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class SplitTaskQueueTest {
    private fun body(goal:String="任务", source:String="user")=JSONObject().put("goal",goal).put("device_id","direct-this-phone")
        .put("source",source).put("conversation_enabled",source=="user")
    private fun id(run:JSONObject)=run.getString("id")
    private fun ids(e:SplitTaskEngine):List<String> = e.queueSnapshot().getJSONArray("items").let { rows -> (0 until rows.length()).map { id(rows.getJSONObject(it)) } }
    private fun reject(action:()->Unit) { try { action(); fail("Must reject") } catch (_:IllegalArgumentException) {} catch (_:IllegalStateException) {} }
    private fun observe(e:SplitTaskEngine) {
        val command=e.poll().getJSONObject("command")
        e.result(JSONObject().put("run_id",command.getString("run_id")).put("command_id",command.getString("id")).put("status","ok")
            .put("data",JSONObject().put("image_base64","cGl4ZWxz").put("visual_frame",JSONObject().put("display_width",100).put("display_height",200))))
    }
    @Test fun removedOrPrunedTaskHistoryCannotReplaySubmissionAfterRestart() {
        for (delete in listOf(true,false)) {
            var saved=""
            val e=SplitTaskEngine(null,{saved=it})
            val input=body("once","schedule").put("request_id","schedule:once")
            val original=e.create(input);e.control(id(original),"cancel",JSONObject())
            if (delete) e.delete(id(original)) else repeat(50) { index ->
                val transient=e.create(body("history $index"));e.control(id(transient),"cancel",JSONObject())
            }
            val restored=SplitTaskEngine(saved,{saved=it})
            val duplicate=restored.create(input)
            assertEquals(id(original),id(duplicate));assertEquals("cancelled",duplicate.getString("status"))
            assertTrue(duplicate.getBoolean("history_removed"));assertFalse(restored.hasUnfinished());assertTrue(restored.poll().isNull("command"))
            reject { restored.create(JSONObject(input.toString()).put("goal","other")) }
        }
    }
    @Test fun deferredManualFirstTaskWaitsForDeviceAdmission() {
        val e=SplitTaskEngine(null,{})
        val run=e.create(body("manual").put("defer_start",true))
        assertEquals("queued",run.getString("status"));assertTrue(e.poll().isNull("command"));assertNull(e.takeWork())
        assertEquals("running",e.start(id(run)).getString("status"))
    }
    @Test fun legacyArrayMigratesWithoutChangingPendingOrder() {
        val legacy=JSONArray().put(JSONObject().put("goal","legacy").put("id","legacy-first").put("device_id","direct-this-phone").put("status","running"))
            .put(JSONObject().put("goal","legacy queued").put("id","legacy-second").put("device_id","direct-this-phone").put("status","queued"))
        var saved=""
        val e=SplitTaskEngine(legacy.toString(),{saved=it})
        assertEquals(listOf("legacy-first","legacy-second"),ids(e));assertEquals("paused",e.get("legacy-first").getString("status"))
        assertEquals(2,JSONObject(saved).getInt("version"));assertEquals(2,SplitTaskEngine.readPersistedRuns(saved).length())
    }
    @Test fun queuePreservesRunningCommandAndStrictOrder() {
        val e=SplitTaskEngine(null,{})
        val a=e.create(body("A")); val command=e.poll().getJSONObject("command").toString()
        val b=e.create(body("B")); val c=e.create(body("C"))
        assertEquals("running",a.getString("status")); assertEquals("queued",b.getString("status"))
        assertEquals(1,b.getInt("queue_position")); assertEquals(2,c.getInt("queue_position"))
        assertEquals(command,e.poll().getJSONObject("command").toString())
        assertEquals(listOf(id(a),id(b),id(c)),ids(e)); reject { e.start(id(c)) }
        e.control(id(a),"cancel",JSONObject()); assertTrue(e.poll().isNull("command")); assertNull(e.takeWork())
        assertEquals("running",e.start(id(b)).getString("status"))
        val started=e.poll().getJSONObject("command").toString()
        e.start(id(b)); assertEquals(started,e.poll().getJSONObject("command").toString())
        reject { e.control(id(b),"cancel",JSONObject().put("expected_status","queued")) }
        assertEquals("running",e.get(id(b)).getString("status"))
    }
    @Test fun cancelWaitingNeverInvalidatesPlanningOrAdvancesPausedHead() {
        val e=SplitTaskEngine(null,{})
        val a=e.create(body("A")); observe(e); val work=e.takeWork()!!
        val b=e.create(body("B")); val c=e.create(body("C"))
        e.control(id(b),"cancel",JSONObject().put("expected_status","queued"))
        assertTrue(e.isCurrent(work)); assertEquals(1,e.get(id(c)).getInt("queue_position"))
        e.control(id(a),"pause",JSONObject()); reject { e.start(id(c)) }; reject { e.control(id(c),"resume",JSONObject()) }
        assertTrue(e.poll().isNull("command")); assertEquals(listOf(id(a),id(c)),ids(e))
    }
    @Test fun restoredHeadCancelRequiresTheObservedStatusAndCannotCancelAResumedRun() {
        var saved=""
        val initial=SplitTaskEngine(null,{saved=it})
        val a=initial.create(body("A")); val b=initial.create(body("B"))
        val e=SplitTaskEngine(saved,{saved=it})
        e.control(id(a),"resume",JSONObject()); val command=e.poll().getJSONObject("command").toString()
        reject { e.control(id(a),"cancel",JSONObject().put("expected_status","paused")) }
        reject { e.control(id(a),"cancel",JSONObject().put("expected_status","invalid")) }
        assertEquals("running",e.get(id(a)).getString("status"))
        assertEquals(command,e.poll().getJSONObject("command").toString())
        e.control(id(a),"pause",JSONObject())
        assertEquals("cancelled",e.control(id(a),"cancel",JSONObject().put("expected_status","paused")).getString("status"))
        assertEquals("running",e.start(id(b)).getString("status"))
    }
    @Test fun completionAndFailureLeaveNextQueuedUntilDeviceFinishesCleanup() {
        for (status in listOf("completed","failed")) {
            val e=SplitTaskEngine(null,{})
            val a=e.create(body("A")); val b=e.create(body("B")); observe(e)
            e.accept(e.takeWork()!!,SplitTestReply.response(JSONObject().put("kind","finish").put("status",status).put("message","已核对结果")))
            assertEquals(status,e.get(id(a)).getString("status")); assertTrue(e.poll().isNull("command")); assertNull(e.takeWork())
            assertEquals("queued",e.get(id(b)).getString("status")); assertEquals("running",e.start(id(b)).getString("status"))
        }
    }
    @Test fun automaticAndManualUseSameQueueAndSubmissionKeys() {
        val e=SplitTaskEngine(null,{})
        val request=body("定时","schedule").put("request_id","schedule:1:123").put("source_metadata",JSONObject().put("rule_id","one"))
        val a=e.create(request); assertEquals("queued",a.getString("status")); assertTrue(e.poll().isNull("command"))
        val b=e.create(body("用户")); assertEquals(listOf(id(a),id(b)),ids(e))
        assertEquals(id(a),id(e.create(request))); assertEquals(2,ids(e).size)
        reject { e.create(JSONObject(request.toString()).put("goal","different")) }
        assertFalse(a.optBoolean("conversation_enabled")); assertEquals("one",a.getJSONObject("source_metadata").getString("rule_id"))
        assertEquals("running",e.start(id(a)).getString("status"))
    }
    @Test fun conversationCanLinkUnfinishedPredecessor() {
        val e=SplitTaskEngine(null,{})
        val a=e.create(body("A")); val b=e.create(body("B").put("parent_run_id",id(a)))
        val c=e.create(body("C").put("parent_run_id",id(b)))
        assertEquals(a.getString("conversation_id"),c.getString("conversation_id"))
        assertEquals(3,e.conversation(id(c)).getJSONArray("items").length())
        reject { e.create(body("自动","trigger").put("parent_run_id",id(a))) }
    }
    @Test fun restartRetainsQueueAndDoesNotReplayRunningTask() {
        var state=""
        val e=SplitTaskEngine(null,{state=it})
        val a=e.create(body("A")); val b=e.create(body("B")); val c=e.create(body("C"))
        val restored=SplitTaskEngine(state,{state=it})
        assertEquals("paused",restored.get(id(a)).getString("status")); assertEquals("queued",restored.get(id(b)).getString("status"))
        assertEquals(listOf(id(a),id(b),id(c)),ids(restored)); assertTrue(restored.poll().isNull("command"))
        reject { restored.start(id(b)) }; restored.control(id(a),"cancel",JSONObject()); restored.start(id(b))
        val next=restored.create(body("D")); assertTrue(next.getLong("queue_sequence")>c.getLong("queue_sequence"))
    }
    @Test fun oldRemoteQueuedTasksAreRevokedOnRestart() {
        var saved=""
        val e=SplitTaskEngine(null,{saved=it}); e.create(body("local"))
        val remote=e.createOwned(body("remote"),"server-run-"+"a".repeat(64),SplitTaskEngine.ServerOwner("account","session",true))
        assertEquals("queued",remote.getString("status"))
        assertEquals("cancelled",SplitTaskEngine(saved,{}).get(id(remote)).getString("status"))
    }
    @Test fun failedPersistenceCannotPublishOrExecuteUncommittedTask() {
        var fail=false; var saved=""
        val e=SplitTaskEngine(null,{if(fail) throw IllegalStateException("disk");saved=it})
        fail=true; reject { e.create(body("first")) }; assertFalse(e.hasUnfinished()); assertTrue(e.poll().isNull("command"))
        fail=false; val a=e.create(body("A")); observe(e); val work=e.takeWork()!!; val committed=saved
        fail=true; reject { e.create(body("B")) }; assertEquals(listOf(id(a)),ids(e)); assertTrue(e.isCurrent(work)); assertEquals(committed,saved)
        fail=false; val b=e.create(body("B")); fail=true
        reject { e.control(id(b),"cancel",JSONObject()) }; assertEquals("queued",e.get(id(b)).getString("status")); assertTrue(e.isCurrent(work))
        fail=false; e.control(id(a),"cancel",JSONObject()); fail=true
        reject { e.start(id(b)) }; assertEquals("queued",e.get(id(b)).getString("status")); assertTrue(e.poll().isNull("command"))
    }
    @Test fun capacityNeverDropsUnfinishedTasks() {
        val e=SplitTaskEngine(null,{})
        val first=e.create(body("A")); repeat(49) { e.create(body("B$it")) }
        reject { e.create(body("overflow")) }; assertEquals(50,ids(e).size); assertEquals(id(first),ids(e).first())
        val second=ids(e)[1];e.control(second,"cancel",JSONObject()); e.create(body("replacement"))
        assertEquals(50,ids(e).size); assertEquals(id(first),ids(e).first())
    }
    @Test fun concurrentSubmissionsHaveUniqueCommittedOrderAndOneRunningTask() {
        val e=SplitTaskEngine(null,{})
        val pool=Executors.newFixedThreadPool(6)
        try {
            val results=(0 until 20).map { index -> pool.submit<JSONObject> { e.create(body("$index")) } }.map { it.get() }
            assertEquals(1,results.count { it.getString("status")=="running" })
            val rows=e.queueSnapshot().getJSONArray("items")
            val sequences=(0 until rows.length()).map { rows.getJSONObject(it).getLong("queue_sequence") }
            assertEquals(sequences.sorted(),sequences);assertEquals(20,sequences.distinct().size)
        } finally { pool.shutdownNow() }
    }
    @Test fun expiredReceiptsAreReclaimedWithoutExpiringQueuedTasksOrAllowingClockRollbackReplay() {
        var clock=1000000L;var saved=""
        val e=SplitTaskEngine(null,{saved=it},{clock})
        val input=body("long queue","schedule").put("request_id",TaskSubmissionKey.create(clock,"old"))
        val old=e.create(input)
        clock+=TaskSubmissionKey.RETENTION_MS
        e.create(body("new").put("request_id",TaskSubmissionKey.create(clock,"new")))
        assertEquals(1,JSONObject(saved).getJSONObject("submissions").length())
        assertEquals("queued",e.get(id(old)).getString("status"))
        reject { e.create(input) }
        val restored=SplitTaskEngine(saved,{saved=it},{1000000L})
        reject { restored.create(input) }
        assertEquals(id(old),ids(restored).first())
        assertEquals("running",restored.start(id(old)).getString("status"))
    }
    @Test fun receiptWindowCapacityRecoversAndLegacyReceiptsDoNotConsumeNewWindowCapacity() {
        for (legacy in listOf(false,true)) {
            var clock=1000000L;var saved=""
            val key=if(legacy) "legacy" else TaskSubmissionKey.create(clock,"first")
            val first=SplitTaskEngine(null,{saved=it},{clock})
            val old=first.create(body("old","schedule").put("request_id",key))
            first.control(id(old),"cancel",JSONObject())
            val root=JSONObject(saved);val receipts=root.getJSONObject("submissions")
            val template=receipts.getJSONObject(receipts.keys().next())
            repeat(2047) { receipts.put("seed-$it",JSONObject(template.toString())) }
            val full=SplitTaskEngine(root.toString(),{saved=it},{clock})
            if(!legacy) reject { full.create(body().put("request_id",TaskSubmissionKey.create(clock,"full"))) }
            clock+=TaskSubmissionKey.RETENTION_MS
            full.create(body().put("request_id",TaskSubmissionKey.create(clock,"next-window")))
            assertEquals(if(legacy) 2049 else 1,JSONObject(saved).getJSONObject("submissions").length())
            if(legacy) assertEquals(id(old),id(full.create(body("old","schedule").put("request_id",key))))
        }
    }
    @Test fun failedReceiptCleanupRollsBackTheExpiryFloorAndKeepsRetryIdentity() {
        var clock=1000000L;var saved="";var fail=false
        val e=SplitTaskEngine(null,{if(fail) error("disk");saved=it},{clock})
        val input=body("old","schedule").put("request_id",TaskSubmissionKey.create(clock,"old"))
        val original=e.create(input);val committed=saved
        clock+=TaskSubmissionKey.RETENTION_MS;fail=true
        reject { e.create(body("new").put("request_id",TaskSubmissionKey.create(clock,"new"))) }
        assertEquals(committed,saved)
        fail=false;clock=1000000L
        assertEquals(id(original),id(e.create(input)))
        assertEquals(listOf(id(original)),ids(e))
    }
    @Test fun fixedServerIdsKeepDeduplicationWithTimedKeysAfterHistoryRemoval() {
        var clock=1000000L;var saved=""
        val e=SplitTaskEngine(null,{saved=it},{clock})
        val owner=SplitTaskEngine.ServerOwner("account","session",true)
        val fixed="server-run-"+"b".repeat(64)
        val input=body("pc").put("defer_start",true).put("request_id",TaskSubmissionKey.create(clock,fixed))
        e.createOwned(input,fixed,owner);e.control(fixed,"cancel",JSONObject());e.delete(fixed)
        assertEquals(fixed,e.createOwned(input,fixed,owner).getString("id"));assertFalse(e.hasUnfinished())
        clock+=TaskSubmissionKey.RETENTION_MS
        e.create(body("new").put("request_id",TaskSubmissionKey.create(clock,"new")))
        reject { e.createOwned(input,fixed,owner) }
    }
}

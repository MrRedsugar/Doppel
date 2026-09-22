package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SplitStateCommitTest {
    private fun task(source:String="user")=JSONObject().put("device_id","direct-this-phone").put("goal","查看设置")
        .put("source",source).put("conversation_enabled",source=="user")
    private fun reply(value:String)=SplitTestReply.response(JSONObject(value))
    private fun screenshot(engine:SplitTaskEngine) {
        val command=engine.poll().getJSONObject("command")
        assertEquals("screenshot",command.getString("kind"))
        engine.result(JSONObject().put("run_id",command.getString("run_id")).put("command_id",command.getString("id"))
            .put("status","ok").put("observation",JSONObject().put("package_name","test.app").put("screen_id","screen"))
            .put("data",JSONObject().put("image_base64","cGl4ZWxz")
                .put("visual_frame",JSONObject().put("display_width",100).put("display_height",200))))
    }
    private val plan="""{"kind":"execute","action":"tap","target":"设置","expected":"打开设置页面"}"""
    private val located="""{"status":"located","action":"tap","points":[[500,500]],"assessment":{"alignment":"consistent"}}"""

    @Test fun eachPublicTransitionCommitsOnceBeforeDispatch() {
        var writes=0
        lateinit var engine:SplitTaskEngine
        engine=SplitTaskEngine(null,{
            // Reentrant read simulates a reader at the durable-store boundary.
            assertTrue("New commands must stay private until save succeeds",engine.poll().isNull("command"))
            writes++
        })
        fun once(action:()->Unit) {val before=writes;action();assertEquals(before+1,writes)}
        var id=""
        once {id=engine.create(task()).getString("id")}
        once {screenshot(engine)}
        lateinit var a:SplitTaskEngine.Work
        once {a=engine.takeWork()!!}
        once {engine.accept(a,reply(plan))}
        once {screenshot(engine)}
        lateinit var b:SplitTaskEngine.Work
        once {b=engine.takeWork()!!}
        once {engine.accept(b,reply(located))}
        val action=engine.poll().getJSONObject("command")
        assertEquals("split_action",action.getString("kind"))
        once {engine.result(JSONObject().put("run_id",id).put("command_id",action.getString("id"))
            .put("status","ok").put("data",JSONObject().put("action_state","accepted")))}
        once {screenshot(engine)}
        once {engine.control(id,"pause",JSONObject())}
        once {engine.control(id,"resume",JSONObject())}
        once {engine.control(id,"cancel",JSONObject())}
        once {id=engine.create(task("schedule")).getString("id")}
        assertTrue(engine.poll().isNull("command"))
        once {engine.start(id)}
        assertEquals("screenshot",engine.poll().getJSONObject("command").getString("kind"))
    }

    @Test fun failedGestureCommitPausesAndCannotDispatchOrReplayAfterRestart() {
        var saved="";var failWrite=false
        val engine=SplitTaskEngine(null,{if(failWrite) error("disk unavailable");saved=it})
        val id=engine.create(task()).getString("id")
        screenshot(engine);engine.accept(engine.takeWork()!!,reply(plan));screenshot(engine)
        val b=engine.takeWork()!!
        val committed=saved
        failWrite=true
        try {engine.accept(b,reply(located));fail("Uncommitted gesture accepted")}
        catch (failure:IllegalStateException) {assertEquals("disk unavailable",failure.message)}
        assertEquals(committed,saved)
        assertEquals("paused",engine.statusOrNull(id))
        assertTrue(engine.poll().isNull("command"));assertNull(engine.takeWork())
        val restored=SplitTaskEngine(saved,{})
        assertEquals("paused",restored.statusOrNull(id));assertTrue(restored.poll().isNull("command"))
        failWrite=false
        engine.control(id,"resume",JSONObject())
        assertEquals("screenshot",engine.poll().getJSONObject("command").getString("kind"))
    }

    @Test fun failedResumeDiscardsNewCaptureButQueuedSaveFailureKeepsCommittedCommand() {
        var saved="";var failWrite=false
        val engine=SplitTaskEngine(null,{if(failWrite) error("disk unavailable");saved=it})
        val id=engine.create(task()).getString("id")
        val initial=engine.poll().getJSONObject("command").getString("id")
        val committed=saved
        failWrite=true
        try {engine.create(task("trigger"));fail("Uncommitted queued task accepted")}
        catch (_:IllegalStateException) {}
        assertEquals(committed,saved)
        assertEquals("running",engine.statusOrNull(id))
        assertEquals(initial,engine.poll().getJSONObject("command").getString("id"))
        failWrite=false;engine.control(id,"pause",JSONObject())
        val paused=saved
        failWrite=true
        try {engine.control(id,"resume",JSONObject());fail("Uncommitted resume accepted")}
        catch (_:IllegalStateException) {}
        assertEquals(paused,saved)
        assertEquals("paused",engine.statusOrNull(id));assertTrue(engine.poll().isNull("command"))
        assertNull(engine.takeWork())
        failWrite=false;engine.control(id,"resume",JSONObject())
        val fresh=engine.poll().getJSONObject("command")
        assertEquals("screenshot",fresh.getString("kind"));assertNotEquals(initial,fresh.getString("id"))
    }

    @Test fun failedReceiptCommitRetainsAcceptedActionWithoutReplayingIt() {
        var saved="";var failWrite=false
        val engine=SplitTaskEngine(null,{if(failWrite) error("disk unavailable");saved=it})
        val id=engine.create(task()).getString("id")
        screenshot(engine);engine.accept(engine.takeWork()!!,reply(plan));screenshot(engine)
        engine.accept(engine.takeWork()!!,reply(located))
        val action=engine.poll().getJSONObject("command")
        failWrite=true
        try {
            engine.result(JSONObject().put("run_id",id).put("command_id",action.getString("id"))
                .put("status","ok").put("data",JSONObject().put("action_state","accepted")))
            fail("Uncommitted receipt acknowledged")
        } catch (_:IllegalStateException) {}
        assertEquals("paused",engine.statusOrNull(id));assertTrue(engine.poll().isNull("command"))
        assertEquals(action.getString("id"),engine.get(id).getJSONObject("last_receipt").getString("command_id"))
        assertTrue(SplitTaskEngine(saved,{}).poll().isNull("command"))
        failWrite=false;engine.control(id,"resume",JSONObject())
        assertEquals("screenshot",engine.poll().getJSONObject("command").getString("kind"))
        val recovered=SplitTaskEngine(saved,{}).get(id).getJSONObject("last_receipt")
        assertEquals("accepted",recovered.getString("action_state"))
        assertEquals(action.getString("id"),recovered.getString("command_id"))
    }

    @Test fun failedTransitionWithoutCommandCannotWakeAnotherModelOrLocalTool() {
        for (stage in listOf("screenshot", "model", "local")) {
            var saved="";var failWrite=false;var writes=0
            val engine=SplitTaskEngine(null,{writes++;if(failWrite) error("disk unavailable");saved=it})
            val id=engine.create(task()).getString("id")
            var work:SplitTaskEngine.Work?=null
            if(stage!="screenshot") {
                screenshot(engine)
                work=engine.takeWork()!!
                if(stage=="local") {
                    engine.accept(work,reply("""{"kind":"search_web","query":"Android 设置文档"}"""))
                    work=engine.takeWork()!!
                    assertEquals("search_web",work.localTool)
                }
            }
            val committed=saved;val before=writes
            failWrite=true
            try {
                when(stage) {
                    "screenshot" -> screenshot(engine)
                    "model" -> engine.accept(work!!,reply("""{"kind":"search_web","query":"Android 设置文档"}"""))
                    else -> engine.acceptLocal(work!!,JSONObject().put("text","Android 设置说明"))
                }
                fail("Uncommitted $stage transition accepted")
            } catch (failure:IllegalStateException) {assertEquals("disk unavailable",failure.message)}
            assertEquals("Failure must not add a second save attempt",before+1,writes)
            assertEquals(committed,saved)
            assertEquals("paused",engine.statusOrNull(id))
            assertFalse(engine.readyForWork());assertNull(engine.takeWork());assertTrue(engine.poll().isNull("command"))
            failWrite=false;engine.control(id,"resume",JSONObject())
            assertEquals("screenshot",engine.poll().getJSONObject("command").getString("kind"))
        }
    }

    @Test fun failedTerminalCommitKeepsQueueHeadPausedUntilExplicitRecovery() {
        for (terminal in listOf("completed", "failed", "cancelled")) {
            var saved="";var failWrite=false;var writes=0
            val engine=SplitTaskEngine(null,{writes++;if(failWrite) error("disk unavailable");saved=it})
            val head=engine.create(task()).getString("id")
            val next=engine.create(task("schedule")).getString("id")
            screenshot(engine)
            val work=if(terminal=="cancelled") null else engine.takeWork()!!
            val committed=saved;val before=writes
            failWrite=true
            try {
                if(terminal=="cancelled") engine.control(head,"cancel",JSONObject())
                else engine.accept(work!!,reply("""{"kind":"finish","status":"$terminal","message":"检查结束"}"""))
                fail("Uncommitted terminal transition accepted")
            } catch (failure:IllegalStateException) {assertEquals("disk unavailable",failure.message)}
            assertEquals(before+1,writes);assertEquals(committed,saved)
            assertEquals("paused",engine.statusOrNull(head));assertEquals("queued",engine.statusOrNull(next))
            assertNull(engine.takeWork());assertTrue(engine.poll().isNull("command"))
            try {engine.start(next);fail("Uncommitted terminal state allowed queue advancement")}
            catch (_:IllegalArgumentException) {}
            val restored=SplitTaskEngine(saved,{})
            assertEquals("paused",restored.statusOrNull(head));assertEquals("queued",restored.statusOrNull(next))
            failWrite=false;engine.control(head,"cancel",JSONObject());engine.start(next)
            assertEquals(next,engine.poll().getJSONObject("command").getString("run_id"))
        }
    }

    @Test fun failedCompletionOutboxIsNotPublishedByLaterRecoverySave() {
        var saved="";var failWrite=false
        val engine=SplitTaskEngine(null,{if(failWrite) error("disk unavailable");saved=it})
        val id=engine.create(task()).getString("id")
        screenshot(engine);val work=engine.takeWork()!!
        val before=engine.internalRun(id)
        val eventKey=before.optString("task_event_key")
        val events=before.getJSONArray("task_events").toString()
        failWrite=true
        try {engine.accept(work,reply("""{"kind":"finish","status":"completed","message":"已完成"}"""));fail("Save must fail")}
        catch (_:IllegalStateException) {}
        assertEquals(eventKey,engine.internalRun(id).optString("task_event_key"))
        assertEquals(events,engine.internalRun(id).getJSONArray("task_events").toString())
        failWrite=false
        engine.checkpoint() // The now-paused state may be committed before the user resumes.
        engine.control(id,"resume",JSONObject())
        for(outbox in listOf(engine.companionTaskEvents(null,null),
            SplitTaskEngine(saved,{}).companionTaskEvents(null,null))) {
            assertFalse("A failed completion must never turn into a later completion notification",
                (0 until outbox.length()).any {outbox.getJSONObject(it).let {it.optString("task_id")==id && it.optString("status")=="completed"}})
            assertTrue("The actual recovery pause must still be observable",
                (0 until outbox.length()).any {outbox.getJSONObject(it).let {it.optString("task_id")==id && it.optString("status")=="paused"}})
        }
    }
}

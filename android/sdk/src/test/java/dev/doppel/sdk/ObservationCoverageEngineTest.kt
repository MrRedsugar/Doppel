package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Engine contracts only: accepted host facts bind finish scope; no device or model is involved. */
class ObservationCoverageEngineTest {
    private data class Session(val engine:DirectTaskEngine,val id:String,var evidence:String="",var screen:String="")
    private fun create(planned:Boolean=true,requiredScope:String?=null,visual:Boolean=false,previous:String?=null,parent:String?=null):Session {
        val engine=DirectTaskEngine(previous,{}, {2000L},plannedControl=planned,visualControl=visual,preferVisualObservation=visual)
        val body=JSONObject().put("device_id",DirectRuntime.DEVICE_ID).put("goal","查看集合内容并报告结果").put("mode","full")
        requiredScope?.let { body.put("read_scope",it) };parent?.let { body.put("parent_run_id",it) }
        return Session(engine,engine.create(body).getString("id"))
    }
    private fun screen(id:String="screen-a",rows:List<Int> = listOf(0,1),count:Int=4):JSONObject {
        val nodes=JSONArray().put(JSONObject().put("id","n0").put("parent_id",JSONObject.NULL).put("role","androidx.recyclerview.widget.RecyclerView")
            .put("enabled",true).put("scrollable",true).put("resource_id","dev.list:id/items").put("bounds",JSONArray(listOf(0,0,1080,2400)))
            .put("collection_info",JSONObject().put("row_count",count).put("column_count",1).put("hierarchical",false)))
        rows.forEachIndexed { index,row -> nodes.put(JSONObject().put("id","n0_$index").put("parent_id","n0").put("role","android.widget.TextView")
            .put("enabled",true).put("text","item-$row").put("bounds",JSONArray(listOf(20,100+index*100,1000,180+index*100)))
            .put("collection_item_info",JSONObject().put("row_index",row).put("row_span",1).put("column_index",0).put("column_span",1))) }
        return JSONObject().put("screen_id",id).put("package_name","dev.list").put("width",1080).put("height",2400)
            .put("tree_complete",true).put("nodes",nodes).apply { put("collection_evidence",ObservationCoverage.collectionEvidence(this)) }
    }
    private fun ordinaryScreen(id:String="screen-a")=JSONObject().put("screen_id",id).put("package_name","dev.calc").put("width",1080).put("height",2400)
        .put("nodes",JSONArray().put(JSONObject().put("id","n0").put("text","42").put("enabled",true)))
    private fun receipt(session:Session,observed:JSONObject=screen(),status:String="ok",data:JSONObject=JSONObject(),visual:Boolean=false):JSONObject {
        val command=session.engine.poll().getJSONObject("command")
        val actual=JSONObject(data.toString()).put("scroll_directions",JSONObject().put("n0",JSONArray(listOf("up","down"))))
        if(visual) actual.put("device_profile",JSONObject().put("visual_gestures",true)).put("image_base64","c3ludGhldGlj")
            .put("mime_type","image/png").put("visual_frame",VisualFrame("capture-1",observed.getString("screen_id"),observed.getString("package_name"),1080,2400,648,1440,0,1000,46000,"a".repeat(64)).json())
        val value=JSONObject().put("run_id",command.getString("run_id")).put("command_id",command.getString("id"))
            .put("status",status).put("observation",observed).put("data",actual)
        assertTrue(session.engine.result(value).getBoolean("accepted"))
        if(status=="ok") {session.evidence=command.getString("id");session.screen=observed.getString("screen_id")}
        return value
    }
    private fun reply(tool:String,args:JSONObject)=JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","tool_calls")
        .put("message",JSONObject().put("tool_calls",JSONArray().put(JSONObject().put("function",JSONObject().put("name",tool).put("arguments",args.toString())))))))
    private fun call(session:Session,tool:String,args:JSONObject) { session.engine.accept(requireNotNull(session.engine.takeWork()),reply(tool,args)) }
    private fun finish(session:Session,scope:String?="visible",collection:String?=null,basis:String="current_screen",outcome:String="completed") {
        val args=JSONObject().put("summary","已核对结果").put("outcome",outcome).put("basis",basis)
            .put("screen_id",session.screen).put("evidence_id",session.evidence)
        scope?.let { args.put("read_scope",it) };collection?.let { args.put("collection_scope",it) }
        call(session,"finish",args)
    }
    private fun run(session:Session)=session.engine.get(session.id)
    private fun coverage(session:Session)=run(session).getJSONObject("observation_coverage")
    private fun scroll(session:Session) = call(session,"action",JSONObject().put("kind","scroll").put("target","n0").put("direction","down"))
    private val exactScope="dev.list/dev.list:id/items"

    @Test fun acceptedObservationAndScrollAreRecordedOnceAndExposedToPlanner() {
        val s=create();val first=receipt(s)
        assertEquals(1,coverage(s).getInt("distinct_viewports"));assertFalse(coverage(s).getBoolean("all_proven"))
        assertFalse(s.engine.result(first).getBoolean("accepted"))
        val work=s.engine.takeWork()!!
        assertTrue(work.payload.toString().contains("collection_scope=$exactScope"))
        assertTrue(work.payload.toString().contains("不能据此声称全部"))
        s.engine.accept(work,reply("action",JSONObject().put("kind","scroll").put("target","n0").put("direction","down")))
        val command=s.engine.poll().getJSONObject("command")
        val forged=JSONObject(first.toString()).put("command_id",command.getString("id")).put("run_id","wrong-run")
        assertFalse(s.engine.result(forged).getBoolean("accepted"))
        val after=receipt(s,screen("screen-b",listOf(2,3)),data=JSONObject().put("action_state","accepted"))
        assertFalse(s.engine.result(after).getBoolean("accepted"))
        assertEquals(2,coverage(s).getInt("distinct_viewports"));assertEquals(1,coverage(s).getInt("accepted_scrolls"))
        assertFalse(coverage(s).getBoolean("all_proven"))
    }
    @Test fun plannerAndVisualActorOfferExplicitScopeAndSeeCoverage() {
        for(visual in listOf(false,true)) {
            val s=create(visual=visual);receipt(s,screen(rows=listOf(0,1,2,3)),visual=visual)
            val work=s.engine.takeWork()!!;assertEquals(visual,work.visualAgent)
            val tools=work.payload.getJSONArray("tools")
            val finish=(0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }.single { it.getString("name")=="finish" }.getJSONObject("parameters")
            assertTrue(finish.getJSONArray("required").toString().contains("read_scope"))
            assertEquals("[\"visible\",\"all\"]",finish.getJSONObject("properties").getJSONObject("read_scope").getJSONArray("enum").toString())
            assertTrue(finish.getJSONObject("properties").has("collection_scope"))
            assertTrue(work.payload.toString().contains("collection_scope=$exactScope"))
        }
    }
    @Test fun partialOrWrongCollectionCannotCompleteAllInEitherMode() {
        for(planned in listOf(false,true)) for(complete in listOf(false,true)) {
            val s=create(planned);receipt(s,screen(rows=if(complete) listOf(0,1,2,3) else listOf(0,1)))
            finish(s,"all",if(complete) "dev.list/another" else exactScope)
            assertNotEquals("completed",run(s).getString("status"))
            assertEquals("completion_coverage_incomplete",run(s).getJSONObject("recovery_feedback").getString("code"))
        }
    }
    @Test fun currentCompleteCollectionAllowsExactAllClaim() {
        val s=create(requiredScope="all");receipt(s,screen(rows=listOf(0,1,2,3)));finish(s,"all",exactScope)
        assertEquals("completed",run(s).getString("status"));assertEquals("all",run(s).getString("result_read_scope"))
        assertEquals(exactScope,run(s).getString("result_collection_scope"))
    }
    @Test fun declaredAllCannotBorrowOldFullViewportOrRawDatasetRevision() {
        for(initial in listOf(listOf(0,1,2,3),listOf(0,1))) {
            val s=create();receipt(s,screen(rows=initial).apply { getJSONObject("collection_evidence").put("dataset_revision","untrusted-same") })
            scroll(s);receipt(s,screen("screen-b",listOf(2,3)).apply { getJSONObject("collection_evidence").put("dataset_revision","untrusted-same") },data=JSONObject().put("action_state","accepted"))
            finish(s,"all",exactScope);assertNotEquals("completed",run(s).getString("status"))
        }
    }
    @Test fun userAllRequirementCannotDowngradeToVisibleOrOmittedScopeInEitherMode() {
        for(planned in listOf(false,true)) for(scope in listOf("visible",null)) {
            val s=create(planned,requiredScope="all");receipt(s)
            assertEquals("all",run(s).getString("required_read_scope"))
            finish(s,scope);assertNotEquals("completed",run(s).getString("status"))
        }
    }
    @Test fun failedCanReportIncompleteAllTaskWithoutClaimingProof() {
        val s=create(requiredScope="all");receipt(s)
        finish(s,scope=null,outcome="failed")
        assertEquals("failed",run(s).getString("status"));assertNotEquals("all",run(s).optString("result_read_scope"))
    }
    @Test fun legacyOrdinaryTaskKeepsExistingFinishAndMessageWhilePlannedRequiresScope() {
        val legacy=create(planned=false);receipt(legacy,ordinaryScreen());finish(legacy,scope=null)
        assertEquals("completed",run(legacy).getString("status"));assertEquals("已核对结果",run(legacy).getString("message"))
        val planned=create();receipt(planned,ordinaryScreen());finish(planned,scope=null)
        assertNotEquals("completed",run(planned).getString("status"))
        val scoped=create();receipt(scoped,ordinaryScreen());finish(scoped)
        assertEquals("completed",run(scoped).getString("status"));assertEquals("已核对结果",run(scoped).getString("message"))
    }
    @Test fun visibleCollectionResultExplicitlyReportsItsRange() {
        val s=create();receipt(s);finish(s)
        assertEquals("completed",run(s).getString("status"));assertEquals("visible",run(s).getString("result_read_scope"))
        assertTrue(run(s).getString("message").contains("当前可见范围"))
    }
    @Test fun failedOrStaleReceiptsCannotInstallFullCollectionEvidence() {
        for(status in listOf("error","stale")) {
            val s=create();receipt(s);scroll(s)
            receipt(s,screen("not-accepted",listOf(0,1,2,3)),status=status,data=JSONObject().put("action_state","unconfirmed"))
            assertFalse(coverage(s).getBoolean("all_proven"));assertEquals(1,coverage(s).getInt("distinct_viewports"))
            assertEquals(0,coverage(s).getInt("accepted_scrolls"))
        }
    }
    @Test fun historicalConversationRemainsAvailableButCannotSatisfyNewAllTask() {
        val prior=JSONObject().put("id","old-run").put("device_id",DirectRuntime.DEVICE_ID).put("goal","查看之前结果")
            .put("status","completed").put("message","先前看到了两行").put("created_at",1000L).put("updated_at",1000L)
        for(required in listOf(null,"all")) {
            val s=create(planned=false,requiredScope=required,previous=JSONArray().put(prior).toString(),parent="old-run")
            receipt(s);finish(s,scope=null,basis="conversation")
            if(required==null) assertEquals("completed",run(s).getString("status")) else assertNotEquals("completed",run(s).getString("status"))
        }
    }
    @Test fun cancellationAndResumeCannotUseEarlierCompleteProof() {
        val s=create();val full=receipt(s,screen(rows=listOf(0,1,2,3)))
        s.engine.control(s.id,"pause",JSONObject());assertFalse(s.engine.result(full).getBoolean("accepted"));assertNull(s.engine.takeWork())
        s.engine.control(s.id,"resume",JSONObject());receipt(s,screen("resumed",listOf(2,3)))
        finish(s,"all",exactScope);assertNotEquals("completed",run(s).getString("status"))
    }
}

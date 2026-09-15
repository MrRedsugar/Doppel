package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ObservationCoverageTest {
    private fun screen(id: String = "s1", value: String = "08:00") = JSONObject().put("screen_id", id).put("package_name", "dev.clock")
        .put("width", 1440).put("height", 3200).put("tree_complete", true).put("nodes", JSONArray()
            .put(JSONObject().put("id", "n1").put("text", value).put("role", "text"))
            .put(JSONObject().put("id", "n2").put("scrollable", true).put("resource_id", "dev.clock:id/alarms")
                .put("scroll_directions", JSONArray(listOf("up", "down")))))
    private fun collection(screen: JSONObject, rows: List<Int>, total: Int = 4, revision: String? = null): JSONObject {
        screen.put("collection_evidence", JSONObject().put("source", "accessibility_collection_info").put("screen_id", screen.getString("screen_id"))
            .put("package_name", screen.getString("package_name")).put("collection_id", "alarms").put("row_count", total).put("column_count", 1)
            .put("visible_rows", JSONArray(rows)).put("structure_complete", true).apply { revision?.let { put("dataset_revision", it) } })
        return screen
    }
    private fun observe(run: JSONObject, screen: JSONObject, trustedRevision: String? = null) =
        ObservationCoverage.observe(run,screen,"evidence-${screen.getString("screen_id")}",1000,trustedRevision)
    private fun box(left: Int, top: Int, right: Int, bottom: Int) = JSONArray(listOf(left, top, right, bottom))
    private fun nativeNode(id: String, parent: String?, bounds: JSONArray) = JSONObject().put("id", id)
        .put("parent_id", parent ?: JSONObject.NULL).put("bounds", bounds)
    private fun nativeScreen(rows: List<Int> = listOf(0,1,2,3), total: Int = 4, id: String = "s1"): JSONObject {
        val nodes = JSONArray().put(nativeNode("root", null, box(0,0,1440,3200)))
            .put(nativeNode("list", "root", box(100,100,1300,2800)).put("resource_id", "dev.clock:id/alarms")
                .put("collection_info", JSONObject().put("row_count", total).put("column_count", 1).put("hierarchical", false).put("selection_mode", 0)))
            .put(nativeNode("body", "list", box(100,100,1300,2800)))
        rows.forEachIndexed { position, row ->
            val top = 200 + position * 400
            nodes.put(nativeNode("item-$position", "body", box(120,top,1280,top+350))
                .put("collection_item_info", JSONObject().put("row_index", row).put("row_span", 1).put("column_index", 0).put("column_span", 1)
                    .put("heading", false).put("selected", false)))
            nodes.put(nativeNode("label-$position", "item-$position", box(150,top+30,1200,top+100)).put("text", "行$row"))
        }
        return screen(id).put("nodes", nodes)
    }
    private fun node(screen: JSONObject, id: String): JSONObject {
        val nodes = screen.getJSONArray("nodes")
        return (0 until nodes.length()).map { nodes.getJSONObject(it) }.first { it.getString("id") == id }
    }
    private fun bindNative(screen: JSONObject): JSONObject {
        val evidence = ObservationCoverage.collectionEvidence(screen)
        assertNotNull("Expected trustworthy collection metadata", evidence)
        return screen.put("collection_evidence", evidence)
    }
    private fun recordBoundary(run: JSONObject, screen: JSONObject, edge: String, id: String = "scroll-$edge", scope: String = "dev.clock/alarms") {
        ObservationCoverage.recordAction(run, JSONObject().put("id",id).put("kind","scroll"), "ok",
            JSONObject().put("no_op",true).put("coverage_boundary", JSONObject().put("source","host_verified_scroll_boundary")
                .put("command_id",id).put("screen_id",screen.getString("screen_id")).put("package_name","dev.clock").put("scope",scope).put("edge",edge)))
    }
    @Test fun visibleLabelsAndStaticScrollActionsNeverProveAll() {
        val run=JSONObject(); val current=screen(); observe(run,current)
        val command=JSONObject().put("id","scroll-1").put("kind","scroll").put("direction","down")
        ObservationCoverage.recordAction(run,command,"ok",JSONObject().put("no_op",true).put("action_state","direction_unavailable"))
        assertFalse(ObservationCoverage.canClaimAll(run,current))
        assertTrue(ObservationCoverage.summary(run,current).contains("当前可见范围"))
    }
    @Test fun newScreenIdsDoNotCountAsNewViewportsWithoutContentChange() {
        val run=JSONObject(); observe(run,screen()); observe(run,screen("s2")); observe(run,screen("s3","09:00"))
        assertEquals(2,run.getJSONObject("observation_coverage").getInt("distinct_viewports"))
    }
    @Test fun oneCompleteBoundCollectionProvesOnlyItsCurrentScope() {
        val run=JSONObject();val current=collection(screen(),listOf(0,1,2,3));observe(run,current)
        assertTrue(ObservationCoverage.canClaimAll(run,current))
        assertTrue(ObservationCoverage.canClaimAll(run,current,"dev.clock/alarms"))
        assertFalse(ObservationCoverage.canClaimAll(run,current,"dev.clock/reminders"))
        assertFalse(ObservationCoverage.canClaimAll(run,current,""))
        assertFalse(ObservationCoverage.canClaimAll(run,screen("elsewhere")))
        assertFalse(ObservationCoverage.canClaimAll(run,JSONObject(current.toString()).put("package_name","other.app")))
    }
    @Test fun crossPageUnionRequiresDatasetRevisionAndNoMissingRows() {
        val run=JSONObject();observe(run,collection(screen(),listOf(0,1),revision="revision-a"),"revision-a")
        val last=collection(screen("s2","10:00"),listOf(2,3),revision="revision-a");observe(run,last,"revision-a")
        assertTrue(ObservationCoverage.canClaimAll(run,last))
        val gaps=JSONObject();observe(gaps,collection(screen(),listOf(0),revision="revision-a"),"revision-a");observe(gaps,last,"revision-a")
        assertFalse(ObservationCoverage.canClaimAll(gaps,last))
        val noRevision=JSONObject();observe(noRevision,collection(screen(),listOf(0,1)));val partial=collection(screen("s2"),listOf(2,3));observe(noRevision,partial)
        assertFalse(ObservationCoverage.canClaimAll(noRevision,partial))
    }
    @Test fun ChangedRevisionOrTotalCannotReuseEarlierRows() {
        val run=JSONObject();observe(run,collection(screen(),listOf(0,1),revision="old"),"old")
        val current=collection(screen("s2"),listOf(2,3),revision="new");observe(run,current,"new")
        assertFalse(ObservationCoverage.canClaimAll(run,current))
        val differentTotal=collection(screen("s3"),listOf(2,3,4),5,"old");observe(run,differentTotal,"old")
        assertFalse(ObservationCoverage.canClaimAll(run,differentTotal))
    }
    @Test fun IncompleteTreeWrongSourceAndOutOfRangeRowsDoNotProveCoverage() {
        val candidates=listOf(collection(screen(),listOf(0,1)).put("tree_complete",false),
            collection(screen(),listOf(0,1),2).apply { getJSONObject("collection_evidence").put("screen_id","old") },
            collection(screen(),listOf(0,1),2).apply { getJSONObject("collection_evidence").put("source","model") },
            collection(screen(),listOf(0,1,2),2), collection(screen(),listOf(0,1),2).apply { getJSONObject("collection_evidence").put("column_count",2) })
        for(current in candidates) {val run=JSONObject();observe(run,current);assertFalse(ObservationCoverage.canClaimAll(run,current))}
    }
    @Test fun InputValuesAndModelBoundaryStatementsNeverEnterProof() {
        val run=JSONObject();val current=screen().apply { getJSONArray("nodes").put(JSONObject().put("id","n3").put("editable",true).put("text","secret-input")) }
        observe(run,current)
        ObservationCoverage.recordAction(run,JSONObject().put("id","scroll-1").put("kind","scroll"),"ok",
            JSONObject().put("boundary","end").put("model_said_complete",true).put("text","secret-input"))
        assertFalse(run.toString().contains("secret-input"));assertFalse(ObservationCoverage.canClaimAll(run,current))
    }
    @Test fun DuplicateActionReceiptsDoNotDoubleCountScrolls() {
        val run=JSONObject();observe(run,screen())
        val command=JSONObject().put("id","scroll-1").put("kind","scroll").put("direction","down")
        repeat(2){ObservationCoverage.recordAction(run,command,"ok",JSONObject().put("action_state","accepted"))}
        for(actionState in listOf("not_dispatched","unconfirmed","")) {
            ObservationCoverage.recordAction(run,JSONObject().put("id","scroll-$actionState").put("kind","scroll"),"ok",JSONObject().put("action_state",actionState))
        }
        assertEquals(1,run.getJSONObject("observation_coverage").getInt("accepted_scrolls"))
    }

    @Test fun revisionInsideObservationNeverAuthorizesCrossPageUnion() {
        val run=JSONObject();observe(run,collection(screen(),listOf(0,1),revision="claimed-revision"))
        val last=collection(screen("s2","10:00"),listOf(2,3),revision="claimed-revision");observe(run,last)
        assertFalse(ObservationCoverage.canClaimAll(run,last))
        assertEquals(2,run.getJSONObject("observation_coverage").getJSONArray("covered_rows").length())
    }
    @Test fun delimiterCharactersCannotMergeDifferentCollectionScopes() {
        val first=collection(screen(),listOf(0,1),revision="x|4|y").apply { getJSONObject("collection_evidence").put("collection_id","a") }
        val other=collection(screen("s2"),listOf(2,3),revision="y").apply { getJSONObject("collection_evidence").put("collection_id","a|4|x") }
        val run=JSONObject();observe(run,first,"x|4|y");observe(run,other,"y")
        assertFalse(ObservationCoverage.canClaimAll(run,other,"dev.clock/a|4|x"))
    }
    @Test fun changedMetadataCannotInheritProofEvenWithReusedScreenId() {
        val changes: List<(JSONObject)->Unit> = listOf(
            { it.getJSONObject("collection_evidence").put("visible_rows",JSONArray(listOf(0))) },
            { it.getJSONObject("collection_evidence").put("row_count",5) },
            { it.getJSONObject("collection_evidence").put("dataset_revision","changed") },
            { it.getJSONArray("nodes").getJSONObject(0).put("text","changed row values") },
            { it.put("width",1080) },
            { it.getJSONArray("nodes").getJSONObject(1).put("resource_id","dev.clock:id/other-list") })
        for((index,change) in changes.withIndex()) {
            val run=JSONObject();val original=collection(screen(),listOf(0,1,2,3));observe(run,original)
            val changed=JSONObject(original.toString());change(changed)
            assertFalse("Changed metadata case $index requires a new observation",ObservationCoverage.canClaimAll(run,changed))
        }
    }
    @Test fun coercedNumbersAndBooleansCannotBecomeCollectionProof() {
        val changes: List<(JSONObject)->Unit> = listOf(
            { it.put("column_count","1") }, { it.put("column_count",1.9) }, { it.put("column_count",1.0) },
            { it.put("column_count",4294967297L) }, { it.put("row_count","4") }, { it.put("row_count",4.1) },
            { it.put("row_count",4294967300L) }, { it.put("visible_rows",JSONArray(listOf(0,1,2,"3"))) },
            { it.put("visible_rows",JSONArray(listOf(0,1,2,3.0))) }, { it.put("structure_complete","true") })
        for((index,change) in changes.withIndex()) {
            val current=collection(screen(),listOf(0,1,2,3));change(current.getJSONObject("collection_evidence"))
            val run=JSONObject();observe(run,current)
            assertFalse("Coercion case $index",ObservationCoverage.canClaimAll(run,current))
        }
        val current=collection(screen(),listOf(0,1,2,3)).put("tree_complete","true")
        val run=JSONObject();observe(run,current);assertFalse(ObservationCoverage.canClaimAll(run,current))
    }
    @Test fun boundariesDoNotFillRowGapsAndAreClearedOnScopeChange() {
        val run=JSONObject();val first=collection(screen(),listOf(0,3));observe(run,first)
        recordBoundary(run,first,"start");recordBoundary(run,first,"end")
        assertTrue(run.getJSONObject("observation_coverage").optBoolean("seen_start"))
        assertTrue(run.getJSONObject("observation_coverage").optBoolean("seen_end"))
        assertFalse(ObservationCoverage.canClaimAll(run,first))
        val other=collection(screen("s2"),listOf(0)).apply { getJSONObject("collection_evidence").put("collection_id","reminders") };observe(run,other)
        assertFalse(run.getJSONObject("observation_coverage").optBoolean("seen_start"))
        assertFalse(run.getJSONObject("observation_coverage").optBoolean("seen_end"))
    }
    @Test fun boundariesAreClearedWhenRevisionChangesOrEvidenceDisappears() {
        val run=JSONObject();val current=collection(screen(),listOf(0));observe(run,current,"old")
        recordBoundary(run,current,"start");observe(run,current,"new")
        assertFalse(run.getJSONObject("observation_coverage").optBoolean("seen_start"))
        recordBoundary(run,current,"end");observe(run,screen("missing"))
        assertFalse(run.getJSONObject("observation_coverage").optBoolean("seen_end"))
    }
    @Test fun wrongScopeBoundaryIsIgnored() {
        val run=JSONObject();val current=collection(screen(),listOf(0));observe(run,current)
        recordBoundary(run,current,"end",scope="dev.clock/other")
        assertFalse(run.getJSONObject("observation_coverage").optBoolean("seen_end"))
    }
    @Test fun nativeCollectionRowsProduceCompleteBoundScopeWithoutInventedRevision() {
        val current=nativeScreen().put("dataset_revision","untrusted")
        node(current,"list").getJSONObject("collection_info").put("dataset_revision","also-untrusted")
        bindNative(current)
        val evidence=current.getJSONObject("collection_evidence")
        assertEquals("accessibility_collection_info",evidence.getString("source"))
        assertEquals("dev.clock:id/alarms",evidence.getString("collection_id"))
        assertEquals("[0,1,2,3]",evidence.getJSONArray("visible_rows").toString())
        assertEquals(4,evidence.getInt("row_count"));assertFalse(evidence.has("dataset_revision"))
        val run=JSONObject();observe(run,current)
        assertTrue(ObservationCoverage.canClaimAll(run,current,"dev.clock/dev.clock:id/alarms"))
    }
    @Test fun nativePartialPagesRemainVisibleOnlyWithoutTrustedRevision() {
        val run=JSONObject();observe(run,bindNative(nativeScreen(listOf(0,1))))
        val last=bindNative(nativeScreen(listOf(2,3),id="s2"));observe(run,last)
        assertFalse(ObservationCoverage.canClaimAll(run,last))
        assertEquals("[2,3]",last.getJSONObject("collection_evidence").getJSONArray("visible_rows").toString())
    }
    @Test fun missingOrRepeatedResourceIdsUseExactNodeScope() {
        val missing=nativeScreen();node(missing,"list").remove("resource_id")
        assertEquals("node:list",bindNative(missing).getJSONObject("collection_evidence").getString("collection_id"))
        val repeated=nativeScreen();node(repeated,"label-0").put("resource_id","dev.clock:id/alarms")
        assertEquals("node:list",bindNative(repeated).getJSONObject("collection_evidence").getString("collection_id"))
    }
    @Test fun multipleNestedOrHierarchicalCollectionsCannotClaimAll() {
        for(parent in listOf("root","item-0")) {
            val current=nativeScreen()
            current.getJSONArray("nodes").put(nativeNode("second",parent,box(150,240,1000,400))
                .put("collection_info",JSONObject().put("row_count",1).put("column_count",1).put("hierarchical",false)))
            assertNull("Second collection under $parent",ObservationCoverage.collectionEvidence(current))
        }
        val hierarchical=nativeScreen();node(hierarchical,"list").getJSONObject("collection_info").put("hierarchical",true)
        assertNull(ObservationCoverage.collectionEvidence(hierarchical))
    }
    @Test fun malformedParentGraphsCannotAssignCollectionRows() {
        val changes: List<(JSONObject)->Unit> = listOf(
            { node(it,"item-0").put("parent_id","missing") }, { node(it,"body").put("parent_id","item-0") },
            { node(it,"item-0").put("id","item-1") }, { node(it,"body").put("parent_id",1) })
        for((index,change) in changes.withIndex()) {
            val current=nativeScreen();change(current)
            assertNull("Parent graph case $index",ObservationCoverage.collectionEvidence(current))
        }
    }
    @Test fun rowIndexOutsideCollectionAncestryCannotFillGap() {
        val current=nativeScreen();node(current,"item-2").put("parent_id","root");bindNative(current)
        assertEquals("[0,1,3]",current.getJSONObject("collection_evidence").getJSONArray("visible_rows").toString())
        val run=JSONObject();observe(run,current);assertFalse(ObservationCoverage.canClaimAll(run,current))
    }
    @Test fun clippedItemOrChildContentCannotCountAsCompleteVisibleRow() {
        for(clipped in listOf("item-0","label-0")) {
            val current=nativeScreen();node(current,clipped).put("bounds",box(50,230,1200,300));bindNative(current)
            assertEquals(clipped,"[1,2,3]",current.getJSONObject("collection_evidence").getJSONArray("visible_rows").toString())
            val run=JSONObject();observe(run,current);assertFalse(ObservationCoverage.canClaimAll(run,current))
        }
    }
    @Test fun clippedCollectionOrInvalidScreenSizeCannotProduceEvidence() {
        val changes: List<(JSONObject)->Unit> = listOf(
            { node(it,"list").put("bounds",box(100,100,1500,2800)) }, { node(it,"root").put("bounds",box(0,0,1000,3200)) },
            { it.put("width","1440") }, { it.put("height",0) }, { it.put("tree_complete",false) })
        for((index,change) in changes.withIndex()) {
            val current=nativeScreen();change(current)
            assertNull("Visibility case $index",ObservationCoverage.collectionEvidence(current))
        }
    }
    @Test fun spansOrInvalidNativeNumbersCannotManufactureVisibleRows() {
        val changes: List<(JSONObject)->Unit> = listOf(
            { node(it,"item-0").getJSONObject("collection_item_info").put("row_span",4) },
            { node(it,"item-0").getJSONObject("collection_item_info").put("column_span",2) },
            { node(it,"item-0").getJSONObject("collection_item_info").put("row_index","0") },
            { node(it,"item-0").getJSONObject("collection_item_info").put("row_index",4294967296L) },
            { node(it,"item-0").getJSONObject("collection_item_info").put("column_index",0.1) },
            { node(it,"list").getJSONObject("collection_info").put("column_count",1.9) },
            { node(it,"list").getJSONObject("collection_info").put("row_count","4") },
            { node(it,"list").getJSONObject("collection_info").put("hierarchical","false") })
        for((index,change) in changes.withIndex()) {
            val current=nativeScreen();change(current)
            assertNull("Native metadata case $index",ObservationCoverage.collectionEvidence(current))
        }
    }
    @Test fun duplicateOrNestedRowMetadataCannotRepresentIndependentRows() {
        val duplicate=nativeScreen();node(duplicate,"item-1").getJSONObject("collection_item_info").put("row_index",0)
        assertNull(ObservationCoverage.collectionEvidence(duplicate))
        val nested=nativeScreen();node(nested,"item-1").put("parent_id","item-0").put("bounds",box(130,240,1200,500))
        assertNull(ObservationCoverage.collectionEvidence(nested))
    }
}

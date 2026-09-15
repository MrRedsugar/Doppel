package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GuardedActionPlanTest {
    private fun node(id: String, text: String) = JSONObject().put("id", id).put("text", text).put("enabled", true).put("clickable", true)
    private fun screen(vararg nodes: JSONObject) = JSONObject().put("screen_id", "screen-current").put("package_name", "example.app").put("nodes", JSONArray(nodes.toList()))
    private fun step(text: String) = JSONObject().put("kind", "tap").put("package_name", "example.app").put("selector", JSONObject().put("text", text))
    private fun visible(text:String)=JSONObject().put("selector",JSONObject().put("text",text)).put("exists",true)
    private fun plan(vararg steps: JSONObject) = GuardedActionPlan.parse(JSONObject().put("objective", "打开设置并查看信息").put("steps", JSONArray(steps.toList())), 1000)
    private fun action(plan: GuardedActionPlan, screen: JSONObject, evidence: String = "host-evidence") = (plan.next(screen, evidence, 1001) as GuardedActionPlan.Decision.Action).value
    @Test fun everyStepUsesNewNodesAndWaitsForItsOwnReceipt() {
        val p = plan(step("设置"), step("关于"))
        assertEquals("n1", action(p, screen(node("n1", "设置"))).getString("target"))
        p.dispatched("c1")
        p.result(JSONObject().put("id", "foreign"), "ok", JSONObject())
        assertTrue(p.next(screen(node("n2", "关于")), "e", 1002) is GuardedActionPlan.Decision.Replan)
        p.result(JSONObject().put("id", "c1"), "ok", JSONObject())
        assertEquals("n23", action(p, screen(node("n23", "关于")), "receipt-c1").getString("target"))
    }
    @Test fun duplicateOrMissingTargetsNeverGuessCoordinates() {
        val p = plan(step("设置"))
        assertTrue(p.next(screen(node("n1", "设置"), node("n2", "设置")), "e", 1001) is GuardedActionPlan.Decision.Replan)
        val missing = plan(step("设置")); repeat(2) { assertTrue(missing.next(screen(), "e", 1001) is GuardedActionPlan.Decision.Observe) }
        assertTrue(missing.next(screen(), "e", 1001) is GuardedActionPlan.Decision.Replan)
    }
    @Test fun expectedOutcomeMustBePresentBeforeNextAction() {
        val p = plan(step("打开").put("expect", visible("设置")), step("关于"))
        action(p, screen(node("n1", "打开"))); p.dispatched("c1"); p.result(JSONObject().put("id", "c1"), "ok", JSONObject())
        repeat(2) { assertTrue(p.next(screen(node("n2", "关于")), "e", 1001) is GuardedActionPlan.Decision.Observe) }
        assertTrue(p.next(screen(node("n2", "关于")), "e", 1001) is GuardedActionPlan.Decision.Replan)
    }
    @Test fun acceptedDoesNotProveFinalExpectedOutcome() {
        val p=plan(step("确认").put("expect", visible("保存成功")));action(p,screen(node("n1","确认")));p.dispatched("a");p.result(JSONObject().put("id","a"),"ok",JSONObject())
        assertTrue(p.next(screen(node("n2","保存失败")),"e",1002) is GuardedActionPlan.Decision.Observe)
        assertTrue(p.next(screen(node("n3","保存成功")),"e",1003) is GuardedActionPlan.Decision.Done)
    }
    @Test fun failedAndUnknownResultsAbortInsteadOfReplaying() {
        for(status in listOf("error","stale","blocked")) {
            val p=plan(step("确认"));action(p,screen(node("n1","确认")));p.dispatched("a");p.result(JSONObject().put("id","a"),status,JSONObject())
            assertTrue(p.next(screen(node("n1","确认")),"e",1001) is GuardedActionPlan.Decision.Replan)
        }
    }
    @Test fun typingRequiresUniqueEditableFocusAndSummaryDoesNotStoreText() {
        val s=JSONObject().put("kind","type").put("package_name","example.app").put("selector",JSONObject().put("focused",true)).put("text","private-input-492")
        val p=plan(s)
        assertEquals("n4",action(p,screen(node("n4","").put("editable",true).put("focused",true))).getString("target"))
        assertFalse(p.summary().toString().contains("private-input-492"))
    }
    @Test fun expiresAndRejectsAssistantSurface() {
        val p=plan(step("设置"));assertTrue(p.next(screen(node("n1","设置")),"e",92000) is GuardedActionPlan.Decision.Replan)
        assertTrue(p.next(screen(node("n1","设置")).put("assistant_surface",true),"e",1001) is GuardedActionPlan.Decision.Replan)
    }
    @Test fun rejectsNodeIdsCoordinatesAndUnlimitedPlans() {
        for(s in listOf(step("设置").put("x",123),step("设置").put("selector",JSONObject().put("id","n1")))) {
            assertThrows(IllegalArgumentException::class.java){plan(s)}
        }
        assertThrows(IllegalArgumentException::class.java){plan(*(0..12).map{step("1")}.toTypedArray())}
    }
    @Test fun oldObservationCannotDriveSecondStepEvenIfTargetStillVisible() {
        val p=plan(step("1"),step("1"));val page=screen(node("n1","1"))
        p.next(page,"source-1",1001);p.dispatched("c1")
        p.result(JSONObject().put("id","c1"),"ok",JSONObject().put("human_takeover",JSONObject.NULL))
        assertTrue(p.next(page,"source-1",1002) is GuardedActionPlan.Decision.Observe)
        assertTrue(p.next(page,"receipt-c1",1003) is GuardedActionPlan.Decision.Action)
    }

    @Test fun editorSubmissionResolvesFreshSessionInsteadOfReusingModelToken() {
        val step=JSONObject().put("kind","ime_action").put("package_name","example.app").put("selector",JSONObject().put("focused",true)).put("action","done")
        val p=plan(step);val node=node("n4","").put("editable",true).put("focused",true).put("ime_action","done").put("ime_editor_id","new-session")
        assertEquals("new-session",action(p,screen(node)).getString("editor_id"))
        assertTrue(plan(step).next(screen(node.put("ime_action","send")),"e",1001) is GuardedActionPlan.Decision.Replan)
    }

    @Test fun proseExpectationIsNotAcceptedAsExecutableCondition() {
        assertThrows(org.json.JSONException::class.java){plan(step("1").put("expect","Digit 1 appears in display"))}
    }
    @Test fun typedPostconditionChecksTheResultFieldRatherThanAnotherDigitButton() {
        val predicate=JSONObject().put("selector",JSONObject().put("resource_id","result")).put("text_equals","1")
        val p=plan(step("1").put("expect",predicate));action(p,screen(node("n1","1")));p.dispatched("c")
        p.result(JSONObject().put("id","c"),"ok",JSONObject())
        assertTrue(p.next(screen(node("n1","1"),node("n2","0").put("resource_id","result")),"e2",1002) is GuardedActionPlan.Decision.Observe)
        assertTrue(p.next(screen(node("n1","1"),node("n2","1").put("resource_id","result")),"e3",1003) is GuardedActionPlan.Decision.Done)
    }

    // Reduced from the real C3 WPS Save wrapper/child pair; no document or account data.
    private fun saveWrapper(id:String="n0")=node(id,"").put("description","").put("role","button")
        .put("bounds",JSONArray(listOf(240,110,350,220)))
    private fun saveLabel(id:String="n0_0")=node(id,"").put("description","save").put("clickable",false)
        .put("bounds",JSONArray(listOf(240,110,350,220)))
    private fun sizedScreen(vararg nodes:JSONObject)=screen(*nodes).put("width",1440).put("height",3200)
    private fun byLabel(field:String="description")=step("unused").put("selector",JSONObject().put(field,"save"))

    @Test fun realSaveWrapperLabelAdvertisedInSummaryResolvesToOneParentAction() {
        val page=sizedScreen(saveWrapper(),saveLabel())
        val original=page.toString()
        assertTrue(ModelScreenSummary.render(page).text.contains("n0: save [点]"))
        for(field in listOf("text","description")) {
            val p=plan(byLabel(field))
            assertEquals("n0",action(p,page).getString("target"))
            p.dispatched("save");p.result(JSONObject().put("id","save"),"ok",JSONObject())
            assertTrue(p.next(page,"fresh-after-save",1002) is GuardedActionPlan.Decision.Done)
        }
        assertEquals(original,page.toString())
    }

    @Test fun inheritedLabelsOnIndependentWrappersRemainAmbiguous() {
        val page=sizedScreen(saveWrapper(),saveLabel(),saveWrapper("n1"),saveLabel("n1_0"))
        assertTrue(plan(byLabel()).next(page,"e",1001) is GuardedActionPlan.Decision.Replan)
    }

    @Test fun passwordEditableAndAmbiguousDescendantsCannotBecomeWrapperTargets() {
        for(flag in listOf("password","editable","scrollable")) {
            val page=sizedScreen(saveWrapper(),saveLabel().put(flag,true))
            val p=plan(byLabel())
            repeat(2){assertTrue(p.next(page,"e",1001) is GuardedActionPlan.Decision.Observe)}
            assertTrue(p.next(page,"e",1001) is GuardedActionPlan.Decision.Replan)
        }
        val page=sizedScreen(saveWrapper(),saveLabel(),saveLabel("n0_1").put("description","close"))
        assertTrue(plan(byLabel()).next(page,"e",1001) is GuardedActionPlan.Decision.Observe)
    }

    @Test fun interactiveChildIsTheOnlyTargetAndWrapperDoesNotBorrowItsLabel() {
        val page=sizedScreen(saveWrapper(),saveLabel().put("clickable",true))
        assertEquals("n0_0",action(plan(byLabel()),page).getString("target"))
        assertTrue(ModelScreenSummary.render(page).text.lineSequence().first{it.startsWith("n0:")}.contains("无文字控件"))
    }

    @Test fun unrelatedOverlappingLabelsAndNamedWrappersDoNotGetFallbackAliases() {
        for(page in listOf(sizedScreen(saveWrapper(),saveLabel("n1")),
            sizedScreen(saveWrapper().put("text","close"),saveLabel()))) {
            assertTrue(plan(byLabel()).next(page,"e",1001) is GuardedActionPlan.Decision.Observe)
        }
    }

    private fun savePredicate()=JSONObject().put("selector",JSONObject().put("resource_id","toolbar_save").put("description","save"))

    @Test fun inheritedLabelAndResourceIdentityWorkForWhenAndExpectAsForActions() {
        val page=sizedScreen(saveWrapper().put("resource_id","toolbar_save").put("selected",false),saveLabel())
        val step=byLabel().put("when",savePredicate().put("exists",true)).put("expect",savePredicate().put("selected_equals",false))
        val p=plan(step)
        assertEquals("n0",action(p,page).getString("target"))
        p.dispatched("save");p.result(JSONObject().put("id","save"),"ok",JSONObject())
        assertTrue(p.next(page,"after-save",1002) is GuardedActionPlan.Decision.Done)
    }

    @Test fun inheritedDisplayLabelCannotFabricateRawTextPostcondition() {
        val page=sizedScreen(saveWrapper().put("resource_id","toolbar_save"),saveLabel())
        val p=plan(byLabel().put("expect",savePredicate().put("text_equals","save")))
        action(p,page);p.dispatched("save");p.result(JSONObject().put("id","save"),"ok",JSONObject())
        repeat(2){assertTrue(p.next(page,"after-save",1002) is GuardedActionPlan.Decision.Observe)}
        assertTrue(p.next(page,"after-save",1003) is GuardedActionPlan.Decision.Replan)
    }

    @Test fun inheritedPredicateStateOnMultipleWrappersRemainsAmbiguous() {
        val page=sizedScreen(saveWrapper().put("resource_id","toolbar_save").put("selected",true),saveLabel(),
            saveWrapper("n1").put("resource_id","toolbar_save").put("selected",true),saveLabel("n1_0"),node("n2","other"))
        val p=plan(step("other").put("when",savePredicate().put("selected_equals",true)))
        repeat(2){assertTrue(p.next(page,"e",1001) is GuardedActionPlan.Decision.Observe)}
        assertTrue(p.next(page,"e",1001) is GuardedActionPlan.Decision.Replan)
    }

}

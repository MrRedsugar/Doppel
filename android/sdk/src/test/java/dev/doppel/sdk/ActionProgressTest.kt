package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ActionProgressTest {
    private fun screen(value: String = "0", id: String = "screen-a", nodeId: String = "n1") = JSONObject()
        .put("screen_id", id).put("package_name", "dev.fixture").put("width", 1440).put("height", 3200)
        .put("nodes", JSONArray().put(JSONObject().put("id", nodeId).put("text", "1").put("role", "button")
            .put("bounds", JSONArray(listOf(100, 1000, 300, 1200))).put("enabled", true).put("clickable", true))
            .put(JSONObject().put("id", "n2").put("text", value).put("role", "android.widget.TextView")
                .put("bounds", JSONArray(listOf(900, 300, 1400, 700))).put("enabled", true)))
    private fun tap(target: String = "n1") = JSONObject().put("id", "command").put("kind", "tap").put("target", target)
    private fun frame(screen: JSONObject, sha: String) = VisualFrame("capture-$sha", screen.getString("screen_id"), "dev.fixture",
        1440, 3200, 648, 1440, 0, 1, 1000, sha)
    private fun canvas() = screen().put("nodes", JSONArray().put(JSONObject().put("id", "n1")
        .put("role", "android.webkit.WebView").put("bounds", JSONArray(listOf(0, 0, 1440, 3200))).put("enabled", true)))
    private fun visual(x: Double = .5, y: Double = .6, label: String = "button") = JSONObject().put("id", "visual-command")
        .put("kind", "visual_gesture").put("gesture", JSONObject().put("kind", "tap").put("x", x).put("y", y)
            .put("duration_ms", 100).put("label", label).put("screen_context", "untrusted").put("safety", "safe"))
    private fun record(run: JSONObject, action: JSONObject, before: JSONObject, after: JSONObject = before) =
        ActionProgress.record(run, action, before, after, 1000)
    private fun receipts(run: JSONObject) = run.getJSONObject("action_progress").getJSONArray("receipts")

    @Test fun twoUnchangedNativeAttemptsBlockThirdDespiteNewScreenGenerationAndNodeIds() {
        val run = JSONObject()
        val first = ActionProgress.page(screen(), null)
        val second = ActionProgress.page(screen(id = "generation-b", nodeId = "n3"), null)
        record(run, tap(), first, second)
        assertNull(ActionProgress.repetition(run, tap("n3"), second))
        record(run, tap("n3"), second)
        val rejection = ActionProgress.repetition(run, tap("n3"), second)
        assertNotNull(rejection)
        assertFalse(rejection!!.getBoolean("action_executed"))
        assertFalse(receipts(run).getJSONObject(0).getBoolean("proves_business_success"))
    }

    @Test fun growingCalculatorExpressionIsProgressEvenWhenTheSameDigitIsPressed() {
        val run = JSONObject()
        val empty = ActionProgress.page(screen("0"), null)
        val one = ActionProgress.page(screen("1"), null)
        val eleven = ActionProgress.page(screen("11"), null)
        record(run, tap(), empty, one)
        record(run, tap(), one, eleven)
        assertEquals("changed", receipts(run).getJSONObject(1).getString("effect"))
        assertNull(ActionProgress.repetition(run, tap(), eleven))
    }

    @Test fun changingTargetsOrActionTypeIsAnAvailableRecovery() {
        val run = JSONObject()
        val page = ActionProgress.page(screen(), null)
        repeat(2) { record(run, tap(), page) }
        assertNull(ActionProgress.repetition(run, tap("n2"), page))
        assertNull(ActionProgress.repetition(run, tap().put("kind", "long_press"), page))
        assertNull(ActionProgress.repetition(run, tap(), ActionProgress.page(screen("123"), null)))
    }

    @Test fun opaqueCanvasWithoutImagesNeverClaimsUnchanged() {
        val run = JSONObject(); val page = ActionProgress.page(canvas(), null)
        repeat(2) { record(run, visual(), page) }
        assertEquals("unknown", receipts(run).getJSONObject(0).getString("effect"))
        assertNull(ActionProgress.repetition(run, visual(), page))
    }

    @Test fun unchangedCanvasPixelsAndCoordinatesBlockEvenIfModelRelabelsTheTarget() {
        val run = JSONObject(); val screen = canvas()
        val page = ActionProgress.page(screen, frame(screen, "same-pixels"))
        record(run, visual(label = "A1"), page)
        record(run, visual(label = "cell A1"), page)
        assertNotNull(ActionProgress.repetition(run, visual(label = "edit"), page))
        assertNull(ActionProgress.repetition(run, visual(x = .75), page))
        val geometry = receipts(run).getJSONObject(0).getJSONObject("target")
        assertEquals(.5, geometry.getDouble("x"), 0.0)
        assertEquals(720.0, geometry.getDouble("device_x"), 0.0)
        assertEquals(1920.0, geometry.getDouble("device_y"), 0.0)
    }

    @Test fun changedCanvasPixelsCannotBeDeclaredBusinessSuccessOrUnchanged() {
        val run = JSONObject(); val screen = canvas()
        val before = ActionProgress.page(screen, frame(screen, "before"))
        val after = ActionProgress.page(screen, frame(screen, "after"))
        repeat(2) { record(run, visual(), before, after) }
        val receipt = receipts(run).getJSONObject(0)
        assertNotEquals("unchanged", receipt.getString("effect"))
        assertFalse(receipt.getBoolean("proves_business_success"))
        assertEquals("repeated_unverified_target", ActionProgress.repetition(run, visual(), after)!!.getString("code"))
        assertEquals("unknown", receipt.getString("effect"))
    }

    @Test fun unchangedNavigationTextCannotProveUnchangedVisualContent() {
        val run = JSONObject()
        // A drawing surface may be an ordinary View beside well-labelled native navigation.
        val source = screen().apply { getJSONArray("nodes").put(JSONObject().put("id", "n4")
            .put("role", "android.view.View").put("bounds", JSONArray(listOf(0, 1300, 1440, 3000)))) }
        val before = ActionProgress.page(source, frame(source, "canvas-before"))
        val after = ActionProgress.page(source, frame(source, "canvas-after"))
        repeat(2) { record(run, visual(), before, after) }
        assertNotEquals("unchanged", receipts(run).getJSONObject(0).getString("effect"))
        assertEquals("repeated_unverified_target", ActionProgress.repetition(run, visual(), after)!!.getString("code"))
    }

    @Test fun nativeTargetRetainsActualBoundsAndNormalizedCenter() {
        val run = JSONObject(); val page = ActionProgress.page(screen(), null)
        record(run, tap(), page)
        val target = receipts(run).getJSONObject(0).getJSONObject("target")
        assertEquals("[100,1000,300,1200]", target.getJSONArray("bounds").toString())
        assertEquals(200.0 / 1440, target.getDouble("x"), 1e-9)
        assertEquals(1100.0 / 3200, target.getDouble("y"), 1e-9)
    }

    @Test fun scrollAtBoundaryIsRecordedButNeverBlockedAsRepeatedClick() {
        val run = JSONObject(); val page = ActionProgress.page(screen(), null)
        val scroll = tap().put("kind", "scroll").put("direction", "down")
        repeat(3) { record(run, scroll, page) }
        assertEquals("unchanged", receipts(run).getJSONObject(0).getString("effect"))
        assertNull(ActionProgress.repetition(run, scroll, page))
    }

    @Test fun inputValuesAndPrivateArgumentsNeverEnterPageOrHistory() {
        val run = JSONObject(); val source = screen()
        source.getJSONArray("nodes").getJSONObject(0).put("editable", true).put("password", true)
            .put("text", "secret-input").put("description", "secret-description").put("state_description", "secret-state")
        val page = ActionProgress.page(source, null)
        val action = tap().put("kind", "type").put("text", "secret-command").put("visual_permit", "secret-permit")
        repeat(2) { record(run, action, page) }
        assertFalse(page.toString().contains("secret-"))
        assertFalse(run.toString().contains("secret-"))
        assertNull(ActionProgress.repetition(run, action, page))
        assertNotEquals("unchanged", receipts(run).getJSONObject(0).getString("effect"))
    }

    @Test fun aDifferentApplicationOrIncompleteObservationNeverTriggersOldRepetition() {
        val run = JSONObject(); val page = ActionProgress.page(screen(), null)
        repeat(2) { record(run, tap(), page) }
        assertNull(ActionProgress.repetition(run, tap(), ActionProgress.page(screen().put("package_name", "other.app"), null)))
        assertNull(ActionProgress.repetition(run, tap(), ActionProgress.page(null, null)))
        val partial = ActionProgress.page(screen().put("tree_complete", false), null)
        val partialRun = JSONObject(); repeat(2) { record(partialRun, tap(), partial) }
        assertNull(ActionProgress.repetition(partialRun, tap(), partial))
    }

    @Test fun historyIsBoundedAndRestoredDataCannotEmitActions() {
        val run = JSONObject(); val page = ActionProgress.page(screen(), null)
        repeat(15) { record(run, tap().put("id", "command-$it"), page) }
        assertTrue(receipts(run).length() in 8..12)
        assertFalse(run.toString().contains("command-0\""))
        val restored = JSONObject(run.toString())
        assertNotNull(ActionProgress.repetition(restored, tap(), page))
        assertFalse(restored.has("pending_command"))
        assertFalse(restored.has("pending_request"))
    }

    @Test fun incompleteOrMismatchedFrameCannotSupplyPixelProof() {
        val source = canvas()
        val mismatched = frame(screen(id = "wrong-screen"), "fake-proof")
        val page = ActionProgress.page(source, mismatched)
        val run = JSONObject(); repeat(2) { record(run, visual(), page) }
        assertEquals("unknown", receipts(run).getJSONObject(0).getString("effect"))
        assertNull(ActionProgress.repetition(run, visual(), page))
    }

    @Test fun deferredResultImageCompletesOnlyTheOriginalAcceptedReceipt() {
        val run = JSONObject(); val observed = canvas()
        val before = ActionProgress.page(observed, frame(observed, "same-pixels"))
        val missingImage = ActionProgress.page(observed, null)
        record(run, visual().put("id", "accepted-1"), before, missingImage)
        assertEquals("unknown", receipts(run).getJSONObject(0).getString("effect"))
        ActionProgress.completeResultFrame(run, "accepted-1", ActionProgress.page(observed, frame(observed, "same-pixels")))
        val receipt = receipts(run).getJSONObject(0)
        assertEquals("unchanged", receipt.getString("effect"))
        assertEquals("same-pixels", receipt.getJSONObject("after").getString("image_sha256"))
        assertFalse(receipt.getBoolean("proves_business_success"))
        assertEquals(1000L, receipt.getLong("accepted_at"))
    }

    @Test fun missingResultObservationCanBeCompletedWithMatchingHostIdentity() {
        val run = JSONObject(); val observed = canvas()
        val before = ActionProgress.page(observed, frame(observed, "same-pixels"))
        record(run, visual(), before, ActionProgress.page(null, null))
        ActionProgress.completeResultFrame(run, "visual-command", ActionProgress.page(observed, frame(observed, "same-pixels")))
        assertEquals("unchanged", receipts(run).getJSONObject(0).getString("effect"))
    }

    @Test fun deferredResultRejectsDifferentCommandOlderReceiptAndRepeatedCompletion() {
        val run = JSONObject(); val observed = canvas()
        val before = ActionProgress.page(observed, frame(observed, "same-pixels"))
        val missingImage = ActionProgress.page(observed, null)
        record(run, visual().put("id", "older"), before, missingImage)
        record(run, visual().put("id", "latest"), before, missingImage)
        val unchanged = run.toString()
        for (id in listOf(null, "", "wrong", "older")) {
            ActionProgress.completeResultFrame(run, id, before)
            assertEquals(unchanged, run.toString())
        }
        ActionProgress.completeResultFrame(run, "latest", before)
        val completed = run.toString()
        ActionProgress.completeResultFrame(run, "latest", ActionProgress.page(observed, frame(observed, "later-image")))
        assertEquals(completed, run.toString())
        assertFalse(receipts(run).getJSONObject(0).getJSONObject("after").has("image_sha256"))
    }

    @Test fun deferredResultRejectsIdentityOrPreviouslyObservedContentMismatch() {
        val observed = canvas()
        val before = ActionProgress.page(observed, frame(observed, "same-pixels"))
        val candidates = listOf(JSONObject(before.toString()).put("package_name", "other.app"),
            JSONObject(before.toString()).put("width", 720), JSONObject(before.toString()).put("height", 1600),
            JSONObject(before.toString()).put("content_fingerprint", "other-content"),
            ActionProgress.page(observed, null), ActionProgress.page(null, null))
        for (candidate in candidates) {
            val run = JSONObject(); record(run, visual(), before, ActionProgress.page(observed, null))
            val unchanged = run.toString()
            ActionProgress.completeResultFrame(run, "visual-command", candidate)
            assertEquals(candidate.toString(), unchanged, run.toString())
        }
    }

    @Test fun deferredImageStillDoesNotProveInputSuccessOrCopyArbitraryFields() {
        val run = JSONObject(); val observed = screen()
        val before = ActionProgress.page(observed, frame(observed, "same-pixels"))
        record(run, tap().put("kind", "type"), before, ActionProgress.page(observed, null))
        val after = JSONObject(before.toString()).put("text", "secret-input").put("visual_permit", "secret-permit")
        ActionProgress.completeResultFrame(run, "command", after)
        assertEquals("unknown", receipts(run).getJSONObject(0).getString("effect"))
        assertFalse(run.toString().contains("secret-"))
    }

    private fun clickableWebViewAsObserved() = screen().apply {
        // The accessibility service normalizes clickable class names, including WebView, to button.
        getJSONArray("nodes").put(JSONObject().put("id", "n4").put("role", "button")
            .put("description", "文档").put("clickable", true).put("enabled", true)
            .put("bounds", JSONArray(listOf(0, 1300, 1440, 3000))))
    }

    @Test fun nativeTapWithVisualSourceCannotInferUnchangedFromNamedWebViewSemantics() {
        val run = JSONObject(); val observed = clickableWebViewAsObserved()
        val first = ActionProgress.page(observed, frame(observed, "page-one"))
        val second = ActionProgress.page(observed, frame(observed, "page-two"))
        val third = ActionProgress.page(observed, frame(observed, "page-three"))
        // Equal labelled trees alone cannot tell whether the document rendered another page.
        record(run, tap(), first, second)
        record(run, tap(), second, third)
        assertEquals("unknown", receipts(run).getJSONObject(0).getString("effect"))
        assertEquals("unknown", receipts(run).getJSONObject(1).getString("effect"))
        assertNull(ActionProgress.repetition(run, tap(), third))
    }

    @Test fun nativeTapWithVisualSourceRequiresItsMissingResultPixels() {
        val run = JSONObject(); val observed = clickableWebViewAsObserved()
        val before = ActionProgress.page(observed, frame(observed, "source"))
        record(run, tap(), before, ActionProgress.page(observed, null))
        val receipt = receipts(run).getJSONObject(0)
        assertEquals("unknown", receipt.getString("effect"))
        assertTrue(receipt.getJSONObject("before").getBoolean("requires_pixels"))
        assertTrue(receipt.getJSONObject("after").getBoolean("requires_pixels"))
        assertFalse(receipt.getBoolean("proves_business_success"))
    }

    @Test fun deferredChangedPixelsDoNotDowngradeNativeVisualEvidenceToSemanticEquality() {
        val run = JSONObject(); val observed = clickableWebViewAsObserved()
        val before = ActionProgress.page(observed, frame(observed, "source"))
        record(run, tap(), before, ActionProgress.page(observed, null))
        ActionProgress.completeResultFrame(run, "command", ActionProgress.page(observed, frame(observed, "changed-result")))
        val receipt = receipts(run).getJSONObject(0)
        assertEquals("unknown", receipt.getString("effect"))
        assertTrue(receipt.getJSONObject("after").getBoolean("requires_pixels"))
        assertFalse(receipt.getBoolean("proves_business_success"))
    }

    @Test fun deferredIdenticalPixelsBlockOnlyWhileTheCurrentPixelsStillMatch() {
        val run = JSONObject(); val observed = clickableWebViewAsObserved()
        val source = ActionProgress.page(observed, frame(observed, "source"))
        repeat(2) { index ->
            val id = "native-$index"
            record(run, tap().put("id", id), source, ActionProgress.page(observed, null))
            ActionProgress.completeResultFrame(run, id, source)
        }
        assertEquals("unchanged", receipts(run).getJSONObject(1).getString("effect"))
        assertNotNull(ActionProgress.repetition(run, tap(), source))
        assertNull(ActionProgress.repetition(run, tap(), ActionProgress.page(observed, frame(observed, "new-current"))))
        assertNull(ActionProgress.repetition(run, tap(), ActionProgress.page(observed, null)))
    }

    @Test fun reliableSemanticChangeStillRecordsPageChangeWithVisualEvidence() {
        val run = JSONObject(); val source = screen("1"); val result = screen("11")
        record(run, tap(), ActionProgress.page(source, frame(source, "source")), ActionProgress.page(result, frame(result, "result")))
        val receipt = receipts(run).getJSONObject(0)
        assertEquals("changed", receipt.getString("effect"))
        assertFalse(receipt.getBoolean("proves_business_success"))
    }
    @Test fun repeatedChangedPagesStillFormANavigationLoop() {
        val run=JSONObject();val a=ActionProgress.page(screen("设置入口"),null);val b=ActionProgress.page(screen("个人页"),null)
        val back=JSONObject().put("id","back").put("kind","back")
        repeat(2){record(run,tap(),a,b);record(run,back,b,a)}
        assertEquals("repeated_navigation_cycle",ActionProgress.repetition(run,tap(),a)!!.getString("code"))
        assertNull(ActionProgress.repetition(run,tap(),ActionProgress.page(screen("新页面"),null)))
    }
    @Test fun oneRoundTripDoesNotBlockIntentionalNavigation() {
        val run=JSONObject();val a=ActionProgress.page(screen("A"),null);val b=ActionProgress.page(screen("B"),null)
        record(run,tap(),a,b);record(run,JSONObject().put("id","back").put("kind","back"),b,a)
        assertNull(ActionProgress.repetition(run,tap(),a))
    }

}

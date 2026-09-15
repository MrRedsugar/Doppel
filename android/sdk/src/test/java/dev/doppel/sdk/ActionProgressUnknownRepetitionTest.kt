package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic canvas observations: changing pixels provide no proof of either progress or failure. */
class ActionProgressUnknownRepetitionTest {
    private fun page(
        tag: String,
        width: Int = 3200,
        height: Int = 1440,
        packageName: String = "dev.fixture",
        rotation: Int = 0,
        semanticText: String? = null,
    ): JSONObject {
        val nodes = JSONArray().put(JSONObject().put("id", "n1")
            .put("role", if (semanticText == null) "android.webkit.WebView" else "button")
            .put("text", semanticText.orEmpty()).put("enabled", true).put("clickable", true)
            .put("bounds", JSONArray(listOf(10, 10, 100, 100))))
        val observation = JSONObject().put("screen_id", "screen-$tag").put("package_name", packageName)
            .put("width", width).put("height", height).put("nodes", nodes)
        val imageScale = minOf(1.0, 1440.0 / maxOf(width, height))
        val frame = VisualFrame("capture-$tag", "screen-$tag", packageName, width, height,
            (width * imageScale).toInt(), (height * imageScale).toInt(), rotation, 1, 1000, "pixels-$tag")
        return ActionProgress.page(observation, frame)
    }

    private fun visual(
        x: Double = 261.67,
        y: Double = 1331.67,
        width: Int = 3200,
        height: Int = 1440,
        kind: String = "tap",
        label: String = "navigation",
    ) = JSONObject().put("kind", "visual_gesture").put("gesture", JSONObject()
        .put("kind", kind).put("x", x / width).put("y", y / height)
        .put("duration_ms", if (kind == "tap") 100 else 600)
        .put("label", label).put("screen_context", "untrusted description").put("safety", "safe")
        .apply { if (kind == "swipe") put("end_x", .7).put("end_y", .7) })

    private fun native(kind: String = "tap") = JSONObject().put("kind", kind).put("target", "n1")
    private fun receipts(run: JSONObject) = run.getJSONObject("action_progress").getJSONArray("receipts")

    private fun record(run: JSONObject, action: JSONObject, before: JSONObject, after: JSONObject) {
        val index = run.optJSONObject("action_progress")?.optJSONArray("receipts")?.length() ?: 0
        ActionProgress.record(run, JSONObject(action.toString()).put("id", "accepted-$index"), before, after, 1000L + index)
    }

    private fun twoUnknown(
        firstAction: JSONObject = visual(),
        secondAction: JSONObject = firstAction,
        pages: List<JSONObject> = listOf(page("a"), page("b"), page("c")),
    ) = JSONObject().also { run ->
        record(run, firstAction, pages[0], pages[1])
        record(run, secondAction, pages[1], pages[2])
        assertUnknownReceipts(run)
    }

    private fun assertUnknownReceipts(run: JSONObject) {
        val receipts = receipts(run)
        for (index in 0 until receipts.length()) {
            assertEquals("receipt $index", "unknown", receipts.getJSONObject(index).getString("effect"))
            assertFalse(receipts.getJSONObject(index).getBoolean("proves_business_success"))
        }
    }

    private fun assertBlocked(run: JSONObject, action: JSONObject = visual(), current: JSONObject = page("current")) {
        val prior = run.toString()
        val rejection = ActionProgress.repetition(run, action, current)
        assertNotNull("Two accepted unknown attempts at this visual target require observation or replanning", rejection)
        assertEquals("repeated_unverified_target", rejection!!.getString("code"))
        assertFalse(rejection.getBoolean("action_executed"))
        assertEquals("Checking a proposal must not alter accepted history", prior, run.toString())
        assertUnknownReceipts(run)
    }

    /** Include all four stored observations and the independent current observation in boundary checks. */
    private fun evidence(run: JSONObject, current: JSONObject): List<JSONObject> = listOf(
        receipts(run).getJSONObject(0).getJSONObject("before"),
        receipts(run).getJSONObject(0).getJSONObject("after"),
        receipts(run).getJSONObject(1).getJSONObject("before"),
        receipts(run).getJSONObject(1).getJSONObject("after"),
        current,
    )

    @Test fun twoUnknownVisualTapsBlockThirdWithoutClaimingUnchangedOrBusinessSuccess() {
        assertBlocked(twoUnknown())
    }

    @Test fun changingCaptureHashScreenGenerationLabelAndSmallDevicePixelJitterDoesNotBypassGate() {
        val run = twoUnknown(visual(label = "first label"), visual(y = 1333.34, label = "another label"))
        assertNotEquals(receipts(run).getJSONObject(0).getJSONObject("target").getString("key"),
            receipts(run).getJSONObject(1).getJSONObject("target").getString("key"))
        assertBlocked(run, visual(y = 1332.50, label = "third label"), page("fresh-current"))
    }

    @Test fun twoUnknownVisualLongPressesAlsoBlockTheThird() {
        val action = visual(kind = "long_press")
        assertBlocked(twoUnknown(action), action)
    }

    @Test fun oneUnknownAttemptStillAllowsAnotherAttempt() {
        val run = JSONObject()
        record(run, visual(), page("a"), page("b"))
        assertUnknownReceipts(run)
        assertNull(ActionProgress.repetition(run, visual(), page("c")))
    }

    @Test fun noAcceptedAttemptCannotBlockAProposal() {
        assertNull(ActionProgress.repetition(JSONObject(), visual(), page("current")))
    }

    @Test fun neighborhoodThresholdHasTwoDevicePixelFloor() {
        val pages = listOf(page("a", 400, 800), page("b", 400, 800), page("c", 400, 800))
        val action = visual(200.0, 400.0, 400, 800)
        val run = twoUnknown(action, pages = pages)
        assertBlocked(run, visual(201.99, 400.0, 400, 800), pages.last())
        assertNull(ActionProgress.repetition(run, visual(202.01, 400.0, 400, 800), pages.last()))
    }

    @Test fun neighborhoodThresholdScalesWithShortDisplayDimension() {
        val run = twoUnknown()
        // min(3200,1440) * 0.003 = 4.32 device pixels.
        assertBlocked(run, visual(x = 261.67 + 4.31))
        assertNull(ActionProgress.repetition(run, visual(x = 261.67 + 4.33), page("current")))
    }

    @Test fun neighborhoodThresholdIsCappedAtEightDevicePixels() {
        val pages = listOf(page("a", 4000, 8000), page("b", 4000, 8000), page("c", 4000, 8000))
        val action = visual(2000.0, 4000.0, 4000, 8000)
        val run = twoUnknown(action, pages = pages)
        assertBlocked(run, visual(2007.99, 4000.0, 4000, 8000), pages.last())
        assertNull(ActionProgress.repetition(run, visual(2008.01, 4000.0, 4000, 8000), pages.last()))
    }

    @Test fun neighborhoodUsesDistanceRatherThanIndependentAxisTolerances() {
        val run = twoUnknown()
        assertNull(ActionProgress.repetition(run, visual(x = 261.67 + 3.1, y = 1331.67 + 3.1), page("current")))
    }

    @Test fun aClearlyDifferentTargetRemainsAvailable() {
        assertNull(ActionProgress.repetition(twoUnknown(), visual(x = 1800.0, y = 500.0), page("current")))
    }

    @Test fun changingFromTapToLongPressRemainsAvailable() {
        assertNull(ActionProgress.repetition(twoUnknown(), visual(kind = "long_press"), page("current")))
    }

    @Test fun theTwoAcceptedAttemptsMustHaveTheSameVisualKind() {
        val run = twoUnknown(visual(kind = "long_press"), visual())
        assertNull(ActionProgress.repetition(run, visual(), page("current")))
    }

    @Test fun theTwoAcceptedAttemptsMustBothTargetTheProposedNeighborhood() {
        val run = twoUnknown(visual(x = 1600.0, y = 600.0), visual())
        assertNull(ActionProgress.repetition(run, visual(), page("current")))
    }

    @Test fun anInterveningAcceptedActionBreaksTheConsecutiveUnknownAttempts() {
        val run = twoUnknown()
        record(run, JSONObject().put("kind", "back"), page("d"), page("e"))
        assertNull(ActionProgress.repetition(run, visual(), page("f")))
    }

    @Test fun aDifferentApplicationInAnyObservationPreventsTheUnknownGate() {
        for (index in 0..4) {
            val run = twoUnknown(); val current = page("current")
            evidence(run, current)[index].put("package_name", "dev.other.fixture")
            assertNull("application mismatch at evidence $index", ActionProgress.repetition(run, visual(), current))
        }
    }

    @Test fun differentDisplayGeometryInAnyObservationPreventsTheUnknownGate() {
        for (field in listOf("width", "height")) for (index in 0..4) {
            val run = twoUnknown(); val current = page("current")
            val observation = evidence(run, current)[index]
            observation.put(field, observation.getInt(field) + 1)
            assertNull("$field mismatch at evidence $index", ActionProgress.repetition(run, visual(), current))
        }
    }

    @Test fun differentRotationInAnyObservationPreventsTheUnknownGate() {
        for (index in 0..4) {
            val run = twoUnknown(); val current = page("current")
            evidence(run, current)[index].put("rotation", 1)
            assertNull("rotation mismatch at evidence $index", ActionProgress.repetition(run, visual(), current))
        }
    }

    @Test fun missingIdentityOrRotationInAnyObservationPreventsTheUnknownGate() {
        for (field in listOf("package_name", "width", "height", "rotation")) for (index in 0..4) {
            val run = twoUnknown(); val current = page("current")
            evidence(run, current)[index].remove(field)
            assertNull("missing $field at evidence $index", ActionProgress.repetition(run, visual(), current))
        }
    }

    @Test fun missingOrBlankPixelsInAnyObservationPreventTheUnknownGate() {
        for (blank in listOf(false, true)) for (index in 0..4) {
            val run = twoUnknown(); val current = page("current")
            val observation = evidence(run, current)[index]
            if (blank) observation.put("image_sha256", " ") else observation.remove("image_sha256")
            assertNull("missing pixel evidence $index, blank=$blank", ActionProgress.repetition(run, visual(), current))
        }
    }

    @Test fun missingOrBlankCaptureIdentityInAnyObservationPreventsTheUnknownGate() {
        for (blank in listOf(false, true)) for (index in 0..4) {
            val run = twoUnknown(); val current = page("current")
            val observation = evidence(run, current)[index]
            if (blank) observation.put("capture_id", " ") else observation.remove("capture_id")
            assertNull("missing capture evidence $index, blank=$blank", ActionProgress.repetition(run, visual(), current))
        }
    }

    @Test fun reliableSemanticChangeInCurrentObservationPermitsAReassessment() {
        val run = twoUnknown(pages = listOf(page("a", semanticText = "A"), page("b", semanticText = "A"),
            page("c", semanticText = "A")))
        val current = page("current", semanticText = "B")
        assertTrue(current.getBoolean("semantic_reliable"))
        assertNull(ActionProgress.repetition(run, visual(), current))
    }

    @Test fun reliableSemanticChangeBetweenOtherwiseUnknownAttemptsBreaksTheSequence() {
        val run = JSONObject()
        record(run, visual(), page("a", semanticText = "A"), page("b", semanticText = "A"))
        record(run, visual(), page("c", semanticText = "B"), page("d", semanticText = "B"))
        assertUnknownReceipts(run)
        assertNull(ActionProgress.repetition(run, visual(), page("current", semanticText = "B")))
    }

    @Test fun unreliableTreeFingerprintChangesDoNotProveProgressOrResetTheUnknownGate() {
        val pages = listOf(page("a"), page("b"), page("c")).mapIndexed { index, page ->
            page.put("content_fingerprint", "opaque-tree-$index")
        }
        assertTrue(pages.all { !it.getBoolean("semantic_reliable") })
        assertBlocked(twoUnknown(pages = pages), current = page("current").put("content_fingerprint", "opaque-current"))
    }

    @Test fun aChangedReceiptDoesNotCountAsAnUnknownAttempt() {
        val run = JSONObject()
        record(run, visual(), page("a", semanticText = "A"), page("b", semanticText = "B"))
        record(run, visual(), page("c", semanticText = "B"), page("d", semanticText = "B"))
        assertEquals("changed", receipts(run).getJSONObject(0).getString("effect"))
        assertNull(ActionProgress.repetition(run, visual(), page("current", semanticText = "B")))
    }

    @Test fun anUnchangedReceiptDoesNotCountAsAnUnknownAttempt() {
        val run = JSONObject(); val first = page("a")
        record(run, visual(), first, first)
        record(run, visual(), first, page("b"))
        assertEquals("unchanged", receipts(run).getJSONObject(0).getString("effect"))
        assertNull(ActionProgress.repetition(run, visual(), page("current")))
    }

    @Test fun nativeUnknownAttemptsDoNotAcquireTheVisualUnknownGate() {
        val pages = listOf(page("a", semanticText = "same"), page("b", semanticText = "same"),
            page("c", semanticText = "same"))
        for (kind in listOf("tap", "long_press")) {
            val run = twoUnknown(native(kind), pages = pages)
            assertNull(ActionProgress.repetition(run, native(kind), page("current", semanticText = "same")))
            assertNull(ActionProgress.repetition(run, visual(55.0, 55.0, kind = kind), page("current", semanticText = "same")))
        }
    }

    @Test fun nativeProposalsDoNotMatchEarlierUnknownVisualTargets() {
        val pages = listOf(page("a", semanticText = "same"), page("b", semanticText = "same"),
            page("c", semanticText = "same"))
        val run = twoUnknown(visual(55.0, 55.0), pages = pages)
        assertNull(ActionProgress.repetition(run, native(), page("current", semanticText = "same")))
    }

    @Test fun inputScrollAndSwipeKeepTheirPreviousNonBlockingBehavior() {
        val actions = listOf(native("type").put("text", "synthetic-input"), native("ime_action"),
            native("scroll").put("direction", "down"), visual(kind = "swipe"))
        for (action in actions) {
            val run = twoUnknown(action)
            assertNull(action.toString(), ActionProgress.repetition(run, action, page("current")))
        }
    }

    @Test fun theExistingNativeUnchangedGateIsPreserved() {
        val run = JSONObject(); val current = page("same", semanticText = "same")
        repeat(2) { record(run, native(), current, current) }
        val rejection = ActionProgress.repetition(run, native(), current)
        assertNotNull(rejection)
        assertEquals("repeated_unchanged_target", rejection!!.getString("code"))
        assertFalse(rejection.getBoolean("action_executed"))
    }
}

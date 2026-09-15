package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class VisualAnchorPlanTest {
    private fun image(dx: Int = 0, omitFirst: Boolean = false): VisualAnchorImage {
        val pixels = IntArray(240 * 160) { 0xff181c20.toInt() }
        for ((index, origin) in listOf(40, 144).withIndex()) {
            if (index == 0 && omitFirst) continue
            for (y in 60 until 92) for (x in origin until origin + 32) {
                val v = ((x - origin) * (83 + index * 12) + y * (47 - index * 9) + x * y * 7) and 255
                pixels[y * 240 + x + dx] = 0xff000000.toInt() or (v shl 16) or ((v xor 137) shl 8) or (v xor 51)
            }
        }
        return VisualAnchorImage(240, 160, pixels)
    }
    private fun frame(id: String = "source", time: Long = 1000, sha: String = "a".repeat(64), pkg: String = "game",
        width: Int = 240, rotation: Int = 1) = VisualAnchorFrame(id, sha, pkg, width, 160, rotation, time)
    private fun spec(id: String, x: Int) = VisualAnchorSpec(id, VisualAnchorRect(x, 60, x + 32, 92), x + 16, 76)
    private val specs = listOf(spec("card", 40), spec("tile", 144))
    private fun step(kind: String = "tap", duration: Long = 80, wait: Long = 0) =
        VisualAnchorStep(kind, "card", if (kind == "swipe") "tile" else null, durationMs = duration, waitTimeoutMs = wait)
    private fun prepare(steps: List<VisualAnchorStep> = listOf(step()), ttl: Long = 10000) =
        VisualAnchorPlan.prepare(frame(), image(), specs, steps, 1000, ttlMs = ttl)
    private fun plan(steps: List<VisualAnchorStep> = listOf(step())) = (prepare(steps) as VisualAnchorPlanPreparation.Prepared).plan

    @Test fun firstFrameIdentityMustMatchAndLaterAnimatedFrameNeedNotShareWholeImageHash() {
        val invalid = plan().evaluate(frame(sha = "b".repeat(64)), image(), 1001)
        assertEquals("source_frame_mismatch", (invalid as VisualAnchorDecision.Stopped).reason)
        val plan = plan(listOf(step(), step("long_press", 500)))
        val first = plan.evaluate(frame(), image(), 1001) as VisualAnchorDecision.Ready
        assertTrue(plan.acknowledge(first.token, true, 1002))
        val second = plan.evaluate(frame("new", 1003, "b".repeat(64)), image(3), 1004) as VisualAnchorDecision.Ready
        assertEquals(59, second.start!!.x); assertEquals("long_press", second.kind)
    }
    @Test fun planningMayTakeTimeButFirstCandidateStillNeedsFreshReobservedPixels() {
        val preparation = VisualAnchorPlan.prepare(frame(), image(), specs, listOf(step()), 8000, ttlMs = 15000)
        val plan = (preparation as VisualAnchorPlanPreparation.Prepared).plan
        val ready = plan.evaluate(frame("fresh", 8001, "b".repeat(64)), image(4), 8002) as VisualAnchorDecision.Ready
        assertEquals(60, ready.start!!.x); assertEquals("fresh", ready.captureId)
    }
    @Test fun twentySecondPlanningStillHasExecutionBudgetButSourceExpiresAtFortyFiveSeconds() {
        val prepared = VisualAnchorPlan.prepare(frame(), image(), specs, listOf(step()), 21000, ttlMs = 15000)
        val motor = (prepared as VisualAnchorPlanPreparation.Prepared).plan
        assertTrue(motor.evaluate(frame("fresh", 35000), image(), 35001) is VisualAnchorDecision.Ready)
        val nearExpiry = (VisualAnchorPlan.prepare(frame(), image(), specs, listOf(step()), 44000, ttlMs = 15000) as VisualAnchorPlanPreparation.Prepared).plan
        assertEquals("plan_expired", (nearExpiry.evaluate(frame("fresh", 46001), image(), 46001) as VisualAnchorDecision.Stopped).reason)
    }
    @Test fun noSecondActionUntilReceiptAndStrictlyNewFrameAfterCompletion() {
        val plan = plan(listOf(step(), step()))
        val first = plan.evaluate(frame(), image(), 1001) as VisualAnchorDecision.Ready
        assertEquals("awaiting_action_result", (plan.evaluate(frame(), image(), 1002) as VisualAnchorDecision.Waiting).reason)
        assertTrue(plan.acknowledge(first.token, true, 1010))
        assertEquals("awaiting_fresh_frame", (plan.evaluate(frame("laterId", 1009), image(), 1011) as VisualAnchorDecision.Waiting).reason)
        assertTrue(plan.evaluate(frame("new", 1012), image(), 1012) is VisualAnchorDecision.Ready)
    }
    @Test fun expiredCancelledOrGeometryChangedPlansNeverProduceAnotherAction() {
        for (change in listOf("package", "width", "rotation", "ttl", "stale", "future")) {
            val plan = plan()
            val changed = when (change) {
                "package" -> frame(pkg = "overlay")
                "width" -> frame(width = 241)
                "rotation" -> frame(rotation = 0)
                "future" -> frame(time = 3000)
                else -> frame()
            }
            val now = when (change) { "ttl" -> 16001L; "stale" -> 2600L; else -> 1001L }
            assertTrue(change, plan.evaluate(changed, image(), now) is VisualAnchorDecision.Stopped)
        }
        val cancelled = plan(); cancelled.cancel()
        assertEquals("cancelled", (cancelled.evaluate(frame(), image(), 1001) as VisualAnchorDecision.Stopped).reason)
    }
    @Test fun rejectedOrUnknownSubmissionStopsAndTokensCannotBeReplayed() {
        val plan = plan(listOf(step(), step()))
        val action = plan.evaluate(frame(), image(), 1001) as VisualAnchorDecision.Ready
        assertFalse(plan.acknowledge("different", true, 1002))
        assertTrue(plan.evaluate(frame("new", 1003), image(), 1003) is VisualAnchorDecision.Stopped)
        val rejected = plan()
        val ready = rejected.evaluate(frame(), image(), 1001) as VisualAnchorDecision.Ready
        assertTrue(rejected.acknowledge(ready.token, false, 1002))
        assertEquals("action_not_confirmed", (rejected.evaluate(frame("new", 1003), image(), 1003) as VisualAnchorDecision.Stopped).reason)
        assertFalse(rejected.acknowledge(ready.token, true, 1004))
    }
    @Test fun allRequiredAnchorsAreCheckedBeforeReturningCurrentTarget() {
        val guarded = VisualAnchorStep("tap", "tile", requiredAnchorIds = listOf("card"))
        val plan = plan(listOf(step("long_press", 500), guarded))
        val first = plan.evaluate(frame(), image(), 1001) as VisualAnchorDecision.Ready
        plan.acknowledge(first.token, true, 1002)
        val result = plan.evaluate(frame("new", 1003), image(omitFirst = true), 1003)
        assertEquals("anchor_card_not_visible_or_changed", (result as VisualAnchorDecision.Stopped).reason)
    }
    @Test fun visibleConditionMayWaitWithinDeadlineButNeverExecuteUnseenTarget() {
        val plan = plan(listOf(step(), step(wait = 500)))
        val first = plan.evaluate(frame(), image(), 1001) as VisualAnchorDecision.Ready
        plan.acknowledge(first.token, true, 1002)
        assertEquals("anchor_card_not_visible_or_changed", (plan.evaluate(frame("f2", 1003), image(omitFirst = true), 1003) as VisualAnchorDecision.Waiting).reason)
        assertTrue(plan.evaluate(frame("f3", 1100), image(), 1100) is VisualAnchorDecision.Ready)
        val timeout = plan(listOf(step(), step(wait = 500)))
        val ready = timeout.evaluate(frame(), image(), 1001) as VisualAnchorDecision.Ready
        timeout.acknowledge(ready.token, true, 1002)
        timeout.evaluate(frame("f2", 1003), image(omitFirst = true), 1003)
        assertEquals("condition_timeout", (timeout.evaluate(frame("f3", 1504), image(omitFirst = true), 1504) as VisualAnchorDecision.Stopped).reason)
    }
    @Test fun twoDragSegmentsEachRequireNewlyLocatedStartAndEndAndValidDuration() {
        val plan = plan(listOf(step("swipe", 150), VisualAnchorStep("swipe", "tile", "card", 200)))
        val first = plan.evaluate(frame(), image(), 1001) as VisualAnchorDecision.Ready
        assertEquals(56, first.start!!.x); assertEquals(160, first.end!!.x)
        plan.acknowledge(first.token, true, 1200)
        val second = plan.evaluate(frame("new", 1201), image(5), 1201) as VisualAnchorDecision.Ready
        assertEquals(165, second.start!!.x); assertEquals(61, second.end!!.x)
        plan.acknowledge(second.token, true, 1402)
        assertTrue(plan.evaluate(frame("final", 1403), image(), 1403) is VisualAnchorDecision.Complete)
        assertEquals("invalid_step", (prepare(listOf(step("swipe", 149))) as VisualAnchorPlanPreparation.Rejected).reason)
    }
    @Test fun waitOnlyStepConsumesNoActionTokenAndInvalidSchemasCannotCreatePlan() {
        val waiting = VisualAnchorStep("wait_visible", requiredAnchorIds = listOf("card"), durationMs = 0, waitTimeoutMs = 100)
        assertTrue(plan(listOf(waiting)).evaluate(frame(), image(), 1001) is VisualAnchorDecision.Complete)
        assertTrue(prepare(emptyList()) is VisualAnchorPlanPreparation.Rejected)
        assertTrue(prepare(listOf(VisualAnchorStep("pay", "card"))) is VisualAnchorPlanPreparation.Rejected)
        assertTrue(prepare(listOf(VisualAnchorStep("tap", "absent"))) is VisualAnchorPlanPreparation.Rejected)
        assertTrue(prepare(ttl = 45001) is VisualAnchorPlanPreparation.Rejected)
    }
}

package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SwipeExtentPolicyTest {
    private fun intent(semantics: String = "reveal_content") = JSONObject()
        .put("action", "swipe").put("target", "关卡地图可拖动背景")
        .put("gesture_semantics", semantics)
    private fun action(vararg paths: String) = JSONObject().put("strokes", JSONArray().apply {
        paths.forEach { put(JSONObject().put("points", JSONArray(it)).put("duration_ms", 700)) }
    })

    @Test fun missingBrowsingExtentBecomesExplicitSmallTargetInstruction() {
        val from = intent()
        val out = JSONObject().put("target", from.getString("target"))
        SwipeExtentPolicy.apply("swipe", from, out)
        assertEquals("small", out.getString("swipe_extent"))
        assertTrue(out.getString("target").contains("小幅"))
        assertTrue(out.getString("target").contains("5%"))
        assertFalse(from.has("swipe_extent"))
    }

    @Test fun physicalGesturesKeepTheirOriginalTargetAndDistance() {
        for (semantics in listOf("physical_gesture", "object_drag")) {
            val from = intent(semantics)
            val out = JSONObject(from.toString())
            SwipeExtentPolicy.apply("swipe", from, out)
            assertEquals(from.toString(), out.toString())
            from.put("swipe_extent","small")
            assertTrue(SwipeExtentPolicy.validateCoordinates(from,action("[[100,500],[900,500]]")).consistent)
        }
    }

    @Test fun mediumDistanceIsRejectedForNewBrowsingEvenWhenTargetIsVisible() {
        for (goal in listOf("inspect", "boundary")) {
            val from = intent().put("swipe_extent", "medium").put("scroll_goal", goal).put("boundary_reason", "直达末端")
            assertThrows(IllegalArgumentException::class.java) { SwipeExtentPolicy.apply("swipe", from, JSONObject()) }
            assertThrows(IllegalArgumentException::class.java) { SwipeExtentPolicy.validateCoordinates(from, action("[[400,500],[600,500]]")) }
        }
    }

    @Test fun largeDistanceNeedsExplicitBoundaryGoalAndReason() {
        val from = intent().put("swipe_extent", "large")
        for (candidate in listOf(from, JSONObject(from.toString()).put("scroll_goal", "boundary"),
            JSONObject(from.toString()).put("scroll_goal", "inspect").put("boundary_reason", "找到目标"))) {
            try {
                SwipeExtentPolicy.apply("swipe", candidate, JSONObject())
                fail("large sweep without explicit boundary goal accepted")
            } catch (_: IllegalArgumentException) { }
            assertThrows(IllegalArgumentException::class.java) {
                SwipeExtentPolicy.validateCoordinates(candidate, action("[[200,500],[700,500]]"))
            }
        }
        val out = JSONObject()
        SwipeExtentPolicy.apply("swipe", from.put("scroll_goal", "boundary")
            .put("boundary_reason", "用户要直接去列表末端"), out)
        assertEquals("large", out.getString("swipe_extent"))
        assertEquals("boundary", out.getString("scroll_goal"))
        assertTrue(out.getString("boundary_reason").isNotBlank())
        assertTrue(SwipeExtentPolicy.validateCoordinates(out, action("[[200,500],[700,500]]")).consistent)
        assertFalse(SwipeExtentPolicy.validateCoordinates(out, action("[[100,500],[900,500]]")).consistent)
    }

    @Test fun smallExtentRejectsLargePathsWithoutRescalingCoordinates() {
        val path = action("[[200,500],[800,500]]")
        val original = path.toString()
        val result = SwipeExtentPolicy.validateCoordinates(intent().put("swipe_extent", "small"), path, 3200, 1440)
        assertFalse(result.consistent)
        assertEquals("swipe_extent_exceeded", result.reasonCode)
        assertEquals(original, path.toString())
    }

    @Test fun pathLengthCannotHideOvershootByReturningToItsStart() {
        val path = action("[[500,500],[900,500],[550,500]]")
        assertFalse(SwipeExtentPolicy.validateCoordinates(intent().put("swipe_extent", "small"), path).consistent)
    }

    @Test fun smallExtentAllowsFineCorrectionsAndRecordsActualPixelSpeed() {
        for ((width, height) in listOf(3200 to 1440, 1440 to 3200)) {
            val checked = SwipeExtentPolicy.validateCoordinates(intent().put("swipe_extent", "small"),
                action("[[500,500],[510,500]]"), width, height)
            assertTrue(checked.consistent)
            val evidence = checked.details.getJSONArray("strokes").getJSONObject(0)
            assertEquals(width * .01, evidence.getDouble("path_length_px"), .001)
            assertEquals(width * .01 / .7, evidence.getDouble("speed_px_per_second"), .001)
        }
    }

    @Test fun sequenceChecksEveryStrokeAndDefaultAppliesToMixedSequences() {
        val from = JSONObject().put("gesture_contracts", JSONArray()
            .put(JSONObject().put("gesture_semantics", "physical_gesture"))
            .put(JSONObject().put("gesture_semantics", "reveal_content")))
        val out = JSONObject()
        SwipeExtentPolicy.apply("swipe_sequence", from, out)
        assertEquals("small", out.getString("swipe_extent"))
        val result = SwipeExtentPolicy.validateCoordinates(out,
            action("[[500,500],[550,500]]", "[[500,800],[500,300]]"))
        assertFalse(result.consistent)
        assertEquals(1, result.details.getInt("stroke_index"))
    }

    @Test fun applyingPolicyTwiceDoesNotRepeatNaturalLanguageSuffix() {
        val out = intent()
        SwipeExtentPolicy.apply("swipe", out, out)
        val once = out.toString()
        SwipeExtentPolicy.apply("swipe", out, out)
        assertEquals(once, out.toString())
    }

    @Test fun mixedSequenceChecksOnlyTheBrowsingStrokes() {
        val from=JSONObject().put("swipe_extent","small").put("gesture_contracts",JSONArray()
            .put(JSONObject().put("gesture_semantics","physical_gesture"))
            .put(JSONObject().put("gesture_semantics","reveal_content")))
        assertTrue(SwipeExtentPolicy.validateCoordinates(from,
            action("[[100,500],[900,500]]","[[500,500],[550,500]]")).consistent)
    }

    @Test fun purelyPhysicalSequenceHasNoBrowsingBoundaryOrDistanceRestriction() {
        val from=JSONObject().put("swipe_extent","large").put("scroll_goal","inspect").put("boundary_reason", "")
            .put("gesture_contracts",JSONArray()
                .put(JSONObject().put("gesture_semantics","physical_gesture"))
                .put(JSONObject().put("gesture_semantics","physical_gesture")))
        val before=from.toString()
        val target=JSONObject(before)
        SwipeExtentPolicy.apply("swipe_sequence", from, target)
        assertEquals(before, target.toString())
        assertTrue(SwipeExtentPolicy.validateCoordinates(from,
            action("[[100,500],[900,500]]","[[500,900],[500,100]]")).consistent)
    }

    @Test fun objectDragAndFacingSequenceKeepsEndpointDistancesWhileMixedBrowsingStaysLimited() {
        val from = JSONObject().put("swipe_extent", "small").put("gesture_contracts", JSONArray()
            .put(JSONObject().put("gesture_semantics", "object_drag"))
            .put(JSONObject().put("gesture_semantics", "physical_gesture")))
        val unchanged = JSONObject(from.toString()).put("target", "源对象拖到指定落点，然后选择方向")
        val original = unchanged.toString()
        SwipeExtentPolicy.apply("swipe_sequence", from, unchanged)
        assertEquals(original, unchanged.toString())
        val paths = action("[[850,850],[400,350]]", "[[400,350],[650,350]]")
        assertTrue(SwipeExtentPolicy.validateCoordinates(from, paths, 1920, 1080).consistent)
        from.getJSONArray("gesture_contracts").getJSONObject(1).put("gesture_semantics", "reveal_content")
        val checked = SwipeExtentPolicy.validateCoordinates(from, paths, 1920, 1080)
        assertFalse(checked.consistent)
        assertEquals(1, checked.details.getInt("stroke_index"))
    }

    @Test fun historicalMediumRecordsRemainReadableWithoutReplayingThem() {
        val executed=action("[[400,500],[650,500]]").put("action", "swipe").put("swipe_extent", "medium")
        val historical=JSONObject().put("id", "old-medium-run").put("goal", "查看关卡列表").put("status", "completed")
            .put("recent_steps", JSONArray().put(JSONObject().put("receipt", JSONObject().put("status", "ok")
                .put("action", "swipe").put("executed_action", executed))))
        var persisted=""
        val engine=SplitTaskEngine(JSONArray().put(historical).toString(), {persisted=it})
        assertFalse(engine.get("old-medium-run").has("recent_steps"))
        assertTrue(engine.poll().isNull("command"))
        engine.create(JSONObject().put("goal","新的独立任务").put("device_id","direct-this-phone").put("mode","full"))
        val values=SplitTaskEngine.readPersistedRuns(persisted)
        val restored=(0 until values.length()).map(values::getJSONObject).single {it.getString("id")=="old-medium-run"}
        assertEquals("medium",restored.getJSONArray("recent_steps").getJSONObject(0).getJSONObject("receipt")
            .getJSONObject("executed_action").getString("swipe_extent"))
        assertEquals(250.0, SwipeMotionEvidence.recent(restored).getJSONObject(0).getDouble("abs_dx"),0.0)
    }
}

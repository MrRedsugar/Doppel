package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectionalGestureContractTest {
    private fun swipeIntent(target: String = "left", finger: String = "right") = JSONObject()
        .put("gesture_semantics", "reveal_content")
        .put("target_relative_direction", target)
        .put("intended_finger_direction", finger)

    private fun swipe(points: String) = JSONObject()
        .put("status", "located")
        .put("action", "swipe")
        .put("strokes", JSONArray().put(JSONObject().put("points", JSONArray(points)).put("duration_ms", 350)))

    @Test fun oppositeMappingCoversAllEightDirections() {
        val expected = mapOf(
            "up" to "down", "up_right" to "down_left", "right" to "left",
            "down_right" to "up_left", "down" to "up", "down_left" to "up_right",
            "left" to "right", "up_left" to "down_right"
        )
        assertEquals(expected, expected.mapValues { DirectionalGestureContract.opposite(it.key) })
    }

    @Test fun revealContentRequiresKnownDirectionsButAllowsBToCorrectPlannerConflict() {
        val parsed = DirectionalGestureContract.parsePlanner("swipe", swipeIntent())
        assertEquals(1, parsed.size)
        assertEquals("left", parsed.single().targetDirection)
        assertEquals("right", parsed.single().fingerDirection)

        for (invalid in listOf(
            swipeIntent("unknown", "right"),
            swipeIntent("sideways", "right")
        )) {
            try {
                DirectionalGestureContract.parsePlanner("swipe", invalid)
                fail("invalid reveal-content contract accepted: $invalid")
            } catch (_: IllegalArgumentException) {
            }
        }
        val contradictory = DirectionalGestureContract.parsePlanner("swipe", swipeIntent("left", "left"))
        assertEquals("left", contradictory.single().fingerDirection)
    }

    @Test fun physicalGestureChecksFingerWithoutInventingTargetRelationship() {
        val value = JSONObject().put("gesture_semantics", "physical_gesture")
            .put("target_relative_direction", "unknown")
            .put("intended_finger_direction", "down_left")
        val parsed = DirectionalGestureContract.parsePlanner("swipe", value)
        assertEquals("unknown", parsed.single().targetDirection)
        assertEquals("down_left", parsed.single().fingerDirection)
    }

    @Test fun physicalGestureAssessmentIgnoresRelativeTargetButRequiresSameFingerDirection() {
        val intent = DirectionalGestureContract.parsePlanner("swipe", JSONObject()
            .put("gesture_semantics", "physical_gesture")
            .put("target_relative_direction", "unknown")
            .put("intended_finger_direction", "down"))
        val visibleTarget = JSONObject().put("target_relative_direction", "down")
            .put("required_finger_direction", "down")
        assertTrue(DirectionalGestureContract.validateAssessment("swipe", intent, visibleTarget).consistent)

        val wrongFinger = JSONObject(visibleTarget.toString()).put("required_finger_direction", "up")
        val rejected = DirectionalGestureContract.validateAssessment("swipe", intent, wrongFinger)
        assertFalse(rejected.consistent)
        assertEquals("grounder_direction_disagreement", rejected.reasonCode)
    }

    @Test fun coordinateDirectionsUseActualLandscapeAndPortraitAspectRatios() {
        val right = DirectionalGestureContract.parsePlanner("swipe", swipeIntent("left", "right"))
        val landscape = DirectionalGestureContract.validateCoordinates(
            "swipe", right, swipe("[[200,400],[800,700]]"), width = 1920, height = 864
        )
        assertTrue(landscape.consistent)
        assertEquals("right", landscape.details.getString("actual_finger_direction"))

        val down = DirectionalGestureContract.parsePlanner("swipe", swipeIntent("up", "down"))
        val portrait = DirectionalGestureContract.validateCoordinates(
            "swipe", down, swipe("[[400,200],[700,800]]"), width = 864, height = 1920
        )
        assertTrue(portrait.consistent)
        assertEquals("down", portrait.details.getString("actual_finger_direction"))

        // The same normalized path on a square really is diagonal, preserving the default API.
        assertFalse(DirectionalGestureContract.validateCoordinates(
            "swipe", right, swipe("[[200,400],[800,700]]")
        ).consistent)
    }

    @Test fun allEightPhysicalDirectionsAreRecoveredFromNormalizedCoordinates() {
        val vectors = mapOf(
            "up" to (0 to -200), "up_right" to (200 to -200), "right" to (200 to 0),
            "down_right" to (200 to 200), "down" to (0 to 200), "down_left" to (-200 to 200),
            "left" to (-200 to 0), "up_left" to (-200 to -200)
        )
        for ((width, height) in listOf(3200 to 1440, 1440 to 3200)) {
            for ((expected, delta) in vectors) {
                val points = JSONArray().put(JSONArray().put(500).put(500))
                    .put(JSONArray().put(500 + delta.first * 1000.0 / width)
                        .put(500 + delta.second * 1000.0 / height))
                val intent = DirectionalGestureContract.parsePlanner("swipe",
                    swipeIntent(DirectionalGestureContract.opposite(expected), expected))
                val actual = DirectionalGestureContract.validateCoordinates(
                    "swipe", intent, swipe(points.toString()), width, height
                )
                assertTrue("$width x $height $expected: ${actual.details}", actual.consistent)
                assertEquals(expected, actual.details.getString("actual_finger_direction"))
            }
        }
    }

    @Test fun invalidScreenDimensionsCannotProduceAValidatedGesture() {
        val intent = DirectionalGestureContract.parsePlanner("swipe", swipeIntent())
        for ((width, height) in listOf(0 to 1920, 864 to 0, -1 to 1920)) {
            try {
                DirectionalGestureContract.validateCoordinates(
                    "swipe", intent, swipe("[[200,500],[800,500]]"), width, height
                )
                fail("invalid screen size accepted: $width x $height")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test fun coordinateCheckRecomputesActualDirectionAndRejectsOppositePath() {
        val intent = DirectionalGestureContract.parsePlanner("swipe", swipeIntent())
        val correct = DirectionalGestureContract.validateCoordinates(
            "swipe", intent, swipe("[[200,500],[800,500]]")
        )
        assertTrue(correct.consistent)
        assertEquals("right", correct.details.getString("actual_finger_direction"))

        val reversed = DirectionalGestureContract.validateCoordinates(
            "swipe", intent, swipe("[[800,500],[200,500]]")
        )
        assertFalse(reversed.consistent)
        assertEquals("gesture_coordinate_mismatch", reversed.reasonCode)
        assertEquals("left", reversed.details.getString("actual_finger_direction"))
    }

    @Test fun bAssessmentMustIndependentlyAgreeWithPlannerDirection() {
        val intent = DirectionalGestureContract.parsePlanner("swipe", swipeIntent())
        val matching = JSONObject().put("target_relative_direction", "left")
            .put("required_finger_direction", "right")
        assertTrue(DirectionalGestureContract.validateAssessment("swipe", intent, matching).consistent)

        val disagreement = JSONObject().put("target_relative_direction", "right")
            .put("required_finger_direction", "left")
        val checked = DirectionalGestureContract.validateAssessment("swipe", intent, disagreement)
        assertFalse(checked.consistent)
        assertEquals("grounder_direction_disagreement", checked.reasonCode)
    }

    @Test fun explicitGrounderCorrectionMayReplaceWrongPlannerDirection() {
        val intent = DirectionalGestureContract.parsePlanner("swipe", swipeIntent("right", "left"))
        val corrected = JSONObject().put("target_relative_direction", "left")
            .put("required_finger_direction", "right")
            .put("direction_corrected", true)
            .put("correction_reason", "截图顺序显示较早章节在左边，需要手指右滑露出左侧内容")
        val checked = DirectionalGestureContract.validateAssessment("swipe", intent, corrected)
        assertTrue(checked.details.toString(), checked.consistent)
    }

    @Test fun correctedCoordinatesAreValidatedAgainstBAndNeverRewritten() {
        val planner = DirectionalGestureContract.parsePlanner("swipe", swipeIntent("right", "right"))
        assertFalse(DirectionalGestureContract.validatePlannerSemantics(planner).consistent)
        val raw = JSONObject().put("target_relative_direction", "right")
            .put("required_finger_direction", "left").put("direction_corrected", true)
            .put("correction_reason", "要露出右侧内容，必须手指左滑")
        val resolved = DirectionalGestureContract.resolveAssessment("swipe", planner, raw)
        assertTrue(resolved.validation.consistent)
        assertTrue(resolved.corrected)
        assertEquals("left", resolved.effectiveIntents.single().fingerDirection)
        assertEquals("right", planner.single().fingerDirection)
        assertEquals(1, resolved.validation.details.getJSONArray("direction_corrections").length())
        val coordinates = swipe("[[600,500],[500,500]]")
        val original = coordinates.toString()
        assertTrue(DirectionalGestureContract.validateCoordinates("swipe", resolved.effectiveIntents, coordinates).consistent)
        assertFalse(DirectionalGestureContract.validateCoordinates("swipe", planner, coordinates).consistent)
        assertEquals(original, coordinates.toString())
    }

    @Test fun physicalMotionIsNotInvertedEvenWithGrounderCorrectionClaim() {
        val planner = DirectionalGestureContract.parsePlanner("swipe", JSONObject()
            .put("gesture_semantics", "physical_gesture").put("target_relative_direction", "unknown")
            .put("intended_finger_direction", "down"))
        val raw = JSONObject().put("target_relative_direction", "down")
            .put("required_finger_direction", "up").put("direction_corrected", true)
            .put("correction_reason", "错误地把下拉通知栏当浏览下方")
        assertFalse(DirectionalGestureContract.resolveAssessment("swipe", planner, raw).validation.consistent)
    }

    @Test fun objectDragRefinesEndpointGeometryOnlyWithExplicitCorrection() {
        val planner = DirectionalGestureContract.parsePlanner("swipe", JSONObject()
            .put("gesture_semantics", "object_drag").put("target_relative_direction", "unknown")
            .put("intended_finger_direction", "up"))
        val assessment = JSONObject().put("target_relative_direction", "up_left")
            .put("required_finger_direction", "up_left").put("direction_corrected", true)
        val resolution = DirectionalGestureContract.resolveAssessment("swipe", planner, assessment)
        assertTrue(resolution.validation.consistent)
        assertTrue(resolution.corrected)
        assertEquals("up_left", resolution.effectiveIntents.single().targetDirection)
        assertEquals("up_left", resolution.effectiveIntents.single().fingerDirection)
        val correction = resolution.validation.details.getJSONArray("direction_corrections").getJSONObject(0)
        assertEquals("up", correction.getJSONObject("planner").getString("intended_finger_direction"))
        assertEquals("up_left", correction.getJSONObject("grounder").getString("required_finger_direction"))
        assertTrue(DirectionalGestureContract.validateCoordinates("swipe", resolution.effectiveIntents,
            swipe("[[850,850],[400,350]]"), 1920, 1080).consistent)
        assertFalse(DirectionalGestureContract.validateCoordinates("swipe", resolution.effectiveIntents,
            swipe("[[850,850],[850,350]]"), 1920, 1080).consistent)
        for (flag in listOf<Any?>(null, false)) {
            val unclaimed = JSONObject(assessment.toString()).apply { if (flag == null) remove("direction_corrected") else put("direction_corrected", flag) }
            assertEquals("grounder_direction_disagreement", DirectionalGestureContract.resolveAssessment("swipe", planner, unclaimed).validation.reasonCode)
        }
    }

    @Test fun objectDragDoesNotUseBrowsingInversionOrUnknownGroundedEndpoint() {
        val value = JSONObject().put("gesture_semantics", "object_drag")
            .put("target_relative_direction", "left").put("intended_finger_direction", "left")
        val planner = DirectionalGestureContract.parsePlanner("swipe", value)
        val wrong = JSONObject().put("target_relative_direction", "left")
            .put("required_finger_direction", "right").put("direction_corrected", true)
        assertEquals("grounder_internal_direction_contradiction",
            DirectionalGestureContract.resolveAssessment("swipe", planner, wrong).validation.reasonCode)
        assertThrows(IllegalArgumentException::class.java) {
            DirectionalGestureContract.resolveAssessment("swipe", planner, wrong.put("target_relative_direction", "unknown"))
        }
        assertTrue(DirectionalGestureContract.validatePlannerSemantics(planner).consistent)
        assertFalse(DirectionalGestureContract.validatePlannerSemantics(DirectionalGestureContract.parsePlanner("swipe",
            JSONObject(value.toString()).put("intended_finger_direction", "right"))).consistent)
        // Known endpoint geometry can change even across the screen; this is not a fixed finger command.
        val corrected = JSONObject().put("target_relative_direction", "right")
            .put("required_finger_direction", "right").put("direction_corrected", true)
        assertTrue(DirectionalGestureContract.resolveAssessment("swipe", planner, corrected).validation.consistent)
    }

    @Test fun objectDragCorrectionDoesNotUnlockFollowingExplicitFacingDirection() {
        val planner = DirectionalGestureContract.parsePlanner("swipe_sequence", JSONObject().put("gesture_contracts", JSONArray()
            .put(JSONObject().put("gesture_semantics", "object_drag").put("target_relative_direction", "unknown").put("intended_finger_direction", "up"))
            .put(JSONObject().put("gesture_semantics", "physical_gesture").put("target_relative_direction", "unknown").put("intended_finger_direction", "right"))))
        val assessments = JSONArray()
            .put(JSONObject().put("target_relative_direction", "up_left").put("required_finger_direction", "up_left").put("direction_corrected", true))
            .put(JSONObject().put("target_relative_direction", "right").put("required_finger_direction", "right").put("direction_corrected", false))
        val resolution = DirectionalGestureContract.resolveAssessment("swipe_sequence", planner, JSONObject().put("gesture_contracts", assessments))
        assertTrue(resolution.validation.consistent)
        assertEquals(listOf("object_drag", "physical_gesture"), resolution.effectiveIntents.map { it.semantics })
        assertEquals(1, resolution.validation.details.getJSONArray("direction_corrections").length())
        assessments.getJSONObject(1).put("required_finger_direction", "left").put("direction_corrected", true)
        val blocked = DirectionalGestureContract.resolveAssessment("swipe_sequence", planner, JSONObject().put("gesture_contracts", assessments))
        assertEquals("grounder_direction_disagreement", blocked.validation.reasonCode)
        assertEquals(1, blocked.validation.details.getInt("stroke_index"))
    }

    @Test fun correctionRequiresExplicitBooleanWithoutExplanationTokens() {
        val planner = DirectionalGestureContract.parsePlanner("swipe", swipeIntent("left", "left"))
        for (raw in listOf(
            JSONObject().put("direction_corrected", false).put("correction_reason", "修正"),
            JSONObject().put("correction_reason", "修正")
        )) {
            raw.put("target_relative_direction", "left").put("required_finger_direction", "right")
            val result = DirectionalGestureContract.resolveAssessment("swipe", planner, raw)
            assertFalse(raw.toString(), result.validation.consistent)
            assertTrue(result.effectiveIntents.isEmpty())
        }
        assertTrue(DirectionalGestureContract.resolveAssessment("swipe", planner, JSONObject()
            .put("target_relative_direction", "left").put("required_finger_direction", "right")
            .put("direction_corrected", true)).validation.consistent)
    }

    @Test fun internallyWrongGrounderDirectionCannotBeAuthorizedByCorrectionFlag() {
        val planner = DirectionalGestureContract.parsePlanner("swipe", swipeIntent())
        val raw = JSONObject().put("target_relative_direction", "right")
            .put("required_finger_direction", "right").put("direction_corrected", true)
            .put("correction_reason", "声称纠正但内部矛盾")
        val result = DirectionalGestureContract.resolveAssessment("swipe", planner, raw)
        assertFalse(result.validation.consistent)
        assertEquals("grounder_internal_direction_contradiction", result.validation.reasonCode)
    }

    @Test fun sequenceCorrectionsRemainAssociatedWithTheirOwnStroke() {
        val planner = DirectionalGestureContract.parsePlanner("swipe_sequence", JSONObject()
            .put("gesture_contracts", JSONArray().put(swipeIntent("left", "left")).put(swipeIntent("down", "up"))))
        val assessment = JSONObject().put("gesture_contracts", JSONArray()
            .put(JSONObject().put("target_relative_direction", "left").put("required_finger_direction", "right")
                .put("direction_corrected", true).put("correction_reason", "露出左侧需要右滑"))
            .put(JSONObject().put("target_relative_direction", "down").put("required_finger_direction", "up")))
        val result = DirectionalGestureContract.resolveAssessment("swipe_sequence", planner, assessment)
        assertTrue(result.validation.consistent)
        assertEquals(listOf("right", "up"), result.effectiveIntents.map { it.fingerDirection })
        val corrections = result.validation.details.getJSONArray("direction_corrections")
        assertEquals(1, corrections.length())
        assertEquals(0, corrections.getJSONObject(0).getInt("stroke_index"))
    }

    @Test fun sequenceRequiresOneContractAndOneAssessmentPerStroke() {
        val planner = JSONObject().put("gesture_contracts", JSONArray()
            .put(swipeIntent("down", "up"))
            .put(swipeIntent("down", "up")))
        val intent = DirectionalGestureContract.parsePlanner("swipe_sequence", planner)
        assertEquals(2, intent.size)
        val action = JSONObject().put("status", "located").put("action", "swipe_sequence")
            .put("strokes", JSONArray()
                .put(JSONObject().put("points", JSONArray("[[500,800],[500,200]]")))
                .put(JSONObject().put("points", JSONArray("[[500,800],[500,200]]"))))
        assertTrue(DirectionalGestureContract.validateCoordinates("swipe_sequence", intent, action).consistent)

        val short = JSONObject(action.toString()).put("strokes", JSONArray().put(action.getJSONArray("strokes").getJSONObject(0)))
        val checked = DirectionalGestureContract.validateCoordinates("swipe_sequence", intent, short)
        assertFalse(checked.consistent)
        assertEquals("gesture_contract_count_mismatch", checked.reasonCode)
    }
}

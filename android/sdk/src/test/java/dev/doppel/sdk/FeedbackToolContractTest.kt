package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FeedbackToolContractTest {
    private fun point() = JSONObject().put("x", 37).put("y", 38)
    private fun schema() = JSONObject().put("type", "object").put("additionalProperties", false)
        .put("properties", JSONObject().put("x", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 99))
            .put("y", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 99)))
        .put("required", JSONArray(listOf("x", "y")))
    @Test fun missingYIsReportedWithoutRepairingTheProposal() {
        val input = point().apply { remove("y") }
        val rejection = requireNotNull(FeedbackToolContract.check(input, schema()))
        assertEquals("$.y", rejection.field); assertEquals("missing_required", rejection.code)
        assertFalse(input.has("y"))
    }
    @Test fun stringsFractionsAndOutOfFramePointsNeverPass() {
        for (bad in listOf<Any>("38", true, 38.1, 100, -1, JSONObject.NULL))
            assertNotNull(FeedbackToolContract.check(point().put("y", bad), schema()))
        assertNull(FeedbackToolContract.check(point(), schema()))
    }
    @Test fun diagnosticsNeverEchoUnknownFieldsOrValues() {
        val rejection = requireNotNull(FeedbackToolContract.check(point().put("private-secret", "private-value"), schema()))
        assertEquals("$", rejection.field); assertEquals("unknown_field", rejection.code)
    }
    @Test fun nestedPlanErrorsAreLocalAndValidBatchStillPasses() {
        val schema = JSONObject().put("type", "object").put("properties", JSONObject().put("steps", JSONObject()
            .put("type", "array").put("minItems", 1).put("maxItems", 2).put("items", schema())))
        val input = JSONObject().put("steps", JSONArray().put(point()).put(point()))
        assertNull(FeedbackToolContract.check(input, schema))
        input.getJSONArray("steps").getJSONObject(1).remove("y")
        assertEquals("$.steps[1].y", FeedbackToolContract.check(input, schema)?.field)
    }
}

package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UsageBalanceTest {
    private fun lifetime(input: Long, output: Long = 0) = UsageBalance.snapshot(JSONObject().put("source", "billing_calls")
        .put("lifetime_input_tokens", input).put("lifetime_output_tokens", output))
    private fun daily(vararg rows: Pair<String, Long>) = UsageBalance.snapshot(JSONObject().put("daily", JSONArray(rows.map {
        JSONObject().put("date", it.first).put("input_tokens", it.second).put("output_tokens", 0)
    })))

    @Test fun setupAndMigrationBaselineDoNotChargeExistingUsageAndOnlyNewUsageCostsMoney() {
        val historical = lifetime(9_000_000)
        assertEquals(0.0, UsageBalance.cost(null, historical, 2.0, 3.0), 0.0)
        assertEquals(0.0, UsageBalance.cost(historical, historical, 2.0, 3.0), 0.0)
        val current = lifetime(10_000_000, 500_000)
        assertEquals(3.5, UsageBalance.cost(historical, current, 2.0, 3.0), 0.0)
        // Reset/recharge commits current as the new baseline; prices only apply to subsequent usage.
        assertEquals(0.0, UsageBalance.cost(current, current, 50.0, 80.0), 0.0)
        assertEquals(5.0, UsageBalance.cost(current, lifetime(10_100_000, 500_000), 50.0, 80.0), 0.0)
        val delayed = UsageBalance.advance(current, historical)
        assertEquals(current.getLong("input"), delayed.getLong("input"))
        assertEquals(0.0, UsageBalance.cost(current, delayed, 50.0, 80.0), 0.0)
        assertEquals(0.0, UsageBalance.cost(current.put("scope", "first-account"), lifetime(20_000_000).put("scope", "second-account"), 1.0, 1.0), 0.0)
    }

    @Test fun rollingWindowExpiryAndRetainedHistoryDeletionCannotHideAllFutureUsage() {
        val old = daily("2026-09-01" to 8_000_000, "2026-09-14" to 100_000)
        val next = daily("2026-09-14" to 200_000, "2026-09-15" to 400_000)
        assertEquals(0.5, UsageBalance.cost(old, next, 1.0, 0.0), 0.0)
        val reduced = daily("2026-09-15" to 100_000)
        assertEquals(0.0, UsageBalance.cost(next, reduced, 1.0, 0.0), 0.0)
        assertEquals(0.2, UsageBalance.cost(reduced, daily("2026-09-15" to 300_000), 1.0, 0.0), 0.000001)
        assertEquals(0.0, UsageBalance.cost(next, lifetime(20_000_000), 1.0, 0.0), 0.0)
    }

    @Test fun arrayGatewayResponsesRemainChartsAndAreNeverInventedLifetimeCounters() {
        val response = JSONObject().put("days", JSONArray().put("2026-09-15").put("2026-09-16"))
            .put("input_tokens", JSONArray().put(12).put(18)).put("output_tokens", JSONArray().put(3).put(4))
            .put("requests", JSONArray().put(1).put(2))
        assertEquals(30L, UsageBalance.total(response, "input_tokens"))
        assertEquals(7L, UsageBalance.total(response, "output_tokens"))
        assertEquals("daily", UsageBalance.snapshot(response).getString("kind"))
        assertEquals("2026-09-16", UsageBalance.daily(response).getJSONObject(1).getString("date"))
        assertThrows(IllegalArgumentException::class.java) { UsageBalance.cost(null, lifetime(1), Double.NaN, 0.0) }
    }
}

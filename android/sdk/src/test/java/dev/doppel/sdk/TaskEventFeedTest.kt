package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskEventFeedTest {
    @Test fun longGatewayTaskAdvancesPastTheFirstPageAndKeepsBoundedRecentHistory() {
        val feed = TaskEventFeed()
        val requested = mutableListOf<Long>()
        val result = feed.refresh("run") { after ->
            requested.add(after)
            JSONArray((after + 1..minOf(after + 500, 521)).map { JSONObject().put("sequence", it).put("message", "Step $it") })
        }
        assertEquals(listOf(0L, 500L), requested)
        assertEquals(80, result.length())
        assertEquals(521L, result.getJSONObject(79).getLong("sequence"))
        assertEquals(80, feed.refresh("run") { after -> assertEquals(521L, after); JSONArray() }.length())
    }

    @Test fun aLargeBacklogYieldsBetweenRefreshesWithoutLosingItsCursor() {
        val feed = TaskEventFeed()
        var calls = 0
        feed.refresh("run") { after ->
            calls++
            JSONArray((after + 1..after + 500).map { JSONObject().put("sequence", it) })
        }
        assertEquals(4, calls)
        feed.refresh("run") { after -> assertEquals(2000L, after); JSONArray() }
    }

    @Test fun directSnapshotsReplaceAndNewRunDoesNotInheritOldEvents() {
        val feed = TaskEventFeed()
        feed.refresh("one") { JSONArray().put(JSONObject().put("message", "old")) }
        val latest = feed.refresh("one") { JSONArray().put(JSONObject().put("message", "new")) }
        assertEquals(1, latest.length())
        assertEquals("new", latest.getJSONObject(0).getString("message"))
        val next = feed.refresh("two") { after -> assertEquals(0L, after); JSONArray() }
        assertEquals(0, next.length())
    }
}

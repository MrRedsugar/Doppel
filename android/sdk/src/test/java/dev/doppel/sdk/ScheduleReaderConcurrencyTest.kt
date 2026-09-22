package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ScheduleReaderConcurrencyTest {
    private fun body(at: Long) = JSONObject().put("device_id", "fixture").put("goal", "Original goal")
        .put("rule", JSONObject().put("kind", "once").put("at_ms", at))

    @Test fun readersRemainResponsiveAndDetachedWhileCreateAndStatusWait() {
        var now = 100000L
        val engine = ScheduleEngine(null, {}, { now }, { "fixture" })
        val id = engine.create(body(101000L)).getString("id")
        engine.create(body(201000L))
        now = 101000L
        val threads = Executors.newFixedThreadPool(2)
        try {
            for ((operation, expectedStatus) in listOf("create" to "dispatching", "status" to "queued")) {
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val port = object : SchedulePort {
                    private fun networkWait() {
                        assertTrue("The writer still serializes mutations", Thread.holdsLock(engine))
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "Test did not release network request" }
                    }
                    override fun readiness(job: JSONObject): String? = null
                    override fun create(job: JSONObject): String { if (operation == "create") networkWait(); return "run-1" }
                    override fun start(runId: String) {}
                    override fun status(runId: String): String { if (operation == "status") networkWait(); return "completed" }
                }
                val writer = threads.submit { engine.tick(port) }
                try {
                    assertTrue("The request must be in flight", entered.await(2, TimeUnit.SECONDS))
                    threads.submit {
                        val item = engine.get(id)
                        assertEquals(expectedStatus, item.getJSONArray("history").getJSONObject(0).getString("status"))
                        assertFalse(item.getBoolean("enabled"))
                        assertEquals(if (operation == "create") 101000L else 201000L, engine.nextDue())
                        val listing = engine.list().getJSONArray("items")
                        assertEquals(2, listing.length())
                        val listed = (0 until listing.length()).map { listing.getJSONObject(it) }.single { it.getString("id") == id }
                        assertEquals(expectedStatus, listed.getJSONArray("history").getJSONObject(0).getString("status"))
                        item.put("goal", "Reader mutation").getJSONArray("history").getJSONObject(0).put("status", "reader mutation")
                        listed.getJSONObject("rule").put("at_ms", 1L)
                        listing.remove(0)
                        assertEquals("Original goal", engine.get(id).getString("goal"))
                        assertEquals(101000L, engine.get(id).getJSONObject("rule").getLong("at_ms"))
                        assertEquals(expectedStatus, engine.get(id).getJSONArray("history").getJSONObject(0).getString("status"))
                        assertEquals(2, engine.list().getJSONArray("items").length())
                    }.get(2, TimeUnit.SECONDS)
                } finally { release.countDown(); writer.get(2, TimeUnit.SECONDS) }
                assertEquals(if (operation == "create") "queued" else "completed",
                    engine.get(id).getJSONArray("history").getJSONObject(0).getString("status"))
            }
        } finally { threads.shutdownNow(); assertTrue(threads.awaitTermination(2, TimeUnit.SECONDS)) }
    }

    @Test fun pendingOrFailedPersistenceNeverPublishesUncommittedChanges() {
        var blockSave = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine = ScheduleEngine(null, {
            if (blockSave) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                error("Synthetic disk failure")
            }
        }, { 100000L }, { "fixture" })
        val id = engine.create(body(101000L)).getString("id")
        val before = engine.list().toString()
        blockSave = true
        val threads = Executors.newFixedThreadPool(2)
        val writer = threads.submit { engine.update(id, JSONObject().put("goal", "Unsaved goal").put("enabled", false)) }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            threads.submit {
                assertEquals(before, engine.list().toString())
                assertEquals("Original goal", engine.get(id).getString("goal"))
                assertEquals(101000L, engine.nextDue())
            }.get(2, TimeUnit.SECONDS)
            release.countDown()
            assertTrue(assertThrows(ExecutionException::class.java) { writer.get(2, TimeUnit.SECONDS) }.cause is IllegalStateException)
            assertEquals(before, engine.list().toString())
            assertTrue(engine.get(id).getBoolean("enabled"))
        } finally { release.countDown(); threads.shutdownNow(); assertTrue(threads.awaitTermination(2, TimeUnit.SECONDS)) }
    }
}

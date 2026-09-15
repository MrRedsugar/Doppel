package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class VisualCaptureStoreTest {
    private var now = 2000L
    private fun store() = VisualCaptureStore { now }
    private fun capture(id: String, at: Long = 1000L) = VisualCapture(
        VisualFrame(id, "screen", "dev.fixture", 100, 100, 10, 10, 0, at, at + 45000, "hash"),
        VisualPixels(10, 10, ByteArray(100) { 100 }))

    @Test fun stoppingFeedbackInvalidatesExecutionButRetainsApprovalEvidence() {
        val store = store(); val source = capture("approval")
        store.put(source)
        val generation = store.generation.get()
        repeat(8) { store.stopFeedback() }
        assertTrue(store.generation.get() > generation)
        assertSame(source, store.remove("approval"))
        assertNull("Captured pixels remain single use", store.remove("approval"))
    }

    @Test fun approvalDoesNotExtendCaptureExpiry() {
        val store = store(); store.put(capture("approval"))
        now = 46001; store.stopFeedback()
        assertNull(store.remove("approval"))
        store.put(capture("already-expired"))
        assertNull(store.remove("already-expired"))
    }

    @Test fun onlyThreeNewestCapturesRemainAndExplicitPurgeDropsAll() {
        val store = store()
        (1..4).forEach { store.put(capture("frame-$it")) }
        assertNull(store.remove("frame-1"))
        assertNotNull(store.remove("frame-2"))
        store.clear()
        assertNull(store.remove("frame-3")); assertNull(store.remove("frame-4"))
    }

    @Test fun replacementDoesNotEvictAnUnrelatedLiveFrame() {
        val store = store()
        (1..3).forEach { store.put(capture("frame-$it")) }
        val replacement = capture("frame-2")
        store.put(replacement)
        assertNotNull(store.remove("frame-1"))
        assertSame(replacement, store.remove("frame-2"))
        assertNotNull(store.remove("frame-3"))
    }
}

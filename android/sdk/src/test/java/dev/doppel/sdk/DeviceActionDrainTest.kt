package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class DeviceActionDrainTest {
    @Test fun stopCannotPassBetweenTheLastAuthorityCheckAndPlatformRegistration() {
        val drain = DeviceActionDrain()
        val executor = Executors.newFixedThreadPool(2)
        val allowed = AtomicBoolean(true)
        val checked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopStarted = CountDownLatch(1)
        val platformCompletion = AtomicReference<() -> Unit>()
        try {
            val dispatch = executor.submit<Boolean> {
                drain.dispatch("old-run", {
                    val admitted = allowed.get()
                    checked.countDown(); check(release.await(3, TimeUnit.SECONDS)); admitted
                }) { finish -> platformCompletion.set(finish); true }
            }
            assertTrue(checked.await(3, TimeUnit.SECONDS))
            allowed.set(false)
            val stopped = executor.submit<Boolean> { stopStarted.countDown(); drain.awaitStopped(setOf("old-run"), 3000) }
            assertTrue(stopStarted.await(3, TimeUnit.SECONDS))
            assertFalse(stopped.isDone)
            release.countDown()
            assertTrue(dispatch.get(3, TimeUnit.SECONDS))
            assertFalse("A submitted gesture needs a real platform callback", stopped.isDone)
            platformCompletion.get().invoke()
            assertTrue(stopped.get(3, TimeUnit.SECONDS))
            assertFalse(drain.dispatch("old-run", allowed::get) { fail("A queued old action escaped stop"); true })
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun failedSubmissionAndOtherRunsCannotProduceFalsePendingActions() {
        val drain = DeviceActionDrain()
        assertFalse(drain.dispatch("failed", { true }) { false })
        assertTrue(runCatching { drain.dispatch("throws", { true }) { error("platform unavailable") } }.isFailure)
        assertTrue(drain.awaitStopped(timeoutMs = 10))
        lateinit var finish: () -> Unit
        assertTrue(drain.dispatch("other-run", { true }) { finish = it; true })
        assertTrue(drain.awaitStopped(setOf("old-run"), 10))
        assertFalse(drain.awaitStopped(setOf("other-run"), 10))
        finish()
        assertTrue(drain.awaitStopped(timeoutMs = 10))
    }

    @Test fun overlayReleaseWaitsForNativeCallbackEvenAfterBusinessCancellation() {
        val drain=DeviceActionDrain()
        lateinit var finish:()->Unit
        var allowed=true
        var releases=0
        assertTrue(drain.dispatch("drag",{allowed}) { finish=it;true })
        allowed=false
        drain.afterStopped(setOf("drag")) {releases++}
        assertEquals(0,releases)
        assertFalse(drain.awaitStopped(timeoutMs=1))
        finish()
        assertEquals(1,releases)
        assertTrue(drain.awaitStopped(timeoutMs=1))
        finish()
        assertEquals(1,releases)
    }

    @Test fun rejectedCleanupRecoversOnlyAfterServiceDisconnectionAndNewInstance() {
        val old=DeviceActionDrain()
        var releases=0
        assertTrue(old.dispatch("uncertain",{true}) {true})
        old.afterStopped {releases++}
        assertFalse(old.awaitStopped(timeoutMs=1))
        assertEquals(0,releases)
        old.onDisconnected()
        assertTrue(old.awaitStopped(timeoutMs=1))
        assertEquals(1,releases)
        assertFalse(old.dispatch("later",{true}) {fail("Destroyed service must not inject");true})
        val rebound=DeviceActionDrain()
        assertTrue(rebound.dispatch("later",{true}) {finish->finish();true})
        assertTrue(rebound.awaitStopped(timeoutMs=1))
        old.onDisconnected()
        assertEquals(1,releases)
    }
}

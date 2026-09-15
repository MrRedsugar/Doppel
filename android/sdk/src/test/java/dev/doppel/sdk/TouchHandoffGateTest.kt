package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TouchHandoffGateTest {
    @Test fun confirmationCompletesWhileMainQueueRemainsBusyAfterTheWindowUpdate() {
        val main=Executors.newSingleThreadExecutor()
        val busy=CountDownLatch(1)
        val published=CountDownLatch(1)
        try {
            val gate=TouchHandoffGate(timeoutMs=1000,compositorMs=40)
            main.submit {gate.applied();published.countDown();busy.await(2,TimeUnit.SECONDS)}
            assertTrue(published.await(1,TimeUnit.SECONDS))
            assertEquals(TouchHandoffGate.Result.READY,gate.await({true}))
            assertEquals("No second main-thread callback was required",1L,busy.count)
        } finally {busy.countDown();main.shutdownNow()}
    }
    @Test fun lateWindowUpdateCannotExtendTheOriginalTotalDeadline() {
        var now=0L
        val gate=TouchHandoffGate(timeoutMs=800,compositorMs=80,now={now})
        now=750;gate.applied()
        assertEquals(TouchHandoffGate.Result.TIMED_OUT,gate.await({true}) {now=800})
    }
    @Test fun releasingTheTicketDuringTheCompositorAllowanceCannotGrantAPass() {
        val state=CompanionTouchPassState()
        val ticket=state.acquire(1,1)!!
        val gate=TouchHandoffGate()
        gate.applied()
        assertEquals(TouchHandoffGate.Result.CANCELLED,gate.await({state.owns(ticket)}) {state.release(ticket)})
    }
    @Test fun cancelledHostBeforeMainAcknowledgesDoesNotWaitOrGrantAPass() {
        val gate=TouchHandoffGate()
        assertEquals(TouchHandoffGate.Result.CANCELLED,gate.await({false}))
        gate.applied()
        assertEquals(TouchHandoffGate.Result.CANCELLED,gate.await({false}))
    }
    @Test fun interruptedBackgroundWaitDoesNotGrantAPass() {
        val gate=TouchHandoffGate()
        val interrupted=AtomicBoolean()
        val entered=CountDownLatch(1)
        val worker=Thread {
            try {entered.countDown();gate.await({true});fail("Interrupted wait must not succeed")}
            catch (_:InterruptedException) {interrupted.set(true)}
        }
        worker.start();assertTrue(entered.await(1,TimeUnit.SECONDS));worker.interrupt();worker.join(1000)
        assertFalse(worker.isAlive);assertTrue(interrupted.get())
    }
    @Test fun mainRejectionHasNoCompositorWait() {
        val gate=TouchHandoffGate();gate.reject()
        assertEquals(TouchHandoffGate.Result.REJECTED,gate.await({true}) {fail("Rejected layout must never settle")})
    }
    @Test fun aNewFeedbackGenerationDuringCleanupPreventsScreenshotApproval() {
        val generation=java.util.concurrent.atomic.AtomicLong(7)
        val gate=TouchHandoffGate(timeoutMs=1000)
        gate.applied()
        assertEquals(TouchHandoffGate.Result.CANCELLED,gate.await({generation.get()==7L}) {generation.incrementAndGet()})
    }
}

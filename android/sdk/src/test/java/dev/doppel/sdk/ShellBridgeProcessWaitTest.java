package dev.doppel.sdk;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public final class ShellBridgeProcessWaitTest {
    private static final ShellBridgeProcessWait.Cancellation ACTIVE = () -> { };

    private static final class FakeClock implements ShellBridgeProcessWait.Clock {
        long time;
        FakeClock(long time) { this.time = time; }
        @Override public long now() { return time; }
    }

    private static final class FakeProcess extends Process {
        final FakeClock clock;
        final long exitAt;
        final int code;
        final List<Long> waits = new ArrayList<>();
        int destroys;
        boolean returnFalseAtExit;
        InterruptedException waitFailure;
        FakeProcess(FakeClock clock, long exitAt, int code) { this.clock = clock; this.exitAt = exitAt; this.code = code; }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { throw new AssertionError("Unbounded wait is forbidden"); }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (waitFailure != null) throw waitFailure;
            long millis = unit.toMillis(timeout);
            assertTrue("Wait must be positive and permit cancellation every 50 ms", millis > 0 && millis <= 50);
            waits.add(millis);
            if (clock.time >= exitAt) return true;
            clock.time = Math.min(exitAt, clock.time + millis);
            return clock.time >= exitAt && !returnFalseAtExit;
        }
        @Override public int exitValue() {
            if (clock.time < exitAt) throw new IllegalThreadStateException("still running");
            return code;
        }
        @Override public void destroy() { destroys++; }
    }

    @Test public void stdoutEofDoesNotImposeAnExtraHundredMillisecondExitDeadline() throws Exception {
        FakeClock clock = new FakeClock(105);
        FakeProcess child = new FakeProcess(clock, 350, 0);
        assertEquals(-1, child.getInputStream().read());
        assertTrue(ShellBridgeProcessWait.awaitExit(child, 4000, clock, ACTIVE));
        assertEquals(350L, clock.time);
        assertTrue("Process must have received more than 100 ms after stdout EOF", clock.time - 105 > 100);
        assertEquals(0, child.destroys);
    }

    @Test public void outputDrainDoesNotRestartTheOriginalTotalBudget() throws Exception {
        FakeClock clock = new FakeClock(3901);
        FakeProcess child = new FakeProcess(clock, 4001, 0);
        assertFalse(ShellBridgeProcessWait.awaitExit(child, 4000, clock, ACTIVE));
        assertEquals("Stop at the original process deadline, not 4000 ms after stdout EOF", 4000L, clock.time);
        assertEquals(0, child.destroys);
    }

    @Test public void exitCodeClassificationRemainsTheCallersResponsibility() throws Exception {
        FakeClock clock = new FakeClock(100);
        FakeProcess child = new FakeProcess(clock, 130, 17);
        assertTrue(ShellBridgeProcessWait.awaitExit(child, 4000, clock, ACTIVE));
        assertEquals(17, child.exitValue());
        assertEquals(0, child.destroys);
    }

    @Test public void preexistingCancellationPropagatesBeforeAnyWait() throws Exception {
        FakeClock clock = new FakeClock(100);
        FakeProcess child = new FakeProcess(clock, 300, 0);
        Exception cancelled = new Exception("cancelled");
        assertSame(cancelled, assertThrows(Exception.class,
            () -> ShellBridgeProcessWait.awaitExit(child, 4000, clock, () -> { throw cancelled; })));
        assertTrue(child.waits.isEmpty());
        assertEquals(100L, clock.time);
        assertEquals(0, child.destroys);
    }

    @Test public void cancellationBetweenWaitSlicesStopsWithoutWaitingForProcessExit() throws Exception {
        FakeClock clock = new FakeClock(100);
        FakeProcess child = new FakeProcess(clock, 500, 0);
        Exception cancelled = new Exception("cancelled");
        int[] checks = {0};
        assertSame(cancelled, assertThrows(Exception.class,
            () -> ShellBridgeProcessWait.awaitExit(child, 4000, clock, () -> { if (++checks[0] == 2) throw cancelled; })));
        assertEquals(150L, clock.time);
        assertEquals(1, child.waits.size());
        assertEquals(0, child.destroys);
    }

    @Test public void alreadyExitedAtDeadlineIsDetectedWithoutAnotherTimedWait() throws Exception {
        FakeClock clock = new FakeClock(4000);
        FakeProcess child = new FakeProcess(clock, 4000, 0);
        assertTrue(ShellBridgeProcessWait.awaitExit(child, 4000, clock, ACTIVE));
        assertTrue(child.waits.isEmpty());
        assertEquals(0, child.destroys);
    }

    @Test public void stillRunningAtDeadlineIsRejectedWithoutDestroyingIt() throws Exception {
        FakeClock clock = new FakeClock(4000);
        FakeProcess child = new FakeProcess(clock, 4001, 0);
        assertFalse(ShellBridgeProcessWait.awaitExit(child, 4000, clock, ACTIVE));
        assertTrue(child.waits.isEmpty());
        assertEquals(4000L, clock.time);
        assertEquals(0, child.destroys);
    }

    @Test public void exitAtTheDeadlineRaceIsCheckedAfterTimedWaitReturnsFalse() throws Exception {
        FakeClock clock = new FakeClock(3950);
        FakeProcess child = new FakeProcess(clock, 4000, 0);
        child.returnFalseAtExit = true;
        assertTrue(ShellBridgeProcessWait.awaitExit(child, 4000, clock, ACTIVE));
        assertEquals(4000L, clock.time);
        assertEquals(0, child.destroys);
    }

    @Test public void interruptedTimedWaitPropagatesWithoutDestroyingTheProcess() throws Exception {
        FakeClock clock = new FakeClock(100);
        FakeProcess child = new FakeProcess(clock, 300, 0);
        InterruptedException interrupted = new InterruptedException("interrupted");
        child.waitFailure = interrupted;
        assertSame(interrupted, assertThrows(InterruptedException.class,
            () -> ShellBridgeProcessWait.awaitExit(child, 4000, clock, ACTIVE)));
        assertEquals(0, child.destroys);
    }
}

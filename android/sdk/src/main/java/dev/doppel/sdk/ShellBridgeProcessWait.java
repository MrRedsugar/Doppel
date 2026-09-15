package dev.doppel.sdk;

import java.util.concurrent.TimeUnit;

/** Waits only within the caller's original process budget; process ownership stays with the caller. */
final class ShellBridgeProcessWait {
    interface Clock { long now(); }
    interface Cancellation { void check() throws Exception; }

    private ShellBridgeProcessWait() { }

    static boolean awaitExit(Process child, long deadlineMillis, Clock clock, Cancellation cancellation) throws Exception {
        while (true) {
            cancellation.check();
            long now = clock.now();
            if (now >= deadlineMillis) {
                try { child.exitValue(); return true; }
                catch (IllegalThreadStateException stillRunning) { return false; }
            }
            if (child.waitFor(Math.min(50L, deadlineMillis - now), TimeUnit.MILLISECONDS)) return true;
        }
    }
}

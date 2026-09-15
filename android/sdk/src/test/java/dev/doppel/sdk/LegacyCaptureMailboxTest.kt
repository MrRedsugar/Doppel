package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class LegacyCaptureMailboxTest {
    @Test fun revocationAfterDecodeButBeforeDeliveryReleasesTheUndeliveredFrame() {
        val disposed = mutableListOf<String>()
        val mailbox = LegacyCaptureMailbox<String> { disposed.add(it) }
        mailbox.deliver("new-frame")
        mailbox.cancel(SecurityException("revoked"))
        assertTrue(mailbox.await(1))
        assertThrows(SecurityException::class.java) { mailbox.take() }
        assertEquals(listOf("new-frame"), disposed)
    }
    @Test fun aLateImageAfterTimeoutIsDisposedAndCannotReplaceTheFailure() {
        val disposed = mutableListOf<String>()
        val mailbox = LegacyCaptureMailbox<String> { disposed.add(it) }
        mailbox.cancel(java.util.concurrent.TimeoutException())
        mailbox.deliver("late-frame")
        assertThrows(java.util.concurrent.TimeoutException::class.java) { mailbox.take() }
        assertEquals(listOf("late-frame"), disposed)
    }
    @Test fun theCallerOwnsATakenFrameAndSubsequentCancellationCannotRecycleIt() {
        val disposed = mutableListOf<String>()
        val mailbox = LegacyCaptureMailbox<String> { disposed.add(it) }
        mailbox.deliver("new-frame")
        assertEquals("new-frame", mailbox.take())
        mailbox.cancel(SecurityException("revoked"))
        assertTrue(disposed.isEmpty())
        assertThrows(IllegalStateException::class.java) { mailbox.take() }
    }
}

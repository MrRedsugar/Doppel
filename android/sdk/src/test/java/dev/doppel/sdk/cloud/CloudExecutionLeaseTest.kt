package dev.doppel.sdk.cloud

import org.junit.Assert.*
import org.junit.Test

class CloudExecutionLeaseTest {
    @Test fun delayedAcknowledgementsCannotExtendOrReviveExecution() {
        var now = 0L
        val lease = CloudExecutionLease { now }
        assertFalse(lease.valid())
        assertTrue(lease.heartbeat("first"))
        now = 20_000
        assertFalse(lease.valid()) // Sending a heartbeat did not authorize anything.
        assertTrue(lease.acknowledge("first", 30_000, 100_000))
        assertTrue(lease.valid())
        assertFalse(lease.mayAccept(110_000)) // Command acceptance deadline is separate from execution lease.
        assertTrue(lease.mayAccept(125_000))
        assertTrue(lease.heartbeat("second"))
        now = 30_000
        assertFalse(lease.valid())
        assertFalse(lease.acknowledge("second", 30_000, 130_000))
        assertFalse(lease.valid())

        val newSocket = CloudExecutionLease { now }
        assertFalse(newSocket.valid())
        assertFalse(newSocket.acknowledge("second", 30_000, 130_000))
        assertTrue(newSocket.heartbeat("new"))
        assertTrue(newSocket.acknowledge("new", 30_000, 130_000))
        now += 10_000
        assertTrue(newSocket.heartbeat("older"))
        now += 10_000
        assertTrue(newSocket.heartbeat("newer"))
        assertTrue(newSocket.acknowledge("newer", 30_000, 150_000))
        assertFalse(newSocket.acknowledge("older", 30_000, 140_000))
        newSocket.close()
        assertFalse(newSocket.valid())
    }
}

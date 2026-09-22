package dev.doppel.sdk

import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class TaskExecutionGateTest {
    @Test fun staleWorkerTicketCannotBeRecapturedAsCurrent() {
        val generation = AtomicLong(7)
        val gate = TaskExecutionGate(generation::get)
        val ticket = generation.get()
        val beforeQueue = gate.capture("local", ticket)
        assertTrue(beforeQueue())
        generation.incrementAndGet()
        assertFalse(beforeQueue())
        assertFalse(gate.capture("local", ticket)())
        assertTrue(gate.capture("local", generation.get())())
    }

    @Test fun revokingOrReplacingRemotePermitCannotReviveOldDispatch() {
        val gate = TaskExecutionGate { 1L }
        val local = gate.capture("local", 1)
        val first = gate.install("remote") { true }
        val queued = gate.capture("remote", 1)
        assertTrue(queued())
        first.close()
        assertFalse(queued())
        assertFalse(gate.capture("remote", 1)())
        val resumed = gate.install("remote") { true }
        val fresh = gate.capture("remote", 1)
        first.close()
        assertFalse(queued())
        assertTrue(fresh())
        assertTrue(local())
        resumed.close()
        assertFalse(fresh())
        assertTrue(local())
    }

    @Test fun authorityIsRecheckedAfterCallbacksAndFailsClosed() {
        val generation = AtomicLong(1)
        val gate = TaskExecutionGate(generation::get)
        val lease = gate.install("remote") { true }
        val queued = gate.capture("remote", 1) { lease.close(); true }
        assertFalse(queued())
        gate.install("remote") { throw IllegalStateException("unavailable") }
        assertFalse(gate.capture("remote", 1)())
        val duringCallback = gate.capture("local", 1) { generation.incrementAndGet(); true }
        assertFalse(duringCallback())
    }
}

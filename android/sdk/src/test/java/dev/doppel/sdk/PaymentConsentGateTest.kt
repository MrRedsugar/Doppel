package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class PaymentConsentGateTest {
    private val id = "payment-v1:00000000-0000-4000-8000-000000000001"
    private class Memory : PaymentConsentStorage {
        var record: PaymentConsentRecord? = null
        var writable = true
        var readable = true
        val attempts = mutableSetOf<String>()
        override fun readConsent(): PaymentConsentRecord? { check(readable); return record }
        override fun writeConsent(value: PaymentConsentRecord): Boolean { if (!writable) return false; record = value; return true }
        override fun hasAttempt(key: String) = key in attempts
        override fun claimAttempt(key: String, at: Long): Boolean = writable && attempts.add(key)
    }
    private fun ready() = PaymentConsentFlow().apply { begin(0); advance(5000, true); advance(10000, true); advance(15000, true) }
    private fun gate(store: Memory, visible: () -> Boolean = { false }) = PaymentConsentGate(store, Any(), visible, { id }, { 1000L })

    @Test fun defaultOldVersionAndInvalidIdsRemainDisabled() {
        val store = Memory()
        val gate = gate(store)
        assertNull(gate.currentId())
        store.record = PaymentConsentRecord(0, id, 1)
        assertNull(gate.currentId())
        store.record = PaymentConsentRecord(1, "forged", 1)
        assertNull(gate.currentId())
    }

    @Test fun enablingRequiresVisibleSettingsAndCompletedOneUseFlow() {
        val store = Memory()
        var visible = false
        val gate = gate(store) { visible }
        assertFalse(gate.enable(ready()))
        visible = true
        assertFalse(gate.enable(PaymentConsentFlow()))
        val flow = ready()
        assertTrue(gate.enable(flow))
        assertTrue(gate.isEnabledForSettings())
        assertNull(gate.currentId())
        assertFalse(gate.enable(flow))
        visible = false
        assertEquals(id, gate.currentId())
        assertEquals(1000L, store.record!!.grantedAt)
    }

    @Test fun failedEnableOrConsentReadNeverAdvertisesAuthority() {
        val store = Memory().apply { writable = false }
        var visible = true
        val gate = gate(store) { visible }
        assertFalse(gate.enable(ready()))
        visible = false
        assertNull(gate.currentId())
        store.record = PaymentConsentRecord(1, id, 1)
        store.readable = false
        assertNull(gate.currentId())
    }

    @Test fun revocationImmediatelyRejectsPreviouslyStampedCommands() {
        val store = Memory().apply { record = PaymentConsentRecord(1, id, 1) }
        val gate = gate(store)
        assertTrue(gate.disable())
        assertNull(gate(store).currentId())
        var clicks = 0
        assertEquals("denied", gate.runPayment(id, "run", "target") { clicks++; true }.status)
        assertEquals(0, clicks)
    }

    @Test fun failedRevocationStaysDisabledInProcessAndReportsFailure() {
        val store = Memory().apply { record = PaymentConsentRecord(1, id, 1); writable = false }
        val gate = gate(store)
        assertFalse(gate.disable())
        assertNull(gate.currentId())
    }

    @Test fun attemptIsCommittedBeforeActionAndNewCommandCannotRepeatIt() {
        val store = Memory().apply { record = PaymentConsentRecord(1, id, 1) }
        val gate = gate(store)
        var clicks = 0
        val first = gate.runPayment(id, "run", "target") { assertEquals(1, store.attempts.size); clicks++; true }
        assertEquals(PaymentAttempt("attempted", true), first)
        assertEquals("duplicate", gate(store).runPayment(id, "run", "target") { clicks++; true }.status)
        assertEquals(1, clicks)
    }

    @Test fun falseOrThrowingActionKeepsTheClaim() {
        for (throws in listOf(false, true)) {
            val store = Memory().apply { record = PaymentConsentRecord(1, id, 1) }
            val gate = gate(store)
            val result = gate.runPayment(id, "run", "target") { if (throws) error("Uncertain"); false }
            assertEquals(PaymentAttempt("attempted", false), result)
            assertEquals("duplicate", gate.runPayment(id, "run", "target") { true }.status)
        }
    }

    @Test fun storageFailurePreventsClickAndDisablesFurtherAuthority() {
        val store = Memory().apply { record = PaymentConsentRecord(1, id, 1); writable = false }
        val gate = gate(store)
        var clicked = false
        assertEquals("storage_error", gate.runPayment(id, "run", "target") { clicked = true; true }.status)
        assertFalse(clicked)
        assertNull(gate.currentId())
    }

    @Test fun settingsWrongIdAndEmptyIdentityCannotExecute() {
        val store = Memory().apply { record = PaymentConsentRecord(1, id, 1) }
        assertEquals("denied", gate(store) { true }.runPayment(id, "run", "target") { true }.status)
        assertEquals("denied", gate(store).runPayment("other", "run", "target") { true }.status)
        assertEquals("denied", gate(store).runPayment(id, "", "target") { true }.status)
    }

    @Test fun reconsentingDoesNotEraseAttemptsFromTheSameTask() {
        val store = Memory().apply { record = PaymentConsentRecord(1, id, 1) }
        var visible = false
        val nextId = "payment-v1:00000000-0000-4000-8000-000000000002"
        val gate = PaymentConsentGate(store, Any(), { visible }, { nextId }, { 1000L })
        gate.runPayment(id, "run", "target") { true }
        gate.disable()
        visible = true
        assertTrue(gate.enable(ready()))
        visible = false
        assertEquals("denied", gate.runPayment(id, "run", "target") { true }.status)
        assertEquals("duplicate", gate.runPayment(nextId, "run", "target") { true }.status)
    }

    private class Grant : PaymentConsentGrant {
        var id: String? = null
        var removable = true
        var writable = true
        override fun read() = id
        override fun clear(): Boolean { if (!removable) return false; id = null; return true }
        override fun write(id: String): Boolean { if (!writable) return false; this.id = id; return true }
    }

    @Test fun grantRemovalPersistsRevocationWhenDatabaseCleanupFails() {
        val database = Memory().apply { record = PaymentConsentRecord(1, id, 1); writable = false }
        val grant = Grant().apply { this.id = this@PaymentConsentGateTest.id }
        val store = PaymentConsentStorageWithGrant(database, grant)
        val gate = PaymentConsentGate(store, Any(), { false }, { id }, { 1000L })
        assertTrue(gate.disable())
        assertFalse(gate.hasStorageFailure())
        assertNull(PaymentConsentGate(store, Any(), { false }, { id }, { 1000L }).currentId())
        assertEquals(id, database.record!!.id)
    }

    @Test fun databaseCleanupPersistsRevocationWhenGrantRemovalFails() {
        val database = Memory().apply { record = PaymentConsentRecord(1, id, 1) }
        val grant = Grant().apply { this.id = this@PaymentConsentGateTest.id; removable = false }
        val store = PaymentConsentStorageWithGrant(database, grant)
        val gate = PaymentConsentGate(store, Any(), { false }, { id }, { 1000L })
        assertTrue(gate.disable())
        assertNull(PaymentConsentGate(store, Any(), { false }, { id }, { 1000L }).currentId())
    }

    @Test fun interruptedGrantPublishAndMismatchedGrantStayOffAfterRestart() {
        val database = Memory()
        val grant = Grant().apply { writable = false }
        val store = PaymentConsentStorageWithGrant(database, grant)
        val gate = PaymentConsentGate(store, Any(), { true }, { id }, { 1000L })
        assertFalse(gate.enable(ready()))
        assertNull(PaymentConsentGate(store, Any(), { false }, { id }, { 1000L }).currentId())
        database.record = PaymentConsentRecord(1, id, 1)
        grant.id = "payment-v1:00000000-0000-4000-8000-000000000002"
        assertNull(PaymentConsentGate(store, Any(), { false }, { id }, { 1000L }).currentId())
    }

    @Test fun failureOfBothRevocationStoresIsReportedAndDisablesThisProcess() {
        val database = Memory().apply { record = PaymentConsentRecord(1, id, 1); writable = false }
        val grant = Grant().apply { this.id = this@PaymentConsentGateTest.id; removable = false }
        val gate = PaymentConsentGate(PaymentConsentStorageWithGrant(database, grant), Any(), { false }, { id }, { 1000L })
        assertFalse(gate.disable())
        assertTrue(gate.hasStorageFailure())
        assertNull(gate.currentId())
    }
}

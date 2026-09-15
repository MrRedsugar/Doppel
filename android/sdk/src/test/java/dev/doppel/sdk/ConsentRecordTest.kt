package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class ConsentRecordTest {
    @Test fun bothDocumentVersionsAndAnExplicitTimeAreRequired() {
        assertFalse(ConsentRecord(null, null, 0).accepts("terms-1", "privacy-1"))
        assertFalse(ConsentRecord("terms-1", "privacy-1", 0).accepts("terms-1", "privacy-1"))
        assertTrue(ConsentRecord("terms-1", "privacy-1", 1).accepts("terms-1", "privacy-1"))
    }
    @Test fun changingEitherNoticeRequiresFreshAcceptance() {
        val accepted = ConsentRecord("terms-1", "privacy-1", 123L)
        assertFalse(accepted.accepts("terms-2", "privacy-1"))
        assertFalse(accepted.accepts("terms-1", "privacy-2"))
    }
}

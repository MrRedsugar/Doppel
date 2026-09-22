package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class TaskSubmissionKeyTest {
    @Test fun expiryFutureAndMalformedKeysAreRejectedWhileLegacyKeysRemainCompatible() {
        val at=1000000L
        val key=TaskSubmissionKey.create(at,"one")
        assertEquals(at,TaskSubmissionKey.validate(key,at+TaskSubmissionKey.RETENTION_MS-1))
        assertThrows(IllegalArgumentException::class.java) { TaskSubmissionKey.validate(key,at+TaskSubmissionKey.RETENTION_MS) }
        assertThrows(IllegalArgumentException::class.java) { TaskSubmissionKey.validate(TaskSubmissionKey.create(at+300001,"future"),at) }
        for (invalid in listOf("q2:bad:key","q2:01:key","q2:100:","q2:99999999999999999:key"))
            assertThrows(IllegalArgumentException::class.java) { TaskSubmissionKey.validate(invalid,at) }
        assertNull(TaskSubmissionKey.validate("schedule:legacy:1",at))
    }
}

package dev.doppel.sdk

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class LegacyCaptureBuffersTest {
    @Test fun rowPaddingAndBufferPositionDoNotBecomeVisiblePixels() {
        val bytes = byteArrayOf(99, 98, 1, 2, 3, 4, 5, 6, 7, 8, 90, 90, 9, 10, 11, 12, 13, 14, 15, 16)
        val source = ByteBuffer.wrap(bytes).apply { position(2) }
        val result = LegacyCaptureBuffers.packRgba(source, 2, 2, 4, 10)
        val packed = ByteArray(result.remaining()); result.get(packed)
        assertArrayEquals((1..16).map { it.toByte() }.toByteArray(), packed)
        assertEquals("The producer buffer remains owned by ImageReader", 2, source.position())
    }

    @Test fun truncatedPlanesUnsupportedStrideAndOversizedFramesAreRejectedBeforeAllocation() {
        assertThrows(IllegalArgumentException::class.java) { LegacyCaptureBuffers.packRgba(ByteBuffer.allocate(15), 2, 2, 4, 8) }
        assertThrows(IllegalArgumentException::class.java) { LegacyCaptureBuffers.packRgba(ByteBuffer.allocate(20), 2, 2, 2, 8) }
        assertThrows(IllegalArgumentException::class.java) { LegacyCaptureBuffers.packRgba(ByteBuffer.allocate(20), 2, 2, 4, 7) }
        assertThrows(IllegalArgumentException::class.java) { LegacyCaptureBuffers.byteCount(16384, 16384) }
        assertEquals(18_432_000, LegacyCaptureBuffers.byteCount(1440, 3200))
    }
}

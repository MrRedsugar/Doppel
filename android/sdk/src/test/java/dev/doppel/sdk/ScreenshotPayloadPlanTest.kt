package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class ScreenshotPayloadPlanTest {
    private fun plan(verificationOnly: Boolean = false, width: Int = 1440, height: Int = 3200, at: Long = 1000) =
        ScreenshotPayloadPlan("capture", "screen", "example.app", width, height, 0, at, verificationOnly)

    @Test fun verificationOnlySkipsEvenAThrowingEncoder() {
        val result = plan(true).encodeForDelivery<String> { throw AssertionError("Encoding must not run") }
        assertNull(result)
    }

    @Test fun verificationOnlyNeverInvokesCountingEncoder() {
        var calls = 0
        val result = plan(true).encodeForDelivery { calls++; Any() }
        assertNull(result)
        assertEquals(0, calls)
    }

    @Test fun deliveryEncodesExactlyOnceAndReturnsTheSameObject() {
        var calls = 0
        val payload = Any()
        val result = plan().encodeForDelivery { calls++; payload }
        assertEquals(1, calls)
        assertSame(payload, result)
    }

    @Test fun deliveryPreservesNullEncoderOutputAndStillInvokesOnce() {
        var calls = 0
        assertNull(plan().encodeForDelivery<String?> { calls++; null })
        assertEquals(1, calls)
    }

    @Test fun deliveryPropagatesTheOriginalEncoderFailureWithoutRetry() {
        var calls = 0
        val failure = IllegalStateException("controlled encoder failure")
        val actual = assertThrows(IllegalStateException::class.java) {
            plan().encodeForDelivery<String> { calls++; throw failure }
        }
        assertSame(failure, actual)
        assertEquals(1, calls)
    }

    @Test fun deliveryDoesNotCacheOrRepeatEncoderCallsAcrossInvocations() {
        var calls = 0
        val value = plan()
        assertEquals(1, value.encodeForDelivery { ++calls })
        assertEquals(2, value.encodeForDelivery { ++calls })
        assertEquals(2, calls)
    }

    @Test fun verificationAndDeliveryHaveIdenticalPixelDimensions() {
        for ((width, height) in listOf(1440 to 3200, 3200 to 1440, 1080 to 1920, 640 to 480, 16384 to 8192)) {
            val delivery = plan(false, width, height)
            val verification = plan(true, width, height)
            assertEquals(delivery.imageWidth, verification.imageWidth)
            assertEquals(delivery.imageHeight, verification.imageHeight)
            assertEquals(delivery.expiresAt, verification.expiresAt)
        }
    }

    @Test fun largeImagesUseTheExistingFloatScaleAndIntegerTruncation() {
        for ((width, height) in listOf(1440 to 3200, 3200 to 1440, 1081 to 2401, 16384 to 16383)) {
            val value = plan(width = width, height = height)
            val scale = 1920f / maxOf(width, height)
            assertEquals((width * scale).toInt(), value.imageWidth)
            assertEquals((height * scale).toInt(), value.imageHeight)
            assertTrue(maxOf(value.imageWidth, value.imageHeight) <= 1920)
        }
        assertEquals(864, plan().imageWidth)
        assertEquals(1920, plan().imageHeight)
    }

    @Test fun imagesAtOrBelowTheLimitAreNeverUpscaled() {
        for ((width, height) in listOf(1 to 1, 640 to 480, 1920 to 1080, 1080 to 1920, 1920 to 1920)) {
            val value = plan(width = width, height = height)
            assertEquals(width, value.imageWidth)
            assertEquals(height, value.imageHeight)
        }
    }

    @Test fun extremeAspectRatiosKeepBothBitmapDimensionsPositive() {
        for (verificationOnly in listOf(false, true)) {
            val portrait = plan(verificationOnly, width = 1, height = 16384)
            val landscape = plan(verificationOnly, width = 16384, height = 1)
            assertEquals(1, portrait.imageWidth)
            assertEquals(1920, portrait.imageHeight)
            assertEquals(1920, landscape.imageWidth)
            assertEquals(1, landscape.imageHeight)
        }
    }

    @Test fun captureTimeAndExpiryDoNotAdvanceWhenEncodingFinishesLater() {
        var clock = 1234L
        val value = plan(at = clock)
        val encoded = value.encodeForDelivery { clock += 50_000; "encoded" }
        assertEquals("encoded", encoded)
        assertEquals(1234L, value.capturedAt)
        assertEquals(46_234L, value.expiresAt)
        assertFalse(value.matches("screen", "example.app", 1440, 3200, 0, clock))
    }

    @Test fun verificationOnlyRetainsTheActualCaptureTimestamp() {
        val value = plan(true, at = 2345L)
        assertNull(value.encodeForDelivery<String> { throw AssertionError("Encoding must not run") })
        assertEquals(2345L, value.capturedAt)
        assertEquals(47_345L, value.expiresAt)
        assertFalse(value.matches("screen", "example.app", 1440, 3200, 0, 2344))
    }

    @Test fun matchingRequiresTheOriginalSourceIdentityAndDisplayGeometry() {
        val value = plan()
        assertTrue(value.matches("screen", "example.app", 1440, 3200, 0, 1000))
        assertFalse(value.matches("other-screen", "example.app", 1440, 3200, 0, 1000))
        assertFalse(value.matches("screen", "other.app", 1440, 3200, 0, 1000))
        assertFalse(value.matches("screen", "example.app", 1439, 3200, 0, 1000))
        assertFalse(value.matches("screen", "example.app", 1440, 3199, 0, 1000))
        assertFalse(value.matches("screen", "example.app", 1440, 3200, 1, 1000))
        assertFalse(value.matches("screen", "example.app", value.imageWidth, value.imageHeight, 0, 1000))
    }

    @Test fun matchingRejectsFutureAndExpiredFramesWithInclusiveLifetimeBounds() {
        val value = plan(at = 1000)
        assertFalse(value.matches("screen", "example.app", 1440, 3200, 0, 999))
        assertTrue(value.matches("screen", "example.app", 1440, 3200, 0, 1000))
        assertTrue(value.matches("screen", "example.app", 1440, 3200, 0, 46_000))
        assertFalse(value.matches("screen", "example.app", 1440, 3200, 0, 46_001))
        assertFalse(value.matches("screen", "example.app", 1440, 3200, 0, -1))
    }

    @Test fun sourceIdsMustBeNonblankAndBounded() {
        for (invalid in listOf("", " ", "\n", "x".repeat(129))) {
            assertThrows(IllegalArgumentException::class.java) { plan().copy(captureId = invalid) }
            assertThrows(IllegalArgumentException::class.java) { plan().copy(screenId = invalid) }
        }
        for (invalid in listOf("", " ", "\n", "x".repeat(256))) {
            assertThrows(IllegalArgumentException::class.java) { plan().copy(packageName = invalid) }
        }
        val value = plan().copy(captureId = "c".repeat(128), screenId = "s".repeat(128), packageName = "p".repeat(255))
        assertEquals(128, value.captureId.length)
        assertEquals(255, value.packageName.length)
    }

    @Test fun displayDimensionsMustBePositiveAndBounded() {
        for (invalid in listOf(Int.MIN_VALUE, -1, 0, 16385, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { plan(width = invalid) }
            assertThrows(IllegalArgumentException::class.java) { plan(height = invalid) }
        }
        assertEquals(1920, plan(width = 16384, height = 16384).imageWidth)
    }

    @Test fun rotationMustBeAPlatformQuarterTurn() {
        for (valid in 0..3) assertEquals(valid, plan().copy(rotation = valid).rotation)
        for (invalid in listOf(Int.MIN_VALUE, -1, 4, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { plan().copy(rotation = invalid) }
        }
    }

    @Test fun captureTimeRejectsNegativeValuesAndExpiryOverflow() {
        for (invalid in listOf(Long.MIN_VALUE, -1L, Long.MAX_VALUE - 44_999, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { plan(at = invalid) }
        }
        assertEquals(45_000L, plan(at = 0).expiresAt)
        val maximum = plan(at = Long.MAX_VALUE - 45_000)
        assertEquals(Long.MAX_VALUE, maximum.expiresAt)
        assertTrue(maximum.matches("screen", "example.app", 1440, 3200, 0, Long.MAX_VALUE))
    }
}

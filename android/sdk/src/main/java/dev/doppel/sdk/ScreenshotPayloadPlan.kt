package dev.doppel.sdk

/** Pixel provenance and delivery policy; screenId names the paired semantic observation, not an atomic tree/pixel read. */
internal data class ScreenshotPayloadPlan(
    val captureId: String,
    val screenId: String,
    val packageName: String,
    val displayWidth: Int,
    val displayHeight: Int,
    val rotation: Int,
    val capturedAt: Long,
    val verificationOnly: Boolean,
) {
    init {
        require(captureId.isNotBlank() && captureId.length <= 128) { "Invalid capture identity" }
        require(screenId.isNotBlank() && screenId.length <= 128) { "Invalid screen identity" }
        require(packageName.isNotBlank() && packageName.length <= 255) { "Invalid package identity" }
        require(displayWidth in 1..16384 && displayHeight in 1..16384) { "Invalid display dimensions" }
        require(rotation in 0..3) { "Invalid display rotation" }
        require(capturedAt in 0..Long.MAX_VALUE - 45_000L) { "Invalid capture timestamp" }
    }

    private val scale = 1920f / maxOf(displayWidth, displayHeight)
    // Keep the existing float/truncation behavior, with a valid bitmap minimum for extreme aspect ratios.
    val imageWidth: Int = if (scale < 1f) (displayWidth * scale).toInt().coerceAtLeast(1) else displayWidth
    val imageHeight: Int = if (scale < 1f) (displayHeight * scale).toInt().coerceAtLeast(1) else displayHeight
    val expiresAt: Long = capturedAt + 45_000L

    fun matches(screen: String, pkg: String, width: Int, height: Int, rotation: Int, now: Long): Boolean =
        screenId == screen && packageName == pkg && displayWidth == width && displayHeight == height &&
            this.rotation == rotation && now in capturedAt..expiresAt

    /** Verification still uses the same pixels; it simply produces no transport payload. */
    fun <T> encodeForDelivery(encode: () -> T): T? = if (verificationOnly) null else encode()
}

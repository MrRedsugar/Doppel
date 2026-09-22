package dev.doppel.sdk

/** Maps a native window buffer onto the display at 1:1 scale; never stretches a capture. */
internal object WindowCaptureGeometry {
    data class Placement(
        val sourceBounds: List<Int>,
        val displayBounds: List<Int>,
        val inferredSurfaceInsets: Boolean,
        val inferredDisplayOrigin: Boolean = false,
    )

    /**
     * Bounds and capture must come from the same display geometry. The caller must reject rotation
     * or window changes during capture; pixels cannot reveal a 180-degree or square-window rotation.
     */
    fun placement(
        width: Int,
        height: Int,
        windowBounds: List<Int>,
        displayWidth: Int,
        displayHeight: Int,
        pixels: IntArray? = null,
        allowDisplaySizedBuffer: Boolean = false,
    ): Placement? {
        if (width <= 0 || height <= 0 || displayWidth <= 0 || displayHeight <= 0 ||
            windowBounds.size != 4 ||
            (pixels != null && pixels.size.toLong() != width.toLong() * height)) return null
        val (left, top, right, bottom) = windowBounds
        val windowWidth = right.toLong() - left
        val windowHeight = bottom.toLong() - top
        if (windowWidth <= 0 || windowHeight <= 0) return null
        val extraWidth = width - windowWidth
        val extraHeight = height - windowHeight
        var inset = 0
        if (extraWidth != 0L || extraHeight != 0L) {
            val buffer = pixels ?: return null
            // Standard IMEs can use a display-sized transparent surface but expose only their
            // touchable keyboard area. Opt in only for that window type; require alpha alignment.
            if (allowDisplaySizedBuffer && width == displayWidth && height == displayHeight &&
                visibleBounds(width, height, buffer) == windowBounds) {
                return Placement(windowBounds, windowBounds, inferredSurfaceInsets = false,
                    inferredDisplayOrigin = true)
            }
            if (extraWidth <= 0 || extraWidth % 2 != 0L || extraWidth != extraHeight) return null
            inset = (extraWidth / 2).toInt()
            val opaque = visibleBounds(width, height, buffer, minimumAlpha = 255) ?: return null
            // ponytail: infer only AOSP's uniform elevation insets. Semi-transparent elevation
            // shadows can extend asymmetrically outside the crop, but opaque content cannot.
            // These pixels cannot prove that an app never used manual surface insets.
            // Do not generalize this to arbitrary window transforms without native geometry data.
            if (opaque[0] < inset || opaque[1] < inset || opaque[2] > width - inset ||
                opaque[3] > height - inset) return null
        }
        val clippedLeft = maxOf(0, left)
        val clippedTop = maxOf(0, top)
        val clippedRight = minOf(displayWidth, right)
        val clippedBottom = minOf(displayHeight, bottom)
        if (clippedRight <= clippedLeft || clippedBottom <= clippedTop) return null
        val sourceLeft = inset + (clippedLeft.toLong() - left).toInt()
        val sourceTop = inset + (clippedTop.toLong() - top).toInt()
        return Placement(
            listOf(sourceLeft, sourceTop, sourceLeft + (clippedRight - clippedLeft),
                sourceTop + (clippedBottom - clippedTop)),
            listOf(clippedLeft, clippedTop, clippedRight, clippedBottom),
            inferredSurfaceInsets = inset != 0,
        )
    }

    private fun visibleBounds(width: Int, height: Int, pixels: IntArray, minimumAlpha: Int = 1): List<Int>? {
        var left = width
        var top = height
        var right = 0
        var bottom = 0
        for (y in 0 until height) for (x in 0 until width) {
            if (pixels[y * width + x] ushr 24 < minimumAlpha) continue
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x + 1)
            bottom = maxOf(bottom, y + 1)
        }
        return if (right > left) listOf(left, top, right, bottom) else null
    }
}

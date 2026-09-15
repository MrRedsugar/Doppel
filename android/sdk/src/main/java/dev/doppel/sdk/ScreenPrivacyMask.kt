package dev.doppel.sdk

/** Masks local sensitive node bounds in the scaled image without moving any actionable coordinates. */
internal object ScreenPrivacyMask {
    fun apply(pixels: IntArray, width: Int, height: Int, displayWidth: Int, displayHeight: Int, bounds: List<List<Int>>) {
        require(width > 0 && height > 0 && pixels.size == width * height && displayWidth > 0 && displayHeight > 0)
        for (rect in bounds) {
            require(rect.size == 4)
            // Round outward and include one source pixel for antialiased edges.
            val left = kotlin.math.floor((rect[0] - 1.0) * width / displayWidth).toInt().coerceIn(0, width)
            val top = kotlin.math.floor((rect[1] - 1.0) * height / displayHeight).toInt().coerceIn(0, height)
            val right = kotlin.math.ceil((rect[2] + 1.0) * width / displayWidth).toInt().coerceIn(left, width)
            val bottom = kotlin.math.ceil((rect[3] + 1.0) * height / displayHeight).toInt().coerceIn(top, height)
            for (y in top until bottom) java.util.Arrays.fill(pixels, y * width + left, y * width + right, 0xff333333.toInt())
        }
    }
}

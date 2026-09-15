package dev.doppel.sdk

/** Exact RGB emptiness, independent of alpha, average brightness or the size of visible details. */
internal object ScreenPixelContent {
    fun hasVisibleRgb(pixels: IntArray): Boolean = pixels.any { it and 0x00ffffff != 0 }
}

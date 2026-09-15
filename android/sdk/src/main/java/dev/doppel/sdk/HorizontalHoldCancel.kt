package dev.doppel.sdk

import kotlin.math.abs

internal object HorizontalHoldCancel {
    fun crossed(startX: Float, currentX: Float, viewportWidth: Float, normalDistance: Float, touchSlop: Float): Boolean {
        if (!startX.isFinite() || !currentX.isFinite() || !viewportWidth.isFinite() ||
            viewportWidth <= 1f || startX < 0f || startX >= viewportWidth || normalDistance <= 0f) return false
        val delta = currentX - startX
        if (delta == 0f) return false
        val available = if (delta > 0f) viewportWidth - 1f - startX else startX
        if (available <= 0f) return false
        // A docked entry has less room toward the edge than the normal cancel distance.
        val threshold = minOf(normalDistance, maxOf(touchSlop * 2f, available * 0.65f), available * 0.9f)
        return abs(delta) >= threshold
    }
}

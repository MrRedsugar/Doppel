package dev.doppel.sdk

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Caller-owned pixels, stable for the duration of a call. No Android or model dependency. */
internal data class VisualAnchorImage(val width: Int, val height: Int, val argb: IntArray) {
    init { require(width in 1..4096 && height in 1..4096 && argb.size.toLong() == width.toLong() * height) }
}
internal data class VisualAnchorRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun translated(dx: Int, dy: Int) = VisualAnchorRect(left + dx, top + dy, right + dx, bottom + dy)
}
internal data class VisualAnchorSpec(val id: String, val bounds: VisualAnchorRect, val targetX: Int, val targetY: Int,
    val searchRadiusPx: Int = 24, val contextPaddingPx: Int = 8)
/** Frozen local reference. Pixels are never exposed in diagnostics or copied into the model context. */
internal class VisualAnchor internal constructor(
    val spec: VisualAnchorSpec, val imageWidth: Int, val imageHeight: Int,
    internal val reference: IntArray, internal val context: IntArray,
    internal val samples: IntArray
)
internal sealed class VisualAnchorPreparation {
    data class Prepared(val anchor: VisualAnchor) : VisualAnchorPreparation()
    data class Rejected(val reason: String) : VisualAnchorPreparation()
}
internal sealed class VisualAnchorMatch {
    data class Matched(val x: Int, val y: Int, val bounds: VisualAnchorRect, val score: Double) : VisualAnchorMatch()
    data class Rejected(val reason: String) : VisualAnchorMatch()
}

/**
 * Bounded translation-only RGB matcher, not a semantic recognizer or an authorization mechanism.
 * Every location is searched on current pixels; low-information and competing matches fail closed.
 */
internal class VisualAnchorTracker {
    fun prepare(image: VisualAnchorImage, spec: VisualAnchorSpec): VisualAnchorPreparation {
        val rect = spec.bounds; val padding = spec.contextPaddingPx
        if (spec.id.length !in 1..128 || rect.left < 0 || rect.top < 0 || rect.right > image.width || rect.bottom > image.height ||
            rect.width !in 12..128 || rect.height !in 12..128 || spec.targetX !in 0 until image.width ||
            spec.targetY !in 0 until image.height ||
            max(max(rect.left - spec.targetX, spec.targetX - (rect.right - 1)), max(rect.top - spec.targetY, spec.targetY - (rect.bottom - 1))) > 128 ||
            spec.searchRadiusPx !in 0..96 || padding !in 0..32 ||
            rect.left < padding || rect.top < padding || rect.right + padding > image.width || rect.bottom + padding > image.height) {
            return VisualAnchorPreparation.Rejected("invalid_region")
        }
        val reference = copy(image, rect)
        if (!textured(reference, rect.width, rect.height)) return VisualAnchorPreparation.Rejected("low_texture")
        val context = copy(image, VisualAnchorRect(rect.left - padding, rect.top - padding, rect.right + padding, rect.bottom + padding))
        // Grid positions are deterministic and span the entire patch, including its edges. Full-pixel
        // checking below, not this cheap shortlist, establishes whether a candidate matches.
        val samples = IntArray(64) { index ->
            ((index / 8) * (rect.height - 1) / 7) * rect.width + ((index % 8) * (rect.width - 1) / 7)
        }
        val anchor = VisualAnchor(spec, image.width, image.height, reference, context, samples)
        return when (val match = locate(anchor, image)) {
            is VisualAnchorMatch.Matched -> VisualAnchorPreparation.Prepared(anchor)
            is VisualAnchorMatch.Rejected -> VisualAnchorPreparation.Rejected(match.reason)
        }
    }

    fun locate(anchor: VisualAnchor, image: VisualAnchorImage): VisualAnchorMatch {
        if (image.width != anchor.imageWidth || image.height != anchor.imageHeight) return VisualAnchorMatch.Rejected("dimensions_changed")
        val spec = anchor.spec; val rect = spec.bounds; val radius = spec.searchRadiusPx; val pad = spec.contextPaddingPx
        val minX = max(pad, rect.left - radius); val maxX = min(image.width - rect.width - pad, rect.left + radius)
        val minY = max(pad, rect.top - radius); val maxY = min(image.height - rect.height - pad, rect.top + radius)
        if (minX > maxX || minY > maxY) return VisualAnchorMatch.Rejected("not_visible")
        val columns = maxX - minX + 1; val rows = maxY - minY + 1
        val coarse = IntArray(columns * rows)
        for (y in minY..maxY) for (x in minX..maxX) {
            var total = 0
            for (sample in anchor.samples) {
                total += difference(anchor.reference[sample], image.argb[(y + sample / rect.width) * image.width + x + sample % rect.width])
            }
            coarse[(y - minY) * columns + x - minX] = total
        }
        // Local minima, rather than the nearest coordinate, preserve other plausible matches.
        val candidates = ArrayList<Candidate>()
        for (y in 0 until rows) for (x in 0 until columns) {
            val score = coarse[y * columns + x]
            if (score > 64 * 24) continue
            var minimum = true
            loop@ for (dy in -2..2) for (dx in -2..2) {
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until columns && ny in 0 until rows && coarse[ny * columns + nx] < score) { minimum = false; break@loop }
            }
            if (minimum) candidates.add(Candidate(x + minX, y + minY, score))
        }
        candidates.sortBy { it.coarse }
        // A scene with more than 32 plausible local extrema is not distinctive enough to trust.
        if (candidates.size > 32 && candidates[32].coarse <= candidates[0].coarse + 64 * 3) return VisualAnchorMatch.Rejected("ambiguous")
        val matches = ArrayList<Match>()
        var contextRejected = false
        for (candidate in candidates.take(32)) {
            val quality = compare(anchor.reference, image, candidate.x, candidate.y, rect.width, rect.height)
            if (quality.mean > 12.0 || quality.changedFraction > .08 || quality.worstCellChangedFraction > .25) continue
            if (!contextMatches(anchor, image, candidate.x, candidate.y)) { contextRejected = true; continue }
            matches.add(Match(candidate.x, candidate.y, quality.mean))
        }
        matches.sortBy { it.score }
        val best = matches.firstOrNull() ?: return VisualAnchorMatch.Rejected(if (contextRejected) "context_changed" else "not_visible_or_changed")
        if (matches.drop(1).any { max(abs(it.x - best.x), abs(it.y - best.y)) >= 3 && it.score <= best.score + 3.0 }) {
            return VisualAnchorMatch.Rejected("ambiguous")
        }
        val dx = best.x - rect.left; val dy = best.y - rect.top
        if (spec.targetX + dx !in 0 until image.width || spec.targetY + dy !in 0 until image.height) return VisualAnchorMatch.Rejected("target_out_of_bounds")
        return VisualAnchorMatch.Matched(spec.targetX + dx, spec.targetY + dy, rect.translated(dx, dy), best.score)
    }

    private data class Candidate(val x: Int, val y: Int, val coarse: Int)
    private data class Match(val x: Int, val y: Int, val score: Double)
    private data class Quality(val mean: Double, val changedFraction: Double, val worstCellChangedFraction: Double)

    private fun compare(reference: IntArray, image: VisualAnchorImage, left: Int, top: Int, width: Int, height: Int): Quality {
        var total = 0L; var changed = 0
        val cellChanged = IntArray(16); val cellCount = IntArray(16)
        for (y in 0 until height) for (x in 0 until width) {
            val delta = difference(reference[y * width + x], image.argb[(top + y) * image.width + left + x])
            val cell = (y * 4 / height) * 4 + (x * 4 / width)
            total += delta; cellCount[cell]++
            if (delta > 40) { changed++; cellChanged[cell]++ }
        }
        val count = width * height
        return Quality(total.toDouble() / count, changed.toDouble() / count,
            cellChanged.indices.maxOf { if (cellCount[it] == 0) 0.0 else cellChanged[it].toDouble() / cellCount[it] })
    }

    private fun contextMatches(anchor: VisualAnchor, image: VisualAnchorImage, left: Int, top: Int): Boolean {
        val padding = anchor.spec.contextPaddingPx
        if (padding == 0) return true
        val width = anchor.spec.bounds.width; val height = anchor.spec.bounds.height; val stride = width + padding * 2
        var total = 0L; var changed = 0; var count = 0
        for (y in -padding until height + padding step 2) for (x in -padding until width + padding step 2) {
            if (x in 0 until width && y in 0 until height) continue
            val delta = difference(anchor.context[(y + padding) * stride + x + padding], image.argb[(top + y) * image.width + left + x])
            total += delta; if (delta > 50) changed++; count++
        }
        return count > 0 && total.toDouble() / count <= 35 && changed.toDouble() / count <= .28
    }

    private fun textured(pixels: IntArray, width: Int, height: Int): Boolean {
        var low = 255; var high = 0; var edges = 0; var comparisons = 0
        for (y in 0 until height) for (x in 0 until width) {
            val pixel = pixels[y * width + x]
            for (shift in intArrayOf(0, 8, 16)) { val value = (pixel shr shift) and 255; low = min(low, value); high = max(high, value) }
            if (x > 0) { comparisons++; if (difference(pixel, pixels[y * width + x - 1]) >= 24) edges++ }
            if (y > 0) { comparisons++; if (difference(pixel, pixels[(y - 1) * width + x]) >= 24) edges++ }
        }
        return high - low >= 48 && comparisons > 0 && edges.toDouble() / comparisons >= .025
    }

    private fun copy(image: VisualAnchorImage, rect: VisualAnchorRect) = IntArray(rect.width * rect.height) { index ->
        image.argb[(rect.top + index / rect.width) * image.width + rect.left + index % rect.width]
    }
    private fun difference(first: Int, second: Int): Int = max(abs((first and 255) - (second and 255)),
        max(abs(((first shr 8) and 255) - ((second shr 8) and 255)), abs(((first shr 16) and 255) - ((second shr 16) and 255))))
}

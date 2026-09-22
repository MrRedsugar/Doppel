package dev.doppel.sdk

/** Keeps window identity, never pixels. Missing from the interactive list does not mean destroyed. */
internal class NativeWindowHistory {
    data class Window(val id: Int, val type: Int, val layer: Int, val bounds: List<Int>,
        val packageName: String, val focused: Boolean, val active: Boolean)
    private data class Background(val window: Window, val geometry: Triple<Int, Int, Int>, val seenAt: Long)
    private var background: Background? = null
    private var foregroundId: Int? = null

    @Synchronized fun observe(current: List<Window>, foregroundId: Int?, geometry: Triple<Int, Int, Int>,
        viewport: List<Int>, now: Long, permissionControllerPackage: String? = null) {
        if (background?.geometry != geometry) clear()
        val foreground = current.firstOrNull { it.id == foregroundId } ?: run { clear(); return }
        val previous = this.foregroundId
        this.foregroundId = foreground.id
        if (foreground.type != 1 || foreground.packageName == "com.android.systemui") { background = null; return }
        if (!covers(foreground.bounds, viewport)) {
            val related = foreground.packageName == background?.window?.packageName ||
                (!permissionControllerPackage.isNullOrBlank() && foreground.packageName == permissionControllerPackage)
            val unknownPackage = foreground.packageName.isBlank() || foreground.packageName.startsWith("window:")
            // A rootless permission popup may never identify its package. Keep only the immediately
            // preceding main window through one such small foreground window, recapturing its pixels.
            // ponytail: an unknown window's ownership is unproven; this background is context only,
            // not evidence that the old app is still the compositor backdrop or accepts interaction.
            if ((!related && !unknownPackage) ||
                previous != foreground.id && previous != background?.window?.id) background = null
            return
        }
        // An unknown full-size application may be a new page. Never attach an older app's pixels.
        if (foreground.packageName.isBlank() || foreground.packageName.startsWith("window:")) {
            background = null; return
        }
        background = Background(foreground, geometry, now)
    }

    @Synchronized fun behind(current: List<Window>, foregroundId: Int, geometry: Triple<Int, Int, Int>,
        viewport: List<Int>, now: Long): Window? {
        val front = current.firstOrNull { it.id == foregroundId } ?: return null
        if (this.foregroundId != foregroundId) return null
        if (front.type != 1 || front.packageName == "com.android.systemui" || covers(front.bounds, viewport)) return null
        if (current.any { it.id != foregroundId && it.type == 1 && covers(it.bounds, viewport) }) return null
        val saved = background ?: return null
        if (saved.geometry != geometry || now - saved.seenAt !in 0..300_000 ||
            saved.window.id == foregroundId || current.any { it.id == saved.window.id }) return null
        return saved.window
    }

    @Synchronized fun knownBackgroundId(): Int? = background?.window?.id
    @Synchronized fun forget(id: Int) { if (background?.window?.id == id) background = null }
    @Synchronized fun clear() { background = null; foregroundId = null }

    companion object {
        fun covers(bounds: List<Int>, other: List<Int>): Boolean = bounds.size == 4 && other.size == 4 &&
            bounds[0] <= other[0] && bounds[1] <= other[1] && bounds[2] >= other[2] && bounds[3] >= other[3]
    }
}

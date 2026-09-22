package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class NativeWindowHistoryTest {
    private val size = Triple(1080, 1920, 0)
    private val viewport = listOf(0, 72, 1080, 1920)
    private val app = NativeWindowHistory.Window(10, 1, 0, listOf(0, 0, 1080, 1920), "app.a", true, true)
    private val popup = app.copy(id = 20, bounds = listOf(27, 429, 1053, 1563))

    @Test fun sameApplicationModalRetainsWindowIdentityButNeverAnOldImage() {
        val history = NativeWindowHistory()
        history.observe(listOf(app), app.id, size, viewport, 100)
        // Discovery of a new foreground must precede use of the cached background.
        assertNull(history.behind(listOf(popup), popup.id, size, viewport, 200))
        history.observe(listOf(popup), popup.id, size, viewport, 200)
        assertEquals(app, history.behind(listOf(popup), popup.id, size, viewport, 200))
        assertNull(history.behind(listOf(app, popup), popup.id, size, viewport, 200))
        history.forget(app.id)
        assertNull(history.behind(listOf(popup), popup.id, size, viewport, 201))
    }

    @Test fun permissionControllerRequiresThePackageResolvedByThePlatform() {
        val permission = popup.copy(packageName = "platform.permission.controller")
        for (resolvedPackage in listOf(null, "", "different.controller", permission.packageName)) {
            val history = NativeWindowHistory()
            history.observe(listOf(app), app.id, size, viewport, 100)
            history.observe(listOf(permission), permission.id, size, viewport, 200, resolvedPackage)
            assertEquals(if (resolvedPackage == permission.packageName) app else null,
                history.behind(listOf(permission), permission.id, size, viewport, 200))
        }
    }

    @Test fun anotherApplicationsSmallWindowNeverInheritsTheOldApp() {
        for (pkg in listOf("app.b", "other.permission.controller")) {
            val history = NativeWindowHistory()
            history.observe(listOf(app), app.id, size, viewport, 100)
            val other = popup.copy(packageName = pkg)
            history.observe(listOf(other), other.id, size, viewport, 200, "platform.permission.controller")
            assertNull(history.behind(listOf(other), other.id, size, viewport, 200))
            // Re-observing the same ID cannot recover an association already discarded.
            history.observe(listOf(popup), popup.id, size, viewport, 300)
            assertNull(history.behind(listOf(popup), popup.id, size, viewport, 300))
        }
    }

    @Test fun singleRootlessModalCanRecaptureContextWithoutWaitingForAPackageEvent() {
        for (unknownPackage in listOf("window:20", "")) {
            for (confirmedPackage in listOf(app.packageName, "platform.permission.controller", "app.b")) {
                val history = NativeWindowHistory()
                history.observe(listOf(app), app.id, size, viewport, 100)
                val pending = popup.copy(packageName = unknownPackage)
                repeat(2) { index ->
                    history.observe(listOf(pending), pending.id, size, viewport, 200L + index)
                    assertEquals(app.id, history.knownBackgroundId())
                    assertEquals(app, history.behind(listOf(pending), pending.id, size, viewport, 200L + index))
                }
                val confirmed = popup.copy(packageName = confirmedPackage)
                history.observe(listOf(confirmed), confirmed.id, size, viewport, 300, "platform.permission.controller")
                assertEquals(if (confirmedPackage == "app.b") null else app,
                    history.behind(listOf(confirmed), confirmed.id, size, viewport, 300))
            }
        }
    }

    @Test fun unknownModalContextCannotSurviveASecondWindowAGapOrAnUnknownFullWindow() {
        for (interruption in listOf("second_modal", "gap", "full_window")) {
            val history = NativeWindowHistory()
            history.observe(listOf(app), app.id, size, viewport, 100)
            val pending = popup.copy(packageName = "window:20")
            history.observe(listOf(pending), pending.id, size, viewport, 200)
            when (interruption) {
                "second_modal" -> {
                    val next = pending.copy(id = 21, packageName = "window:21")
                    history.observe(listOf(next), next.id, size, viewport, 250)
                }
                "gap" -> history.observe(emptyList(), null, size, viewport, 250)
                "full_window" -> {
                    val full = pending.copy(bounds = app.bounds)
                    history.observe(listOf(full), full.id, size, viewport, 250)
                }
            }
            history.observe(listOf(popup), popup.id, size, viewport, 300)
            assertNull(history.behind(listOf(popup), popup.id, size, viewport, 300))
        }
    }

    @Test fun aGapOrASecondModalBreaksTheContinuousForegroundRelationship() {
        for (gap in listOf(true, false)) {
            val history = NativeWindowHistory()
            history.observe(listOf(app), app.id, size, viewport, 100)
            history.observe(listOf(popup), popup.id, size, viewport, 200)
            val next = if (gap) popup else popup.copy(id = 21)
            if (gap) history.observe(emptyList(), null, size, viewport, 250)
            history.observe(listOf(next), next.id, size, viewport, 300)
            assertNull(history.behind(listOf(next), next.id, size, viewport, 300))
        }
    }

    @Test fun coldStartRotationExpiryAndSystemPanelsCannotReuseTheBackground() {
        val history = NativeWindowHistory()
        history.observe(listOf(popup), popup.id, size, viewport, 100)
        assertNull(history.behind(listOf(popup), popup.id, size, viewport, 100))
        history.observe(listOf(app), app.id, size, viewport, 100)
        history.observe(listOf(popup), popup.id, size, viewport, 200)
        assertNull(history.behind(listOf(popup), popup.id, size.copy(third = 1), viewport, 200))
        history.observe(listOf(popup), popup.id, size, viewport, 300000)
        assertNull(history.behind(listOf(popup), popup.id, size, viewport, 300101))
        val panel = popup.copy(type = 3, packageName = "com.android.systemui")
        history.observe(listOf(panel), panel.id, size, viewport, 300102)
        assertNull(history.knownBackgroundId())
    }

    @Test fun aNewFullApplicationReplacesTheOldBackground() {
        val history = NativeWindowHistory()
        history.observe(listOf(app), app.id, size, viewport, 100)
        val next = app.copy(id = 30, packageName = "app.b")
        val nextPopup = popup.copy(packageName = next.packageName)
        history.observe(listOf(next), next.id, size, viewport, 300)
        history.observe(listOf(nextPopup), nextPopup.id, size, viewport, 400)
        assertEquals(next, history.behind(listOf(nextPopup), nextPopup.id, size, viewport, 400))
        history.observe(listOf(next.copy(packageName = "window:30")), next.id, size, viewport, 500)
        assertNull(history.knownBackgroundId())
    }
}

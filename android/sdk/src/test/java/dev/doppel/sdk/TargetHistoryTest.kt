package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class TargetHistoryTest {
    private val root = TargetNodeSnapshot("n0", listOf(0, 0, 1080, 1920), className = "Frame")
    private val ticker = TargetNodeSnapshot("n0_0", listOf(0, 0, 100, 80), text = "suggestion one")
    private val button = TargetNodeSnapshot("n0_1", listOf(0, 100, 300, 200), text = "Open menu", clickable = true)
    private fun screen(id: String, nodes: List<TargetNodeSnapshot> = listOf(root, ticker, button), time: Long = 1000) =
        TargetScreenSnapshot(id, "fixture.app", 7, 1, 1080, 1920, time, nodes)
    private fun fresh() = screen("new", listOf(root, ticker.copy(text = "another suggestion"), button), 2000)
    private fun history() = TargetHistory().apply { remember(screen("old")) }

    @Test fun unrelatedDynamicLabelDoesNotInvalidateStableNamedTarget() {
        assertTrue(history().revalidates("old", fresh(), button.id, "tap"))
    }
    @Test fun targetMovementContentAndAncestorChangesRemainStale() {
        for (changed in listOf(button.copy(text = "Different menu"), button.copy(bounds = listOf(0, 200, 300, 300)), button.copy(enabled = false))) {
            assertFalse(history().revalidates("old", screen("new", listOf(root, ticker, changed), 2000), button.id, "tap"))
        }
        assertFalse(history().revalidates("old", screen("new", listOf(root.copy(resourceId = "new_form"), ticker, button), 2000), button.id, "tap"))
    }
    @Test fun controlStateChangesCannotRevalidateAnOldTarget() {
        val toggle = button.copy(checkable = true, checked = false, selected = false, stateDescription = "Off")
        val stored = screen("old", listOf(root, ticker, toggle))
        for (changed in listOf(toggle.copy(checked = true), toggle.copy(checkable = false),
            toggle.copy(selected = true), toggle.copy(stateDescription = "On"))) {
            val history = TargetHistory().apply { remember(stored) }
            assertFalse(history.revalidates("old", screen("new", listOf(root, ticker, changed), 2000), toggle.id, "tap"))
        }
    }
    @Test fun packageWindowNavigationRotationAndTruncationRemainStale() {
        for (changed in listOf(fresh().copy(packageName = "other.app"), fresh().copy(windowId = 8), fresh().copy(navigationGeneration = 2), fresh().copy(width = 1920, height = 1080), fresh().copy(complete = false))) {
            assertFalse(history().revalidates("old", changed, button.id, "tap"))
        }
    }
    @Test fun unnamedAndCommitTargetsNeverUseRelaxedValidation() {
        for (label in listOf("", "Send", "Submit order", "Confirm", "Delete", "Save", "Pay")) {
            val named = button.copy(text = label)
            val stored = screen("old", listOf(root, ticker, named))
            val changed = screen("new", listOf(root, ticker.copy(text = "changed"), named), 2000)
            assertFalse(TargetHistory().apply { remember(stored) }.revalidates("old", changed, button.id, "tap"))
        }
    }
    @Test fun expiredMissingAndConsumedHistoryCannotAuthorizeAction() {
        assertFalse(history().revalidates("old", fresh().copy(capturedAt = 50000), button.id, "tap"))
        assertFalse(history().revalidates("unknown", fresh(), button.id, "tap"))
        assertFalse(history().revalidates("old", fresh(), "n0_9", "tap"))
        val history = history(); history.clear()
        assertFalse(history.revalidates("old", fresh(), button.id, "tap"))
    }
    @Test fun namedChildCanIdentifyContainerButChangedChildCannot() {
        val container = button.copy(text = "", childCount = 1)
        val label = TargetNodeSnapshot("n0_1_0", button.bounds, text = "Open menu")
        val old = screen("old", listOf(root, ticker, container, label))
        val history = TargetHistory().apply { remember(old) }
        assertTrue(history.revalidates("old", screen("new", listOf(root, ticker.copy(text = "changed"), container, label), 2000), container.id, "tap"))
        assertFalse(history.revalidates("old", screen("new", listOf(root, ticker, container, label.copy(text = "Different item")), 2000), container.id, "tap"))
    }
    @Test fun namedInputMustRetainItsExactPreviousValue() {
        val field = button.copy(text = "", hint = "Search", clickable = true, editable = true)
        val history = TargetHistory().apply { remember(screen("old", listOf(root, ticker, field))) }
        assertTrue(history.revalidates("old", screen("new", listOf(root, ticker.copy(text = "changed"), field), 2000), field.id, "type"))
        assertFalse(history.revalidates("old", screen("new", listOf(root, ticker, field.copy(text = "someone typed")), 2000), field.id, "type"))
    }
    @Test fun scrollingRequiresSameScrollableResourceAndContext() {
        val list = button.copy(text = "", resourceId = "fixture:list", clickable = false, scrollable = true)
        val history = TargetHistory().apply { remember(screen("old", listOf(root, ticker, list))) }
        assertTrue(history.revalidates("old", screen("new", listOf(root, ticker.copy(text = "changed"), list), 2000), list.id, "scroll"))
        assertFalse(history.revalidates("old", screen("new", listOf(root, ticker, list.copy(resourceId = "other:list")), 2000), list.id, "scroll"))
        val unnamed = list.copy(resourceId = "")
        assertFalse(TargetHistory().apply { remember(screen("old", listOf(root, ticker, unnamed))) }
            .revalidates("old", screen("new", listOf(root, ticker.copy(text = "changed"), unnamed), 2000), unnamed.id, "scroll"))
    }
}

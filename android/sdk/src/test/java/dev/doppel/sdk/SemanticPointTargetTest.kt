package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SemanticPointTargetTest {
    private val frame = VisualFrame("capture", "screen", "example.app", 1000, 2000, 500, 1000, 0, 100, 30100, "digest")
    private fun gesture(label: String = "点击打开按钮", kind: String = "tap", x: Double = .15, y: Double = .075) =
        VisualGesture(kind, frame.captureId, x, y, null, null, if (kind == "long_press") 600 else 80, label, "当前页面", "safe")
    private fun node(id: String = "n0_3_0", label: String = "打开", bounds: List<Int> = listOf(100, 100, 300, 300)) =
        JSONObject().put("id", id).put("text", label).put("bounds", JSONArray(bounds)).put("enabled", true).put("clickable", true)
    private fun observation(vararg nodes: JSONObject) = JSONObject().put("screen_id", frame.screenId)
        .put("package_name", frame.packageName).put("width", frame.displayWidth).put("height", frame.displayHeight)
        .put("tree_complete", true).put("nodes", JSONArray(nodes.toList()))
    private fun resolve(value: JSONObject, proposal: VisualGesture = gesture(), source: VisualFrame = frame) =
        SemanticPointTarget.resolve(value, source, proposal)

    @Test fun uniquelyLabeledControlReturnsItsOriginalIdWithoutMutatingInputs() {
        val value = observation(node("n0_3_0_12"))
        val before = value.toString()
        val proposal = gesture()
        assertEquals("n0_3_0_12", resolve(value, proposal))
        assertEquals(before, value.toString())
        assertEquals(gesture(), proposal)
    }

    @Test fun wpsBlankSpreadsheetFixtureInheritsOnlyTheTrueChildLabel() {
        val value = observation(
            node(label = "", bounds = listOf(40, 210, 1035, 390)),
            node("n0_3_0_0", "空白表格", listOf(497, 276, 637, 323)).put("clickable", false)
        ).put("width", 1440).put("height", 3200)
        val source = frame.copy(displayWidth = 1440, displayHeight = 3200, imageWidth = 864, imageHeight = 1920)
        val proposal = gesture("点击\"空白表格\"按钮新建表格", x = 560.0 / 1440, y = 300.0 / 3200)
        assertEquals("n0_3_0", resolve(value, proposal, source))
    }

    @Test fun longPressRequiresItsOwnCapability() {
        val target = node().put("clickable", false).put("long_clickable", true)
        assertEquals("n0_3_0", resolve(observation(target), gesture(kind = "long_press")))
        assertNull(resolve(observation(target)))
        assertNull(resolve(observation(node()), gesture(kind = "long_press")))
    }

    @Test fun mismatchedSourceIdentityOrDisplayGeometryCannotResolve() {
        for ((key, value) in listOf("screen_id" to "other", "package_name" to "other.app", "width" to 999, "height" to 1999,
            "rotation" to 1, "capture_id" to "other", "width" to "1000", "height" to 2000.5)) {
            assertNull(key, resolve(observation(node()).put(key, value)))
        }
        assertNull(resolve(observation(node()), gesture().copy(captureId = "other")))
        assertEquals("n0_3_0", resolve(observation(node()).put("rotation", 0).put("capture_id", "capture")))
    }

    @Test fun duplicateIdsAnywhereRejectEvenOutsideThePointAndAfterThreeHundredNodes() {
        assertNull(resolve(observation(node(), node(bounds = listOf(700, 1000, 800, 1100)))))
        val nodes = mutableListOf(node())
        repeat(300) { nodes.add(node("n1_$it", "", listOf(700, 1000, 800, 1100)).put("clickable", false)) }
        nodes.add(node(bounds = listOf(700, 1000, 800, 1100)))
        assertNull(resolve(observation(*nodes.toTypedArray())))
    }

    @Test fun overlappingActionableNodesRejectBeforeFilteringTheirLabelsOrSafety() {
        for (other in listOf(
            node("n9", "不匹配"),
            node("n9", "不匹配").put("password", true),
            node("n9", "不匹配").put("checkable", true),
            node("n9", "不匹配").put("clickable", false).put("long_clickable", true),
            node("n9", "不匹配", listOf(0, 0, 1000, 2000))
        )) assertNull(resolve(observation(node(), other)))
    }

    @Test fun explicitlyInvisibleAndDisabledOverlaysAreNotActionable() {
        for (flag in listOf("visible", "enabled")) {
            val other = node("n9", "不匹配").put(flag, false)
            assertEquals("n0_3_0", resolve(observation(node(), other)))
        }
    }

    @Test fun disabledInvisibleProtectedAndStatefulTargetsCannotResolve() {
        for ((key, value) in listOf("enabled" to false, "visible" to false, "password" to true, "checkable" to true)) {
            assertNull(key, resolve(observation(node().put(key, value))))
        }
        assertNull(resolve(observation(node().put("enabled", "true"))))
        assertNull(resolve(observation(node().put("password", "false"))))
    }

    @Test fun boundsAreHalfOpenAndUseDisplayPixelsDespiteDownscaledImage() {
        assertEquals("n0_3_0", resolve(observation(node()), gesture(x = .1, y = .05)))
        assertEquals("n0_3_0", resolve(observation(node()), gesture(x = .2999, y = .1499)))
        for ((x, y) in listOf(.3 to .075, .15 to .15, .0999 to .075, .15 to .0499)) {
            assertNull(resolve(observation(node()), gesture(x = x, y = y)))
        }
    }

    @Test fun quarterScreenIsAllowedButLargerOrOffscreenBoundsAreRejected() {
        assertEquals("n0_3_0", resolve(observation(node(bounds = listOf(0, 0, 500, 1000)))))
        assertNull(resolve(observation(node(bounds = listOf(0, 0, 501, 1000)))))
        assertNull(resolve(observation(node(bounds = listOf(-1, 0, 500, 1000)))))
        assertNull(resolve(observation(node(bounds = listOf(0, 0, 1001, 200)))))
    }

    @Test fun malformedBoundsCannotEstablishAUniqueCandidate() {
        for (bounds in listOf(JSONArray(listOf(100, 100, 300)), JSONArray(listOf(100, 100, 100, 300)),
            JSONArray(listOf("100", 100, 300, 300)), JSONArray(listOf(100.5, 100, 300, 300)),
            JSONArray(listOf(100, 100, Long.MAX_VALUE, 300)))) {
            assertNull(resolve(observation(node().put("bounds", bounds))))
            assertNull(resolve(observation(node(), node("n9").put("bounds", bounds))))
        }
    }

    @Test fun labelsRequireFullLiteralTextWithoutCaseOrWhitespaceNormalization() {
        assertNull(resolve(observation(node(label = "打开表格"))))
        assertNull(resolve(observation(node(label = "Open")), gesture("点击open按钮")))
        assertNull(resolve(observation(node(label = "空白  表格")), gesture("点击空白 表格按钮")))
        assertNull(resolve(observation(node(label = ""))))
        assertNull(resolve(observation(node().put("text", 123))))
        assertNull(resolve(observation(node()), gesture("")))
        assertEquals("n0_3_0", resolve(observation(node(label = "").put("description", "打开"))))
    }

    @Test fun distinctTextAndDescriptionMustBothAppearAsTheCompleteControlLabel() {
        val value = observation(node().put("description", "新建文档"))
        assertNull(resolve(value))
        assertEquals("n0_3_0", resolve(value, gesture("点击打开 新建文档按钮")))
        assertEquals("n0_3_0", resolve(observation(node().put("description", "打开"))))
    }

    @Test fun inheritedLabelsAreNotTruncatedToSummaryLength() {
        val fullLabel = "文".repeat(221)
        val value = observation(node(label = ""), node("n0_3_0_0", fullLabel).put("clickable", false))
        assertNull(resolve(value, gesture(fullLabel.take(220))))
        assertEquals("n0_3_0", resolve(value, gesture(fullLabel)))
    }

    @Test fun unrelatedSiblingLabelsAndMultipleDescendantLabelsCannotBeInherited() {
        assertNull(resolve(observation(node(label = ""), node("n9", "打开").put("clickable", false))))
        assertNull(resolve(observation(node(label = ""), node("n0_3_0_0", "打开").put("clickable", false),
            node("n0_3_0_1", "删除").put("clickable", false))))
        assertNull(resolve(observation(node(label = ""), node("n0_3_00", "打开").put("clickable", false))))
    }

    @Test fun inheritedLabelRejectsInteractiveProtectedOrEscapingDescendants() {
        for (flag in listOf("clickable", "long_clickable", "editable", "scrollable", "password", "checkable")) {
            val child = node("n0_3_0_0", "打开", listOf(220, 220, 250, 250)).put("clickable", false).put(flag, true)
            assertNull(flag, resolve(observation(node(label = ""), child)))
        }
        assertNull(resolve(observation(node(label = ""), node("n0_3_0_0", "打开", listOf(220, 220, 350, 350)).put("clickable", false))))
    }

    @Test fun invalidAndTruncatedNodeIdsAreNeverRepaired() {
        for (id in listOf(" n0_3_0", "n0_3_0 ", "n0_3_", "n0_3...", "n0_" + "1".repeat(120))) {
            assertNull(id, resolve(observation(node(id))))
        }
    }

    @Test fun incompleteAssistantOrMalformedObservationsCannotResolve() {
        assertNull(resolve(observation(node()).put("tree_complete", false)))
        assertNull(resolve(observation(node()).put("assistant_surface", true)))
        assertNull(resolve(observation(node()).put("nodes", JSONArray().put(node()).put("unknown"))))
        assertNull(resolve(JSONObject()))
    }

    @Test fun unsupportedMalformedOrUnsafeGesturesRemainOnTheVisualPath() {
        val valid = gesture()
        val invalid = listOf(valid.copy(kind = "swipe"), valid.copy(kind = "type"), valid.copy(endX = .2),
            valid.copy(endY = .2), valid.copy(x = Double.NaN), valid.copy(y = Double.POSITIVE_INFINITY),
            valid.copy(x = -0.01), valid.copy(y = 1.0), valid.copy(durationMs = 0), valid.copy(durationMs = 201),
            valid.copy(kind = "long_press", durationMs = 200)) +
            listOf("payment", "verification", "sensitive", "uncertain").map { valid.copy(safety = it) }
        for (proposal in invalid) assertNull(proposal.toString(), resolve(observation(node()), proposal))
    }
}

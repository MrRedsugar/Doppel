package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelScreenSummaryTest {
    private fun node(id: String, text: String = "", tap: Boolean = false, bounds: List<Int> = listOf(20, 20, 120, 100)) = JSONObject()
        .put("id", id).put("text", text).put("enabled", true).put("clickable", tap).put("bounds", JSONArray(bounds))
    private fun screen(vararg nodes: JSONObject) = JSONObject().put("screen_id", "screen").put("package_name", "example")
        .put("width", 1080).put("height", 2400).put("nodes", JSONArray(nodes.toList()))

    @Test fun distinctInteractiveLabelsKeepGeometryAndSelectedRemainsActionable() {
        val result = ModelScreenSummary.render(screen(
            node("n1", "考勤", true, listOf(0, 2100, 200, 2400)).put("selected", true),
            node("n2", "下班打卡", true, listOf(400, 1500, 800, 1900)),
        ))
        assertTrue(result.text.contains("coordinate_space=device_pixels"))
        val selected = result.text.lineSequence().first { it.startsWith("n1:") }
        val submit = result.text.lineSequence().first { it.startsWith("n2:") }
        assertTrue(selected.contains("bounds=[0,2100,200,2400]"))
        assertTrue(selected.contains("region=bottom"))
        assertTrue(selected.contains("selected=true"))
        assertTrue(submit.contains("bounds=[400,1500,800,1900]"))
        assertEquals(listOf("n1", "n2"), result.targetIds)
    }

    @Test fun observedRoleAndFocusRemainExplicitWithoutInventingNavigationSemantics() {
        val result = ModelScreenSummary.render(screen(
            node("n1", "正文", true).put("editable", true).put("role", "input").put("focused", true),
            node("n2", "考勤", true).put("role", "button").put("selected", true).put("focused", false),
            node("n3", "未说明", true),
            node("n4", "秘密", true).put("password", true).put("role", "private-role").put("focused", true),
        ))
        val input = result.text.lineSequence().first { it.startsWith("n1:") }
        val button = result.text.lineSequence().first { it.startsWith("n2:") }
        val unknown = result.text.lineSequence().first { it.startsWith("n3:") }
        assertTrue(input.contains("role=input"))
        assertTrue(input.contains("focused=true"))
        assertTrue(button.contains("role=button"))
        assertTrue(button.contains("focused=false"))
        assertTrue(button.contains("selected=true"))
        assertFalse(button.contains("navigation"))
        assertFalse(unknown.contains("role="))
        assertFalse(unknown.contains("focused="))
        assertFalse(result.text.contains("private-role"))
        assertFalse(result.text.contains("秘密"))
        assertEquals(listOf("n1", "n2", "n3"), result.targetIds)
    }

    @Test fun imageCoordinatesScaleDeviceRectanglesExactlyWithoutMutatingObservation() {
        val value = screen(
            node("n1", "上方", true, listOf(200, 400, 600, 800)),
            node("n2", "中部", true, listOf(200, 1400, 600, 1800)),
            node("n3", "底部", true, listOf(200, 2600, 600, 3000)),
        ).put("width", 1440).put("height", 3200)
        val before = value.toString()
        val result = ModelScreenSummary.render(value, imageWidth = 648, imageHeight = 1440)
        assertTrue(result.text.contains("coordinate_space=image_pixels size=648x1440"))
        assertTrue(result.text.contains("bounds=[90,180,270,360] region=top"))
        assertTrue(result.text.contains("bounds=[90,630,270,810] region=middle"))
        assertTrue(result.text.contains("bounds=[90,1170,270,1350] region=bottom"))
        assertEquals(before, value.toString())
        assertEquals(listOf("n1", "n2", "n3"), result.targetIds)
    }

    @Test fun imageGeometryClipsToImageEdgesAndPreservesSubpixelTargetCoverage() {
        val value = screen(
            node("n1", "跨边界", true, listOf(-2, -3, 1442, 3210)),
            node("n2", "最后像素", true, listOf(1439, 3199, 1440, 3200)),
            node("n3", "屏幕外", true, listOf(1450, 10, 1460, 20)),
            node("n4", "无位置", true).put("bounds", JSONArray(listOf(5, 5, 5, 20))),
        ).put("width", 1440).put("height", 3200)
        val result = ModelScreenSummary.render(value, imageWidth = 648, imageHeight = 1440)
        assertTrue(result.text.contains("bounds=[0,0,648,1440]"))
        assertTrue(result.text.contains("bounds=[647,1439,648,1440]"))
        for (id in listOf("n3", "n4")) {
            val row = result.text.lineSequence().first { it.startsWith("$id:") }
            assertTrue(row.contains("bounds=unknown"))
            assertFalse(row.contains("region="))
        }
    }

    @Test fun incompleteOrInvalidImageSizeNeverMislabelsDeviceCoordinatesAsImagePixels() {
        val value = screen(node("n1", "按钮", true))
        for (dimensions in listOf(648 to null, null to 1440, 0 to 1440, 648 to -1)) {
            val result = ModelScreenSummary.render(value, imageWidth = dimensions.first, imageHeight = dimensions.second)
            assertTrue(result.text.contains("coordinate_space=device_pixels"))
            assertTrue(result.text.contains("image_geometry=unavailable"))
            assertTrue(result.text.contains("bounds=[20,20,120,100]"))
            assertFalse(result.text.contains("coordinate_space=image_pixels"))
        }
    }

    @Test fun smallImmediateParentProvidesBoundedContextWithoutGuessingNavigationRoles() {
        val result = ModelScreenSummary.render(screen(
            node("n0", "工具区", bounds = listOf(0, 0, 300, 300)).put("resource_id", "example:id/quick_tools"),
            node("n0_0", "打开", true, listOf(20, 20, 120, 100)),
            node("n1", "整页背景", bounds = listOf(0, 0, 1080, 2400)),
            node("n1_0", "打开", true, listOf(400, 1600, 800, 1900)),
        ))
        val small = result.text.lineSequence().first { it.startsWith("n0_0:") }
        val full = result.text.lineSequence().first { it.startsWith("n1_0:") }
        assertTrue(small.contains("parent=工具区/quick_tools"))
        assertFalse(full.contains("parent="))
        assertFalse(small.contains("navigation"))
        assertFalse(full.contains("navigation"))
    }

    @Test fun newContextNeverBorrowsPasswordOrEditableInputFields() {
        for (sensitiveFlag in listOf("password", "editable")) {
            val result = ModelScreenSummary.render(screen(
                node("n0", "输入值秘密", bounds = listOf(0, 0, 300, 300)).put(sensitiveFlag, true)
                    .put("resource_id", "example:id/private_input").put("description", "输入描述秘密"),
                node("n0_0", "确认", true).put("checked", false).put("checkable", true),
            ))
            val child = result.text.lineSequence().first { it.startsWith("n0_0:") }
            assertFalse(child.contains("秘密"))
            assertFalse(child.contains("private_input"))
            assertFalse(child.contains("parent="))
            assertTrue(child.contains("checked=false"))
            if (sensitiveFlag == "password") {
                assertFalse(result.text.contains("秘密"))
                assertFalse(result.text.contains("private_input"))
                assertFalse(result.targetIds.contains("n0"))
            }
        }
        val input = ModelScreenSummary.render(screen(
            node("n0", "一般上下文", bounds = listOf(0, 0, 300, 300)).put("resource_id", "example:id/container"),
            node("n0_0", "", true).put("editable", true).put("hint", "不发布的hint").put("input_value", "不发布的value"),
        ))
        val inputRow = input.text.lineSequence().first { it.startsWith("n0_0:") }
        assertFalse(inputRow.contains("parent="))
        assertFalse(input.text.contains("不发布"))
    }

    @Test fun largerGeometricRowsStillExcludeTargetsOmittedByCharacterBudget() {
        val result = ModelScreenSummary.render(screen(*(1..20).map {
            node("n$it", "不同标签$it" + "字".repeat(150), true)
        }.toTypedArray()), maxChars = 1024, imageWidth = 648, imageHeight = 1440)
        assertTrue(result.text.length <= 1024)
        assertTrue(result.truncatedNodes > 0)
        assertEquals(result.shownNodes, result.targetIds.size)
        for (id in result.targetIds) assertTrue(result.text.lineSequence().any { it.startsWith("$id:") && it.contains("bounds=") })
        assertFalse(result.targetIds.contains("n20"))
    }

    @Test fun aSmallTappableWrapperBorrowsOnlyItsSingleNonInteractiveVisibleLabel() {
        val value = screen(node("n0", tap = true), node("n0_0", "蓝牙"))
        val before = value.toString()
        val result = ModelScreenSummary.render(value)
        assertTrue(result.text.contains("n0: 蓝牙 [点]"))
        assertEquals(listOf("n0"), result.targetIds)
        assertEquals(before, value.toString())
    }

    @Test fun aWrapperCannotBorrowAmbiguousPasswordOrInteractiveChildLabels() {
        val children = listOf(
            arrayOf(node("n0_0", "A"), node("n0_1", "B")),
            arrayOf(node("n0_0", "秘密").put("password", true)),
            arrayOf(node("n0_0", "其他按钮", tap = true)),
        )
        for (child in children) {
            val result = ModelScreenSummary.render(screen(node("n0", tap = true), *child))
            assertTrue(result.text.lineSequence().first { it.startsWith("n0:") }.contains("无文字控件"))
            assertFalse(result.text.contains("秘密"))
        }
        val overlay = ModelScreenSummary.render(screen(node("n0", tap = true, bounds = listOf(0, 0, 1080, 2400)), node("n1", "背景文字")))
        assertTrue(overlay.text.lineSequence().first { it.startsWith("n0:") }.contains("无文字控件"))
    }

    @Test fun inheritedLabelRequiresDescendantIdentityNotJustOverlappingBounds() {
        val value=screen(node("n0",tap=true),node("n1","unrelated"))
        val before=value.toString()
        val result=ModelScreenSummary.render(value)
        assertTrue(result.text.lineSequence().first{it.startsWith("n0:")}.contains("无文字控件"))
        assertEquals(before,value.toString())
    }

    @Test fun unknownCheckedStateMustNotBecomeUnchecked() {
        val result = ModelScreenSummary.render(screen(node("n1", "点赞", true).put("checkable", true)))
        assertTrue(result.text.contains("checked=unknown"))
        assertFalse(result.text.contains("checked=false"))
    }

    @Test fun truncationDoesNotAdvertiseUnseenTargetsOrClaimTheWholePage() {
        val result = ModelScreenSummary.render(screen(*(1..10).map { node("n$it", "项目$it", true) }.toTypedArray()), maxNodes = 3)
        assertEquals(listOf("n1", "n2", "n3"), result.targetIds)
        assertEquals(7, result.truncatedNodes)
        assertTrue(result.text.contains("不代表完整页面"))
        assertFalse(result.text.contains("项目4"))
    }

    @Test fun characterBudgetIncludesTruncationNoticeAndStateStaysSingleLine() {
        val result = ModelScreenSummary.render(screen(*(1..20).map { node("n$it", "文字".repeat(150), true)
            .put("state_description", "状态\n  已开启") }.toTypedArray()), maxChars = 1024)
        assertTrue(result.text.length <= 1024)
        assertTrue(result.truncatedNodes > 0)
        assertFalse(result.text.contains("状态\n"))
    }

    @Test fun disabledAndInvisibleElementsNeverEnterTargetEnumeration() {
        val result = ModelScreenSummary.render(screen(node("n1", "删除", true).put("enabled", false),
            node("n2", "屏幕外", true).put("visible", false), node("n3", "下一页", true)))
        assertEquals(listOf("n3"), result.targetIds)
        assertTrue(result.text.contains("删除 [] enabled=false"))
        assertFalse(result.text.contains("屏幕外"))
    }

    @Test fun repeatedClockRowsAndCheckableValuesAreNotDeduplicatedAway() {
        val nodes = (1..4).map { node("n$it", "07:00", true).put("checkable", true).put("checked", it % 2 == 0) }
        val result = ModelScreenSummary.render(screen(*nodes.toTypedArray()))
        assertEquals(4, result.targetIds.size)
        assertEquals(2, Regex("checked=true").findAll(result.text).count())
        assertEquals(2, Regex("checked=false").findAll(result.text).count())
    }
}

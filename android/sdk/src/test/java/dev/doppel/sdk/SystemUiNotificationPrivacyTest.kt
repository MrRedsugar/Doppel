package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SystemUiNotificationPrivacyTest {
    private fun node(id: String, parent: String?, resource: String = "", text: String = "", top: Int = 0, bottom: Int = 50) =
        JSONObject().put("id", id).put("parent_id", parent ?: JSONObject.NULL).put("resource_id", resource)
            .put("text", text).put("bounds", JSONArray(listOf(0, top, 300, bottom)))

    @Test fun splitTitleAndBodyMaskOnlyTheirCardAndAllOfItsExportedChildren() {
        val tree = JSONArray().put(node("root", null, "com.android.systemui:id/notification_stack_scroller", bottom = 1000))
            .put(node("otp", "root", "android:id/status_bar_latest_event_content", top = 100, bottom = 240))
            .put(node("otp-title", "otp", "android:id/title", "登录验证码", 100, 140))
            .put(node("otp-body", "otp", "android:id/text", "246810", 140, 200))
            .put(node("normal", "root", "android:id/status_bar_latest_event_content", top = 300, bottom = 430))
            .put(node("normal-body", "normal", "android:id/text", "Please review this code.", 340, 400))
        val mask = SystemUiNotificationPrivacy.find(tree)
        assertEquals(setOf("otp", "otp-title", "otp-body"), mask.nodeIds)
        assertEquals(listOf(listOf(0, 100, 300, 240)), mask.bounds)
        assertFalse(mask.nodeIds.contains("root"))
        assertFalse(mask.nodeIds.contains("normal-body"))
    }

    @Test fun groupedNotificationsNeverCombineOneCardsCodeDiscussionWithAnotherCardsNumbers() {
        val group = node("group", null, bottom = 1000).put("class_name", "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow")
        val tree = JSONArray().put(group)
            .put(node("left", "group", "android:id/status_bar_latest_event_content", top = 100, bottom = 200))
            .put(node("left-text", "left", text = "登录验证码稍后发送"))
            .put(node("right", "group", "android:id/status_bar_latest_event_content", top = 300, bottom = 400))
            .put(node("right-text", "right", text = "订单 246810"))
        val mask = SystemUiNotificationPrivacy.find(tree)
        assertTrue(mask.nodeIds.isEmpty())
        assertTrue(mask.bounds.isEmpty())
    }

    @Test fun fallbackColumnCombinesFieldsButDoesNotTrustLookalikeApplicationIds() {
        val tree = JSONArray().put(node("column", null, "android:id/notification_main_column", top = 100, bottom = 250))
            .put(node("split", "column", text = "246810").put("description", "登录验证码"))
            .put(node("fake", null, "dev.example:id/notification_main_column", "登录验证码 135790"))
        val mask = SystemUiNotificationPrivacy.find(tree)
        assertEquals(setOf("column", "split"), mask.nodeIds)
        assertEquals(listOf(listOf(0, 100, 300, 250)), mask.bounds)
    }
    @Test fun alreadyHiddenParentDescriptionMustNotLeaveItsUnlabelledCodeChildReadable() {
        val box = listOf(0, 100, 300, 250)
        val tree = JSONArray().put(node("card", null, "android:id/status_bar_latest_event_content", top = 100, bottom = 250))
            .put(node("code-only", "card", text = "246810", top = 150, bottom = 200))
        val mask = SystemUiNotificationPrivacy.find(tree, listOf(box))
        assertEquals(setOf("card", "code-only"), mask.nodeIds)
        assertEquals(listOf(box), mask.bounds)
    }
}

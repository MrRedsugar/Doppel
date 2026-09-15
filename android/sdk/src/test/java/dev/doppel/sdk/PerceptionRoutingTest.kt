package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerceptionRoutingTest {
    private fun node(id: String = "n1", role: String = "button") = JSONObject()
        .put("id", id).put("role", role).put("bounds", JSONArray(listOf(20, 40, 160, 100)))
        .put("enabled", true).put("clickable", true).put("editable", false).put("password", false)
    private fun button(id: String = "n1") = node(id).put("text", "Open")
    private fun input(id: String = "n1") = node(id, "input").put("editable", true)
        .put("resource_id", "example.app:id/editor").put("text", "").put("focused", true)
    private fun screen(vararg nodes: JSONObject) = JSONObject().put("package_name", "example.app")
        .put("width", 400).put("height", 800).put("tree_complete", true).put("nodes", JSONArray(nodes.toList()))

    @Test fun calculatorInputDoesNotForceAnOtherwiseNativePageToPixels() {
        for (value in listOf("", "12", "5,040")) {
            assertFalse(PerceptionRouting.requiresPixels(screen(input().put("text", value), button("n2"))))
        }
    }

    @Test fun aLargeEmptyNativeEditorRemainsAnAvailableSemanticTarget() {
        val editor = input().put("role", "android.widget.EditText")
            .put("bounds", JSONArray(listOf(0, 100, 400, 780)))
        assertFalse(PerceptionRouting.requiresPixels(screen(editor)))
        editor.remove("resource_id")
        assertFalse(PerceptionRouting.requiresPixels(screen(editor)))
    }

    @Test fun unfocusedNativeDialogInputsCanUseTheirResourceSelectors() {
        val name = input().put("focused", false).put("resource_id", "example.app:id/name")
        val extension = input("n2").put("focused", false).put("resource_id", "example.app:id/extension")
        assertFalse(PerceptionRouting.requiresPixels(screen(name, extension)))
    }

    @Test fun aVisibleUnnamedCanvasStillRequiresPixelsBesideNativeEditors() {
        val canvas = node("n3").put("resource_id", "example.app:id/grid")
            .put("bounds", JSONArray(listOf(0, 0, 400, 800)))
        assertTrue(PerceptionRouting.requiresPixels(screen(input(), button("n2"), canvas)))
    }

    @Test fun knownRenderedSurfacesRequirePixelsEvenWithLabelsAndOtherControls() {
        for (role in listOf("android.webkit.WebView", "android.view.SurfaceView", "android.view.TextureView", "custom.Canvas")) {
            assertTrue(role, PerceptionRouting.requiresPixels(screen(button(), node("n2", role).put("text", "Scene"))))
        }
    }

    @Test fun hiddenOrOffscreenSurfacesDoNotOverrideVisibleNativeControls() {
        val surface = node("n2", "android.webkit.WebView").put("visible", false)
        assertFalse(PerceptionRouting.requiresPixels(screen(button(), surface)))
        surface.put("visible", true).put("bounds", JSONArray(listOf(500, 0, 700, 300)))
        assertFalse(PerceptionRouting.requiresPixels(screen(button(), surface)))
    }

    @Test fun truncatedOrIncompleteObservationsDoNotClaimSemanticCoverage() {
        assertTrue(PerceptionRouting.requiresPixels(screen(button()).put("tree_complete", false)))
        assertTrue(PerceptionRouting.requiresPixels(screen(button()).put("truncated", true)))
        assertTrue(PerceptionRouting.requiresPixels(screen(*(0 until 300).map { button("n$it") }.toTypedArray())))
    }

    @Test fun missingDisabledProtectedOrUnresolvableTargetsNeedAnotherObservationChannel() {
        assertTrue(PerceptionRouting.requiresPixels(null))
        assertTrue(PerceptionRouting.requiresPixels(screen()))
        assertTrue(PerceptionRouting.requiresPixels(screen(button().put("enabled", false))))
        assertTrue(PerceptionRouting.requiresPixels(screen(input().put("password", true))))
        assertTrue(PerceptionRouting.requiresPixels(screen(button().put("clickable", false))))
        assertTrue(PerceptionRouting.requiresPixels(screen(button().put("id", "invented"))))
        assertTrue(PerceptionRouting.requiresPixels(screen(button().put("bounds", JSONArray(listOf(0, 0, 0, 20))))))
    }

    @Test fun realScrollDirectionsAndASelectorMakeAListSemanticallyUsable() {
        val list = node(role = "android.widget.ScrollView").put("clickable", false).put("scrollable", true)
            .put("resource_id", "example.app:id/list").put("bounds", JSONArray(listOf(0, 0, 400, 800)))
        assertTrue(PerceptionRouting.requiresPixels(screen(list)))
        list.put("scroll_directions", JSONArray(listOf("down")))
        assertFalse(PerceptionRouting.requiresPixels(screen(list)))
    }

    @Test fun routingDoesNotRewriteOrPersistEditableContents() {
        val page = screen(input().put("text", "synthetic draft"))
        val original = page.toString()
        assertFalse(PerceptionRouting.requiresPixels(page))
        org.junit.Assert.assertEquals(original, page.toString())
    }
}

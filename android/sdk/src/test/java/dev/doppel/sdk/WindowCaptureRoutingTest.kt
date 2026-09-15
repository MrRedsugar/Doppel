package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class WindowCaptureRoutingTest {
    private val target=WindowCaptureRouting.Window(7,true,false,true,true,"game",listOf(0,0,1920,1080))
    private fun route(vararg windows:WindowCaptureRouting.Window)=WindowCaptureRouting.choose(34,1920,1080,7,"game",windows.toList())
    @Test fun observedApplicationIsSelectedRegardlessOfOtherWindows() {
        val extras=listOf(target.copy(id=8,ownOverlay=true,focused=false,active=false,packageName="doppel",bounds=listOf(1600,200,1920,340)),
            target.copy(id=9,application=false,packageName="system",bounds=listOf(0,0,1920,80)),
            target.copy(id=10,application=false,packageName="ime",bounds=listOf(0,600,1920,1080)),
            target.copy(id=11,packageName="other",bounds=listOf(10,10,200,200)),
            target.copy(id=12,application=false,packageName="",bounds=listOf(0,0,1920,1080)),
            target.copy(id=13,bounds=listOf(200,300,1000,800)))
        val selected=route(target,*extras.toTypedArray())
        assertEquals(7,selected.windowId)
        assertEquals(target.bounds,selected.bounds)
        assertEquals("application_window",selected.reason)
    }
    @Test fun activeOrFocusedApplicationRetainsItsActualDisplayBounds() {
        for(value in listOf(target.copy(active=false),target.copy(focused=false),
            target.copy(bounds=listOf(0,80,1920,1080)),target.copy(bounds=listOf(0,0,960,1080)),
            target.copy(bounds=listOf(1400,700,1920,1080),pictureInPicture=true))) {
            val selected=route(value)
            assertEquals(7,selected.windowId)
            assertEquals(value.bounds,selected.bounds)
        }
    }
    @Test fun missingOrUnidentifiedTargetNeverSelectsAnotherApplicationsWindow() {
        for(value in listOf(target.copy(packageName="changed"),target.copy(application=false),target.copy(focused=false,active=false))) {
            assertNull(route(value).windowId)
            assertNull(route(value).bounds)
        }
        assertNull(WindowCaptureRouting.choose(33,1920,1080,7,"game",listOf(target)).windowId)
        assertNull(WindowCaptureRouting.choose(34,1920,1080,7,"",listOf(target.copy(packageName=""))).windowId)
        assertNull(route(target.copy(id=10)).windowId)
        assertNull(route(target,target.copy()).windowId)
        assertNull(WindowCaptureRouting.choose(34,1920,1080,null,"game",listOf(target)).windowId)
    }
    @Test fun invalidOrOutOfDisplayBoundsCannotAuthorizeAPixelMapping() {
        for(bounds in listOf(emptyList(),listOf(0,0,1920),listOf(-1,0,1920,1080),listOf(0,-1,1920,1080),
            listOf(0,0,1921,1080),listOf(0,0,1920,1081),listOf(20,20,20,60),listOf(20,60,40,20))) {
            val selected=route(target.copy(bounds=bounds))
            assertNull(selected.windowId)
            assertNull(selected.bounds)
            assertEquals("invalid_window_bounds",selected.reason)
        }
        assertNull(WindowCaptureRouting.choose(34,0,1080,7,"game",listOf(target)).windowId)
        assertNull(WindowCaptureRouting.choose(34,1920,-1,7,"game",listOf(target)).windowId)
    }
    @Test fun explicitlyObservedNotificationShadeUsesItsOwnWindowAndNeverTheAppBelow() {
        val shade = target.copy(id = 20, application = false, packageName = "com.android.systemui", systemUi = true)
        fun select(value: WindowCaptureRouting.Window) = WindowCaptureRouting.choose(34,1920,1080,20,value.packageName,
            listOf(target.copy(focused = false, active = false), value))
        assertEquals(20, select(shade).windowId)
        assertEquals("system_ui_window", select(shade).reason)
        for (invalid in listOf(shade.copy(focused = false, active = false), shade.copy(ownOverlay = true),
            shade.copy(systemUi = false), shade.copy(packageName = "unrelated.overlay"))) {
            assertNull(select(invalid).windowId)
        }
        assertNull(route(target.copy(ownOverlay = true)).windowId)
    }
}

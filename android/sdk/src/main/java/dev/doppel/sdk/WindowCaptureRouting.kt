package dev.doppel.sdk

/** Capture the observed application window; its bounds let the host map pixels to the display. */
internal object WindowCaptureRouting {
    data class Window(val id:Int,val application:Boolean,val ownOverlay:Boolean,val focused:Boolean,val active:Boolean,
        val packageName:String,val bounds:List<Int>,val pictureInPicture:Boolean=false,val systemUi:Boolean=false)
    data class Route(val windowId:Int?=null,val reason:String,val bounds:List<Int>?=null)
    fun choose(sdk:Int,width:Int,height:Int,windowId:Int?,packageName:String,windows:List<Window>):Route {
        if(sdk<34) return Route(reason="unsupported_sdk")
        if(width<=0 || height<=0) return Route(reason="invalid_display")
        val target=windows.singleOrNull {it.id==windowId} ?: return Route(reason="missing_window")
        val interactiveSystemUi = target.systemUi && target.packageName == "com.android.systemui"
        if(target.ownOverlay || (!target.application && !interactiveSystemUi) || packageName.isBlank() || target.packageName!=packageName || !(target.focused || target.active))
            return Route(reason="ambiguous_window")
        val bounds=target.bounds
        if(bounds.size!=4 || bounds[0]<0 || bounds[1]<0 || bounds[2]>width || bounds[3]>height ||
            bounds[2]<=bounds[0] || bounds[3]<=bounds[1]) return Route(reason="invalid_window_bounds")
        return Route(target.id,if (target.application) "application_window" else "system_ui_window",bounds.toList())
    }
}

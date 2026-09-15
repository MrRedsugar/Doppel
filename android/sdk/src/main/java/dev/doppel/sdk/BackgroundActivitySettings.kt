package dev.doppel.sdk

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast

/** OEM background-window permission is independent of Android overlay permission. */
internal object BackgroundActivitySettings {
    private val isXiaomi: Boolean
        get() = Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) ||
            listOf("xiaomi", "redmi", "poco").any { Build.BRAND.equals(it, ignoreCase = true) }

    val title: String get() = if (isXiaomi) "后台弹出界面" else "后台运行设置"
    val detail: String get() = if (isXiaomi) {
        "小米系统还会限制后台打开窗口，请在权限页检查“后台弹出界面”。"
    } else {
        "部分系统限制后台打开窗口，可在应用详情中检查权限与后台运行设置。"
    }

    fun open(activity: Activity) {
        val targets = mutableListOf<Intent>()
        if (isXiaomi) targets.add(Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", activity.packageName))
        targets.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
        for (intent in targets) {
            try { activity.startActivity(intent); return }
            catch (_: ActivityNotFoundException) { }
            catch (_: SecurityException) { }
        }
        Toast.makeText(activity, "系统未提供此入口，请从系统设置打开本应用的详情页。", Toast.LENGTH_LONG).show()
    }
}

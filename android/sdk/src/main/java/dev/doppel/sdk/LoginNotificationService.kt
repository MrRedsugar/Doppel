package dev.doppel.sdk

import android.app.Notification
import android.content.pm.ApplicationInfo
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class LoginNotificationService : NotificationListenerService() {
    override fun onNotificationPosted(notification: StatusBarNotification) {
        if (!LoginAssist.sensitiveSessionActive()) return
        val foreground = DoppelAccessibilityService.instance?.foregroundPackage().orEmpty()
        val sms = Telephony.Sms.getDefaultSmsPackage(this).orEmpty()
        val fixture = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            foreground == "dev.doppel.testapp" && notification.packageName == foreground
        val trusted = if (fixture) foreground else sms
        if (trusted.isEmpty() || notification.packageName != trusted) return
        val extras = notification.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        LoginAssist.session.receive(notification.packageName, trusted, foreground, "$title $body", notification.postTime)
    }
    override fun onListenerDisconnected() { LoginAssist.clearSession(); super.onListenerDisconnected() }
}

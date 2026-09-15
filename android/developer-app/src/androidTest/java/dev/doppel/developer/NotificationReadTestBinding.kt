@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.app.Instrumentation
import android.content.ComponentName
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import dev.doppel.sdk.LoginAssist
import dev.doppel.sdk.LoginNotificationService
import org.junit.Assert.assertTrue

/** Changes only this app's listener grant and restores it; never replaces other listener settings. */
internal object NotificationReadTestBinding {
    fun connect(inst: Instrumentation): AutoCloseable {
        val context = inst.targetContext
        val granted = LoginAssist(context).notificationAccess()
        val component = ComponentName(context, LoginNotificationService::class.java)
        val flat = component.flattenToString()
        require(flat.matches(Regex("[A-Za-z0-9_.$/]+")))
        fun command(action: String) {
            ParcelFileDescriptor.AutoCloseInputStream(inst.getUiAutomation(1).executeShellCommand("cmd notification $action $flat")).use { it.readBytes() }
        }
        fun restored() = LoginAssist(context).notificationAccess() == granted &&
            (granted || LoginNotificationService.connected == null)
        fun restore() {
            if (!granted) command("disallow_listener")
            val until = SystemClock.elapsedRealtime() + 10000
            while (!restored() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
            assertTrue("The original listener grant and connection state must be restored", restored())
        }
        if (!granted) command("allow_listener")
        NotificationListenerService.requestRebind(component)
        try {
            val until = SystemClock.elapsedRealtime() + 10000
            while ((!LoginAssist(context).notificationAccess() || LoginNotificationService.connected == null) && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
            assertTrue("The actual notification listener must be granted and connected",
                LoginAssist(context).notificationAccess() && LoginNotificationService.connected != null)
        } catch (error: Throwable) {
            try { restore() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
        return AutoCloseable { restore() }
    }
}

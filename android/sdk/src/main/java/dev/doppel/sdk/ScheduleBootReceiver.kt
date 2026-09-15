package dev.doppel.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arm only; boot/time broadcasts never launch a model task or phone action. */
class ScheduleBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        runCatching { ScheduleManager.get(context).arm() }
    }
}

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")

package dev.doppel.developer

import android.app.NotificationManager
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.os.Vibrator
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.TaskCompletionDelivery
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Android notifications/settings in a disposable package; no worker, model or task execution. */
class CompletionNotificationDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val automation get() = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    @Test fun androidAutomaticGroupingDoesNotAddAnotherCompletionSound() {
        assertEquals("Use the disposable queue package only", "dev.doppel.queueqa", context.packageName)
        assertEquals("true", InstrumentationRegistry.getArguments().getString("queue_qa"))
        assertNull(DeviceWorkerService.instance)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(manager.areNotificationsEnabled())
        val prefs = context.getSharedPreferences("doppel", 0)
        check(prefs.edit().putBoolean("completion_speech", false).commit())
        var delivery: TaskCompletionDelivery? = null
        val ids = (0..4).map { "notification-group-${UUID.randomUUID()}" }
        fun alerts() = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("logcat -b events -d -v brief"))
            .bufferedReader().use { reader -> reader.lineSequence().filter { it.contains("notification_alert") && it.contains("|${context.packageName}|") }
                .map { it.substringAfterLast('[').substringBefore(']').split(',') }.filter { it.getOrNull(2) == "1" }.map { it.first() }.toList() }
        val before = alerts().size
        try {
            inst.runOnMainSync { delivery = TaskCompletionDelivery(context) }
            ids.forEach { id ->
                requireNotNull(delivery).deliver(JSONObject().put("id", id).put("status", "completed").put("message", "分组通知隔离验证"))
                assertTrue(await { manager.activeNotifications.any { it.id == id.hashCode() } })
                // Let each full 3.4-second fixture sound finish; this also avoids Android's burst suppression.
                SystemClock.sleep(3600)
            }
            val active = manager.activeNotifications
            val summaries = active.filter { it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0 }
            assertTrue("Five ungrouped children must exercise Android's actual automatic grouping", summaries.isNotEmpty())
            val after = alerts()
            assertEquals("The five children must alert five times, without an audible automatic summary", ids.size, after.size - before)
            ids.forEach { id -> assertEquals(1, after.count { it.contains("|${context.packageName}|${id.hashCode()}|null|") }) }
            val evidence = File(context.getExternalFilesDir(null), "completion-notification").apply { check(isDirectory || mkdirs()) }
            File(evidence, "automatic-grouping.json").writeText(JSONObject().put("passed", true).put("completion_notifications", ids.size)
                .put("automatic_summaries", summaries.size).put("audible_alerts", after.size - before).put("model_requests", 0).toString(2))
        } finally {
            inst.runOnMainSync { delivery?.close() }
            ids.forEach { manager.cancel(it.hashCode()) }
        }
    }

    @Test fun completionUsesBundledSoundAndVibrationWithSystemOwnedControls() {
        assertEquals("Never run this test against either user's app", "dev.doppel.notificationqa", context.packageName)
        assertNull(DeviceWorkerService.instance)
        val prefs = context.getSharedPreferences("doppel", 0)
        assertTrue(prefs.getString("active_run", "").isNullOrBlank())
        assertTrue(prefs.edit().putBoolean("completion_speech", false).commit())
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue("The isolated harness must grant POST_NOTIFICATIONS", manager.areNotificationsEnabled())
        val evidence = File(context.getExternalFilesDir(null), "completion-notification").apply { mkdirs() }
        val checks = JSONObject().put("package", context.packageName).put("model_requests", 0)
        var delivery: TaskCompletionDelivery? = null
        var passed = false
        try {
            TaskCompletionDelivery.registerChannels(context)
            val completed = requireNotNull(manager.getNotificationChannel("task_completed"))
            val sound = Uri.parse("android.resource://${context.packageName}/raw/task_completed")
            assertEquals("任务完成", completed.name.toString())
            assertEquals(NotificationManager.IMPORTANCE_DEFAULT, completed.importance)
            assertEquals(sound, completed.sound)
            assertTrue(completed.shouldVibrate())
            assertEquals(AudioAttributes.USAGE_NOTIFICATION, completed.audioAttributes.usage)
            assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, completed.audioAttributes.contentType)
            assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, manager.getNotificationChannel("results").sound)
            assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, manager.getNotificationChannel("task_pauses").sound)
            assertTrue(context.contentResolver.openInputStream(sound)!!.use { it.read() >= 0 })
            val player = requireNotNull(MediaPlayer.create(context, sound))
            try {
                assertTrue(player.duration > 0)
                checks.put("sound_duration_ms", player.duration)
                // Decode without manually emitting another sound over Android's actual notification.
                player.setVolume(0f, 0f)
                player.start()
                assertTrue("Android must actually start decoding the bundled audio", await { player.currentPosition > 0 })
            } finally { player.release() }
            checks.put("bundled_audio_readable_and_playable", true).put("completion_vibration_enabled", true)
                .put("other_channels_retain_default_sound", true)

            inst.runOnMainSync { delivery = TaskCompletionDelivery(context) }
            for ((status, channel) in listOf("completed" to "task_completed", "failed" to "results", "cancelled" to "results")) {
                val id = "notification-$status-${UUID.randomUUID()}"
                val run = JSONObject().put("id", id).put("status", status).put("message", "通知集成测试：$status")
                requireNotNull(delivery).deliver(run)
                assertTrue("$status must emit a real notification", await { manager.activeNotifications.any { it.id == id.hashCode() } })
                val first = manager.activeNotifications.single { it.id == id.hashCode() }
                assertEquals(channel, first.notification.channelId)
                if (status == "completed") {
                    Thread.sleep(350)
                    ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys notification")).use {
                        val dump = it.bufferedReader().readText()
                        val record = dump.split("NotificationRecord(").firstOrNull { section ->
                            section.substringBefore('\n').contains("id=${id.hashCode()} ") &&
                                section.substringBefore('\n').contains(context.packageName)
                        }.orEmpty().substringBefore("  NotificationRecord(")
                        File(evidence, "completed-notification-record.txt").writeText(record.lineSequence().take(70).joinToString("\n"))
                    }
                }
                assertTrue(prefs.getStringSet("delivered_results", emptySet()).orEmpty().contains(id))
                Thread.sleep(30)
                requireNotNull(delivery).deliver(run)
                inst.waitForIdleSync()
                assertEquals("Duplicate delivery must not post/alert again", first.postTime,
                    manager.activeNotifications.single { it.id == id.hashCode() }.postTime)
                checks.put("${status}_channel", channel).put("${status}_deduplicated", true)
            }
            val pauseId = "notification-paused-${UUID.randomUUID()}"
            val pause = JSONObject().put("id", pauseId).put("status", "paused").put("updated_at", System.currentTimeMillis())
                .put("message", "请手动完成安全验证")
            requireNotNull(delivery).deliverPause(pause)
            assertTrue(await { manager.activeNotifications.any { it.id == ("pause:$pauseId").hashCode() } })
            assertEquals("task_pauses", manager.activeNotifications.single { it.id == ("pause:$pauseId").hashCode() }.notification.channelId)
            checks.put("paused_channel", "task_pauses")
            inst.runOnMainSync { delivery?.dismiss() }

            val settings = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, "task_completed")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val handler = requireNotNull(context.packageManager.resolveActivity(settings, 0))
            context.startActivity(settings)
            assertTrue("Android must open the channel's system settings", await {
                automation.rootInActiveWindow?.packageName?.toString() == handler.activityInfo.packageName
            })
            assertTrue("The system settings must show this completion channel", await {
                nodes(automation.rootInActiveWindow).any { it.text?.toString() == "任务完成" }
            })
            automation.waitForIdle(500, 5000)
            checks.put("system_settings_opened", true)
            screenshot(File(evidence, "system-channel-settings.png"))

            // Only genuine Settings UI mutations represent a user's choice. Do not simulate
            // user changes through app-only channel APIs, which cannot change these fields.
            val hasVibrator = context.getSystemService(Vibrator::class.java).hasVibrator()
            val vibrationChanged = hasVibrator && clickSetting(setOf("Vibration", "Vibrate", "振动", "震动")) &&
                await { !manager.getNotificationChannel("task_completed").shouldVibrate() }
            val silentClicked = clickSetting(setOf("Silent", "静音", "无声", "静默"))
            val silentChanged = silentClicked && await { manager.getNotificationChannel("task_completed").let {
                it.sound == null || it.importance < NotificationManager.IMPORTANCE_DEFAULT
            } }
            val userChoice = manager.getNotificationChannel("task_completed")
            TaskCompletionDelivery.registerChannels(context)
            val registered = manager.getNotificationChannel("task_completed")
            assertEquals(userChoice.sound, registered.sound)
            assertEquals(userChoice.shouldVibrate(), registered.shouldVibrate())
            assertEquals(userChoice.importance, registered.importance)
            checks.put("system_vibration_off_verified", vibrationChanged).put("system_silent_verified", silentChanged)
                .put("registration_preserves_current_settings", true).put("device_has_vibrator", hasVibrator)
            automation.waitForIdle(500, 5000)
            screenshot(File(evidence, "system-channel-settings-after.png"))
            if (hasVibrator) assertTrue("The device's system Vibration toggle must work", vibrationChanged)
            assertTrue("Use the real system Silent option, adapting the selector if this OEM names it differently", silentChanged)
            assertTrue("System channel switch must be usable", clickSetting(setOf("显示通知", "Show notifications", "允许通知", "Allow notifications")))
            assertTrue(await { manager.getNotificationChannel("task_completed").importance == NotificationManager.IMPORTANCE_NONE })
            TaskCompletionDelivery.registerChannels(context)
            assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel("task_completed").importance)
            val blockedId = "notification-blocked-${UUID.randomUUID()}"
            requireNotNull(delivery).deliver(JSONObject().put("id", blockedId).put("status", "completed").put("message", "已关闭完成提醒"))
            inst.waitForIdleSync(); Thread.sleep(500)
            assertTrue(manager.activeNotifications.none { it.id == blockedId.hashCode() })
            assertEquals("Disabling completion must not disable failures", NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel("results").importance)
            checks.put("system_channel_off_blocks_completion_notification", true).put("other_channels_unchanged_after_disable", true)
            assertNull("Notification delivery must never start the worker", DeviceWorkerService.instance)
            passed = true
        } finally {
            inst.runOnMainSync { delivery?.close() }
            manager.cancelAll()
            File(evidence, "report.json").writeText(checks.put("passed", passed).toString(2))
        }
    }

    private fun await(test: () -> Boolean): Boolean {
        val until = SystemClock.uptimeMillis() + 5000
        do { if (test()) return true; Thread.sleep(50) } while (SystemClock.uptimeMillis() < until)
        return test()
    }

    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList()
        else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }

    private fun clickSetting(labels: Set<String>): Boolean {
        var node = nodes(automation.rootInActiveWindow).firstOrNull { it.text?.toString() in labels } ?: return false
        while (!node.isClickable) node = node.parent ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun screenshot(file: File) {
        automation.takeScreenshot()?.let { image ->
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
}

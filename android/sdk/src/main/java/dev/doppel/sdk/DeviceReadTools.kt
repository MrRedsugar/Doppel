package dev.doppel.sdk

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject

/** Bounded native reads; callers supply the current task's cancellation/authority check. */
internal object DeviceReadTools {
    private fun reply(status: String, message: String, data: JSONObject = JSONObject()) =
        JSONObject().put("status", status).put("message", message).put("data", data)

    fun execute(context: Context, kind: String, args: JSONObject, authorized: () -> Boolean = { true }): JSONObject {
        fun cancelled() = !authorized() || Thread.currentThread().isInterrupted
        if (cancelled()) return reply("cancelled", "读取已中断")
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard.isDeviceLocked || keyguard.isKeyguardLocked) return reply("blocked", "请先解锁手机再读取本机内容",
            JSONObject().put("human_takeover", "device_locked"))
        return try {
            val result = when (kind) {
                "read_notifications" -> notifications(context, args)
                "read_calendar" -> calendar(context, args, ::cancelled)
                "read_clipboard" -> ClipboardReadActivity.read(context) { !cancelled() }
                else -> reply("error", "不支持的本机读取工具")
            }
            if (cancelled()) reply("cancelled", "读取已中断") else result
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); reply("cancelled", "读取已中断")
        } catch (_: SecurityException) {
            reply("blocked", "本机读取权限不可用，请检查系统权限", JSONObject().put("human_takeover", "device_read_permission"))
        } catch (_: Exception) {
            if (cancelled()) reply("cancelled", "读取已中断") else reply("error", "本机内容暂时无法读取，请稍后重试")
        }
    }

    private fun notifications(context: Context, args: JSONObject): JSONObject {
        val filter = when (val value = args.opt("package_name")) { null, JSONObject.NULL -> ""; is String -> value.trim(); else -> return reply("error", "通知应用筛选格式无效") }
        if (filter.length > 255 || filter.isNotEmpty() && !filter.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*"))) return reply("error", "通知应用包名无效")
        if (!LoginAssist(context).notificationAccess()) return reply("blocked", "请在系统权限中开启 Doppel 的通知使用权",
            JSONObject().put("human_takeover", "notification_access").put("reason_code", "notification_permission_required"))
        val listener = LoginNotificationService.connected ?: return reply("error", "通知服务尚未连接，请稍后重试",
            JSONObject().put("reason_code", "notification_listener_disconnected"))
        val current = listener.activeNotifications.orEmpty().filter { it.packageName != context.packageName && (filter.isEmpty() || it.packageName == filter) }
            .sortedByDescending { it.postTime }
        val rows = JSONArray()
        for (item in current.take(20)) {
            val extras = item.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
                ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.take(5)?.joinToString("\n").orEmpty()
            val verification = DeviceReadPrivacy.codeNotification(title, body)
            rows.put(JSONObject().put("package_name", item.packageName).put("posted_at", item.postTime)
                .put("title", if (verification) "验证码通知" else LoginAssist.redact(item.packageName, title).take(160))
                .put("text", if (verification) "验证码仅在本机使用，不发送给模型。" else LoginAssist.redact(item.packageName, body).take(800))
                .put("verification_code_hidden", verification))
        }
        return reply("ok", "已读取当前通知", JSONObject().put("items", rows).put("total", current.size)
            .put("truncated", current.size > 20).put("source", "active_notifications")
            .put("package_name", filter)
            .put("login_status", LoginAssist(context).taskStatus(DoppelAccessibilityService.instance?.foregroundPackage().orEmpty(), args.optString("run_id"))))
    }

    private fun calendar(context: Context, args: JSONObject, cancelled: () -> Boolean): JSONObject {
        val days = args.opt("days") as? Number ?: return reply("error", "请指定读取 1 至 31 天的日历")
        if (days.toDouble() != days.toInt().toDouble() || days.toInt() !in 1..31) return reply("error", "日历范围必须为 1 至 31 天")
        val zone = java.time.ZoneId.systemDefault()
        val date = when (val requested = args.opt("start_date")) {
            null, JSONObject.NULL, "" -> java.time.LocalDate.now(zone)
            is String -> if (requested.length != 10) null else runCatching { java.time.LocalDate.parse(requested) }.getOrNull()
            else -> null
        } ?: return reply("error", "日历开始日期格式应为 yyyy-MM-dd")
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            return reply("blocked", "请在系统权限中允许 Doppel 读取日历", JSONObject()
                .put("human_takeover", "calendar_permission").put("reason_code", "calendar_permission_required"))
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val columns = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION)
        val items = JSONArray(); var truncated = false
        CalendarContract.Instances.query(context.contentResolver, columns, start, end).use { cursor ->
            if (cursor == null) return reply("error", "日历服务暂时不可用")
            while (cursor.moveToNext()) {
                if (cancelled()) return reply("cancelled", "日历读取已中断")
                if (items.length() >= 50) { truncated = true; break }
                items.put(JSONObject().put("event_id", cursor.getLong(0))
                    .put("title", LoginAssist.redact("", cursor.getString(1).orEmpty()).take(200))
                    .put("begin", cursor.getLong(2)).put("end", cursor.getLong(3)).put("all_day", cursor.getInt(4) != 0)
                    .put("location", LoginAssist.redact("", cursor.getString(5).orEmpty()).take(200)))
            }
        }
        return reply("ok", "已读取日历", JSONObject().put("items", items).put("from", start)
            .put("until", end).put("start_date", date.toString()).put("timezone", zone.id).put("truncated", truncated))
    }
}

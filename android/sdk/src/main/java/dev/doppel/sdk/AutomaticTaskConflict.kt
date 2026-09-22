package dev.doppel.sdk

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import android.view.View
import android.widget.LinearLayout
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/** Legacy notification compatibility. New automatic tasks enter the shared queue directly. */
internal object AutomaticTaskConflict {
    private const val LIFETIME_MS = 300000L
    private const val CHANNEL = "automatic_task_conflicts"
    private class Pending(val token: String, val expectedRunId: String, val connection: String,
                          val source: JSONObject, val expiresAt: Long) { var claimed = false }
    private val pending = LinkedHashMap<String, Pending>()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { Thread(it, "automatic-task-conflict").apply { isDaemon = true } }
    @Volatile private var lastNotificationWarning = 0L

    private fun notificationsAllowed(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun notificationSettingsView(activity: Activity): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val allowed = notificationsAllowed(activity)
        addView(UiTheme.text(activity, if (allowed) "自动任务提醒已开启。触发的任务会按顺序排队，轮到执行时会提醒。" else
            "请允许通知，以接收自动任务的状态提醒；队列顺序不受通知开关影响。", 13f, UiTheme.muted))
        addView(UiTheme.command(activity, if (allowed) "自动任务通知设置" else "开启自动任务通知") {
            if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 8342)
            else activity.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.packageName))
        })
        if (!allowed) addView(UiTheme.command(activity, "打开系统通知设置") {
            activity.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.packageName))
        })
    }

    fun offerTrigger(context: Context, expectedRunId: String, goal: String, sourceLabel: String,
                     sourceRuleId: String, sourceRuleVersion: String): Boolean = offer(context, expectedRunId,
        JSONObject().put("kind", "trigger").put("goal", goal).put("label", sourceLabel)
            .put("rule_id", sourceRuleId).put("rule_version", sourceRuleVersion))

    fun offerSchedule(context: Context, expectedRunId: String, job: JSONObject): Boolean = offer(context, expectedRunId,
        JSONObject().put("kind", "schedule").put("goal", job.getString("goal"))
            .put("id", job.getString("id")).put("version", ScheduleManager.conflictVersion(job)))

    private fun offer(context: Context, expectedRunId: String, source: JSONObject): Boolean {
        if (expectedRunId.isBlank() || source.optString("goal").isBlank()) return false
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java)
        if (!notificationsAllowed(app)) {
            val now = SystemClock.elapsedRealtime()
            if (lastNotificationWarning == 0L || now - lastNotificationWarning >= 60000L) {
                lastNotificationWarning = now
                main.post { Toast.makeText(app, "请在系统通知设置中允许自动任务提醒", Toast.LENGTH_LONG).show() }
            }
            return false
        }
        val connection = AutomaticTaskNotice.connectionStamp(app)
        val request = synchronized(pending) {
            pending.entries.removeAll { SystemClock.elapsedRealtime() >= it.value.expiresAt }
            pending.values.firstOrNull { it.expectedRunId == expectedRunId && it.connection == connection && it.source.toString() == source.toString() }
                ?.let { return true }
            // Bound memory without silently replacing a different pending user choice.
            if (pending.size >= 24) return false
            Pending(UUID.randomUUID().toString(), expectedRunId, connection, JSONObject(source.toString()), SystemClock.elapsedRealtime() + LIFETIME_MS)
                .also { pending[it.token] = it }
        }
        return try {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "自动任务提醒", NotificationManager.IMPORTANCE_HIGH))
            val action = PendingIntent.getActivity(app, request.token.hashCode(),
                Intent(app, AutomaticTaskConflictActivity::class.java).putExtra("token", request.token)
                    .setAction("dev.doppel.automatic_conflict.${request.token}")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val details = PendingIntent.getActivity(app, request.token.hashCode() xor 0x40000000,
                Intent(app, if (source.optString("kind") == "schedule") ScheduleActivity::class.java else AutoTriggerSettingsActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val message = "已有任务正在执行。点击「加入队列」后，自动任务会按顺序执行。\n${source.optString("goal").take(160)}"
            manager.notify(request.token, 1, Notification.Builder(app, CHANNEL).setSmallIcon(UiIcons.history)
                .setContentTitle("自动任务等待入队")
                .setContentText("加入队列后按顺序执行")
                .setStyle(Notification.BigTextStyle().bigText(message)).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(details).addAction(Notification.Action.Builder(null, "加入队列", action).build())
                .setTimeoutAfter(LIFETIME_MS).setOnlyAlertOnce(true).build())
            main.postDelayed({ synchronized(pending) { if (pending[request.token] === request) pending.remove(request.token) } }, LIFETIME_MS)
            true
        } catch (_: Exception) { synchronized(pending) { pending.remove(request.token) }; false }
    }

    /** Old notification actions now admit their source to FIFO; they never cancel an existing task. */
    fun accept(context: Context, token: String, complete: (Boolean, String) -> Unit) {
        val app = context.applicationContext
        val request = synchronized(pending) {
            pending[token]?.takeUnless { it.claimed }?.also { it.claimed = true }
        }
        if (request == null) { complete(false, "这条提醒已处理或已失效"); return }
        fun finish(accepted: Boolean) {
            synchronized(pending) { pending.remove(token) }
            app.getSystemService(NotificationManager::class.java).cancel(token, 1)
            main.post { complete(accepted, if (accepted) "自动任务将按队列顺序执行" else "提醒已失效，请在自动任务设置中查看") }
        }
        io.execute {
            try {
                if (SystemClock.elapsedRealtime() >= request.expiresAt || request.connection != AutomaticTaskNotice.connectionStamp(app) ||
                    !sourceValid(app, request.source)) { finish(false); return@execute }
                if (request.source.getString("kind") == "schedule") {
                    finish(ScheduleManager.get(app).continueConflict(request.source.getString("id"), request.source.getString("version")))
                } else AutoTriggerTaskLauncher.launch(app, request.source.getString("goal"), onAdmission = ::finish,
                    sourceLabel = request.source.optString("label"), sourceRuleId = request.source.optString("rule_id"),
                    sourceRuleVersion = request.source.optString("rule_version"))
            } catch (_: Exception) { finish(false) }
        }
    }

    private fun sourceValid(context: Context, source: JSONObject): Boolean = if (source.optString("kind") == "schedule")
        ScheduleManager.get(context).isConflictCurrent(source.optString("id"), source.optString("version"))
    else source.optString("rule_id").isBlank() || AutoTriggerStore(context).list().any {
        it.id == source.optString("rule_id") && it.enabled && it.action == "task" && it.taskGoal == source.optString("goal") &&
            it.json().toString() == source.optString("rule_version")
    }
}

/** Compatibility action only admits work; it never changes the currently executing task. */
class AutomaticTaskConflictActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { finish(); return }
        AutomaticTaskConflict.accept(this, intent.getStringExtra("token").orEmpty()) { _, message ->
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
            if (!isDestroyed) finish()
        }
    }
}

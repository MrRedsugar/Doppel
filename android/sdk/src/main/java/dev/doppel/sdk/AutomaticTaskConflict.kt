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
import java.util.concurrent.atomic.AtomicBoolean

/** A visible, expiring user choice; a background trigger never cancels another task. */
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
        addView(UiTheme.text(activity, if (allowed) "自动任务提醒已开启。任务冲突时会发消息，可选择结束当前任务后继续执行。" else
            "请允许通知。否则任务冲突时无法显示「继续执行」按钮，自动任务会保持等待。", 13f, UiTheme.muted))
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
                main.post { Toast.makeText(app, "自动任务被中断；请在定时任务或自动触发设置中开启通知，才能选择继续执行", Toast.LENGTH_LONG).show() }
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
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "自动任务中断提醒", NotificationManager.IMPORTANCE_HIGH))
            val action = PendingIntent.getActivity(app, request.token.hashCode(),
                Intent(app, AutomaticTaskConflictActivity::class.java).putExtra("token", request.token)
                    .setAction("dev.doppel.automatic_conflict.${request.token}")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val details = PendingIntent.getActivity(app, request.token.hashCode() xor 0x40000000,
                Intent(app, if (source.optString("kind") == "schedule") ScheduleActivity::class.java else AutoTriggerSettingsActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val message = "自动任务被中断，请先结束当前任务。点击「继续执行」会结束当前任务，并立即执行这项自动任务。\n${source.optString("goal").take(160)}"
            manager.notify(request.token, 1, Notification.Builder(app, CHANNEL).setSmallIcon(UiIcons.history)
                .setContentTitle("自动任务被中断，请先结束当前任务")
                .setContentText("继续执行会结束当前任务，并立即执行自动任务")
                .setStyle(Notification.BigTextStyle().bigText(message)).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(details).addAction(Notification.Action.Builder(null, "继续执行", action).build())
                .setTimeoutAfter(LIFETIME_MS).setOnlyAlertOnce(true).build())
            main.postDelayed({ synchronized(pending) { if (pending[request.token] === request) pending.remove(request.token) } }, LIFETIME_MS)
            true
        } catch (_: Exception) { synchronized(pending) { pending.remove(request.token) }; false }
    }

    /** The notification carries only a random token; neither it nor another app can supply a task to cancel. */
    fun accept(context: Context, token: String, complete: (Boolean, String) -> Unit) {
        val app = context.applicationContext
        val request = synchronized(pending) { pending[token]?.takeUnless { it.claimed } }
        if (request == null) { complete(false, "这条提醒已处理或已失效"); return }
        if (AutomaticUnlockSession.active) {
            complete(false, "请先长按悬浮窗并验证密码，结束当前任务后再点击继续执行"); return
        }
        if (!TaskSubmissionGate.creating.compareAndSet(false, true)) { complete(false, "正在处理其他任务，请稍后重试"); return }
        val claimed = synchronized(pending) {
            if (pending[token] !== request || request.claimed) false else { request.claimed = true; true }
        }
        if (!claimed) { TaskSubmissionGate.creating.set(false); complete(false, "这条提醒正在处理"); return }
        val gateway = Gateway(app)
        val ticket = TaskControl.currentGeneration()
        val ownsGate = AtomicBoolean(true)
        fun releaseGate() { if (ownsGate.compareAndSet(true, false)) TaskSubmissionGate.creating.set(false) }
        fun finish(success: Boolean, message: String) {
            releaseGate()
            synchronized(pending) { pending.remove(token) }
            app.getSystemService(NotificationManager::class.java).cancel(token, 1)
            main.post { complete(success, message) }
        }
        fun valid() = runCatching { SystemClock.elapsedRealtime() < request.expiresAt &&
            request.connection == AutomaticTaskNotice.connectionStamp(app) && !AutomaticUnlockSession.active &&
            AutomaticTaskNotice.localBlockReason(app, true) == null && sourceValid(app, request.source) }.getOrDefault(false)
        fun dispatch(expectedGeneration: Long) {
            try {
                check(valid() && TaskControl.isCurrent(expectedGeneration)) { "设备或自动任务已变化，请重新确认" }
                val cleared = synchronized(gateway.prefs) {
                    val active = gateway.prefs.getString("active_run", "").orEmpty()
                    if (active.isNotBlank() && active != request.expectedRunId) false else {
                        val edit = gateway.prefs.edit().remove("active_run")
                        if (gateway.prefs.getString("voice_pending_worker_run", "") == request.expectedRunId)
                            edit.remove("voice_pending_worker_run").remove("voice_pending_worker_generation")
                        edit.commit()
                    }
                }
                check(cleared && valid()) { "当前任务已变化，未执行自动任务" }
                // Each real launcher reacquires its own gate and rechecks that no new task won this gap.
                releaseGate()
                val accepted = if (request.source.getString("kind") == "schedule") {
                    ScheduleManager.get(app).continueConflict(request.source.getString("id"), request.source.getString("version"))
                } else AutoTriggerTaskLauncher.launch(app, request.source.getString("goal"), sourceLabel = request.source.optString("label"),
                    sourceRuleId = request.source.optString("rule_id"), sourceRuleVersion = request.source.optString("rule_version"), immediate = true)
                // Dispatchers own any gate they acquire after this point; do not clear theirs in finish().
                synchronized(pending) { pending.remove(token) }
                app.getSystemService(NotificationManager::class.java).cancel(token, 1)
                main.post { complete(accepted, if (accepted) "正在尝试启动自动任务" else "设备状态已变化，自动任务未启动") }
            } catch (error: Exception) { finish(false, error.message ?: "当前任务未确认结束，自动任务未启动") }
        }
        io.execute {
            try {
                check(valid() && TaskControl.isCurrent(ticket)) { "提醒已过期，或设备、规则已变化" }
                val active = gateway.prefs.getString("active_run", "").orEmpty()
                check(active.isBlank() || active == request.expectedRunId) { "当前已是另一项任务，未中断它" }
                val run = gateway.request("GET", "/runs/${request.expectedRunId}")
                check(run.optString("id") == request.expectedRunId) { "任务状态与提醒不匹配" }
                check(valid() && TaskControl.isCurrent(ticket)) { "设备状态已变化，未执行自动任务" }
                if (TaskPresentation.terminal(run.optString("status"))) dispatch(ticket)
                else {
                    check(active == request.expectedRunId) { "无法确认当前任务，自动任务未启动" }
                    main.post {
                        if (!valid() || !TaskControl.isCurrent(ticket) || gateway.prefs.getString("active_run", "") != request.expectedRunId) {
                            finish(false, "当前任务已变化，未中断它"); return@post
                        }
                        TaskControl.request(app, request.expectedRunId, "cancel") { cancelled, error ->
                            if (cancelled == null || cancelled.optString("id") != request.expectedRunId || !TaskPresentation.terminal(cancelled.optString("status")))
                                finish(false, error ?: "当前任务未确认结束，自动任务未启动")
                            else {
                                // Completion of the old run is independent of whether its replacement is still valid.
                                synchronized(gateway.prefs) {
                                    if (request.connection == AutomaticTaskNotice.connectionStamp(app) &&
                                        gateway.prefs.getString("active_run", "") == request.expectedRunId) {
                                        DeviceWorkerService.instance?.acceptEndedRun(cancelled)
                                        val edit = gateway.prefs.edit()
                                        if (gateway.prefs.getString("active_run", "") == request.expectedRunId) edit.remove("active_run")
                                        if (gateway.prefs.getString("voice_pending_worker_run", "") == request.expectedRunId)
                                            edit.remove("voice_pending_worker_run").remove("voice_pending_worker_generation")
                                        edit.commit()
                                        PauseDetails.clear(app, request.expectedRunId)
                                    }
                                }
                                val afterCancel = TaskControl.currentGeneration()
                                io.execute { dispatch(afterCancel) }
                            }
                        }
                    }
                }
            } catch (error: Exception) { finish(false, error.message ?: "暂时无法确认任务状态") }
        }
    }

    private fun sourceValid(context: Context, source: JSONObject): Boolean = if (source.optString("kind") == "schedule")
        ScheduleManager.get(context).isConflictCurrent(source.optString("id"), source.optString("version"))
    else source.optString("rule_id").isBlank() || AutoTriggerStore(context).list().any {
        it.id == source.optString("rule_id") && it.enabled && it.action == "task" && it.taskGoal == source.optString("goal") &&
            it.json().toString() == source.optString("rule_version")
    }
}

/** Internal notification action. System lock must be dismissed by its owner before changing active work. */
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

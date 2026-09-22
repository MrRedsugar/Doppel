package dev.doppel.sdk.cloud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import dev.doppel.sdk.FirstUseConsent

/** Explicit cross-device connection. No boot start, process resurrection, or dataSync keepalive. */
class CloudConnectionService : Service() {
    companion object {
        private const val ENABLE = "dev.doppel.cloud.ENABLE"
        private const val STOP = "dev.doppel.cloud.STOP"
        private const val CHANNEL = "doppel_cloud_connection"
        private const val NOTIFICATION = 4108
        private const val REQUEST = "request_generation"
        private var requestGeneration = 0L
        private var requestedEnabled = false
        @Volatile private var instance: CloudConnectionService? = null
        @Volatile var state: String = "disconnected"
            private set
        private var sharedConnection: CloudPhoneConnection? = null

        // One process-wide owner also lets logout stop account work while the visible connection is off.
        @Synchronized private fun connection(context: Context): CloudPhoneConnection = sharedConnection
            ?: CloudPhoneConnection(context.applicationContext) { value ->
                state = value
                instance?.showState(value)
            }.also { sharedConnection = it }

        /** Called from the account UI's background executor after a successful login. */
        @Synchronized internal fun installSession(context: Context, session: CloudSession) {
            FirstUseConsent.requireAccepted(context)
            CloudSessionStore(context).save(session)
            enable(context)
        }

        @Synchronized fun enable(context: Context) {
            FirstUseConsent.requireAccepted(context)
            val previous = requestGeneration
            val wasEnabled = requestedEnabled
            requestedEnabled = true
            try {
                context.startForegroundService(Intent(context, CloudConnectionService::class.java).setAction(ENABLE)
                    .putExtra(REQUEST, ++requestGeneration))
            } catch (error: Exception) { requestGeneration = previous; requestedEnabled = wasEnabled; throw error }
        }
        @Synchronized fun disable(context: Context) {
            requestedEnabled = false; requestGeneration++
            instance?.generation = requestGeneration
            connection(context).close()
        }
        fun clear(context: Context, expectedSessionId: String, reason: String = "logout") {
            connection(context).clear(expectedSessionId, reason)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var generation = -1L
    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "服务器连接", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { disable(this); return START_NOT_STICKY }
        if (intent?.action != ENABLE) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIFICATION, notification("正在连接服务器"))
        val requested = intent.getLongExtra(REQUEST, -1)
        synchronized(Companion) {
            if (requested == requestGeneration && requestedEnabled) { generation = requested; connection(this).start() }
            else if (!requestedEnabled) stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun showState(value: String) = main.post {
        if (instance !== this || state != value) return@post
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(when (value) {
            "online" -> "已连接，电脑可查看手机状态"
            "connecting" -> "正在连接服务器"
            "signed_out", "session_revoked" -> "登录已失效，请重新登录"
            "stop_unconfirmed" -> "停止结果尚未确认，请在手机核对"
            "storage_unavailable" -> "会话保存失败，请在手机处理"
            else -> "连接不可用，远程控制已暂停"
        }))
        synchronized(Companion) {
            if (generation == requestGeneration && !connection(this).wantsConnection && !connection(this).isStopping &&
                (!requestedEnabled || value in setOf("signed_out", "connection_closed"))) stopSelf()
        }
    }
    private fun notification(message: String): Notification {
        val stop = PendingIntent.getService(this, NOTIFICATION,
            Intent(this, CloudConnectionService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Doppel · 服务器连接").setContentText(message).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "关闭连接", stop).build()).build()
    }
    override fun onDestroy() {
        synchronized(Companion) {
            // A newer foreground start may already be queued while Android destroys the old service.
            if (instance === this) {
                if (generation == requestGeneration) connection(this).close()
                instance = null
            }
        }
        main.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

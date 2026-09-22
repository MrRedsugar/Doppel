package dev.doppel.sdk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import dev.doppel.sdk.companion.CompanionEndpoint
import dev.doppel.sdk.companion.CompanionIdentity
import org.json.JSONObject
import java.util.concurrent.Executors

/** Explicit, visible LAN session. Never restarts itself or starts from a task/status query. */
class SdkCompanionService : Service() {
    companion object {
        const val ENABLE = "dev.doppel.companion.ENABLE"
        const val STOP = "dev.doppel.companion.STOP"
        private const val CHANNEL = "doppel_pc_connection"
        private const val NOTIFICATION = 4107
        @Volatile var instance: SdkCompanionService? = null
            private set
        @Volatile private var lastErrorCode: String? = null

        fun currentState(context: Context): JSONObject = instance?.phoneState()
            ?: CompanionEndpoint.localPairingState(context).put("service_enabled", false)
                .put("discovery_ready", false).put("trusted_lan_presence", false).put("error_code", lastErrorCode ?: JSONObject.NULL)
        fun hasTrustedLanPresence(context: Context): Boolean = runCatching {
            instance?.takeIf { it.packageName == context.packageName }?.endpoint?.hasTrustedLanPresence() == true
        }.getOrDefault(false)
        fun revoke(context: Context, pairId: String) = CompanionEndpoint.revokeLocalPair(context, pairId)

        fun enable(context: Context) {
            FirstUseConsent.requireAccepted(context)
            lastErrorCode = null
            context.startForegroundService(Intent(context, SdkCompanionService::class.java).setAction(ENABLE))
        }
        fun disable(context: Context) { context.stopService(Intent(context, SdkCompanionService::class.java)) }
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var alive = true
    @Volatile private var endpoint: CompanionEndpoint? = null
    @Volatile private var errorCode: String? = null
    @Volatile private var starting = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "PC 连接", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ENABLE) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIFICATION, notification("正在开启局域网连接"))
        if (starting || endpoint != null) return START_NOT_STICKY
        starting = true
        io.execute {
            var candidate: CompanionEndpoint? = null
            try {
                FirstUseConsent.requireAccepted(this)
                if (!alive) return@execute
                candidate = CompanionEndpoint(this, SdkCompanionHost(this, CompanionIdentity.loadOrCreate(this)))
                candidate.start()
                val started = candidate
                main.post {
                    starting = false
                    if (!alive) { started.close(); return@post }
                    endpoint = started
                    monitor.run()
                }
            } catch (_: Exception) {
                candidate?.close()
                errorCode = "service_unavailable"
                lastErrorCode = errorCode
                main.post { starting = false; if (alive) stopSelf() }
            }
        }
        return START_NOT_STICKY
    }

    fun phoneState(): JSONObject = endpoint?.phoneState() ?: CompanionEndpoint.localPairingState(this)
        .put("service_enabled", false).put("discovery_ready", false).put("trusted_lan_presence", false)
        .put("starting", starting).put("error_code", errorCode ?: JSONObject.NULL)

    fun openPairing(): String {
        FirstUseConsent.requireAccepted(this)
        check(!AutomaticUnlockSession.locked(this)) { "请先解锁手机" }
        return checkNotNull(endpoint) { "PC 连接尚未开启" }.openPairing()
    }
    fun decidePairing(requestId: String, approved: Boolean, scopes: Set<String>) {
        if (approved) FirstUseConsent.requireAccepted(this)
        check(!AutomaticUnlockSession.locked(this)) { "请先解锁手机" }
        checkNotNull(endpoint) { "PC 连接尚未开启" }.decidePairing(requestId, approved, scopes)
    }
    fun cancelPairing() { endpoint?.cancelPairing() }
    fun revokePair(pairId: String) { endpoint?.revokePair(pairId) }

    private val monitor = object : Runnable {
        override fun run() {
            if (!alive) return
            val state = endpoint?.phoneState() ?: return
            if (!state.optBoolean("service_enabled")) {
                lastErrorCode = state.optString("error_code").takeUnless { it.isBlank() || it == "null" }
                stopSelf(); return
            }
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION,
                notification(if (state.optBoolean("discovery_ready")) "已开启，已配对电脑可在局域网连接" else "正在发布局域网发现"))
            main.postDelayed(this, 3000)
        }
    }

    private fun notification(message: String): Notification {
        val stop = PendingIntent.getService(this, NOTIFICATION,
            Intent(this, SdkCompanionService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Doppel · PC 连接").setContentText(message).setOngoing(true)
            .setOnlyAlertOnce(true).addAction(Notification.Action.Builder(null, "关闭连接", stop).build()).build()
    }

    override fun onDestroy() {
        alive = false
        main.removeCallbacks(monitor)
        endpoint?.close()
        endpoint = null
        if (instance === this) instance = null
        io.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

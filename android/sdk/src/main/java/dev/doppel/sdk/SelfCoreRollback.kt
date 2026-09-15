package dev.doppel.sdk

import android.content.Context
import android.content.SharedPreferences

/** One-time connection migration for the alpha17 developer build; no task is executed. */
internal object SelfCoreRollback {
    fun changes(values: Map<String, *>, developerBuild: Boolean, hasKey: Boolean): Map<String, Any?> {
        if (!developerBuild || values["artemis_mode"] != true) return emptyMap()
        return linkedMapOf<String, Any?>(
            "artemis_mode" to false,
            "direct_mode" to hasKey,
            "device_id" to if (hasKey) DirectRuntime.DEVICE_ID else (values["server_device_id"] as? String).orEmpty(),
            "self_core_rollback_revision" to 1
        ).apply {
            for (key in listOf("active_run", "conversation_tail", "conversation_scope", "conversation_epoch")) {
                (values[key] as? String)?.let { put("retired_artemis_$key", it) }
                put(key, null)
            }
            put("voice_pending_worker_run", null)
            put("voice_pending_worker_generation", null)
            put("last_result", null)
        }
    }

    fun preferences(context: Context): SharedPreferences {
        val prefs = context.getSharedPreferences("doppel", Context.MODE_PRIVATE)
        synchronized(this) {
            if (prefs.getBoolean("artemis_mode", false)) {
                val changes = changes(prefs.all, DirectMode.available(context), DirectCredentials(context).hasKey())
                if (changes.isNotEmpty()) {
                    val editor = prefs.edit()
                    for ((key, value) in changes) when (value) {
                        null -> editor.remove(key)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is String -> editor.putString(key, value)
                        else -> error("Unsupported rollback preference")
                    }
                    check(editor.commit()) { "未能恢复本机连接，请检查存储空间后重新打开" }
                }
            }
        }
        return prefs
    }
}

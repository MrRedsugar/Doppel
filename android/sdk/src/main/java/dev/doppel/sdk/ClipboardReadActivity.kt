package dev.doppel.sdk

import android.app.Activity
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Android only grants clipboard access to a focused window. This short-lived task returns to the source app. */
internal class ClipboardReadActivity : Activity() {
    private var request: Request? = null
    private var readStarted = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val current = pending.get()?.takeIf { it.id == intent.getStringExtra("request_id") && it.authorized() }
        if (current == null) { finishAndRemoveTask(); return }
        request = current; current.activity = this
        setContentView(TextView(this).apply {
            text = "正在读取剪贴板…"; gravity = Gravity.CENTER; textSize = 17f
            setBackgroundColor(0xff121212.toInt()); setTextColor(0xffeeeeee.toInt())
        })
        mainHandler.postDelayed({
            if (!isFinishing) complete(result("error", "未取得剪贴板读取焦点，请稍后重试"))
        }, 4000)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || readStarted) return
        readStarted = true
        val current = request ?: return
        val keyguard = getSystemService(android.app.KeyguardManager::class.java)
        if (!current.authorized() || pending.get() !== current || keyguard.isDeviceLocked || keyguard.isKeyguardLocked) { complete(result("cancelled", "剪贴板读取已中断")); return }
        val output = try {
            val manager = getSystemService(ClipboardManager::class.java)
            val clip = manager.primaryClip
            if (clip == null || clip.itemCount == 0) result("ok", "剪贴板没有可读取的内容", JSONObject().put("available", false))
            else {
                val text = clip.getItemAt(0).text?.toString()
                val sensitive = clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
                when {
                    text == null -> result("ok", "剪贴板不是文本", JSONObject().put("available", false).put("format", "non_text"))
                    DeviceReadPrivacy.clipboardBlocked(text, sensitive) -> result("ok", "剪贴板含敏感内容，仅可在本机粘贴，不发送给模型",
                        JSONObject().put("available", false).put("sensitive", true))
                    else -> result("ok", "已读取剪贴板", JSONObject().put("available", true)
                        .put("text", LoginAssist.redact("", text).take(4000)).put("truncated", text.length > 4000))
                }
            }
        } catch (_: Exception) { result("error", "剪贴板暂时不可读取") }
        complete(if (current.authorized()) output else result("cancelled", "剪贴板读取已中断"))
    }

    private fun complete(value: JSONObject) {
        request?.let { current -> if (pending.get() === current) current.value.compareAndSet(null, value) }
        finishAndRemoveTask()
    }
    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        request?.let { current -> current.activity = null; current.value.compareAndSet(null, result("cancelled", "剪贴板读取已结束")); current.finished.countDown() }
        request = null
        super.onDestroy()
    }

    private class Request(val authorized: () -> Boolean) {
        val id = UUID.randomUUID().toString()
        val finished = CountDownLatch(1)
        val value = AtomicReference<JSONObject?>()
        @Volatile var activity: ClipboardReadActivity? = null
    }
    companion object {
        private val pending = AtomicReference<Request?>()
        private fun result(status: String, message: String, data: JSONObject = JSONObject()) =
            JSONObject().put("status", status).put("message", message).put("data", data.put("screen_changed", true))

        fun read(context: Context, authorized: () -> Boolean): JSONObject {
            if (Looper.myLooper() == Looper.getMainLooper()) return result("error", "请在任务线程读取剪贴板")
            val current = Request(authorized)
            if (!authorized()) return result("cancelled", "剪贴板读取已中断")
            if (!pending.compareAndSet(null, current)) return result("error", "剪贴板正在读取，请稍后重试")
            try {
                context.startActivity(Intent(context, ClipboardReadActivity::class.java)
                    .putExtra("request_id", current.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (authorized() && SystemClock.elapsedRealtime() < deadline && !current.finished.await(100, TimeUnit.MILLISECONDS)) Unit
                return if (!authorized()) result("cancelled", "剪贴板读取已中断") else if (current.finished.count != 0L) result("error", "剪贴板读取超时，请稍后重试")
                    else current.value.get() ?: result("error", "剪贴板读取未完成")
            } finally {
                pending.compareAndSet(current, null)
                Handler(Looper.getMainLooper()).post { current.activity?.finishAndRemoveTask() }
            }
        }
    }
}

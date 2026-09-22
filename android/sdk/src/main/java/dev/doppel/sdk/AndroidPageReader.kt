package dev.doppel.sdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import okhttp3.HttpUrl
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A private WebView process owns website code/cookies. No model key or app bridge crosses IPC. */
internal class AndroidPageReader(context: Context) : WebPageReader {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val active = AtomicReference<Request?>()

    private class Request {
        val id = UUID.randomUUID().toString()
        val results = LinkedBlockingQueue<Bundle>(1)
        @Volatile var remote: Messenger? = null
        @Volatile var closed = false
    }

    override fun cancel() { active.get()?.let { request ->
        request.closed = true
        runCatching { request.remote?.send(Message.obtain(null, ReaderService.CANCEL).apply {
            data = Bundle().apply { putString("id", request.id) }
        }) }
    } }

    override fun read(url: HttpUrl, format: String, deadline: Long, check: () -> Unit): WebResearchPage {
        require(Looper.myLooper() != Looper.getMainLooper()) { "网页读取不能阻塞界面线程" }
        if (Build.VERSION.SDK_INT < 28) throw WebResearchFailure("reader_unavailable", "本机网页阅读需要 Android 9 或以上版本。")
        val request = Request()
        if (!active.compareAndSet(null, request)) throw WebResearchFailure("busy", "已有网页正在读取。")
        val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (!request.closed && message.data.getString("id") == request.id) request.results.offer(message.data)
            true
        })
        var bound = false // Only accessed on the main looper.
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (request.closed) return
                request.remote = Messenger(binder)
                runCatching { request.remote!!.send(Message.obtain(null, ReaderService.READ).apply {
                    replyTo = reply
                    data = Bundle().apply {
                        putString("id", request.id); putString("url", url.toString())
                        putString("format", format); putLong("deadline", deadline)
                    }
                }) }.onFailure { disconnected() }
            }
            private fun disconnected() { request.results.offer(Bundle().apply {
                putString("error", "reader_disconnected"); putString("message", "网页阅读进程已中断，请重试。")
            }) }
            override fun onServiceDisconnected(name: ComponentName) = disconnected()
            override fun onBindingDied(name: ComponentName) = disconnected()
            override fun onNullBinding(name: ComponentName) = disconnected()
        }
        main.post {
            if (!request.closed) {
                bound = runCatching { context.bindService(Intent(context, ReaderService::class.java), connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
                if (!bound) request.results.offer(Bundle().apply {
                    putString("error", "reader_unavailable"); putString("message", "无法启动本机网页阅读器。")
                })
            }
        }
        try {
            while (true) {
                check()
                if (request.closed) throw WebResearchFailure("cancelled", "网页读取已取消。")
                if (System.nanoTime() >= deadline) throw WebResearchFailure("deadline_exceeded", "网页加载超时。")
                val result = request.results.poll(100, TimeUnit.MILLISECONDS) ?: continue
                check()
                result.getString("error")?.let { throw WebResearchFailure(it, result.getString("message").orEmpty()) }
                return WebResearchPage(url, result.getByteArray("bytes") ?: throw WebResearchFailure("empty_page", "网页没有返回内容。"),
                    result.getString("type") ?: "text/plain")
            }
        } finally {
            request.closed = true
            runCatching { request.remote?.send(Message.obtain(null, ReaderService.CANCEL).apply {
                data = Bundle().apply { putString("id", request.id) }
            }) }
            active.compareAndSet(request, null)
            main.post { if (bound) runCatching { context.unbindService(connection) } }
        }
    }
}

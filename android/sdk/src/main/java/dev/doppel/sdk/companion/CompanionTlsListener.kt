package dev.doppel.sdk.companion

import java.io.Closeable
import java.io.FilterInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Host owns foreground-service lifetime; this listener never starts or restarts itself. */
internal class CompanionTlsListener(context: SSLContext, address: InetAddress, private val http: CompanionHttp) : Closeable {
    private val sockets = ConcurrentHashMap.newKeySet<SSLSocket>()
    private val workers = ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(16),
        { task -> Thread(task, "companion-request").apply { isDaemon = true } })
    private val server = (context.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
        enabledProtocols = supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        reuseAddress = false
        bind(InetSocketAddress(address, 0), 16)
    }
    val port: Int get() = server.localPort

    private val acceptor = Thread({
        while (!server.isClosed) {
            val socket = try { server.accept() as SSLSocket } catch (_: Exception) { break }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            sockets.add(socket)
            try {
                workers.execute {
                    try {
                        socket.use {
                            it.soTimeout = 5000
                            it.startHandshake()
                            val bounded = object : FilterInputStream(it.inputStream) {
                                private fun remaining() {
                                    val millis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                                    if (millis <= 0) throw java.net.SocketTimeoutException("Request deadline")
                                    socket.soTimeout = minOf(5000L, millis).toInt()
                                }
                                override fun read(): Int { remaining(); return super.read() }
                                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                                    remaining(); return `in`.read(bytes, offset, length)
                                }
                            }
                            http.serve(bounded.buffered(), it.outputStream, it.inetAddress)
                        }
                    } catch (_: Exception) {
                        // No request/credential logging; timeout or failed TLS closes this connection.
                    } finally { sockets.remove(socket) }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                sockets.remove(socket)
                socket.close()
            }
        }
    }, "companion-accept").apply { isDaemon = true; start() }

    override fun close() {
        server.close()
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }
}

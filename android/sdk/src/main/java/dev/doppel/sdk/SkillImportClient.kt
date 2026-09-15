package dev.doppel.sdk

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

internal class SkillImportClient(context: Context, private val gateway: Gateway) {
    private val app = context.applicationContext
    private val active = AtomicReference<HttpURLConnection?>()
    @Volatile private var closed = false

    fun upload(uri: Uri): JSONObject {
        FirstUseConsent.requireAccepted(app)
        check(!closed) { "导入已取消" }
        require(uri.scheme == "content") { "请从文件选择器选择 Skills 文件" }
        val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                check(!closed && !Thread.currentThread().isInterrupted) { "导入已取消" }
                FirstUseConsent.requireAccepted(app)
                val count = input.read(buffer)
                if (count < 0) break
                check(count > 0) { "文件读取未完成" }
                require(output.size() + count <= 2 * 1024 * 1024) { "Skills 文件不得超过 2 MB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("文件无法读取")
        val zip = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
            ((bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) || (bytes[2] == 5.toByte() && bytes[3] == 6.toByte()))
        require(zip || bytes.size <= 65536) { "SKILL.md 不得超过 64 KB" }
        if (DirectMode.isEnabled(app)) {
            check(!closed && !Thread.currentThread().isInterrupted) { "导入已取消" }
            FirstUseConsent.requireAccepted(app)
            return DirectSkills(app).importPackage(bytes, if (zip) "zip" else "markdown")
        }
        val base = gateway.prefs.getString("base_url", "http://10.0.2.2:8765")!!.trimEnd('/')
        val endpoint = URI(base)
        require(endpoint.scheme in setOf("http", "https") && endpoint.host != null && endpoint.userInfo == null &&
            endpoint.rawQuery == null && endpoint.rawFragment == null) { "服务地址无效" }
        FirstUseConsent.requireAccepted(app)
        val connection = URI(base + "/v1/skills/import").toURL().openConnection() as HttpURLConnection
        active.set(connection)
        try {
            check(!closed) { "导入已取消" }
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer " + gateway.prefs.getString("token", ""))
            connection.setRequestProperty("Content-Type", if (zip) "application/zip" else "text/markdown")
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            FirstUseConsent.requireAccepted(app)
            connection.outputStream.use { FirstUseConsent.requireAccepted(app); it.write(bytes) }
            val code = connection.responseCode
            check(code == 201) { when (code) {
                401 -> "登录已过期，请重新连接账户"
                409 -> "同名 Skill 已存在，请先移除旧版本"
                413 -> "Skills 文件超过大小限制"
                415, 422 -> "Skills 文件未通过校验，请检查格式、目录和大小"
                else -> "Skills 导入未完成，请检查服务连接"
            } }
            val result = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(count > 0 && output.size() + count <= 32768) { "Skills 响应无效" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return JSONObject(String(result, Charsets.UTF_8))
        } finally {
            active.compareAndSet(connection, null)
            connection.disconnect()
        }
    }

    fun close() {
        closed = true
        val connection = active.getAndSet(null) ?: return
        Thread({ try { connection.disconnect() } catch (_: Exception) { } }, "doppel-skill-close")
            .apply { isDaemon = true; start() }
    }
}

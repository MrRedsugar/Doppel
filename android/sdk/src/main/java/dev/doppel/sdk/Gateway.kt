package dev.doppel.sdk

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

class Gateway(private val context: Context) {
    val prefs = context.getSharedPreferences("doppel", Context.MODE_PRIVATE)
    fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val base = prefs.getString("base_url", "http://10.0.2.2:8765")!!.trimEnd('/')
        val uri = URI(base)
        require(uri.scheme in setOf("http", "https") && uri.userInfo == null && uri.host != null) { "服务地址无效" }
        val connection = URI(base + "/v1" + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10000; connection.readTimeout = 25000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer " + prefs.getString("token", ""))
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException(when(code) { 401 -> "身份验证无效，请检查凭据"; 402 -> "额度不足"; 403 -> "没有操作权限"; 501 -> "服务尚未启用"; else -> "服务请求失败 ($code)" })
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    private fun binaryConnection(path: String): HttpURLConnection {
        val base = prefs.getString("base_url", "http://10.0.2.2:8765")!!.trimEnd('/')
        val uri = URI(base)
        require(uri.scheme in setOf("http", "https") && uri.userInfo == null && uri.host != null) { "服务地址无效" }
        return (URI(base + "/v1" + path).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 30000; instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer " + prefs.getString("token", ""))
        }
    }
    private fun documentName(value: String): String {
        require(value.length in 6..120 && value.endsWith(".xlsx", true) && !value.contains("..") && value.none { it == '/' || it == '\\' || it == '"' || it.code < 32 }) { "仅支持安全命名的 .xlsx 文件" }
        return value
    }
    fun image(path: String): ByteArray {
        val connection = binaryConnection(path)
        try {
            check(connection.responseCode == 200) { "图片不可用 (${connection.responseCode})" }
            require(connection.contentType?.substringBefore(';') in setOf("image/png", "image/jpeg", "image/webp")) { "图片格式不受支持" }
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0
                while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 8 * 1024 * 1024) { "图片过大" }; output.write(buffer, 0, count) }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
    }
    fun clearDocumentCache(name: String? = null) {
        val folder = java.io.File(context.cacheDir, "documents")
        val files = if (name == null) folder.listFiles()?.toList().orEmpty() else listOf(java.io.File(folder, documentName(name)))
        for (file in files) if (file.isFile) {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.documents", file)
            context.revokeUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            check(file.delete()) { "本机文档缓存清理失败" }
        }
    }
    fun uploadDocument(uri: android.net.Uri, name: String): JSONObject {
        val filename = documentName(name)
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0
            while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 20 * 1024 * 1024) { "文件不得超过 20 MB" }; output.write(buffer, 0, count) }
            output.toByteArray()
        } ?: error("文件不可读取")
        val boundary = "Doppel" + java.util.UUID.randomUUID().toString().replace("-", "")
        val connection = binaryConnection("/documents")
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.outputStream.use { out ->
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\nContent-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n".toByteArray())
                out.write(bytes); out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            check(connection.responseCode in 200..299) { "文件上传失败 (${connection.responseCode})" }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    fun openDocument(value: String, packageName: String? = null) {
        val uri = android.net.Uri.parse(value)
        require(uri.scheme == "doppel-document" && uri.query == null && uri.fragment == null) { "文档地址无效" }
        val filename = documentName(uri.authority.orEmpty())
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "文档地址无效" }
        val path = java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
        val connection = binaryConnection("/documents/$path")
        val folder = java.io.File(context.cacheDir, "documents").apply { mkdirs() }
        val target = java.io.File(folder, filename)
        val temporary = java.io.File.createTempFile("download-", ".part", folder)
        try {
            check(connection.responseCode == 200) { "文件下载失败 (${connection.responseCode})" }
            require(connection.contentLengthLong <= 20 * 1024 * 1024) { "文件不得超过 20 MB" }
            connection.inputStream.use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0
                while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 20 * 1024 * 1024) { "文件不得超过 20 MB" }; output.write(buffer, 0, count) }
            } }
            check(temporary.renameTo(target)) { "文件缓存失败" }
        } finally { connection.disconnect(); temporary.delete() }
        val content = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.documents", target)
        val open = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(content, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (packageName != null) {
            require(packageName.matches(Regex("[A-Za-z0-9_.]+"))) { "目标应用无效" }
            open.setPackage(packageName)
            check(open.resolveActivity(context.packageManager) != null) { "指定应用不可打开此文档" }
        } else if (context.packageManager.getLaunchIntentForPackage("cn.wps.moffice_eng") != null) open.setPackage("cn.wps.moffice_eng")
        context.startActivity(open)
    }
}

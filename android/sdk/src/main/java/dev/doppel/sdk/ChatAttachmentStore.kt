package dev.doppel.sdk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Canvas
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Private local copies survive picker grants, conversation changes, and process restarts. */
internal class ChatAttachmentStore(context: Context) {
    private val context = context.applicationContext
    private val directory = File(this.context.noBackupFilesDir, "chat-attachments-v1")

    fun import(uri: Uri): JSONObject = synchronized(lock) {
        require(uri.scheme == "content" || uri.scheme == "file") { "无法读取附件来源，请重新选择文件" }
        require(directory.isDirectory || directory.mkdirs()) { "无法创建附件存储目录" }
        // Uncommitted files can remain after process death; never prune saved attachment directories.
        directory.listFiles()?.filter { it.name.matches(Regex("\\.pending-[0-9a-f-]{36}")) &&
            System.currentTimeMillis() - it.lastModified() > 24L * 60 * 60 * 1000 }?.forEach { it.deleteRecursively() }
        var name = uri.lastPathSegment?.substringAfterLast('/')?.take(180).orEmpty().ifBlank { "附件" }
        var declaredSize = -1L
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { index ->
                        if (!cursor.isNull(index)) name = cursor.getString(index).take(180).ifBlank { name }
                    }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { index ->
                        if (!cursor.isNull(index)) declaredSize = cursor.getLong(index)
                    }
                }
            }
        }
        require(declaredSize <= MAX_FILE_BYTES) { "单个附件不能超过 20 MB" }
        val mime = context.contentResolver.getType(uri).orEmpty().ifBlank { mimeForName(name) }
        val isImage = mime.startsWith("image/") || name.substringAfterLast('.', "").lowercase() in imageExtensions
        // Documents are intentionally not parsed here: the model reads them only when needed.
        if (!isImage) ChatDocumentText.format(name, mime)
        val usedBytes = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        require(usedBytes < MAX_STORE_BYTES) { "附件存储已满，请先删除不需要的附件" }
        val id = UUID.randomUUID().toString()
        val pending = File(directory, ".pending-$id")
        require(pending.mkdir()) { "无法保存附件" }
        try {
            val original = File(pending, "source")
            var copied = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                original.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= MAX_FILE_BYTES) { "单个附件不能超过 20 MB" }
                        require(usedBytes + copied <= MAX_STORE_BYTES) { "附件存储已满，请先删除不需要的附件" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IllegalArgumentException("无法读取附件，请重新选择文件")
            require(copied > 0) { "附件为空，请重新选择文件" }
            if (isImage) {
                normalizeImage(original, File(pending, "image.jpg"))
                require(original.delete()) { "无法完成图片保存" }
            }
            val metadata = JSONObject().put("id", id).put("name", name).put("mime", if (isImage) "image/jpeg" else mime)
                .put("kind", if (isImage) "image" else "document").put("size", copied).put("text_chars", 0)
            File(pending, "metadata.json").writeText(metadata.toString())
            require(usedBytes + pending.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= MAX_STORE_BYTES) { "附件存储已满，请先删除不需要的附件" }
            require(pending.renameTo(File(directory, id))) { "附件保存失败，请重试" }
            metadata
        } catch (error: Throwable) {
            pending.deleteRecursively()
            throw error
        }
    }

    fun readText(id: String): String = synchronized(lock) {
        val folder = folder(id)
        val metadata = metadata(id)
        require(metadata.optString("kind") == "document") { "此附件是图片，请使用图片输入" }
        val cache = File(folder, "text.txt")
        if (cache.isFile) return@synchronized readBoundedText(cache)
        val source = File(folder, "source")
        require(source.isFile && source.length() in 1..MAX_FILE_BYTES) { "附件内容已丢失，请重新发送" }
        try {
            if (ChatDocumentText.format(metadata.getString("name"), metadata.getString("mime")) == "pdf") PDFBoxResourceLoader.init(context)
            val text = ChatDocumentText.extract(source, metadata.getString("name"), metadata.getString("mime"))
            val temporary = File(folder, ".text.pending")
            try {
                val bytes = text.toByteArray(Charsets.UTF_8)
                val used = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                require(used + bytes.size <= MAX_STORE_BYTES) { "附件存储已满，无法保存提取的文字；请先删除不需要的附件" }
                temporary.writeBytes(bytes)
                require(temporary.renameTo(cache)) { "无法缓存附件文字，请重试" }
            } finally { temporary.delete() }
            text
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("无法读取“${metadata.optString("name")}”：文件可能已损坏、加密或不含可读取文字，请另存为无密码文档后重试", error)
        }
    }

    fun imageDataUrl(id: String): String {
        val file = previewFile(id) ?: throw IllegalArgumentException("图片附件已丢失，请重新发送")
        require(file.length() in 1..MAX_IMAGE_BYTES.toLong()) { "图片内容无效，请重新发送" }
        return "data:image/jpeg;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }

    fun previewFile(id: String): File? {
        val info = metadata(id)
        if (info.optString("kind") != "image") return null
        return File(folder(id), "image.jpg").takeIf { it.isFile }
    }

    /** Ignore caller-supplied paths/text; resolve all fields from the private store. */
    fun validate(refs: JSONArray): JSONArray {
        require(refs.length() <= MAX_ATTACHMENTS) { "每条消息最多发送 6 个附件" }
        val ids = HashSet<String>()
        val result = JSONArray()
        var imageBytes = 0L
        for (index in 0 until refs.length()) {
            val id = refs.optJSONObject(index)?.optString("id").orEmpty()
            require(ids.add(id)) { "不能重复发送同一个附件" }
            val info = metadata(id)
            if (info.optString("kind") == "image") {
                val image = File(folder(id), "image.jpg")
                require(image.isFile && image.length() in 1..MAX_IMAGE_BYTES.toLong()) { "图片附件已丢失，请重新发送" }
                imageBytes += image.length()
                require(imageBytes <= MAX_IMAGES_TOTAL_BYTES) { "图片内容过大，请减少本次发送的图片数量" }
            } else {
                val source = File(folder(id), "source")
                require(source.isFile && source.length() in 1..MAX_FILE_BYTES) { "文件附件已丢失，请重新发送" }
                ChatDocumentText.format(info.getString("name"), info.getString("mime"))
            }
            result.put(info)
        }
        return result
    }

    /** Call only after confirming no saved message, task or draft references this ID. */
    fun delete(id: String) = synchronized(lock) { folder(id).deleteRecursively() }

    private fun metadata(id: String): JSONObject {
        val file = File(folder(id), "metadata.json")
        require(file.isFile && file.length() <= 8192) { "附件记录已丢失，请重新发送" }
        val info = try { JSONObject(file.readText()) } catch (error: Exception) { throw IllegalArgumentException("附件记录无效，请重新发送", error) }
        require(info.optString("id") == id && info.optString("kind") in setOf("image", "document")) { "附件记录无效，请重新发送" }
        return info
    }

    private fun folder(id: String): File {
        require(id.matches(idPattern)) { "附件标识无效，请重新发送" }
        return File(directory, id)
    }

    private fun readBoundedText(file: File): String {
        require(file.length() <= ChatDocumentText.MAX_TEXT_CHARS * 4L) { "附件缓存无效，请重新发送" }
        val text = file.readText()
        require(text.isNotBlank() && text.length <= ChatDocumentText.MAX_TEXT_CHARS) { "附件缓存无效，请重新发送" }
        return text
    }

    private fun normalizeImage(source: File, output: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 120_000_000) { "无法读取图片，或图片尺寸过大，请改为普通 JPG/PNG 图片" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_EDGE * 2) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: throw IllegalArgumentException("无法读取图片，请改为 JPG/PNG 后重试")
        var transformed: Bitmap? = null
        var normalized: Bitmap? = null
        try {
            val orientation = runCatching { ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
                val scale = minOf(1f, MAX_IMAGE_EDGE.toFloat() / maxOf(decoded.width, decoded.height))
                postScale(scale, scale)
            }
            transformed = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            normalized = Bitmap.createBitmap(transformed.width, transformed.height, Bitmap.Config.ARGB_8888)
            Canvas(normalized).apply { drawColor(Color.WHITE); drawBitmap(transformed, 0f, 0f, null) }
            output.outputStream().use { stream -> require(normalized.compress(Bitmap.CompressFormat.JPEG, 88, stream)) { "图片转换失败" } }
            require(output.length() <= MAX_IMAGE_BYTES) { "图片过大，请选择较小图片" }
        } finally {
            normalized?.recycle()
            if (transformed != null && transformed !== decoded) transformed.recycle()
            decoded.recycle()
        }
    }

    companion object {
        const val MAX_ATTACHMENTS = 6
        const val supportedDescription = "图片；Word（DOC/DOCX）、PPT（PPT/PPTX）、Excel（XLS/XLSX）、PDF、TXT、Markdown、CSV、JSON、HTML 等文本。文件由 AI 按需读取文字片段；单个 20 MB，最多 6 个，本机最多提取 200 万字符。扫描文档请发送图片。"
        private const val MAX_FILE_BYTES = 20L * 1024 * 1024
        private const val MAX_STORE_BYTES = 256L * 1024 * 1024
        private const val MAX_IMAGE_EDGE = 1600
        private const val MAX_IMAGE_BYTES = 3 * 1024 * 1024
        private const val MAX_IMAGES_TOTAL_BYTES = 12L * 1024 * 1024
        private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp")
        private val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        // ponytail: imports/read caches serialize per process; split locks only if concurrent large reads become measurable.
        private val lock = Any()
        private fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "ppt" -> "application/vnd.ms-powerpoint"
            "xls" -> "application/vnd.ms-excel"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "html", "htm" -> "text/html"
            else -> "application/octet-stream"
        }
    }
}

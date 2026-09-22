package dev.doppel.sdk

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/** System pickers grant access only to files the user selected. Stored drafts contain local references. */
internal class ChatAttachmentsUi(
    private val activity: Activity,
    private val gateway: Gateway,
    private val executor: Executor,
    private val busy: () -> Boolean,
    private val changed: (String?) -> Unit
) {
    companion object {
        private const val PICK = 81
        private const val CAMERA = 82
        private val importing = ConcurrentHashMap.newKeySet<String>()
    }
    private val store = ChatAttachmentStore(activity)
    private var pickedFor = ""
    private var cameraName = ""
    private var draftView: LinearLayout? = null
    private var rendered = ""
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
    private fun key() = gateway.conversationKey()
    private fun draftKey(key: String) = "attachment_draft_$key"
    fun processing() = importing.contains(key())
    fun draft(): JSONArray = JSONArray(gateway.prefs.getString(draftKey(key()), "[]"))
    fun hasDraft() = draft().length() > 0 || processing()
    fun replaceDraft(refs: JSONArray) {
        check(!processing()) { "附件正在导入，请稍候" }
        val previous = draft()
        val valid = store.validate(refs)
        check(gateway.prefs.edit().putString(draftKey(key()), valid.toString()).commit()) { "附件草稿未保存" }
        releaseUnreferenced(previous); refresh()
    }
    fun clearDraft() {
        val previous = draft()
        check(gateway.prefs.edit().remove(draftKey(key())).commit()) { "附件草稿未清除" }
        releaseUnreferenced(previous); refresh()
    }
    private fun releaseUnreferenced(refs: JSONArray) {
        val cleanup = Runnable {
            // A failed task submission may already own the file; preserve any uncertain reference.
            runCatching {
                synchronized(gateway.prefs) {
                    val taskState = DirectRunStateFile(activity.noBackupFilesDir).read().orEmpty()
                    val preferences = gateway.prefs.all.values.filterIsInstance<String>()
                    for (i in 0 until refs.length()) {
                        val id = refs.getJSONObject(i).getString("id")
                        if (id !in taskState && preferences.none { id in it }) store.delete(id)
                    }
                }
            }
        }
        try { executor.execute(cleanup) } catch (_: java.util.concurrent.RejectedExecutionException) { cleanup.run() }
    }
    fun restore(state: Bundle?) {
        pickedFor = state?.getString("attachment_picker_conversation").orEmpty()
        cameraName = state?.getString("attachment_camera_name").orEmpty()
    }
    fun save(state: Bundle) {
        state.putString("attachment_picker_conversation", pickedFor)
        state.putString("attachment_camera_name", cameraName)
    }
    fun attachDraftView(view: LinearLayout) { draftView = view; rendered = ""; refresh() }
    fun detachDraftView() { draftView = null; rendered = "" }
    fun refresh() {
        val view = draftView ?: return
        val refs = draft()
        val stamp = key() + refs.toString() + processing() + busy()
        if (rendered == stamp) return
        rendered = stamp; view.removeAllViews()
        view.visibility = if (refs.length() == 0 && !processing()) View.GONE else View.VISIBLE
        append(view, refs, removable = !busy() && !processing())
        if (processing()) view.addView(UiTheme.text(activity, "正在导入附件…", 12f, UiTheme.muted))
    }
    fun choose() {
        if (busy() || processing()) return
        if (draft().length() >= ChatAttachmentStore.MAX_ATTACHMENTS) { changed("每条消息最多添加 ${ChatAttachmentStore.MAX_ATTACHMENTS} 个附件"); return }
        UiDialog.Builder(activity).setTitle("添加附件").setItems(arrayOf("相机", "相册", "文件")) { _, which ->
            if (busy() || processing()) return@setItems
            pickedFor = key()
            try {
                if (which == 0) {
                    val dir = File(activity.cacheDir, "documents").apply { mkdirs() }
                    cameraName = "chat-camera-${UUID.randomUUID()}.jpg"
                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.documents", File(dir, cameraName))
                    activity.startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        .apply { clipData = ClipData.newRawUri("照片", uri) }, CAMERA)
                } else {
                    activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(if (which == 1) "image/*" else "*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), PICK)
                }
            } catch (_: Exception) { cleanupCamera(); changed("无法打开${arrayOf("相机", "相册", "文件选择器")[which]}，请检查是否安装了可用应用") }
        }.setNegativeButton("取消", null).show()
    }
    private fun cameraFile(): File? = cameraName.takeIf { it.matches(Regex("chat-camera-[a-f0-9-]{36}\\.jpg")) }
        ?.let { File(activity.cacheDir, "documents/$it") }
    private fun cleanupCamera() { cameraFile()?.delete(); cameraName = "" }
    fun result(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != PICK && requestCode != CAMERA) return false
        val captured = if (requestCode == CAMERA) cameraFile() else null
        val forKey = pickedFor
        pickedFor = ""; cameraName = ""
        if (resultCode != Activity.RESULT_OK) { captured?.delete(); return true }
        if (forKey.isBlank()) { captured?.delete(); changed("选择附件的对话已失效，请重新选择"); return true }
        val uris = if (captured != null) listOf(Uri.fromFile(captured)) else {
            val clip = data?.clipData
            if (clip != null) (0 until clip.itemCount).map { clip.getItemAt(it).uri } else listOfNotNull(data?.data)
        }.distinct()
        if (uris.isEmpty()) { captured?.delete(); changed("没有读取到附件，请重新选择"); return true }
        val count = JSONArray(gateway.prefs.getString(draftKey(forKey), "[]")).length()
        if (count + uris.size > ChatAttachmentStore.MAX_ATTACHMENTS) {
            captured?.delete(); changed("每条消息最多添加 ${ChatAttachmentStore.MAX_ATTACHMENTS} 个附件，请减少选择数量"); return true
        }
        if (!importing.add(forKey)) { captured?.delete(); return true }
        refresh(); changed(null)
        executor.execute {
            val errors = mutableListOf<String>()
            try {
                for (uri in uris) {
                    var imported: JSONObject? = null
                    try {
                    val item = store.import(uri).also { imported = it }
                    synchronized(gateway.prefs) {
                        val refs = JSONArray(gateway.prefs.getString(draftKey(forKey), "[]")).put(item)
                        val valid = store.validate(refs)
                        check(gateway.prefs.edit().putString(draftKey(forKey), valid.toString()).commit()) { "附件草稿未保存" }
                    }
                    } catch (error: Exception) {
                        imported?.let { releaseUnreferenced(JSONArray().put(it)) }
                        errors += error.message ?: "附件导入失败"
                    }
                }
            } finally {
                captured?.delete(); importing.remove(forKey)
                activity.runOnUiThread {
                    if (!activity.isDestroyed) { refresh(); changed(errors.distinct().joinToString("；").takeIf { it.isNotEmpty() }) }
                }
            }
        }
        return true
    }
    fun append(parent: LinearLayout, refs: JSONArray?, removable: Boolean = false) {
        if (refs == null) return
        for (i in 0 until refs.length()) {
            val item = refs.optJSONObject(i) ?: continue
            val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(3), 0, dp(3)) }
            val icon = ImageView(activity).apply { setImageResource(UiIcons.files); scaleType = ImageView.ScaleType.CENTER_CROP }
            row.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })
            if (item.optString("kind") == "image") executor.execute {
                val bitmap = runCatching {
                    store.previewFile(item.getString("id"))?.let { file ->
                        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 8 })
                    }
                }.getOrNull()
                activity.runOnUiThread { if (!activity.isDestroyed && icon.isAttachedToWindow) icon.setImageBitmap(bitmap) else bitmap?.recycle() }
            }
            val label = UiTheme.text(activity, item.optString("name") + "\n" +
                if (item.optString("kind") == "image") "图片" else "${(item.optLong("size") / 1024).coerceAtLeast(1)} KB · AI 按需读取", 12f).apply { maxLines = 2 }
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            row.contentDescription = "附件 ${item.optString("name")}"
            row.isFocusable = true; row.setOnClickListener { preview(item) }
            if (removable) row.addView(UiTheme.icon(activity, UiIcons.close, "移除 ${item.optString("name")}") {
                if (busy() || processing()) return@icon
                val current = draft()
                val kept = JSONArray((0 until current.length()).mapNotNull { current.optJSONObject(it) }.filter { it.optString("id") != item.optString("id") })
                if (gateway.prefs.edit().putString(draftKey(key()), kept.toString()).commit()) {
                    releaseUnreferenced(JSONArray().put(item)); refresh(); changed(null)
                } else changed("附件草稿未保存，请重试")
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }
    private fun preview(item: JSONObject) {
        if (item.optString("kind") != "image") {
            UiDialog.Builder(activity).setTitle(item.optString("name")).setMessage("文件已保存在本机。发送后，AI 可按需调用读取工具提取文字；原文件不会交给模型。\n\n" + ChatAttachmentStore.supportedDescription)
                .setPositiveButton("知道了", null).show(); return
        }
        executor.execute {
            val bitmap = runCatching { store.previewFile(item.getString("id"))?.let { BitmapFactory.decodeFile(it.path) } }.getOrNull()
            activity.runOnUiThread {
                if (activity.isDestroyed) { bitmap?.recycle(); return@runOnUiThread }
                if (bitmap == null) { changed("图片暂不可用"); return@runOnUiThread }
                val view = ImageView(activity).apply { setImageBitmap(bitmap); adjustViewBounds = true }
                UiDialog.Builder(activity).setTitle(item.optString("name")).setView(view).setPositiveButton("关闭", null).create().apply {
                    setOnDismissListener { view.setImageDrawable(null); bitmap.recycle() }; show()
                }
            }
        }
    }
}

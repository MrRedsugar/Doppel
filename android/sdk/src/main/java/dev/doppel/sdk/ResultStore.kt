package dev.doppel.sdk

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** Raw results remain only until acknowledgement; IDs survive deletion to prevent replay. */
class ResultStore(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val legacy = app.getSharedPreferences("command_results", Context.MODE_PRIVATE)
    private val helper = object : SQLiteOpenHelper(app, "command_results.db", null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() } }
        override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE results (id TEXT PRIMARY KEY, result TEXT NOT NULL)") }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Unsupported result ledger migration") }
    }
    private val database = helper.writableDatabase
    val ledger = CommandLedger(::read, ::write)
    private fun read(id: String): String? {
        // Screenshot JSON can exceed CursorWindow's per-row limit. Read UTF-8 bytes in
        // small BLOB slices; a transaction keeps all slices from the same stored value.
        // Decoding only after reassembly preserves Unicode across slice boundaries.
        database.beginTransactionNonExclusive()
        try {
            val size = database.rawQuery("SELECT length(CAST(result AS BLOB)) FROM results WHERE id=?", arrayOf(id)).use {
                if (it.moveToFirst()) it.getLong(0) else null
            }
            val raw = if (size == null) legacy.getString(id, null) else {
                check(size in 0..Int.MAX_VALUE.toLong()) { "命令结果大小无效" }
                val bytes = ByteArray(size.toInt())
                var offset = 0
                while (offset < bytes.size) {
                    val count = minOf(64 * 1024, bytes.size - offset)
                    val part = database.rawQuery("SELECT substr(CAST(result AS BLOB),?,?) FROM results WHERE id=?",
                        arrayOf((offset + 1).toString(), count.toString(), id)).use {
                        check(it.moveToFirst()) { "命令结果读取中断" }
                        it.getBlob(0)
                    }
                    check(part.size == count) { "命令结果读取不完整" }
                    part.copyInto(bytes, offset)
                    offset += count
                }
                String(bytes, Charsets.UTF_8)
            }
            database.setTransactionSuccessful()
            return raw
        } finally { database.endTransaction() }
    }
    private fun write(id: String, value: String): Boolean = try {
        database.insertWithOnConflict("results", null, ContentValues().apply { put("id", id); put("result", value) }, SQLiteDatabase.CONFLICT_REPLACE) != -1L
    } catch (_: Exception) { false }
    private fun tombstone(raw: String, erased: Boolean): String {
        val result = JSONObject(raw)
        return JSONObject().put("command_id", result.getString("command_id")).put("run_id", result.getString("run_id"))
            .put("status", "error").put("message", "结果已确认或删除，本机原始数据已清理；禁止重放")
            .put("data", JSONObject().put("acknowledged", true).put("erased", erased)
                .put("result_hash", result.optJSONObject("data")?.optString("result_hash")?.takeIf { it.isNotBlank() } ?: Policy.hash(raw))).toString()
    }
    fun acknowledge(id: String): Boolean {
        val raw = read(id) ?: return true
        return write(id, tombstone(raw, false)) && legacy.edit().remove(id).commit()
    }
    fun erase(runId: String, commandIds: Set<String> = emptySet()) {
        val ids = linkedSetOf<String>()
        database.query("results", arrayOf("id"), null, null, null, null, null).use { cursor -> while (cursor.moveToNext()) ids.add(cursor.getString(0)) }
        legacy.all.forEach { (id, value) -> if (value is String) ids.add(id) }
        for (id in ids) {
            if (commandIds.isNotEmpty() && id !in commandIds) continue
            val raw = read(id) ?: continue
            val result = JSONObject(raw)
            if (result.optString("run_id") == runId) {
                check(write(id, tombstone(raw, true))) { "本机结果清理失败" }
                check(legacy.edit().remove(id).commit()) { "旧结果清理失败" }
            }
        }
    }
    override fun close() { helper.close() }
}

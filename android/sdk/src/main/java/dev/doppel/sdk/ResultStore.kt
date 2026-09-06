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
    private fun read(id: String): String? = database.query("results", arrayOf("result"), "id=?", arrayOf(id), null, null, null).use { if (it.moveToFirst()) it.getString(0) else legacy.getString(id, null) }
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
        val rows = mutableMapOf<String, String>()
        database.query("results", arrayOf("id", "result"), null, null, null, null, null).use { cursor -> while (cursor.moveToNext()) rows[cursor.getString(0)] = cursor.getString(1) }
        legacy.all.forEach { (id, value) -> if (value is String) rows.putIfAbsent(id, value) }
        for ((id, raw) in rows) {
            val result = JSONObject(raw)
            if (result.optString("run_id") == runId && (commandIds.isEmpty() || id in commandIds)) {
                check(write(id, tombstone(raw, true))) { "本机结果清理失败" }
                check(legacy.edit().remove(id).commit()) { "旧结果清理失败" }
            }
        }
    }
    override fun close() { helper.close() }
}

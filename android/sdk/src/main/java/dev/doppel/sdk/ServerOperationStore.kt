package dev.doppel.sdk

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.Closeable
import java.io.File

/** Durable deduplication and metadata-only receipts. No task bodies, credentials or execution leases. */
internal class ServerOperationStore(context: Context) : Closeable {
    private val app = context.applicationContext
    private var closed = false
    private val helper = object : SQLiteOpenHelper(app,
        File(app.noBackupFilesDir, "server-operations-v1.db").absolutePath, null, 1,
        DatabaseErrorHandler { throw SQLiteDatabaseCorruptException("Server operation ledger is corrupt; refusing to discard replay protection") }) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
            db.execSQL("PRAGMA synchronous=FULL")
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE operations (
                account_id TEXT NOT NULL, session_id TEXT NOT NULL, operation_id TEXT NOT NULL,
                kind TEXT NOT NULL, payload_hash TEXT NOT NULL, task_id TEXT,
                PRIMARY KEY(account_id, operation_id))""")
            db.execSQL("""CREATE TABLE receipts (
                account_id TEXT NOT NULL, operation_id TEXT NOT NULL, receipt_seq INTEGER NOT NULL CHECK(receipt_seq>0),
                receipt_json TEXT NOT NULL, acknowledged INTEGER NOT NULL DEFAULT 0 CHECK(acknowledged IN (0,1)),
                PRIMARY KEY(account_id, operation_id, receipt_seq),
                FOREIGN KEY(account_id, operation_id) REFERENCES operations(account_id, operation_id))""")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("Unsupported server operation ledger migration")
        }
    }

    @Synchronized fun claim(accountId: String, sessionId: String, operationId: String, kind: String, payloadHash: String): Boolean {
        ids(accountId, sessionId, operationId)
        require(kind in setOf("create_task", "control_task")) { "Invalid operation kind" }
        require(payloadHash.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid payload SHA256" }
        val hash = payloadHash.lowercase()
        return transaction { db ->
            val existing = db.rawQuery("SELECT session_id, kind, payload_hash FROM operations WHERE account_id=? AND operation_id=?",
                arrayOf(accountId, operationId)).use {
                if (!it.moveToFirst()) false else {
                    require(it.getString(0) == sessionId && it.getString(1) == kind && it.getString(2) == hash) {
                        "Operation identity or payload conflict"
                    }
                    true
                }
            }
            if (existing) false else {
                db.insertOrThrow("operations", null, ContentValues().apply {
                    put("account_id", accountId); put("session_id", sessionId); put("operation_id", operationId)
                    put("kind", kind); put("payload_hash", hash)
                })
                true
            }
        }
    }

    @Synchronized fun find(accountId: String, sessionId: String, operationId: String): JSONObject? {
        ids(accountId, sessionId, operationId)
        return database().rawQuery("$RECORD_SELECT WHERE o.account_id=? AND o.session_id=? AND o.operation_id=?",
            arrayOf(accountId, sessionId, operationId)).use { if (it.moveToFirst()) record(it) else null }
    }

    @Synchronized fun attachTask(accountId: String, sessionId: String, operationId: String, taskId: String) {
        ids(accountId, sessionId, operationId, taskId)
        transaction { db ->
            val attached = requireOperation(db, accountId, sessionId, operationId)
            require(attached == null || attached == taskId) { "Operation already belongs to a different task" }
            check(db.update("operations", ContentValues().apply { put("task_id", taskId) },
                "account_id=? AND session_id=? AND operation_id=?", arrayOf(accountId, sessionId, operationId)) == 1)
        }
    }

    /** Only immutable protocol metadata is accepted; caller-supplied envelope identity is overwritten. */
    @Synchronized fun appendReceipt(accountId: String, sessionId: String, operationId: String, receipt: JSONObject): JSONObject {
        ids(accountId, sessionId, operationId)
        val value = JSONObject(receipt.toString())
        require(value.keys().asSequence().all { it in RECEIPT_KEYS }) { "Receipt contains non-metadata fields" }
        require(value.opt("phase") in PHASES) { "Invalid receipt phase" }
        if (!value.isNull("task_id")) ids(value.get("task_id") as? String ?: error("Invalid receipt task ID"))
        require(value.isNull("task_status") || value.opt("task_status") in TASK_STATES) { "Invalid receipt task state" }
        if (!value.isNull("task_revision")) require(value.opt("task_revision") is Number &&
            value.get("task_revision").toString().toLongOrNull()?.let { it >= 0 } == true) { "Invalid task revision" }
        require(value.isNull("error_code") || (value.opt("error_code") as? String)?.matches(Regex("[a-z][a-z0-9_]{0,63}")) == true) {
            "Invalid receipt error code"
        }
        return transaction { db ->
            val taskId = requireOperation(db, accountId, sessionId, operationId)
            require(value.isNull("task_id") || value.getString("task_id") == taskId) { "Attach task before publishing its receipt" }
            val previous = db.rawQuery("SELECT COALESCE(MAX(receipt_seq),0) FROM receipts WHERE account_id=? AND operation_id=?",
                arrayOf(accountId, operationId)).use { check(it.moveToFirst()); it.getLong(0) }
            check(previous < Long.MAX_VALUE) { "Receipt sequence exhausted" }
            val seq = previous + 1
            value.put("type", "receipt").put("session_id", sessionId).put("operation_id", operationId).put("receipt_seq", seq)
                .put("task_id", taskId ?: JSONObject.NULL)
            for (key in listOf("task_status", "task_revision", "error_code")) if (!value.has(key)) value.put(key, JSONObject.NULL)
            db.insertOrThrow("receipts", null, ContentValues().apply {
                put("account_id", accountId); put("operation_id", operationId); put("receipt_seq", seq); put("receipt_json", value.toString())
            })
            JSONObject(value.toString())
        }
    }

    @Synchronized fun pendingReceipts(accountId: String, sessionId: String): List<JSONObject> {
        ids(accountId, sessionId)
        return database().rawQuery("""SELECT r.receipt_json FROM receipts r JOIN operations o
            ON r.account_id=o.account_id AND r.operation_id=o.operation_id
            WHERE o.account_id=? AND o.session_id=? AND r.acknowledged=0 ORDER BY r.rowid""",
            arrayOf(accountId, sessionId)).use { cursor -> buildList { while (cursor.moveToNext()) add(JSONObject(cursor.getString(0))) } }
    }

    /** An old/duplicate ACK marks only its exact stored sequence; receipt evidence is never deleted. */
    @Synchronized fun acknowledgeReceipt(accountId: String, sessionId: String, operationId: String, seq: Long): Boolean {
        ids(accountId, sessionId, operationId)
        require(seq > 0) { "Invalid receipt sequence" }
        return database().update("receipts", ContentValues().apply { put("acknowledged", 1) },
            """account_id=? AND operation_id=? AND receipt_seq=? AND EXISTS
                (SELECT 1 FROM operations o WHERE o.account_id=receipts.account_id AND o.operation_id=receipts.operation_id AND o.session_id=?)""",
            arrayOf(accountId, operationId, seq.toString(), sessionId)) == 1
    }

    @Synchronized fun recordsForSession(accountId: String, sessionId: String): List<JSONObject> {
        ids(accountId, sessionId)
        return database().rawQuery("$RECORD_SELECT WHERE o.account_id=? AND o.session_id=? ORDER BY o.rowid",
            arrayOf(accountId, sessionId)).use { cursor -> buildList { while (cursor.moveToNext()) add(record(cursor)) } }
    }

    @Synchronized override fun close() { helper.close(); closed = true }

    private fun database(): SQLiteDatabase { check(!closed) { "Operation ledger is closed" }; return helper.writableDatabase }
    private fun <T> transaction(action: (SQLiteDatabase) -> T): T {
        // ponytail: one device serializes its short local writes; no caller/network work runs in this transaction.
        val db = database()
        db.beginTransaction()
        try { return action(db).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }

    private fun requireOperation(db: SQLiteDatabase, accountId: String, sessionId: String, operationId: String): String? =
        db.rawQuery("SELECT task_id FROM operations WHERE account_id=? AND session_id=? AND operation_id=?",
            arrayOf(accountId, sessionId, operationId)).use {
            check(it.moveToFirst()) { "Operation is not claimed by this session" }
            if (it.isNull(0)) null else it.getString(0)
        }

    private fun record(cursor: Cursor): JSONObject = JSONObject().put("account_id", cursor.getString(0))
        .put("session_id", cursor.getString(1)).put("operation_id", cursor.getString(2)).put("kind", cursor.getString(3))
        .put("payload_hash", cursor.getString(4)).put("task_id", if (cursor.isNull(5)) JSONObject.NULL else cursor.getString(5))
        .put("latest_receipt", if (cursor.isNull(6)) JSONObject.NULL else JSONObject(cursor.getString(6)))

    private fun ids(vararg values: String) {
        require(values.all { it.length in 1..128 && it.all { c -> c.code in 33..126 } }) { "Invalid operation identifier" }
    }

    companion object {
        private const val RECORD_SELECT = """SELECT o.account_id,o.session_id,o.operation_id,o.kind,o.payload_hash,o.task_id,
            (SELECT r.receipt_json FROM receipts r WHERE r.account_id=o.account_id AND r.operation_id=o.operation_id ORDER BY r.receipt_seq DESC LIMIT 1)
            FROM operations o"""
        private val RECEIPT_KEYS = setOf("type", "session_id", "operation_id", "receipt_seq", "phase", "task_id", "task_status", "task_revision", "error_code")
        private val PHASES = setOf("accepted_by_phone", "control_applied", "completed", "failed", "cancelled", "rejected", "outcome_unknown")
        private val TASK_STATES = setOf("queued", "running", "paused", "awaiting_approval", "awaiting_input", "completed", "failed", "cancelled")
    }
}

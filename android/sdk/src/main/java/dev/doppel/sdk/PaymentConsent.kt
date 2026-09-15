package dev.doppel.sdk

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

class PaymentConsent(context: Context) {
    companion object {
        private val lock = Any()
        private val settingsOwners = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        private var sharedGate: PaymentConsentGate? = null
        val settingsVisible: Boolean get() = synchronized(lock) { settingsOwners.isNotEmpty() }
        internal fun enterSettings(owner: Any) {
            val changed = synchronized(lock) { settingsOwners.add(owner) }
            if (changed) DeviceWorkerService.instance?.paymentSettingsVisibilityChanged()
        }
        internal fun leaveSettings(owner: Any) {
            val changed = synchronized(lock) { settingsOwners.remove(owner) }
            if (changed) DeviceWorkerService.instance?.paymentSettingsVisibilityChanged()
        }
    }
    private val gate = synchronized(lock) {
        val app = context.applicationContext
        sharedGate ?: PaymentConsentGate(PaymentConsentStorageWithGrant(PaymentConsentDatabase(app), PaymentConsentFile(app.noBackupFilesDir)), lock, { settingsVisible },
            { "payment-v1:" + UUID.randomUUID().toString() }, System::currentTimeMillis).also { sharedGate = it }
    }
    fun currentId(): String? = gate.currentId()
    fun isEnabledForSettings(): Boolean = gate.isEnabledForSettings()
    internal fun hasStorageFailure(): Boolean = gate.hasStorageFailure()
    fun disable(): Boolean = gate.disable()
    internal fun enable(flow: PaymentConsentFlow): Boolean = gate.enable(flow)
    fun runPayment(expectedId: String, runId: String, fingerprint: String, action: () -> Boolean): PaymentAttempt =
        gate.runPayment(expectedId, runId, fingerprint, action)
}

private class PaymentConsentFile(private val directory: File) : PaymentConsentGrant {
    private val active = File(directory, "payment-active-grant")
    private val pending = File(directory, "payment-active-grant.new")
    private fun syncDirectory() {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }
    override fun read(): String? {
        if (!active.exists()) return null
        if (active.length() !in 1..128) return null
        return active.readText(Charsets.UTF_8)
    }
    override fun clear(): Boolean {
        val removed = listOf(active, pending).map { !it.exists() || it.delete() }.all { it }
        syncDirectory()
        return removed && !active.exists() && !pending.exists()
    }
    override fun write(id: String): Boolean {
        FileOutputStream(pending).use { output -> output.write(id.toByteArray(Charsets.UTF_8)); output.flush(); output.fd.sync() }
        Files.move(pending.toPath(), active.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory()
        return read() == id
    }
}

private class PaymentConsentDatabase(app: Context) : PaymentConsentStorage {
    private val helper = object : SQLiteOpenHelper(app, File(app.noBackupFilesDir, "payment-consent.db").absolutePath, null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE consent (slot INTEGER PRIMARY KEY CHECK(slot=1), version INTEGER NOT NULL, consent_id TEXT, granted_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE attempts (fingerprint TEXT PRIMARY KEY, attempted_at INTEGER NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Unsupported payment consent storage version") }
    }
    override fun readConsent(): PaymentConsentRecord? = helper.readableDatabase.query("consent", arrayOf("version", "consent_id", "granted_at"), "slot=1", null, null, null, null).use {
        if (!it.moveToFirst()) null else PaymentConsentRecord(it.getInt(0), if (it.isNull(1)) null else it.getString(1), it.getLong(2))
    }
    override fun writeConsent(value: PaymentConsentRecord): Boolean = helper.writableDatabase.insertWithOnConflict("consent", null, ContentValues().apply {
        put("slot", 1); put("version", value.version); put("consent_id", value.id); put("granted_at", value.grantedAt)
    }, SQLiteDatabase.CONFLICT_REPLACE) != -1L
    override fun hasAttempt(key: String): Boolean = helper.readableDatabase.query("attempts", arrayOf("fingerprint"), "fingerprint=?", arrayOf(key), null, null, null).use { it.moveToFirst() }
    override fun claimAttempt(key: String, at: Long): Boolean = helper.writableDatabase.insertOrThrow("attempts", null, ContentValues().apply {
        put("fingerprint", key); put("attempted_at", at)
    }) != -1L
}

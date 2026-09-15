package dev.doppel.developer

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.Policy
import dev.doppel.sdk.ResultStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Default device regression using real SQLite; independent of game, model and user data. */
class ResultStoreDeviceTest {
    private class IsolatedStoreContext(base: Context) : ContextWrapper(base) {
        private val prefix = "result-store-device-${UUID.randomUUID()}-"
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = baseContext.getDatabasePath(prefix + name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            baseContext.openOrCreateDatabase(prefix + name, mode, factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase =
            baseContext.openOrCreateDatabase(prefix + name, mode, factory, handler)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = baseContext.getSharedPreferences(prefix + name, mode)
        fun clean() {
            baseContext.deleteDatabase(prefix + "command_results.db")
            baseContext.deleteSharedPreferences(prefix + "command_results")
        }
    }

    private fun withStoreContext(test: (IsolatedStoreContext) -> Unit) {
        val context = IsolatedStoreContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try { test(context) } finally { context.clean() }
    }

    private fun result(id: String, run: String, payload: String): String = JSONObject()
        .put("command_id", id).put("run_id", run).put("status", "ok")
        .put("data", JSONObject().put("image_base64", payload).put("label", "截图结果🙂")).toString()

    private fun largePayload(): String = buildString(4 * 1024 * 1024 + 256) {
        // Vary marker alignment so multi-byte text crosses at least one 64 KiB BLOB boundary.
        repeat(64) { index -> append("a".repeat(65520 + index % 4)); append("中🙂é"); append(index) }
        append("z".repeat(4096))
    }

    private fun assertTombstone(store: ResultStore, id: String, run: String, originalHash: String, erased: Boolean) {
        val saved = requireNotNull(store.ledger.cached(id))
        assertTrue("Large raw screenshot must be gone after acknowledgement/deletion", saved.length < 2048)
        val value = JSONObject(saved)
        assertEquals(id, value.getString("command_id"))
        assertEquals(run, value.getString("run_id"))
        val data = value.getJSONObject("data")
        assertTrue(data.getBoolean("acknowledged"))
        assertEquals(erased, data.getBoolean("erased"))
        assertEquals(originalHash, data.getString("result_hash"))
        assertFalse("A tombstone must still prohibit replay", store.ledger.claim(id, "must-not-replay"))
    }

    @Test fun existingLargeSqliteReceiptRetransmitsExactlyAndAcknowledgesIdempotently() = withStoreContext { context ->
        val id = "existing-large"
        val run = "existing-run"
        val raw = result(id, run, largePayload())
        assertTrue(raw.toByteArray(Charsets.UTF_8).size > 4 * 1024 * 1024)
        val hash = Policy.hash(raw)
        // Seed a v1 row directly, proving existing installations need no migration.
        context.openOrCreateDatabase("command_results.db", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE results (id TEXT PRIMARY KEY, result TEXT NOT NULL)")
            db.version = 1
            assertTrue(db.insert("results", null, ContentValues().apply { put("id", id); put("result", raw) }) != -1L)
        }
        ResultStore(context).use { store ->
            assertEquals("Retry must receive the exact original bytes as text", raw, store.ledger.cached(id))
            assertFalse(store.ledger.claim(id, "must-not-replay"))
            assertTrue(store.acknowledge(id))
            assertTombstone(store, id, run, hash, false)
            val first = store.ledger.cached(id)
            assertTrue(store.acknowledge(id))
            assertEquals(first, store.ledger.cached(id))
        }
        ResultStore(context).use { assertTombstone(it, id, run, hash, false) }
    }

    @Test fun eraseHandlesLargeRowsSelectivelyAndPreservesHashesAndOtherRuns() = withStoreContext { context ->
        val large = largePayload()
        val rows = linkedMapOf(
            "large-one" to result("large-one", "erase-run", large),
            "large-two" to result("large-two", "erase-run", large + "tail"),
            "other-run" to result("other-run", "keep-run", large),
            "small" to result("small", "erase-run", "small-receipt")
        )
        ResultStore(context).use { store ->
            for ((id, raw) in rows) {
                assertTrue(store.ledger.claim(id, result(id, "pending", "uncertain")))
                assertTrue(store.ledger.finish(id, raw))
                assertEquals(raw, store.ledger.cached(id))
            }
            store.erase("erase-run", setOf("large-one"))
            assertTombstone(store, "large-one", "erase-run", Policy.hash(rows.getValue("large-one")), true)
            assertEquals(rows["large-two"], store.ledger.cached("large-two"))
            assertEquals(rows["other-run"], store.ledger.cached("other-run"))
            assertEquals(rows["small"], store.ledger.cached("small"))
            store.erase("erase-run")
            val first = store.ledger.cached("large-one")
            store.erase("erase-run")
            assertEquals(first, store.ledger.cached("large-one"))
            for (id in listOf("large-one", "large-two", "small"))
                assertTombstone(store, id, "erase-run", Policy.hash(rows.getValue(id)), true)
            assertEquals(rows["other-run"], store.ledger.cached("other-run"))
            assertTrue(store.acknowledge("missing"))
        }
    }

    @Test fun smallAndLegacyReceiptsKeepExactReplayAndCleanupBehavior() = withStoreContext { context ->
        val legacy = context.getSharedPreferences("command_results", Context.MODE_PRIVATE)
        val old = result("legacy-large", "legacy-run", largePayload())
        assertTrue(legacy.edit().putString("legacy-large", old).commit())
        val small = result("new-small", "small-run", "123+456=579")
        ResultStore(context).use { store ->
            assertNull(store.ledger.cached("new-small"))
            assertTrue(store.ledger.claim("new-small", small))
            assertEquals(small, store.ledger.cached("new-small"))
            assertTrue(store.acknowledge("new-small"))
            assertTombstone(store, "new-small", "small-run", Policy.hash(small), false)
            assertEquals(old, store.ledger.cached("legacy-large"))
            store.erase("legacy-run")
            assertFalse(legacy.contains("legacy-large"))
            assertTombstone(store, "legacy-large", "legacy-run", Policy.hash(old), true)
        }
    }
}

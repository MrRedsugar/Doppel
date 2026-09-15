package dev.doppel.developer

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.AutoTriggerRule
import dev.doppel.sdk.AutoTriggerStore
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real isolated preferences; no user rules, model requests or device actions. */
class AutoTriggerStoreDeviceTest {
    @Test fun unchangedRulesAreReusedButOtherWritersRemainVisibleAndCannotLoseUpdates() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "trigger-store-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(ignored: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        val prefs = context.getSharedPreferences("unused", 0)
        val first = AutoTriggerStore(context)
        val second = AutoTriggerStore(context)
        val pool = Executors.newFixedThreadPool(2)
        fun rule(id: String) = AutoTriggerRule(id = id, packageName = "dev.doppel.fixture", matchResourceId = "fixture:id/$id")
        try {
            first.save(rule("one")); first.save(rule("two"))
            val snapshot = first.list()
            repeat(10) { assertSame("Stable content must reuse parsed immutable rules", snapshot[0], first.list()[0]) }
            (snapshot as MutableList).clear()
            assertEquals("Caller list mutation must not change the cache", 2, first.list().size)
            second.setEnabled("one", false)
            assertFalse(first.list().single { it.id == "one" }.enabled)
            second.save(rule("one").copy(matchText = "Updated label"))
            assertEquals("Updated label", first.list().single { it.id == "one" }.matchText)
            second.remove("two")
            assertEquals(listOf("one"), first.list().map { it.id })
            second.clear()
            assertTrue(first.list().isEmpty())
            assertTrue(prefs.edit().putInt("rules", 42).commit())
            assertTrue("Wrong preference type must be treated as invalid configuration", first.list().isEmpty())
            assertTrue(prefs.edit().putString("rules", "invalid json").commit())
            assertTrue(first.list().isEmpty())
            assertTrue(first.list().isEmpty())
            assertTrue(prefs.edit().putString("rules", "[${rule("raw").json()}]").commit())
            assertEquals("raw", first.list().single().id)
            first.clear()
            val start = CountDownLatch(1)
            val futures = listOf(first, second).mapIndexed { index, store ->
                pool.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    repeat(25) { store.save(rule("$index-$it")) }
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals("Concurrent stores must preserve every committed addition", 50, first.list().size)
            assertEquals(first.list().toSet(), second.list().toSet())
            assertEquals(first.list().toSet(), AutoTriggerStore(context).list().toSet())
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(base.deleteSharedPreferences(name))
        }
    }
}

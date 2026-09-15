package dev.doppel.developer

import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DirectRuntime
import dev.doppel.sdk.DirectSkills
import dev.doppel.sdk.FirstUseConsent
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Exercises the production adapter without model calls, game input, or skill-content changes. */
class SkillSwitchDeviceTest {
    @Test fun disabledSkillsCannotBeReadByNameOrAutomaticallyInjected() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit skill_switch_regression=true required", args.getString("skill_switch_regression") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(FirstUseConsent.isAccepted(context))
        assertNull("Stop the worker before changing its skill settings", DeviceWorkerService.instance)
        assertFalse("Preserve unfinished user tasks", DirectRuntime.get(context).hasUnfinishedRun())
        val skills = DirectSkills(context)
        val original = skills.enabledState()
        assertTrue("Installed built-in skill is required for the regression", "arknights" in original)
        try {
            skills.setEnabled("arknights", true)
            val entry = skills.read("arknights")
            assertTrue(entry.getBoolean("found"))
            val revision = entry.getString("revision")
            val resource = entry.getJSONArray("resources").getString(0)
            assertTrue(skills.resource("arknights", resource, revision).getBoolean("found"))
            original.keys.forEach { skills.setEnabled(it, false) }
            val secondAdapter = DirectSkills(context)
            assertTrue(secondAdapter.enabledState().values.none { it })
            assertEquals(0, secondAdapter.list().getJSONArray("items").length())
            assertEquals(0, secondAdapter.list("明日方舟", 0, 20).getInt("total"))
            for (name in original.keys) {
                assertEquals("skill_disabled", secondAdapter.read(name).getString("error"))
                assertEquals("skill_disabled", secondAdapter.resource(name, "references/evidence.json").getString("error"))
            }
            assertEquals("skill_disabled", secondAdapter.read("arknights", revision).getString("error"))
            assertEquals("skill_disabled", secondAdapter.resource("arknights", resource, revision).getString("error"))
            assertFalse(secondAdapter.relevant("帮我通关明日方舟 TR-9", "com.hypergryph.arknights.bilibili", "").getBoolean("found"))
            skills.setEnabled("arknights", true)
            val reloaded = secondAdapter.read("arknights", revision)
            assertTrue(reloaded.getBoolean("found"))
            assertEquals("Switches must never rewrite skill contents", entry.getString("instructions"), reloaded.getString("instructions"))
        } finally {
            original.forEach { (name, enabled) -> skills.setEnabled(name, enabled) }
            assertEquals("Restore every original switch, including already-disabled skills", original, skills.enabledState())
        }
    }
}

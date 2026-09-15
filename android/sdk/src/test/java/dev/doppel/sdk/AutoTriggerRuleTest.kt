package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTriggerRuleTest {
    @Test fun roundTripPreservesSelectorsAndSafetyLimits() {
        val source = AutoTriggerRule(packageName = "com.example.reader", matchResourceId = "com.example:id/skip",
            matchText = "跳过", targetText = "跳过", action = "click", cooldownMs = 2500, burstLimit = 2, burstWindowMs = 12000)
        val copy = AutoTriggerRule.parse(JSONObject(source.json().toString()))
        assertEquals(source, copy)
    }

    @Test fun defaultsUseTriggerNodeWhenTargetIsEmpty() {
        val rule = AutoTriggerRule(packageName = "com.example.reader", matchResourceId = "com.example:id/skip")
        assertTrue(rule.targetText.isEmpty()); assertTrue(rule.targetResourceId.isEmpty())
        assertEquals("click", rule.action)
    }

    @Test(expected = IllegalArgumentException::class)
    fun ruleMustHaveAResourceId() {
        AutoTriggerRule(packageName = "com.example.reader", matchText = "跳过")
    }

    @Test(expected = IllegalArgumentException::class)
    fun ruleRejectsOverlyAggressiveCooldown() {
        AutoTriggerRule(packageName = "com.example.reader", matchResourceId = "com.example:id/skip", cooldownMs = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun taskActionRequiresTaskGoal() {
        AutoTriggerRule(packageName = "com.example.reader", matchResourceId = "com.example:id/skip", action = "task")
    }

    @Test fun taskActionRoundTripPreservesGoal() {
        val source = AutoTriggerRule(packageName = "com.example.reader", matchResourceId = "com.example:id/skip",
            action = "task", taskGoal = "打开订单并确认")
        assertEquals(source, AutoTriggerRule.parse(JSONObject(source.json().toString())))
    }
}

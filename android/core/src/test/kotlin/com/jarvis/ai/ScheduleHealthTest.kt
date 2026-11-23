package com.jarvis.ai

import com.jarvis.ai.automation.ScheduleFacts
import com.jarvis.ai.automation.ScheduleHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScheduleHealthTest {
    private val now = 1_000_000_000L
    private val healthy = ScheduleFacts(
        nowMs = now, exactAlarmsAllowed = true, notificationsAllowed = true, batteryUnrestricted = true,
        nextDueMs = now + 60_000, lastWakeMs = now - 1_000,
    )

    private fun titles(facts: ScheduleFacts) = ScheduleHealth.check(facts).map { it.title }

    @Test
    fun aHealthySetupHasNothingToReport() {
        assertEquals(emptyList(), titles(healthy))
    }

    @Test
    fun notificationsOffIsBlocking() {
        val findings = ScheduleHealth.check(healthy.copy(notificationsAllowed = false))
        assertEquals(listOf("Notifications are off"), findings.map { it.title })
        assertTrue(findings.single().blocking)
    }

    @Test
    fun inexactAlarmsAndBatterySavingAreWarningsNotBlockers() {
        val findings = ScheduleHealth.check(healthy.copy(exactAlarmsAllowed = false, batteryUnrestricted = false))
        assertEquals(2, findings.size)
        assertTrue(findings.none { it.blocking })
    }

    @Test
    fun aRunDueLongAgoWithNoWakeSinceMeansAndroidNeverWokeTheApp() {
        val due = now - 30 * 60_000
        val findings = ScheduleHealth.check(healthy.copy(nextDueMs = due, lastWakeMs = due - 60_000))
        assertEquals(listOf("A run was due and the app never woke"), findings.map { it.title })
        assertTrue(findings.single().blocking)
        assertTrue(findings.single().fix.contains("30 minutes ago"))
    }

    @Test
    fun neverWakingAtAllCountsAsNoWake() {
        val findings = ScheduleHealth.check(healthy.copy(nextDueMs = now - 20 * 60_000, lastWakeMs = null))
        assertEquals(listOf("A run was due and the app never woke"), findings.map { it.title })
    }

    @Test
    fun aRecentlyDueRunIsNotYetAProblem() {
        assertEquals(emptyList(), titles(healthy.copy(nextDueMs = now - 5 * 60_000, lastWakeMs = null)))
    }

    @Test
    fun ifTheAppWokeAfterTheDueTimeItIsNotReported() {
        val due = now - 30 * 60_000
        assertEquals(emptyList(), titles(healthy.copy(nextDueMs = due, lastWakeMs = due + 1_000)))
    }

    @Test
    fun nothingScheduledMeansNoWakeProblem() {
        assertEquals(emptyList(), titles(healthy.copy(nextDueMs = null, lastWakeMs = null)))
    }
}

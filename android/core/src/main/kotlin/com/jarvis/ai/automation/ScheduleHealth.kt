package com.jarvis.ai.automation

/** What the app can learn about whether Android will wake it for a schedule. All of it comes from Android or from the app's own record. */
data class ScheduleFacts(
    val nowMs: Long,
    val exactAlarmsAllowed: Boolean,
    val notificationsAllowed: Boolean,
    /** True when the app is exempt from battery optimisation (Android may otherwise skip wake-ups while the phone is idle). */
    val batteryUnrestricted: Boolean,
    /** The next time anything (an automation or a reminder) is due, or null when nothing is scheduled. */
    val nextDueMs: Long?,
    /** The last time the app actually woke to do scheduled work, or null when it never has. */
    val lastWakeMs: Long?,
)

data class HealthFinding(
    val title: String,
    /** What to do about it, in plain words. */
    val fix: String,
    /** True when scheduled work will not work at all until this is fixed; false when it will work but late or unreliably. */
    val blocking: Boolean,
)

/**
 * Explains why a scheduled automation or reminder might not have run. It only states what the facts show; it never guesses a cause
 * it cannot see, and says "probably" where Android gives no direct answer.
 */
object ScheduleHealth {
    /** A run later than this after its due time, with no wake-up since, means Android never woke the app. */
    const val GRACE_MS = 10 * 60_000L

    fun check(facts: ScheduleFacts): List<HealthFinding> = buildList {
        val scheduled = facts.nextDueMs != null
        if (!facts.notificationsAllowed) {
            add(
                HealthFinding(
                    "Notifications are off",
                    "Automations that show a notification, and reminders, cannot show anything. Allow notifications in Prefs > Manage access.",
                    blocking = true,
                ),
            )
        }
        if (!facts.exactAlarmsAllowed) {
            add(
                HealthFinding(
                    "Exact timing is not allowed",
                    "Android runs scheduled work in loose batches, so a run can be several minutes late. Allow \"Alarms & reminders\" in Prefs > Manage access for on-time runs.",
                    blocking = false,
                ),
            )
        }
        if (!facts.batteryUnrestricted) {
            add(
                HealthFinding(
                    "Battery saving may stop wake-ups",
                    "While the phone is idle Android may skip this app's wake-ups. Set the app's battery use to Unrestricted in Prefs > Manage access.",
                    blocking = false,
                ),
            )
        }
        val due = facts.nextDueMs
        if (scheduled && due != null && facts.nowMs - due > GRACE_MS && (facts.lastWakeMs == null || facts.lastWakeMs < due)) {
            add(
                HealthFinding(
                    "A run was due and the app never woke",
                    "Something was due ${(facts.nowMs - due) / 60_000} minutes ago and the app has not woken since. Android probably stopped the app " +
                        "(force-stop, or aggressive battery saving on this phone). Open the app once to catch up, and set battery use to Unrestricted.",
                    blocking = true,
                ),
            )
        }
    }
}

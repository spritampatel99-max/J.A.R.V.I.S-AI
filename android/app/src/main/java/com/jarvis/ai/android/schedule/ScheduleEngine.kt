package com.jarvis.ai.android.schedule

import com.jarvis.ai.automation.AutomationRunner
import com.jarvis.ai.automation.AutomationStore
import com.jarvis.ai.core.common.TimeSource
import com.jarvis.ai.productivity.Notifier
import com.jarvis.ai.productivity.ProductivityStore
import com.jarvis.ai.productivity.WakeScheduler
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Does what is due, then asks Android for the next wake-up. It runs when the alarm fires, after a restart, and whenever the app opens, so a
 * missed alarm is caught up the next time anything starts. One lock keeps two triggers from delivering the same reminder twice.
 */
class ScheduleEngine(
    private val productivity: ProductivityStore,
    private val automations: AutomationStore,
    private val runner: AutomationRunner,
    private val notifier: Notifier,
    private val wake: WakeScheduler,
    private val time: TimeSource,
) {
    private val lock = Mutex()

    suspend fun tick() = lock.withLock {
        val now = time.nowMs()
        for (reminder in productivity.dueReminders(now)) {
            val late = now - reminder.atMs > LATE_AFTER_MS
            val title = if (late) "Reminder (was due ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(reminder.atMs))})" else "Reminder"
            // Marked as delivered either way, and remembers whether Android would show it, so the planner can say so honestly.
            val shown = notifier.notify("reminder-${reminder.id}", title, reminder.text)
            productivity.markDelivered(reminder.id, notified = shown)
        }
        runner.runDue()
        scheduleNext()
    }

    /** Asks Android for the next wake-up, or cancels the request when nothing is waiting. Call after anything that changes a time. */
    fun scheduleNext() {
        val now = time.nowMs()
        val next = listOfNotNull(productivity.nextReminderMs(now), automations.nextWakeMs()).minOrNull()
        if (next == null) wake.cancel() else wake.schedule(maxOf(next, now + MIN_LEAD_MS))
    }

    private companion object {
        const val LATE_AFTER_MS = 2 * 60_000L
        const val MIN_LEAD_MS = 1_000L
    }
}

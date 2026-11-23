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
    /** Told every time the app really woke to do scheduled work, so the Automations screen can tell "never woke" from "ran and failed". */
    private val onWake: (Long) -> Unit = {},
    /** The optional nightly review. It only reads and notifies; it is asked for a wake-up time and to run when owed. */
    private val nightly: com.jarvis.ai.update.NightlyHook? = null,
) {
    private val lock = Mutex()

    suspend fun tick() = lock.withLock {
        val now = time.nowMs()
        onWake(now)
        for (reminder in productivity.dueReminders(now)) {
            val late = now - reminder.atMs > LATE_AFTER_MS
            val title = if (late) "Reminder (was due ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(reminder.atMs))})" else "Reminder"
            // Marked as delivered either way, and remembers whether Android would show it, so the planner can say so honestly.
            val shown = notifier.notify("reminder-${reminder.id}", title, reminder.text)
            productivity.markDelivered(reminder.id, notified = shown)
        }
        runner.runDue()
        // A failure in the review must never stop reminders or the next wake-up from being set.
        try {
            nightly?.runIfDue(now)
        } catch (e: kotlinx.coroutines.CancellationException) {
            scheduleNext()
            throw e
        } catch (e: Exception) {
            // Tried again at the next wake-up.
        }
        scheduleNext()
    }

    /** Asks Android for the next wake-up, or cancels the request when nothing is waiting. Call after anything that changes a time. */
    fun scheduleNext() {
        val now = time.nowMs()
        val next = listOfNotNull(productivity.nextReminderMs(now), automations.nextWakeMs(), nightly?.nextMs(now)).minOrNull()
        if (next == null) wake.cancel() else wake.schedule(maxOf(next, now + MIN_LEAD_MS))
    }

    private companion object {
        const val LATE_AFTER_MS = 2 * 60_000L
        const val MIN_LEAD_MS = 1_000L
    }
}

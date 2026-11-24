package com.jarvis.ai.automation

import com.jarvis.ai.core.common.TimeSource
import com.jarvis.ai.productivity.Notifier
import com.jarvis.ai.productivity.ProductivityStore
import com.jarvis.ai.productivity.WakeScheduler
import com.jarvis.ai.update.NightlyHook
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Does what is due, then asks Android for the next wake-up. It runs when the alarm fires, after a restart, and whenever the app opens, so a
 * missed alarm is caught up the next time anything starts. One lock keeps two triggers in this process from running at once; the durable
 * "claimed before it runs" mark in [AutomationStore] covers the case a lock cannot: the same alarm delivered twice, or a restart mid-run.
 *
 * The next wake-up is asked for BEFORE the longer work (automations, nightly review) and again at the end, and in a `finally`, so no failure or
 * cut-off in any stage can leave Android with no wake-up scheduled. Each stage, and each reminder, fails on its own: one broken item never stops the rest.
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
    private val nightly: NightlyHook? = null,
) {
    private val lock = Mutex()

    /** The most recent stage that failed, as a short sentence with no details from the data, or null. Kept for diagnostics; cleared by a clean tick. */
    @Volatile
    var lastProblem: String? = null
        private set

    suspend fun tick() = lock.withLock {
        val now = time.nowMs()
        var problem: String? = null
        fun failed(stage: String, e: Exception) {
            problem = "$stage failed (${e.javaClass.simpleName})."
        }
        try {
            try {
                onWake(now)
            } catch (e: Exception) {
                failed("Recording the wake-up", e)
            }
            try {
                // A write that failed earlier is tried again here, so a disk that has room again saves what was only in memory.
                if (productivity.saveFailed) productivity.retrySave()
                if (automations.saveFailed) automations.retrySave()
            } catch (e: Exception) {
                failed("Saving again", e)
            }
            try {
                deliverReminders(now)
            } catch (e: Exception) {
                failed("Delivering reminders", e)
            }
            // The wake-up is requested now, before work that may be cut short, so even a stage that never returns cannot leave none scheduled.
            scheduleNext()
            try {
                runner.runDue()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed("Running automations", e)
            }
            try {
                nightly?.runIfDue(now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed("The nightly review", e)
            }
        } finally {
            lastProblem = problem
            scheduleNext()
        }
    }

    private fun deliverReminders(now: Long) {
        for (reminder in productivity.dueReminders(now)) {
            val late = now - reminder.atMs > LATE_AFTER_MS
            val title = if (late) "Reminder (was due ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(reminder.atMs))})" else "Reminder"
            // Marked as delivered either way, and remembers whether Android would show it, so the planner can say so honestly.
            // A notifier that throws counts as "not shown": the reminder is not retried forever, and nothing claims it was seen.
            val shown = try {
                notifier.notify("reminder-${reminder.id}", title, reminder.text)
            } catch (e: Exception) {
                false
            }
            productivity.markDelivered(reminder.id, notified = shown)
        }
    }

    /**
     * Asks Android for the next wake-up, or cancels the request when nothing is waiting. Call after anything that changes a time. It never throws:
     * if Android refuses the request, the next time the app opens or any wake arrives, it is asked again.
     */
    fun scheduleNext() {
        try {
            val now = time.nowMs()
            val next = listOfNotNull(productivity.nextReminderMs(now), automations.nextWakeMs(), nightly?.nextMs(now)).minOrNull()
            if (next == null) wake.cancel() else wake.schedule(maxOf(next, now + MIN_LEAD_MS))
        } catch (e: Exception) {
            lastProblem = "Asking Android for the next wake-up failed (${e.javaClass.simpleName})."
        }
    }

    private companion object {
        const val LATE_AFTER_MS = 2 * 60_000L
        const val MIN_LEAD_MS = 1_000L
    }
}

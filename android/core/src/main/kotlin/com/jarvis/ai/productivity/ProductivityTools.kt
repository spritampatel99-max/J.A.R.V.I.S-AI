package com.jarvis.ai.productivity

import com.jarvis.ai.ai.tools.AlarmTime
import com.jarvis.ai.ai.tools.ParamSpec
import com.jarvis.ai.ai.tools.ParamType
import com.jarvis.ai.ai.tools.Tool
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolError
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.ToolSpec
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.core.security.Risk
import java.time.Clock
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The planner and reminder tools. They only write to the app's own private data, so they carry low risk. */
object ProductivityTools {
    const val ADD_TASK = "add_task"
    const val ADD_NOTE = "add_note"
    const val ADD_REMINDER = "add_reminder"
    const val DAILY_OVERVIEW = "daily_overview"
    const val SHOW_NOTIFICATION = "show_notification"
    const val NOTIFY_OVERVIEW = "notify_overview"

    val NAMES = setOf(ADD_TASK, ADD_NOTE, ADD_REMINDER, DAILY_OVERVIEW, SHOW_NOTIFICATION, NOTIFY_OVERVIEW)

    fun create(store: ProductivityStore, wake: WakeScheduler, notifier: Notifier, clock: Clock = Clock.systemDefaultZone()): List<Tool> =
        listOf(
            AddTaskTool(store, clock), AddNoteTool(store), AddReminderTool(store, wake, notifier, clock), DailyOverviewTool(store, clock),
            ShowNotificationTool(notifier), NotifyOverviewTool(store, notifier, clock),
        )
}

private val TIME_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)

class AddTaskTool(private val store: ProductivityStore, private val clock: Clock) : Tool {
    override val spec = ToolSpec(
        name = ProductivityTools.ADD_TASK,
        description = "Add a task to the person's planner.",
        params = listOf(
            ParamSpec("title", ParamType.STRING, "The task", maxLength = ProductivityStore.MAX_TITLE),
            ParamSpec("priority", ParamType.ENUM, "How important it is", required = false, enumValues = listOf("low", "normal", "high")),
            ParamSpec("due_date", ParamType.STRING, "Optional due date, YYYY-MM-DD", required = false, maxLength = 10),
        ),
        risk = Risk.LOW,
        resultFormat = "CONFIRMED after the task is read back from the planner.",
    )

    override fun describe(args: ToolArgs): String = "Add the task \"${args.string("title").take(60)}\" to the planner"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val zone = clock.zone
        val due = args.stringOrNull("due_date")?.let { raw ->
            val date = AlarmTime.parseDate(raw) ?: return ToolResult.Failure(ToolError.InvalidArguments("due_date must look like 2026-11-07"))
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        }
        val priority = Priority.entries.firstOrNull { it.name.equals(args.stringOrNull("priority"), ignoreCase = true) } ?: Priority.NORMAL
        val task = store.addTask(args.string("title"), priority, due)
            ?: return ToolResult.Failure(ToolError.InvalidArguments("The task was empty."))
        val saved = store.state.value.tasks.any { it.id == task.id }
        if (!saved) return ToolResult.Failure(ToolError.Failed("The task could not be saved."))
        val where = if (store.saveFailed) " It is in memory only: the phone could not write it to storage." else ""
        return ToolResult.Success("Added the task \"${task.title}\".$where", Verification.CONFIRMED, mapOf("id" to task.id))
    }
}

class AddNoteTool(private val store: ProductivityStore) : Tool {
    override val spec = ToolSpec(
        name = ProductivityTools.ADD_NOTE,
        description = "Save a note in the person's planner.",
        params = listOf(ParamSpec("text", ParamType.STRING, "The note", maxLength = ProductivityStore.MAX_NOTE)),
        risk = Risk.LOW,
        resultFormat = "CONFIRMED after the note is read back from the planner.",
    )

    override fun describe(args: ToolArgs): String = "Save a note: \"${args.string("text").take(60)}\""

    override suspend fun execute(args: ToolArgs): ToolResult {
        val note = store.addNote(args.string("text")) ?: return ToolResult.Failure(ToolError.InvalidArguments("The note was empty."))
        if (store.state.value.notes.none { it.id == note.id }) return ToolResult.Failure(ToolError.Failed("The note could not be saved."))
        val where = if (store.saveFailed) " It is in memory only: the phone could not write it to storage." else ""
        return ToolResult.Success("Saved the note.$where", Verification.CONFIRMED, mapOf("id" to note.id))
    }
}

class AddReminderTool(
    private val store: ProductivityStore,
    private val wake: WakeScheduler,
    private val notifier: Notifier,
    private val clock: Clock,
) : Tool {
    override val spec = ToolSpec(
        name = ProductivityTools.ADD_REMINDER,
        description = "Remind the person with a notification at a time. Use this for reminders; use set_alarm for an alarm in the Clock app.",
        params = listOf(
            ParamSpec("text", ParamType.STRING, "What to remind the person of", maxLength = ProductivityStore.MAX_TITLE),
            ParamSpec("hour", ParamType.INT, "Hour in 24-hour time", min = 0, max = 23),
            ParamSpec("minute", ParamType.INT, "Minute", min = 0, max = 59),
            ParamSpec("date", ParamType.STRING, "Optional date, YYYY-MM-DD. Without it, the next time that clock time occurs.", required = false, maxLength = 10),
        ),
        risk = Risk.LOW,
        resultFormat = "HANDED_OFF: the reminder is saved; Android is asked to wake the app, and whether it fires on time cannot be observed.",
    )

    override fun describe(args: ToolArgs): String =
        "Remind you \"${args.string("text").take(60)}\" at %02d:%02d".format(Locale.ROOT, args.int("hour"), args.int("minute"))

    override suspend fun execute(args: ToolArgs): ToolResult {
        val now = ZonedDateTime.now(clock)
        val hour = args.int("hour")
        val minute = args.int("minute")
        val at: ZonedDateTime = args.stringOrNull("date")?.let { raw ->
            val date = AlarmTime.parseDate(raw) ?: return ToolResult.Failure(ToolError.InvalidArguments("date must look like 2026-11-07"))
            date.atTime(LocalTime.of(hour, minute)).atZone(now.zone)
        } ?: AlarmTime.nextOccurrence(hour, minute, now)

        val reminder = store.addReminder(args.string("text"), at.toInstant().toEpochMilli())
            ?: return ToolResult.Failure(ToolError.InvalidArguments("That time is in the past, more than a year away, or the text was empty."))

        val whenText = at.format(TIME_FORMAT)
        val notes = mutableListOf<String>()
        if (!notifier.canNotify()) notes += "Android notifications are off for this app, so nothing will be shown: turn them on in Prefs > Manage access."
        val next = store.nextReminderMs(now.toInstant().toEpochMilli())
        if (next != null) {
            when (val result = wake.schedule(next)) {
                is WakeResult.Scheduled -> if (!result.exact) notes += "Android may deliver it a few minutes late."
                is WakeResult.Unavailable -> notes += "Android did not accept the wake-up request (${result.reason}), so it may not fire."
            }
        }
        if (store.saveFailed) notes += "It is in memory only: the phone could not write it to storage."
        return ToolResult.Success(
            "Saved a reminder for $whenText: \"${reminder.text}\"." + notes.joinToString("") { " $it" },
            Verification.HANDED_OFF,
            mapOf("id" to reminder.id),
        )
    }
}

class DailyOverviewTool(private val store: ProductivityStore, private val clock: Clock) : Tool {
    override val spec = ToolSpec(
        name = ProductivityTools.DAILY_OVERVIEW,
        description = "Read out today's overview from the planner: overdue and due tasks and the reminders of the next 24 hours.",
        params = emptyList(),
        risk = Risk.LOW,
        resultFormat = "CONFIRMED: computed from the stored tasks and reminders.",
    )

    override fun describe(args: ToolArgs): String = "Read today's overview from the planner"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val overview = store.overview(clock.millis(), clock.zone)
        return ToolResult.Success(overview.text(clock.zone), Verification.CONFIRMED)
    }
}

class ShowNotificationTool(private val notifier: Notifier) : Tool {
    override val spec = ToolSpec(
        name = ProductivityTools.SHOW_NOTIFICATION,
        description = "Show the person a notification from this app right now.",
        params = listOf(ParamSpec("text", ParamType.STRING, "What the notification says", maxLength = 200)),
        risk = Risk.LOW,
        resultFormat = "HANDED_OFF: Android was asked to show it; whether the person saw it cannot be observed.",
    )

    override fun describe(args: ToolArgs): String = "Show a notification: \"${args.string("text").take(60)}\""

    override suspend fun execute(args: ToolArgs): ToolResult {
        if (!notifier.canNotify()) return ToolResult.Failure(ToolError.Unavailable("Android notifications are off for this app. Turn them on in Prefs > Manage access."))
        if (!notifier.notify("manual", "J.A.R.V.I.S.", args.string("text"))) return ToolResult.Failure(ToolError.Failed("Android did not accept the notification."))
        return ToolResult.Success("Asked Android to show the notification.", Verification.HANDED_OFF)
    }
}

class NotifyOverviewTool(private val store: ProductivityStore, private val notifier: Notifier, private val clock: Clock) : Tool {
    override val spec = ToolSpec(
        name = ProductivityTools.NOTIFY_OVERVIEW,
        description = "Show today's planner overview as a notification.",
        params = emptyList(),
        risk = Risk.LOW,
        resultFormat = "HANDED_OFF: Android was asked to show it.",
    )

    override fun describe(args: ToolArgs): String = "Show today's overview as a notification"

    override suspend fun execute(args: ToolArgs): ToolResult {
        if (!notifier.canNotify()) return ToolResult.Failure(ToolError.Unavailable("Android notifications are off for this app. Turn them on in Prefs > Manage access."))
        val text = store.overview(clock.millis(), clock.zone).text(clock.zone)
        if (!notifier.notify("overview", "Today", text)) return ToolResult.Failure(ToolError.Failed("Android did not accept the notification."))
        return ToolResult.Success("Asked Android to show today's overview.", Verification.HANDED_OFF)
    }
}

package com.jarvis.ai.routines

import com.jarvis.ai.ai.tools.ParamSpec
import com.jarvis.ai.ai.tools.ParamType
import com.jarvis.ai.ai.tools.Tool
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolError
import com.jarvis.ai.ai.tools.ToolRegistry
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.ToolSpec
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.automation.AutoAction
import com.jarvis.ai.automation.AutoTrigger
import com.jarvis.ai.automation.AutomationResult
import com.jarvis.ai.automation.AutomationStore
import com.jarvis.ai.core.security.Risk
import com.jarvis.ai.productivity.Notifier
import java.time.ZoneId
import java.util.Locale
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Personal routines: morning, evening or any list of steps the person wants read to them. The steps are plain text. Reading a routine reads
 * the text out; nothing in a step is ever run, called or sent. A routine may have a daily reminder, delivered by an ordinary automation.
 */
object RoutineTools {
    const val CREATE = "create_routine"
    const val READ = "read_routine"
    const val LIST = "list_routines"
    const val DELETE = "delete_routine"

    /** Used by the reminder's automation. It only reads the routine and shows a notification, so it is allowed to run unattended. */
    const val NOTIFY = "notify_routine"

    val NAMES = setOf(CREATE, READ, LIST, DELETE, NOTIFY)

    fun create(store: RoutineStore, reminders: RoutineReminders, notifier: Notifier): List<Tool> =
        listOf(CreateRoutineTool(store, reminders), ReadRoutineTool(store), ListRoutinesTool(store, reminders), DeleteRoutineTool(store, reminders), NotifyRoutineTool(store, notifier))

    /** The numbered text of a routine, as read out and shown. */
    fun text(routine: Routine): String = buildString {
        append(routine.name).append(" routine: ").append(routine.steps.size).append(if (routine.steps.size == 1) " step" else " steps").append('.')
        routine.steps.forEachIndexed { i, step -> append('\n').append(i + 1).append(". ").append(step) }
    }

    internal fun missing(match: RoutineMatch, query: String): ToolResult.Failure = when (match) {
        is RoutineMatch.Several -> ToolResult.Failure(ToolError.NeedsInput("Which routine: ${match.names.joinToString(" or ") { "\"$it\"" }}?"))
        else -> ToolResult.Failure(ToolError.Failed("There is no routine called \"${query.take(40)}\". Say \"list my routines\" to hear the ones you have."))
    }
}

/**
 * Keeps a routine's daily reminder in step with an automation named "Routine: <name>". The automation runs [RoutineTools.NOTIFY], which reads the
 * routine's CURRENT steps when it fires, so editing a routine never leaves an old copy in a reminder.
 */
class RoutineReminders(
    private val automations: AutomationStore,
    private val registry: () -> ToolRegistry,
    private val zone: () -> ZoneId,
) {
    sealed interface Result {
        data class Set(val automationId: String) : Result
        data class Problem(val text: String) : Result
    }

    fun schedule(routine: Routine): Result {
        val hour = routine.reminderHour ?: return Result.Problem("No reminder time was given.")
        val minute = routine.reminderMinute ?: return Result.Problem("No reminder time was given.")
        val trigger = AutoTrigger.Daily(hour, minute)
        val actions = listOf(AutoAction(RoutineTools.NOTIFY, buildJsonObject { put("name", routine.name) }))
        val name = "Routine: ${routine.name}".take(60)
        val existing = routine.automationId?.let { id -> automations.automations.value.firstOrNull { it.id == id } }
        val saved = if (existing != null) {
            automations.update(existing.id, name, trigger, actions, registry(), zone())
        } else {
            automations.create(name, trigger, actions, registry(), zone())
        }
        return when (saved) {
            is AutomationResult.Saved -> {
                // A reminder the person asked for now must not stay paused from an earlier pause.
                if (!saved.automation.enabled) automations.setEnabled(saved.automation.id, true, zone())
                Result.Set(saved.automation.id)
            }
            is AutomationResult.Rejected -> Result.Problem(saved.problems.joinToString(" "))
        }
    }

    fun clear(automationId: String?) {
        if (automationId != null && automations.automations.value.any { it.id == automationId }) automations.delete(automationId)
    }

    /** True while the reminder's automation still exists and is switched on (the person can pause or delete it on the Automations screen). */
    fun isActive(automationId: String?): Boolean =
        automationId != null && automations.automations.value.any { it.id == automationId && it.enabled }
}

class CreateRoutineTool(private val store: RoutineStore, private val reminders: RoutineReminders) : Tool {
    override val spec = ToolSpec(
        name = RoutineTools.CREATE,
        description = "Save a routine: a named list of steps the person wants read to them (a morning or evening routine, for example), with an optional daily reminder. " +
            "A routine with the same name is replaced, including its reminder. The steps are only read out, never run.",
        params = listOf(
            ParamSpec("name", ParamType.STRING, "The routine's name, for example Morning", maxLength = RoutineStore.MAX_NAME),
            ParamSpec(
                "steps", ParamType.STRING, "The steps, separated by semicolons or new lines, in the person's own words",
                maxLength = RoutineStore.MAX_STEPS * (RoutineStore.MAX_STEP + 2),
            ),
            ParamSpec("hour", ParamType.INT, "Optional daily reminder hour, 24-hour time. Give hour and minute together.", required = false, min = 0, max = 23),
            ParamSpec("minute", ParamType.INT, "Optional daily reminder minute", required = false, min = 0, max = 59),
        ),
        risk = Risk.LOW,
        resultFormat = "CONFIRMED after the routine is read back from storage. With a reminder: HANDED_OFF, because whether Android wakes the app on time cannot be observed.",
    )

    override fun describe(args: ToolArgs): String {
        val at = args.intOrNull("hour")?.let { h -> " with a daily reminder at %02d:%02d".format(Locale.ROOT, h, args.intOrNull("minute") ?: 0) }.orEmpty()
        return "Save the routine \"${args.string("name").take(40)}\"$at"
    }

    override suspend fun execute(args: ToolArgs): ToolResult {
        val hour = args.intOrNull("hour")
        val minute = args.intOrNull("minute")
        if ((hour == null) != (minute == null)) return ToolResult.Failure(ToolError.InvalidArguments("Give both the hour and the minute for the reminder."))
        val steps = RoutineText.splitSteps(args.string("steps"))
        val saved = when (val r = store.save(args.string("name"), steps, hour, minute)) {
            is RoutineSave.Rejected -> return ToolResult.Failure(ToolError.InvalidArguments(r.problems.joinToString(" ")))
            is RoutineSave.Saved -> r
        }
        val routine = saved.routine
        if (store.routines.value.none { it.id == routine.id }) return ToolResult.Failure(ToolError.Failed("The routine could not be saved."))

        val notes = mutableListOf<String>()
        var verification = Verification.CONFIRMED
        if (routine.reminderHour != null) {
            when (val r = reminders.schedule(routine)) {
                is RoutineReminders.Result.Set -> {
                    store.setAutomation(routine.id, r.automationId)
                    verification = Verification.HANDED_OFF
                    notes += "A reminder is set for every day at ${routine.reminderText}. Android is asked to wake the app; whether it arrives on time cannot be observed."
                }
                is RoutineReminders.Result.Problem -> notes += "The routine is saved, but the reminder was not set: ${r.text}"
            }
        } else if (routine.automationId != null) {
            reminders.clear(routine.automationId)
            store.setAutomation(routine.id, null)
            notes += "The old daily reminder was removed."
        }
        if (store.saveFailed) notes += "It is in memory only: the phone could not write it to storage."
        val verb = if (saved.replaced) "Updated" else "Saved"
        return ToolResult.Success(
            "$verb the routine \"${routine.name}\" with ${routine.steps.size} ${if (routine.steps.size == 1) "step" else "steps"}." + notes.joinToString("") { " $it" },
            verification,
            mapOf("id" to routine.id),
        )
    }
}

class ReadRoutineTool(private val store: RoutineStore) : Tool {
    override val spec = ToolSpec(
        name = RoutineTools.READ,
        description = "Read a saved routine's steps out to the person (\"start my morning routine\"). The steps are only read, never run.",
        params = listOf(ParamSpec("name", ParamType.STRING, "Which routine, for example morning", maxLength = RoutineStore.MAX_NAME)),
        risk = Risk.LOW,
        resultFormat = "CONFIRMED: the stored steps, numbered. Private: spoken by the phone's own voice only.",
    )

    override fun describe(args: ToolArgs): String = "Read the \"${args.string("name").take(40)}\" routine"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val query = args.string("name")
        return when (val m = store.find(query)) {
            is RoutineMatch.Found -> ToolResult.Success(RoutineTools.text(m.routine), Verification.CONFIRMED, answer = true, private = true)
            else -> RoutineTools.missing(m, query)
        }
    }
}

class ListRoutinesTool(private val store: RoutineStore, private val reminders: RoutineReminders) : Tool {
    override val spec = ToolSpec(
        name = RoutineTools.LIST,
        description = "List the person's saved routines and their reminder times.",
        params = emptyList(),
        risk = Risk.LOW,
        resultFormat = "CONFIRMED: computed from the stored routines. Private.",
    )

    override fun describe(args: ToolArgs): String = "List your routines"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val list = store.routines.value
        if (list.isEmpty()) {
            return ToolResult.Success("You have no routines yet. Say, for example, \"create a morning routine: drink water; stretch; check the planner\".", Verification.CONFIRMED, answer = true, private = true)
        }
        val text = list.joinToString("\n") { r ->
            val at = if (reminders.isActive(r.automationId)) ", reminder at ${r.reminderText}" else ""
            "${r.name}: ${r.steps.size} ${if (r.steps.size == 1) "step" else "steps"}$at"
        }
        return ToolResult.Success("Your routines:\n$text", Verification.CONFIRMED, answer = true, private = true)
    }
}

class DeleteRoutineTool(private val store: RoutineStore, private val reminders: RoutineReminders) : Tool {
    override val spec = ToolSpec(
        name = RoutineTools.DELETE,
        description = "Delete a saved routine and its daily reminder.",
        params = listOf(ParamSpec("name", ParamType.STRING, "Which routine", maxLength = RoutineStore.MAX_NAME)),
        risk = Risk.MEDIUM,
        resultFormat = "CONFIRMED after the routine is gone from storage.",
    )

    override fun describe(args: ToolArgs): String = "Delete the \"${args.string("name").take(40)}\" routine"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val query = args.string("name")
        val routine = when (val m = store.find(query)) {
            is RoutineMatch.Found -> m.routine
            else -> return RoutineTools.missing(m, query)
        }
        store.delete(routine.id)
        reminders.clear(routine.automationId)
        if (store.routines.value.any { it.id == routine.id }) return ToolResult.Failure(ToolError.Failed("The routine could not be deleted."))
        return ToolResult.Success("Deleted the routine \"${routine.name}\"${if (routine.automationId != null) " and its reminder" else ""}.", Verification.CONFIRMED)
    }
}

/** What the reminder's automation runs. It re-reads the routine each time, so the notification always shows the current steps. */
class NotifyRoutineTool(private val store: RoutineStore, private val notifier: Notifier) : Tool {
    override val spec = ToolSpec(
        name = RoutineTools.NOTIFY,
        description = "Show a saved routine's steps as a notification.",
        params = listOf(ParamSpec("name", ParamType.STRING, "Which routine", maxLength = RoutineStore.MAX_NAME)),
        risk = Risk.LOW,
        resultFormat = "HANDED_OFF: Android was asked to show it.",
    )

    override fun describe(args: ToolArgs): String = "Show the \"${args.string("name").take(40)}\" routine as a notification"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val query = args.string("name")
        val routine = when (val m = store.find(query)) {
            is RoutineMatch.Found -> m.routine
            else -> return RoutineTools.missing(m, query)
        }
        if (!notifier.canNotify()) return ToolResult.Failure(ToolError.Unavailable("Android notifications are off for this app. Turn them on in Prefs > Manage access."))
        val body = routine.steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
        if (!notifier.notify("routine-${routine.id}", "${routine.name} routine", body)) return ToolResult.Failure(ToolError.Failed("Android did not accept the notification."))
        return ToolResult.Success("Asked Android to show the ${routine.name} routine.", Verification.HANDED_OFF)
    }
}

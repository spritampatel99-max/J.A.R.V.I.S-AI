package com.jarvis.ai.automation

import com.jarvis.ai.ai.tools.ArgValidator
import com.jarvis.ai.ai.tools.ToolRegistry
import com.jarvis.ai.ai.tools.ValidationResult
import com.jarvis.ai.core.security.Risk
import com.jarvis.ai.productivity.ProductivityTools
import java.time.DayOfWeek
import java.time.ZonedDateTime
import kotlinx.serialization.json.JsonObject

/** When an automation runs. Everything is local time. */
sealed interface AutoTrigger {
    /** The first run strictly after [from]. */
    fun nextAfter(from: ZonedDateTime): ZonedDateTime

    fun describe(): String

    data class Daily(val hour: Int, val minute: Int) : AutoTrigger {
        override fun nextAfter(from: ZonedDateTime): ZonedDateTime {
            val today = from.toLocalDate().atTime(hour, minute).atZone(from.zone)
            return if (today.isAfter(from)) today else from.toLocalDate().plusDays(1).atTime(hour, minute).atZone(from.zone)
        }

        override fun describe() = "Every day at %02d:%02d".format(hour, minute)
    }

    data class Weekly(val days: Set<DayOfWeek>, val hour: Int, val minute: Int) : AutoTrigger {
        override fun nextAfter(from: ZonedDateTime): ZonedDateTime {
            for (offset in 0..7) {
                val date = from.toLocalDate().plusDays(offset.toLong())
                if (date.dayOfWeek !in days) continue
                val candidate = date.atTime(hour, minute).atZone(from.zone)
                if (candidate.isAfter(from)) return candidate
            }
            error("no day selected")
        }

        override fun describe() = days.sorted().joinToString(", ") { it.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() } } +
            " at %02d:%02d".format(hour, minute)
    }

    /** Android cannot reliably wake an app more often than every 15 minutes, so that is the floor. */
    data class Every(val minutes: Int) : AutoTrigger {
        override fun nextAfter(from: ZonedDateTime): ZonedDateTime = from.plusMinutes(minutes.toLong())
        override fun describe() = if (minutes % 60 == 0) "Every ${minutes / 60} hour${if (minutes == 60) "" else "s"}" else "Every $minutes minutes"
    }
}

/** One step of an automation: a tool from the allow-list and its arguments. */
data class AutoAction(val tool: String, val parameters: JsonObject)

data class RunRecord(val atMs: Long, val ok: Boolean, val summary: String)

data class Automation(
    val id: String,
    val name: String,
    val trigger: AutoTrigger,
    val actions: List<AutoAction>,
    val enabled: Boolean,
    val createdMs: Long,
    val nextRunMs: Long?,
    val history: List<RunRecord> = emptyList(),
) {
    val lastRun: RunRecord? get() = history.lastOrNull()
}

/**
 * What an automation may do. It runs with nobody watching, so it may only use tools that
 *  - are on this short allow-list (they only touch the app's own data or show a notification), and
 *  - are LOW risk right now (checked against the live tool registry, so raising a tool's risk removes it from automations), and
 *  - need no confirmation, because nobody is there to give one and nothing is ever approved on the person's behalf.
 * Calls, alarms, messages, the flashlight and everything else need a person present and cannot be automated.
 */
object AutomationRules {
    val ALLOWED_TOOLS: Set<String> = setOf(
        ProductivityTools.ADD_TASK,
        ProductivityTools.ADD_NOTE,
        ProductivityTools.SHOW_NOTIFICATION,
        ProductivityTools.NOTIFY_OVERVIEW,
    )

    const val MAX_ACTIONS = 5
    const val MIN_INTERVAL_MINUTES = 15
    const val MAX_INTERVAL_MINUTES = 24 * 60
    const val MAX_AUTOMATIONS = 30

    fun validate(name: String, trigger: AutoTrigger, actions: List<AutoAction>, registry: ToolRegistry): List<String> {
        val problems = mutableListOf<String>()
        if (name.trim().length !in 2..60) problems += "The name must be 2 to 60 characters."
        when (trigger) {
            is AutoTrigger.Daily -> if (trigger.hour !in 0..23 || trigger.minute !in 0..59) problems += "That time of day is not valid."
            is AutoTrigger.Weekly -> {
                if (trigger.days.isEmpty()) problems += "Choose at least one day."
                if (trigger.hour !in 0..23 || trigger.minute !in 0..59) problems += "That time of day is not valid."
            }
            is AutoTrigger.Every -> if (trigger.minutes !in MIN_INTERVAL_MINUTES..MAX_INTERVAL_MINUTES) {
                problems += "Android cannot reliably run something more often than every $MIN_INTERVAL_MINUTES minutes, or less often than once a day with this trigger."
            }
        }
        if (actions.isEmpty()) problems += "Add at least one action."
        if (actions.size > MAX_ACTIONS) problems += "At most $MAX_ACTIONS actions."
        for ((i, action) in actions.withIndex()) {
            val n = i + 1
            val tool = registry.get(action.tool)
            when {
                action.tool !in ALLOWED_TOOLS -> problems += "Action $n (${action.tool.take(40)}) cannot run on its own. Automations may only add a task, add a note, or show a notification."
                tool == null -> problems += "Action $n (${action.tool}) is not available in this build."
                tool.spec.risk != Risk.LOW -> problems += "Action $n (${action.tool}) needs your confirmation, so it cannot run unattended."
                else -> when (val check = ArgValidator.validate(tool.spec, action.parameters)) {
                    is ValidationResult.Invalid -> problems += check.errors.map { "Action $n: $it" }
                    is ValidationResult.Valid -> Unit
                }
            }
        }
        return problems
    }
}

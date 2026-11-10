package com.jarvis.ai.automation

import com.jarvis.ai.ai.tools.ToolRegistry
import com.jarvis.ai.core.common.IdGenerator
import com.jarvis.ai.core.common.TextStorage
import com.jarvis.ai.core.common.TimeSource
import com.jarvis.ai.core.common.bool
import com.jarvis.ai.core.common.long
import com.jarvis.ai.core.common.obj
import com.jarvis.ai.core.common.objs
import com.jarvis.ai.core.common.parseJson
import com.jarvis.ai.core.common.str
import com.jarvis.ai.core.common.strings
import java.io.IOException
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface AutomationResult {
    data class Saved(val automation: Automation) : AutomationResult
    data class Rejected(val problems: List<String>) : AutomationResult
}

/**
 * The person's automations, kept in a private file. A trigger and its actions are checked by [AutomationRules] before anything
 * is stored, and again by [AutomationRunner] right before anything runs.
 */
class AutomationStore(private val storage: TextStorage, private val time: TimeSource) {
    private val lock = Any()
    private val ids = IdGenerator(time, "auto")
    private val flow = MutableStateFlow(load())

    val automations: StateFlow<List<Automation>> = flow.asStateFlow()

    @Volatile
    var saveFailed: Boolean = false
        private set

    fun create(name: String, trigger: AutoTrigger, actions: List<AutoAction>, registry: ToolRegistry, zone: ZoneId): AutomationResult =
        synchronized(lock) {
            val problems = AutomationRules.validate(name, trigger, actions, registry).toMutableList()
            if (flow.value.size >= AutomationRules.MAX_AUTOMATIONS) problems += "At most ${AutomationRules.MAX_AUTOMATIONS} automations. Delete one first."
            if (problems.isNotEmpty()) return AutomationResult.Rejected(problems)
            val now = time.nowMs()
            val automation = Automation(
                id = ids.next(),
                name = name.trim(),
                trigger = trigger,
                actions = actions,
                enabled = true,
                createdMs = now,
                nextRunMs = nextRun(trigger, now, zone),
            )
            commit(flow.value + automation)
            AutomationResult.Saved(automation)
        }

    fun update(id: String, name: String, trigger: AutoTrigger, actions: List<AutoAction>, registry: ToolRegistry, zone: ZoneId): AutomationResult =
        synchronized(lock) {
            val existing = flow.value.firstOrNull { it.id == id } ?: return AutomationResult.Rejected(listOf("That automation no longer exists."))
            val problems = AutomationRules.validate(name, trigger, actions, registry)
            if (problems.isNotEmpty()) return AutomationResult.Rejected(problems)
            val now = time.nowMs()
            val changed = existing.copy(
                name = name.trim(),
                trigger = trigger,
                actions = actions,
                nextRunMs = if (existing.enabled) nextRun(trigger, now, zone) else null,
            )
            commit(flow.value.map { if (it.id == id) changed else it })
            AutomationResult.Saved(changed)
        }

    /** Pausing clears the next run; resuming schedules the next one from now, so missed runs are not replayed. */
    fun setEnabled(id: String, enabled: Boolean, zone: ZoneId) = synchronized(lock) {
        val now = time.nowMs()
        commit(flow.value.map {
            if (it.id != id) it else it.copy(enabled = enabled, nextRunMs = if (enabled) nextRun(it.trigger, now, zone) else null)
        })
    }

    fun delete(id: String) = synchronized(lock) { commit(flow.value.filter { it.id != id }) }

    /** Enabled automations whose time has come. */
    fun due(nowMs: Long): List<Automation> = flow.value.filter { it.enabled && (it.nextRunMs ?: Long.MAX_VALUE) <= nowMs }

    /** When Android should next wake the app for an automation, or null. */
    fun nextWakeMs(): Long? = flow.value.filter { it.enabled }.mapNotNull { it.nextRunMs }.minOrNull()

    /**
     * Records a run and moves to the next run strictly after now. If the phone was off for several scheduled times, the
     * automation runs once, not once per missed time.
     */
    fun recordRun(id: String, ok: Boolean, summary: String, zone: ZoneId, reschedule: Boolean = true) = synchronized(lock) {
        val now = time.nowMs()
        commit(flow.value.map {
            if (it.id != id) it else it.copy(
                history = (it.history + RunRecord(now, ok, summary.take(MAX_SUMMARY))).takeLast(MAX_HISTORY),
                nextRunMs = if (it.enabled && reschedule) nextRun(it.trigger, now, zone) else it.nextRunMs,
            )
        })
    }

    private fun nextRun(trigger: AutoTrigger, nowMs: Long, zone: ZoneId): Long =
        trigger.nextAfter(Instant.ofEpochMilli(nowMs).atZone(zone)).toInstant().toEpochMilli()

    private fun commit(next: List<Automation>) {
        flow.value = next
        saveFailed = try {
            storage.write(encode(next))
            false
        } catch (e: IOException) {
            true
        }
    }

    private fun load(): List<Automation> = (parseJson(storage.read()) as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::automationFrom) }

    private fun encode(list: List<Automation>): String = buildJsonArray {
        for (a in list) add(buildJsonObject {
            put("id", a.id)
            put("name", a.name)
            put("enabled", a.enabled)
            put("createdMs", a.createdMs)
            a.nextRunMs?.let { put("nextRunMs", it) }
            put("trigger", triggerJson(a.trigger))
            put("actions", buildJsonArray { for (x in a.actions) add(buildJsonObject { put("tool", x.tool); put("parameters", x.parameters) }) })
            put("history", buildJsonArray {
                for (r in a.history) add(buildJsonObject { put("atMs", r.atMs); put("ok", r.ok); put("summary", r.summary) })
            })
        })
    }.toString()

    private fun triggerJson(t: AutoTrigger): JsonObject = buildJsonObject {
        when (t) {
            is AutoTrigger.Daily -> { put("type", "daily"); put("hour", t.hour); put("minute", t.minute) }
            is AutoTrigger.Weekly -> {
                put("type", "weekly"); put("hour", t.hour); put("minute", t.minute)
                put("days", buildJsonArray { for (d in t.days.sorted()) add(kotlinx.serialization.json.JsonPrimitive(d.name)) })
            }
            is AutoTrigger.Every -> { put("type", "every"); put("minutes", t.minutes) }
        }
    }

    private fun triggerFrom(o: JsonObject): AutoTrigger? {
        val hour = o.long("hour")?.toInt()
        val minute = o.long("minute")?.toInt()
        return when (o.str("type")) {
            "daily" -> if (hour != null && minute != null) AutoTrigger.Daily(hour, minute) else null
            "weekly" -> if (hour != null && minute != null) {
                AutoTrigger.Weekly(o.strings("days").mapNotNull { n -> DayOfWeek.entries.firstOrNull { it.name == n } }.toSet(), hour, minute)
            } else null
            "every" -> o.long("minutes")?.toInt()?.let { AutoTrigger.Every(it) }
            else -> null
        }
    }

    // An automation that cannot be read is dropped, never half-loaded: a damaged entry must not run as something else.
    private fun automationFrom(o: JsonObject): Automation? {
        val trigger = triggerFrom(o.obj("trigger") ?: return null) ?: return null
        val actions = o.objs("actions").map { AutoAction(it.str("tool") ?: return null, it.obj("parameters") ?: JsonObject(emptyMap())) }
        return Automation(
            id = o.str("id") ?: return null,
            name = o.str("name") ?: return null,
            trigger = trigger,
            actions = actions,
            enabled = o.bool("enabled") ?: false,
            createdMs = o.long("createdMs") ?: 0L,
            nextRunMs = o.long("nextRunMs"),
            history = o.objs("history").mapNotNull { RunRecord(it.long("atMs") ?: return@mapNotNull null, it.bool("ok") ?: false, it.str("summary").orEmpty()) },
        )
    }

    companion object {
        const val MAX_HISTORY = 20
        const val MAX_SUMMARY = 300
    }
}

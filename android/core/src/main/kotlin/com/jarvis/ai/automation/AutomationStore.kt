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

    /** Set when the saved file could not be read as a list of automations, or when some entries in it were unreadable. The damaged text is backed up first. */
    @Volatile
    var loadProblem: String? = null
        private set

    private val flow = MutableStateFlow(load())

    val automations: StateFlow<List<Automation>> = flow.asStateFlow()

    /** True when the last attempt to write to storage failed: what is on screen exists only in memory until a later save works. */
    @Volatile
    var saveFailed: Boolean = false
        private set

    init {
        recoverInterrupted()
    }

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
                runningSinceMs = null,
                history = (it.history + RunRecord(now, ok, summary.take(MAX_SUMMARY))).takeLast(MAX_HISTORY),
                nextRunMs = if (it.enabled && reschedule) nextRun(it.trigger, now, zone) else it.nextRunMs,
            )
        })
    }

    /**
     * The next time strictly after [nowMs]. A fixed-interval "every N minutes" automation counts from the slot that was due ([anchorMs]), so a
     * late start never shifts the grid; slots missed while the phone was off are skipped, not replayed.
     */
    private fun nextRun(trigger: AutoTrigger, nowMs: Long, zone: ZoneId, anchorMs: Long? = null): Long {
        if (trigger is AutoTrigger.Every && trigger.mode == RepeatMode.FIXED_INTERVAL && anchorMs != null) {
            val step = trigger.minutes * 60_000L
            if (anchorMs > nowMs) return anchorMs
            return anchorMs + ((nowMs - anchorMs) / step + 1) * step
        }
        return trigger.nextAfter(Instant.ofEpochMilli(nowMs).atZone(zone)).toInstant().toEpochMilli()
    }

    /**
     * Takes one scheduled occurrence for running, and saves that fact BEFORE anything runs. It moves the schedule past this occurrence at the
     * same time, so an alarm delivered twice, or a restart while it runs, cannot start the same occurrence again. Returns false when the
     * automation is not due, is paused, is already running, or no longer exists. [scheduled] = false is "run now": no due check, schedule unchanged.
     */
    fun claim(id: String, zone: ZoneId, scheduled: Boolean = true): Boolean = synchronized(lock) {
        val now = time.nowMs()
        val a = flow.value.firstOrNull { it.id == id } ?: return false
        if (a.runningSinceMs != null) return false
        if (scheduled) {
            if (!a.enabled) return false
            val due = a.nextRunMs ?: return false
            if (due > now) return false
        }
        commit(flow.value.map {
            if (it.id != id) it else it.copy(
                runningSinceMs = now,
                nextRunMs = if (scheduled && it.enabled) nextRun(it.trigger, now, zone, anchorMs = it.nextRunMs) else it.nextRunMs,
            )
        })
        true
    }

    /** Records how a claimed run ended and clears the "running" mark. [interrupted] = it was cut off, so nobody knows whether the actions happened. */
    fun finishRun(id: String, ok: Boolean, summary: String, interrupted: Boolean = false) = synchronized(lock) {
        val now = time.nowMs()
        commit(flow.value.map {
            if (it.id != id) it else it.copy(
                runningSinceMs = null,
                history = (it.history + RunRecord(now, ok && !interrupted, summary.take(MAX_SUMMARY), interrupted)).takeLast(MAX_HISTORY),
            )
        })
    }

    /**
     * Runs once when the store opens. Only one process runs the app and runs are claimed one at a time, so a "running" mark found now belongs to
     * a run that was cut off by the process dying. It is recorded as interrupted and NOT run again: it may already have added the task or shown
     * the notification, and doing it twice is worse than waiting for the next scheduled time.
     */
    private fun recoverInterrupted() = synchronized(lock) {
        if (flow.value.none { it.runningSinceMs != null }) return
        commit(flow.value.map {
            val since = it.runningSinceMs ?: return@map it
            it.copy(
                runningSinceMs = null,
                history = (it.history + RunRecord(since, false, INTERRUPTED_NOTE, interrupted = true)).takeLast(MAX_HISTORY),
            )
        })
    }

    /** Writes what is in memory again. Called at each wake-up while [saveFailed] is true, so a full disk that clears up saves the data by itself. */
    fun retrySave(): Boolean = synchronized(lock) {
        commit(flow.value)
        !saveFailed
    }

    private fun commit(next: List<Automation>) {
        flow.value = next
        saveFailed = try {
            storage.write(encode(next))
            false
        } catch (e: Exception) {
            // Not only IOException: any failure to write means the data is not safely stored.
            true
        }
    }

    private fun load(): List<Automation> {
        val raw = storage.read()
        if (raw.isNullOrBlank()) return emptyList()
        val array = parseJson(raw) as? JsonArray
        if (array == null) {
            storage.backupCorrupt(raw)
            loadProblem = "The saved automations file could not be read, so none were loaded. A copy of it was kept."
            return emptyList()
        }
        val items = array.mapNotNull { (it as? JsonObject)?.let(::automationFrom) }
        val dropped = array.size - items.size
        if (dropped > 0) {
            storage.backupCorrupt(raw)
            loadProblem = "$dropped saved automation(s) could not be read and were left out. A copy of the file was kept."
        }
        return items
    }

    private fun encode(list: List<Automation>): String = buildJsonArray {
        for (a in list) add(buildJsonObject {
            put("id", a.id)
            put("name", a.name)
            put("enabled", a.enabled)
            put("createdMs", a.createdMs)
            a.nextRunMs?.let { put("nextRunMs", it) }
            put("trigger", triggerJson(a.trigger))
            put("actions", buildJsonArray { for (x in a.actions) add(buildJsonObject { put("tool", x.tool); put("parameters", x.parameters) }) })
            a.runningSinceMs?.let { put("runningSinceMs", it) }
            put("history", buildJsonArray {
                for (r in a.history) add(buildJsonObject { put("atMs", r.atMs); put("ok", r.ok); put("summary", r.summary); put("interrupted", r.interrupted) })
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
            is AutoTrigger.Every -> { put("type", "every"); put("minutes", t.minutes); put("mode", t.mode.name) }
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
            "every" -> o.long("minutes")?.toInt()?.let { AutoTrigger.Every(it, RepeatMode.entries.firstOrNull { m -> m.name == o.str("mode") } ?: RepeatMode.FIXED_INTERVAL) }
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
            history = o.objs("history").mapNotNull {
                RunRecord(it.long("atMs") ?: return@mapNotNull null, it.bool("ok") ?: false, it.str("summary").orEmpty(), it.bool("interrupted") ?: false)
            },
            runningSinceMs = o.long("runningSinceMs"),
        )
    }

    companion object {
        const val MAX_HISTORY = 20
        const val MAX_SUMMARY = 300
        const val INTERRUPTED_NOTE = "Interrupted: the app stopped while this was running. It was not run again, because it may already have happened."
    }
}

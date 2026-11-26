package com.jarvis.ai.routines

import com.jarvis.ai.core.common.IdGenerator
import com.jarvis.ai.core.common.TextStorage
import com.jarvis.ai.core.common.TimeSource
import com.jarvis.ai.core.common.long
import com.jarvis.ai.core.common.parseJson
import com.jarvis.ai.core.common.str
import com.jarvis.ai.core.common.strings
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A routine: a name and a list of steps in the person's own words ("Drink a glass of water", "Stretch for five minutes").
 * The steps are only ever read out or shown. They are text, never commands: nothing in a routine is run, called or sent.
 */
data class Routine(
    val id: String,
    val name: String,
    val steps: List<String>,
    /** The daily reminder time, or null for none. Kept in step with the automation that delivers it ([RoutineReminders]). */
    val reminderHour: Int? = null,
    val reminderMinute: Int? = null,
    /** The automation that shows the reminder, so deleting the routine can remove it. */
    val automationId: String? = null,
) {
    val reminderText: String? get() = if (reminderHour != null && reminderMinute != null) "%02d:%02d".format(Locale.ROOT, reminderHour, reminderMinute) else null
}

sealed interface RoutineSave {
    data class Saved(val routine: Routine, val replaced: Boolean) : RoutineSave
    data class Rejected(val problems: List<String>) : RoutineSave
}

sealed interface RoutineMatch {
    data class Found(val routine: Routine) : RoutineMatch
    data class Several(val names: List<String>) : RoutineMatch
    data object None : RoutineMatch
}

/** Turns what the person or the assistant wrote into clean steps. */
object RoutineText {
    private val LEADING_MARK = Regex("""^\s*(?:[-*•·]+|\(?\d{1,2}[.):])\s*""")

    /**
     * Splits on new lines or semicolons; if neither is there, on commas. A leading "1." or "-" is removed. Empty pieces are dropped.
     * The text is never interpreted: a step that looks like a command is still just a sentence to read out.
     */
    fun splitSteps(raw: String): List<String> {
        val pieces = if (raw.contains('\n') || raw.contains(';')) raw.split('\n', ';') else raw.split(',')
        return pieces.map { LEADING_MARK.replace(it, "").trim().replace(Regex("""\s+"""), " ") }.filter { it.isNotEmpty() }
    }

    /** The routine's name for matching: lower case, without "my", "the" or the word "routine". "My Morning routine" and "morning" match. */
    fun key(name: String): String =
        name.lowercase(Locale.ROOT).replace(Regex("""[^\p{L}\p{N} ]"""), " ").split(' ')
            .filter { it.isNotEmpty() && it !in NOISE }.joinToString(" ")

    private val NOISE = setOf("my", "the", "a", "an", "routine", "routines")
}

/**
 * The person's routines, kept in a private file on the phone. Same safety habits as the other stores: a damaged file is backed up and reported
 * (never silently emptied), a failed write is remembered and retried, and nothing is sent anywhere.
 */
class RoutineStore(private val storage: TextStorage, private val time: TimeSource) : com.jarvis.ai.automation.RetrySave {
    private val lock = Any()
    private val ids = IdGenerator(time, "routine")

    /** Set when the saved file, or some entries in it, could not be read. A copy of the damaged text was kept. */
    @Volatile
    var loadProblem: String? = null
        private set

    /** True when the last write failed: what is on screen exists only in memory until a later save works. */
    @Volatile
    override var saveFailed: Boolean = false
        private set

    private val flow = MutableStateFlow(load())
    val routines: StateFlow<List<Routine>> = flow.asStateFlow()

    /** Saves a routine. A routine with the same name (ignoring "my", "the", "routine" and capitals) is replaced, keeping its id. */
    fun save(name: String, steps: List<String>, reminderHour: Int?, reminderMinute: Int?): RoutineSave = synchronized(lock) {
        val cleanName = name.trim().replace(Regex("""\s+"""), " ")
        val cleanSteps = steps.map { it.trim() }.filter { it.isNotEmpty() }
        val problems = mutableListOf<String>()
        if (cleanName.length !in MIN_NAME..MAX_NAME) problems += "The name must be $MIN_NAME to $MAX_NAME characters."
        if (RoutineText.key(cleanName).isEmpty()) problems += "The name needs a real word, not only \"routine\"."
        if (cleanSteps.isEmpty()) problems += "Add at least one step."
        if (cleanSteps.size > MAX_STEPS) problems += "At most $MAX_STEPS steps."
        if (cleanSteps.any { it.length > MAX_STEP }) problems += "Each step can be at most $MAX_STEP characters."
        if ((reminderHour == null) != (reminderMinute == null)) problems += "Give both the hour and the minute for the reminder."
        if (reminderHour != null && reminderMinute != null && (reminderHour !in 0..23 || reminderMinute !in 0..59)) problems += "That reminder time is not valid."
        val existing = flow.value.firstOrNull { RoutineText.key(it.name) == RoutineText.key(cleanName) }
        if (existing == null && flow.value.size >= MAX_ROUTINES) problems += "At most $MAX_ROUTINES routines. Delete one first."
        if (problems.isNotEmpty()) return RoutineSave.Rejected(problems)

        val routine = Routine(
            id = existing?.id ?: ids.next(),
            name = cleanName,
            steps = cleanSteps,
            reminderHour = reminderHour,
            reminderMinute = reminderMinute,
            automationId = existing?.automationId,
        )
        commit(if (existing == null) flow.value + routine else flow.value.map { if (it.id == existing.id) routine else it })
        RoutineSave.Saved(routine, replaced = existing != null)
    }

    /** Records which automation delivers this routine's reminder (or none). */
    fun setAutomation(id: String, automationId: String?) = synchronized(lock) {
        commit(flow.value.map { if (it.id == id) it.copy(automationId = automationId) else it })
    }

    fun delete(id: String): Routine? = synchronized(lock) {
        val gone = flow.value.firstOrNull { it.id == id } ?: return null
        commit(flow.value.filter { it.id != id })
        gone
    }

    /**
     * Finds a routine from what the person said. An exact name wins; otherwise the words must all appear in exactly one name, so "morning"
     * finds "Morning routine" but never guesses between "Morning" and "Morning workout".
     */
    fun find(query: String): RoutineMatch = synchronized(lock) {
        val key = RoutineText.key(query)
        if (key.isEmpty()) return RoutineMatch.None
        val list = flow.value
        list.firstOrNull { RoutineText.key(it.name) == key }?.let { return RoutineMatch.Found(it) }
        val words = key.split(' ')
        val partial = list.filter { r -> RoutineText.key(r.name).split(' ').let { have -> words.all { it in have } } }
        when (partial.size) {
            0 -> RoutineMatch.None
            1 -> RoutineMatch.Found(partial.single())
            else -> RoutineMatch.Several(partial.map { it.name })
        }
    }

    /** Writes what is in memory again. Called when a write failed earlier. */
    override fun retrySave(): Boolean = synchronized(lock) {
        commit(flow.value)
        !saveFailed
    }

    private fun commit(next: List<Routine>) {
        flow.value = next
        saveFailed = try {
            storage.write(encode(next))
            false
        } catch (e: Exception) {
            true
        }
    }

    private fun load(): List<Routine> {
        val raw = storage.read()
        if (raw.isNullOrBlank()) return emptyList()
        val array = parseJson(raw) as? JsonArray
        if (array == null) {
            storage.backupCorrupt(raw)
            loadProblem = "The saved routines file could not be read, so none were loaded. A copy of it was kept."
            return emptyList()
        }
        val items = array.mapNotNull { (it as? JsonObject)?.let(::routineFrom) }
        val dropped = array.size - items.size
        if (dropped > 0) {
            storage.backupCorrupt(raw)
            loadProblem = "$dropped saved routine(s) could not be read and were left out. A copy of the file was kept."
        }
        return items
    }

    private fun encode(list: List<Routine>): String = buildJsonArray {
        for (r in list) add(buildJsonObject {
            put("id", r.id)
            put("name", r.name)
            put("steps", buildJsonArray { r.steps.forEach { add(JsonPrimitive(it)) } })
            r.reminderHour?.let { put("hour", it) }
            r.reminderMinute?.let { put("minute", it) }
            r.automationId?.let { put("automationId", it) }
        })
    }.toString()

    private fun routineFrom(o: JsonObject): Routine? {
        val steps = o.strings("steps")
        if (steps.isEmpty() || steps.size > MAX_STEPS) return null
        return Routine(
            id = o.str("id") ?: return null,
            name = o.str("name") ?: return null,
            steps = steps.map { it.take(MAX_STEP) },
            reminderHour = o.long("hour")?.toInt()?.takeIf { it in 0..23 },
            reminderMinute = o.long("minute")?.toInt()?.takeIf { it in 0..59 },
            automationId = o.str("automationId"),
        )
    }

    companion object {
        const val MIN_NAME = 2
        const val MAX_NAME = 40
        const val MAX_STEPS = 12
        const val MAX_STEP = 120
        const val MAX_ROUTINES = 20
    }
}

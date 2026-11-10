package com.jarvis.ai.productivity

import com.jarvis.ai.core.common.IdGenerator
import com.jarvis.ai.core.common.TextStorage
import com.jarvis.ai.core.common.TimeSource
import com.jarvis.ai.core.common.bool
import com.jarvis.ai.core.common.long
import com.jarvis.ai.core.common.objs
import com.jarvis.ai.core.common.parseJson
import com.jarvis.ai.core.common.str
import com.jarvis.ai.core.security.SecretRedactor
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class Priority(val label: String) { LOW("Low"), NORMAL("Normal"), HIGH("High") }

data class TaskItem(
    val id: String,
    val title: String,
    val priority: Priority,
    /** End of the due day, epoch ms, or null. Only a date is kept, not a time. */
    val dueMs: Long?,
    val done: Boolean,
    val createdMs: Long,
)

data class NoteItem(val id: String, val text: String, val createdMs: Long)

data class ReminderItem(
    val id: String,
    val text: String,
    val atMs: Long,
    val delivered: Boolean,
    /** False when the time came but Android would not show the notification (for example notifications were off). */
    val notified: Boolean = true,
)

data class ProductivityState(
    val tasks: List<TaskItem> = emptyList(),
    val notes: List<NoteItem> = emptyList(),
    val reminders: List<ReminderItem> = emptyList(),
)

/** A summary of today, computed from what is stored. Nothing here is estimated or invented. */
data class DailyOverview(
    val overdue: List<TaskItem>,
    val dueToday: List<TaskItem>,
    val otherOpen: List<TaskItem>,
    val upcomingReminders: List<ReminderItem>,
    val noteCount: Int,
) {
    val isEmpty: Boolean get() = overdue.isEmpty() && dueToday.isEmpty() && otherOpen.isEmpty() && upcomingReminders.isEmpty()

    fun text(zone: ZoneId): String {
        if (isEmpty) return "Nothing planned. You have no open tasks and no reminders in the next 24 hours." +
            if (noteCount > 0) " You have $noteCount note${if (noteCount == 1) "" else "s"}." else ""
        return buildString {
            fun section(title: String, lines: List<String>) {
                if (lines.isEmpty()) return
                appendLine("$title (${lines.size}):")
                lines.forEach { appendLine("- $it") }
            }
            section("Overdue", overdue.map { it.title })
            section("Due today", dueToday.map { it.title + if (it.priority == Priority.HIGH) " (high priority)" else "" })
            section("Other open tasks", otherOpen.take(5).map { it.title })
            section(
                "Reminders in the next 24 hours",
                upcomingReminders.map { "%02d:%02d %s".format(Instant.ofEpochMilli(it.atMs).atZone(zone).hour, Instant.ofEpochMilli(it.atMs).atZone(zone).minute, it.text) },
            )
            if (noteCount > 0) append("Notes saved: $noteCount")
        }.trimEnd()
    }
}

/**
 * Tasks, notes and reminders, kept on the phone in a small private file. The spec names Room for this; Room could not be
 * built or verified in the build environment, so a plain atomic file with the same data is used (see docs/ROADMAP.md).
 * Text is redacted of API-key-shaped strings, and every list is capped.
 */
class ProductivityStore(
    private val storage: TextStorage,
    private val time: TimeSource,
    private val redact: (String) -> String = SecretRedactor::redactPatterns,
) {
    private val ids = IdGenerator(time, "item")
    private val lock = Any()
    private val flow = MutableStateFlow(ProductivityState())
    val state: StateFlow<ProductivityState> = flow.asStateFlow()

    @Volatile
    var saveFailed: Boolean = false
        private set

    init {
        load()
    }

    fun addTask(title: String, priority: Priority = Priority.NORMAL, dueMs: Long? = null): TaskItem? = synchronized(lock) {
        val clean = clean(title, MAX_TITLE) ?: return null
        val task = TaskItem(ids.next(), clean, priority, dueMs, done = false, createdMs = time.nowMs())
        commit(flow.value.copy(tasks = (flow.value.tasks + task).takeLast(MAX_ITEMS)))
        task
    }

    fun setTaskDone(id: String, done: Boolean) = synchronized(lock) {
        commit(flow.value.copy(tasks = flow.value.tasks.map { if (it.id == id) it.copy(done = done) else it }))
    }

    fun deleteTask(id: String) = synchronized(lock) { commit(flow.value.copy(tasks = flow.value.tasks.filterNot { it.id == id })) }

    fun addNote(text: String): NoteItem? = synchronized(lock) {
        val clean = clean(text, MAX_NOTE) ?: return null
        val note = NoteItem(ids.next(), clean, time.nowMs())
        commit(flow.value.copy(notes = (flow.value.notes + note).takeLast(MAX_ITEMS)))
        note
    }

    fun deleteNote(id: String) = synchronized(lock) { commit(flow.value.copy(notes = flow.value.notes.filterNot { it.id == id })) }

    /** @return null when the text is empty or the time is not in the future (or more than a year away). */
    fun addReminder(text: String, atMs: Long): ReminderItem? = synchronized(lock) {
        val now = time.nowMs()
        if (atMs <= now || atMs > now + MAX_AHEAD_MS) return null
        val clean = clean(text, MAX_TITLE) ?: return null
        val reminder = ReminderItem(ids.next(), clean, atMs, delivered = false)
        commit(flow.value.copy(reminders = (flow.value.reminders + reminder).takeLast(MAX_ITEMS)))
        reminder
    }

    fun deleteReminder(id: String) = synchronized(lock) { commit(flow.value.copy(reminders = flow.value.reminders.filterNot { it.id == id })) }

    fun markDelivered(id: String, notified: Boolean = true) = synchronized(lock) {
        commit(flow.value.copy(reminders = flow.value.reminders.map { if (it.id == id) it.copy(delivered = true, notified = notified) else it }))
    }

    /** Reminders whose time has come and that were not delivered yet, oldest first. */
    fun dueReminders(nowMs: Long): List<ReminderItem> = flow.value.reminders.filter { !it.delivered && it.atMs <= nowMs }.sortedBy { it.atMs }

    /** The next undelivered reminder time after [nowMs], or null. */
    fun nextReminderMs(nowMs: Long): Long? = flow.value.reminders.filter { !it.delivered && it.atMs > nowMs }.minOfOrNull { it.atMs }

    fun overview(nowMs: Long, zone: ZoneId): DailyOverview {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val open = flow.value.tasks.filter { !it.done }
        fun dueDay(t: TaskItem) = t.dueMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val overdue = open.filter { dueDay(it)?.isBefore(today) == true }.sortedBy { it.dueMs }
        val dueToday = open.filter { dueDay(it) == today }.sortedByDescending { it.priority.ordinal }
        val other = open.filter { it !in overdue && it !in dueToday }.sortedByDescending { it.priority.ordinal }
        val reminders = flow.value.reminders.filter { !it.delivered && it.atMs in nowMs..(nowMs + DAY_MS) }.sortedBy { it.atMs }
        return DailyOverview(overdue, dueToday, other, reminders, flow.value.notes.size)
    }

    // ---- storage -----------------------------------------------------------------------------------

    private fun clean(text: String, max: Int): String? = redact(text.trim()).take(max).trim().takeIf { it.isNotEmpty() }

    private fun commit(next: ProductivityState) {
        flow.value = next
        saveFailed = try {
            storage.write(
                buildJsonObject {
                    put("tasks", buildJsonArray {
                        for (t in next.tasks) add(buildJsonObject {
                            put("id", t.id); put("title", t.title); put("priority", t.priority.name)
                            t.dueMs?.let { put("dueMs", it) }
                            put("done", t.done); put("createdMs", t.createdMs)
                        })
                    })
                    put("notes", buildJsonArray {
                        for (n in next.notes) add(buildJsonObject { put("id", n.id); put("text", n.text); put("createdMs", n.createdMs) })
                    })
                    put("reminders", buildJsonArray {
                        for (r in next.reminders) add(buildJsonObject { put("id", r.id); put("text", r.text); put("atMs", r.atMs); put("delivered", r.delivered); put("notified", r.notified) })
                    })
                }.toString(),
            )
            false
        } catch (e: IOException) {
            true
        }
    }

    private fun load() {
        val root = parseJson(storage.read()) as? JsonObject ?: return
        flow.value = ProductivityState(
            tasks = root.objs("tasks").mapNotNull { o ->
                TaskItem(
                    id = o.str("id") ?: return@mapNotNull null,
                    title = o.str("title") ?: return@mapNotNull null,
                    priority = Priority.entries.firstOrNull { it.name == o.str("priority") } ?: Priority.NORMAL,
                    dueMs = o.long("dueMs"),
                    done = o.bool("done") ?: false,
                    createdMs = o.long("createdMs") ?: 0L,
                )
            },
            notes = root.objs("notes").mapNotNull { o ->
                NoteItem(o.str("id") ?: return@mapNotNull null, o.str("text") ?: return@mapNotNull null, o.long("createdMs") ?: 0L)
            },
            reminders = root.objs("reminders").mapNotNull { o ->
                ReminderItem(
                    o.str("id") ?: return@mapNotNull null,
                    o.str("text") ?: return@mapNotNull null,
                    o.long("atMs") ?: return@mapNotNull null,
                    o.bool("delivered") ?: false,
                    o.bool("notified") ?: true,
                )
            },
        )
    }

    companion object {
        const val MAX_ITEMS = 500
        const val MAX_TITLE = 120
        const val MAX_NOTE = 1_000
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val MAX_AHEAD_MS = 366L * DAY_MS
    }
}

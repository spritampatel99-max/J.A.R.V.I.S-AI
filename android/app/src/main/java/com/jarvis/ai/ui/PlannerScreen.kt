package com.jarvis.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.ai.AppContainer
import com.jarvis.ai.android.device.AccessChecks
import com.jarvis.ai.android.device.AccessKey
import com.jarvis.ai.android.device.AccessState
import com.jarvis.ai.productivity.Priority
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private enum class DueChoice(val label: String) { NONE("No date"), TODAY("Today"), TOMORROW("Tomorrow") }
private enum class DayChoice(val label: String) { TODAY("Today"), TOMORROW("Tomorrow") }

/** Planner: tasks, notes and reminders, all kept on this phone. Everything shown is what is really stored. */
@Composable
fun PlannerScreen(container: AppContainer) {
    val state by container.productivity.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()

    var taskTitle by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(Priority.NORMAL) }
    var due by remember { mutableStateOf(DueChoice.NONE) }
    var noteText by remember { mutableStateOf("") }
    var reminderText by remember { mutableStateOf("") }
    var reminderDay by remember { mutableStateOf(DayChoice.TODAY) }
    var hour by remember { mutableStateOf(LocalTime.now().plusHours(1).hour) }
    var minute by remember { mutableStateOf(0) }
    var pickTime by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val now = System.currentTimeMillis()
    val notificationsOff = !container.notifier.canNotify()
    val exactAllowed = AccessChecks.state(context, AccessKey.EXACT_ALARMS) != AccessState.NOT_ALLOWED

    PageColumn {
        SectionTitle(Glyph.CALENDAR, "Today", "from your planner")
        GlassCard {
            Text(container.productivity.overview(now, zone).text(zone), color = JarvisColors.Ink)
        }
        if (container.productivity.saveFailed) {
            GlassCard(accent = JarvisColors.Warning) {
                Text("The planner could not be saved to the phone's storage. Free up space; recent changes may be lost if the app closes.", color = JarvisColors.Warning)
            }
        }

        // ---- tasks ----
        SectionTitle(Glyph.CHECK_CIRCLE, "Tasks", "${state.tasks.count { !it.done }} open")
        GlassCard {
            FormField("New task", taskTitle, { taskTitle = it }, maxLength = 200)
            ChoiceChips(Priority.entries, priority, { it.label }, { priority = it })
            ChoiceChips(DueChoice.entries, due, { it.label }, { due = it })
            GlowButton("Add task", enabled = taskTitle.isNotBlank(), onClick = {
                val dueMs = when (due) {
                    DueChoice.NONE -> null
                    DueChoice.TODAY -> endOfDay(LocalDate.now(zone), zone)
                    DueChoice.TOMORROW -> endOfDay(LocalDate.now(zone).plusDays(1), zone)
                }
                val added = container.productivity.addTask(taskTitle, priority, dueMs)
                message = if (added == null) "That task was not added." else null
                if (added != null) taskTitle = ""
            })
        }
        for (task in state.tasks.sortedWith(compareBy({ it.done }, { it.dueMs ?: Long.MAX_VALUE }))) {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(
                        checked = task.done,
                        onCheckedChange = { container.productivity.setTaskDone(task.id, it) },
                        colors = CheckboxDefaults.colors(checkedColor = JarvisColors.Cyan, uncheckedColor = JarvisColors.Muted, checkmarkColor = JarvisColors.Void),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(task.title, color = if (task.done) JarvisColors.Muted else JarvisColors.Ink)
                        MutedText(buildString {
                            append(task.priority.label)
                            task.dueMs?.let { append(" - due ${formatWhen(it, zone).substringBeforeLast(',')}") }
                        })
                    }
                    GlyphButton(Glyph.TRASH, onClick = { container.productivity.deleteTask(task.id) }, size = 40.dp, description = "Delete task")
                }
            }
        }

        // ---- reminders ----
        SectionTitle(Glyph.BELL, "Reminders", "${state.reminders.count { !it.delivered }} waiting")
        GlassCard {
            FormField("Remind me to", reminderText, { reminderText = it }, maxLength = 200)
            ChoiceChips(DayChoice.entries, reminderDay, { it.label }, { reminderDay = it })
            GlassChip("At ${formatClock(hour, minute)}", onClick = { pickTime = true }, glyph = Glyph.CLOCK)
            GlowButton("Set reminder", enabled = reminderText.isNotBlank(), onClick = {
                val date = LocalDate.now(zone).plusDays(if (reminderDay == DayChoice.TODAY) 0 else 1)
                val at = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
                val added = container.productivity.addReminder(reminderText, at)
                if (added == null) {
                    message = "That time has already passed, or is too far away. Pick a time in the next year."
                } else {
                    container.schedule.scheduleNext()
                    message = "Reminder set for ${formatWhen(added.atMs, zone)}."
                    reminderText = ""
                }
            })
            message?.let { MutedText(it) }
            if (notificationsOff) Text("Notifications are off, so a reminder cannot be shown. Turn them on in Prefs > Manage access.", color = JarvisColors.Warning, style = MaterialTheme.typography.bodySmall)
            if (!exactAllowed) MutedText("Exact alarms are not allowed, so Android may show a reminder a few minutes late.")
        }
        for (reminder in state.reminders.sortedBy { it.atMs }) {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(reminder.text, color = JarvisColors.Ink)
                        MutedText(formatWhen(reminder.atMs, zone))
                    }
                    when {
                        !reminder.delivered -> StatusPill("Waiting", JarvisColors.Cyan)
                        reminder.notified -> StatusPill("Shown", JarvisColors.Good)
                        else -> StatusPill("Not shown", JarvisColors.Warning)
                    }
                    GlyphButton(Glyph.TRASH, onClick = {
                        container.productivity.deleteReminder(reminder.id)
                        container.schedule.scheduleNext()
                    }, size = 40.dp, description = "Delete reminder")
                }
                if (reminder.delivered && !reminder.notified) MutedText("The time came, but Android would not show the notification.")
            }
        }

        // ---- notes ----
        SectionTitle(Glyph.LOGS, "Notes", "${state.notes.size} saved")
        GlassCard {
            FormField("New note", noteText, { noteText = it }, singleLine = false, maxLength = 1_000, minLines = 2)
            GlowButton("Add note", enabled = noteText.isNotBlank(), onClick = {
                if (container.productivity.addNote(noteText) != null) noteText = ""
            })
        }
        for (note in state.notes.sortedByDescending { it.createdMs }) {
            GlassCard {
                Text(note.text, color = JarvisColors.Ink)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MutedText(formatWhen(note.createdMs, zone), Modifier.weight(1f))
                    GlyphButton(Glyph.TRASH, onClick = { container.productivity.deleteNote(note.id) }, size = 40.dp, description = "Delete note")
                }
            }
        }
    }

    if (pickTime) {
        TimeDialog(hour, minute, onConfirm = { h, m ->
            hour = h
            minute = m
            pickTime = false
        }, onDismiss = { pickTime = false })
    }
}

private fun endOfDay(date: LocalDate, zone: ZoneId): Long = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

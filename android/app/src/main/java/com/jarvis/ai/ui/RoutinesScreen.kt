package com.jarvis.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Text
import com.jarvis.ai.AppContainer
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.routines.Routine
import com.jarvis.ai.routines.RoutineTools
import kotlinx.coroutines.launch

/**
 * Personal routines: named lists of steps that Jarvis reads out ("start my morning routine"), with an optional daily reminder.
 * Saving goes through the same tool the assistant uses, so a routine made here and one made by voice behave the same way.
 * The steps are only text. Nothing in a routine is ever run.
 */
@Composable
fun RoutinesScreen(container: AppContainer) {
    val routines by container.routines.routines.collectAsStateWithLifecycle()
    val automations by container.automations.automations.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var steps by remember { mutableStateOf("") }
    var withReminder by remember { mutableStateOf(false) }
    var hour by remember { mutableStateOf(7) }
    var minute by remember { mutableStateOf(0) }
    var pickTime by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }

    PageColumn {
        SectionTitle(Glyph.CALENDAR, "Routines", "${routines.size} saved")
        GlassCard {
            Text("Make a list of steps in your own words. Say \"start my morning routine\" and Jarvis reads them out. A daily reminder can show them as a notification.", color = JarvisColors.Ink)
            MutedText("The steps are only read or shown. Nothing in a routine is ever run, called or sent.")
        }
        if (container.routines.saveFailed) {
            GlassCard(accent = JarvisColors.Warning) {
                Text("Routines could not be saved to the phone's storage. Free up space; recent changes may be lost if the app closes.", color = JarvisColors.Warning)
            }
        }
        container.routines.loadProblem?.let { problem ->
            GlassCard(accent = JarvisColors.Warning) { Text(problem, color = JarvisColors.Warning) }
        }

        SectionTitle(Glyph.SPARK, if (editing == null) "New routine" else "Edit routine")
        GlassCard {
            FormField("Name, for example Morning", name, { name = it }, maxLength = 40)
            FormField("Steps, one per line", steps, { steps = it }, singleLine = false, maxLength = 1_500, minLines = 4)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Daily reminder", Modifier.weight(1f), color = JarvisColors.Ink)
                SpectrumSwitch(withReminder, { withReminder = it })
            }
            if (withReminder) GlassChip("Every day at ${formatClock(hour, minute)}", onClick = { pickTime = true }, glyph = Glyph.CLOCK)
            if (withReminder && !container.notifier.canNotify()) {
                Text("Notifications are off, so the reminder cannot be shown. Turn them on in Prefs > Manage access.", color = JarvisColors.Warning, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlowButton("Save routine", enabled = name.isNotBlank() && steps.isNotBlank(), onClick = {
                    val args = buildMap<String, Any> {
                        put("name", name)
                        put("steps", steps)
                        if (withReminder) {
                            put("hour", hour)
                            put("minute", minute)
                        }
                    }
                    scope.launch {
                        val tool = container.registry.get(RoutineTools.CREATE)
                        val result = tool?.execute(ToolArgs(args))
                        when (result) {
                            is ToolResult.Success -> {
                                message = result.message
                                container.schedule.scheduleNext()
                                name = ""
                                steps = ""
                                withReminder = false
                                editing = null
                            }
                            is ToolResult.Failure -> message = result.error.message
                            null -> message = "The routine tool is not available."
                        }
                    }
                })
                if (editing != null) {
                    GlowButton("Cancel", subtle = true, onClick = {
                        editing = null
                        name = ""
                        steps = ""
                        withReminder = false
                    })
                }
            }
            message?.let { MutedText(it) }
        }

        for (routine in routines) {
            RoutineCard(
                routine = routine,
                reminderActive = container.routineReminders.isActive(routine.automationId) && automations.any { it.id == routine.automationId },
                onRead = { container.voice.speakPrivately(RoutineTools.text(routine)) },
                onEdit = {
                    editing = routine.id
                    name = routine.name
                    steps = routine.steps.joinToString("\n")
                    withReminder = routine.reminderHour != null
                    hour = routine.reminderHour ?: 7
                    minute = routine.reminderMinute ?: 0
                    message = null
                },
                onDelete = {
                    scope.launch {
                        val result = container.registry.get(RoutineTools.DELETE)?.execute(ToolArgs(mapOf("name" to routine.name)))
                        container.schedule.scheduleNext()
                        message = (result as? ToolResult.Success)?.message ?: (result as? ToolResult.Failure)?.error?.message
                    }
                },
            )
        }
        if (routines.isEmpty()) MutedText("No routines yet.")
    }

    if (pickTime) {
        TimeDialog(hour, minute, onConfirm = { h, m ->
            hour = h
            minute = m
            pickTime = false
        }, onDismiss = { pickTime = false })
    }
}

@Composable
private fun RoutineCard(routine: Routine, reminderActive: Boolean, onRead: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(routine.name, Modifier.weight(1f), color = JarvisColors.Ink)
            if (routine.reminderHour != null) {
                if (reminderActive) StatusPill("Daily ${routine.reminderText}", JarvisColors.Cyan) else StatusPill("Reminder off", JarvisColors.Warning)
            }
            GlyphButton(Glyph.TRASH, onClick = onDelete, size = 40.dp, description = "Delete routine")
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            routine.steps.forEachIndexed { i, step -> Text("${i + 1}. $step", color = JarvisColors.Ink) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip("Read it out", onClick = onRead, glyph = Glyph.VOLUME)
            GlassChip("Edit", onClick = onEdit, glyph = Glyph.TUNE)
        }
    }
}

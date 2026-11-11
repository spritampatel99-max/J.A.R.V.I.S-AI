package com.jarvis.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.ai.AppContainer
import com.jarvis.ai.automation.AutoAction
import com.jarvis.ai.automation.AutoTrigger
import com.jarvis.ai.automation.Automation
import com.jarvis.ai.automation.AutomationResult
import com.jarvis.ai.automation.AutomationRules
import com.jarvis.ai.productivity.ProductivityTools
import java.time.DayOfWeek
import java.time.ZoneId
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private enum class TriggerKind(val label: String) { DAILY("Every day"), WEEKLY("On chosen days"), EVERY("Repeating") }

private enum class ActionKind(val label: String, val tool: String, val hint: String?) {
    NOTIFY("Show a notification", ProductivityTools.SHOW_NOTIFICATION, "What should it say?"),
    TASK("Add a task", ProductivityTools.ADD_TASK, "Task title"),
    NOTE("Add a note", ProductivityTools.ADD_NOTE, "Note text"),
    OVERVIEW("Show today's overview", ProductivityTools.NOTIFY_OVERVIEW, null),
}

private class ActionDraft(kind: ActionKind = ActionKind.NOTIFY, text: String = "") {
    var kind by mutableStateOf(kind)
    var text by mutableStateOf(text)

    fun toAction(): AutoAction = AutoAction(
        kind.tool,
        when (kind) {
            ActionKind.NOTIFY -> buildJsonObject { put("text", text.trim()) }
            ActionKind.TASK -> buildJsonObject { put("title", text.trim()) }
            ActionKind.NOTE -> buildJsonObject { put("text", text.trim()) }
            ActionKind.OVERVIEW -> JsonObject(emptyMap())
        },
    )
}

private val INTERVALS = listOf(15, 30, 60, 120, 240, 480, 720, 1440)

private fun intervalLabel(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes == 60 -> "1 hour"
    minutes < 1440 -> "${minutes / 60} hours"
    else -> "1 day"
}

/**
 * Automations: small jobs that run on a schedule with nobody watching. They can only add a task, add a note or show a notification,
 * because those are the only actions that are safe to run without asking. Anything that would need an approval cannot be automated.
 */
@Composable
fun AutomationsScreen(container: AppContainer) {
    val automations by container.automations.automations.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val scope = rememberCoroutineScope()

    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(TriggerKind.DAILY) }
    var hour by remember { mutableStateOf(8) }
    var minute by remember { mutableStateOf(0) }
    var days by remember { mutableStateOf(setOf(DayOfWeek.MONDAY)) }
    var interval by remember { mutableStateOf(60) }
    val actions = remember { mutableStateListOf(ActionDraft()) }
    var pickTime by remember { mutableStateOf(false) }
    var problems by remember { mutableStateOf<List<String>>(emptyList()) }
    var deleting by remember { mutableStateOf<Automation?>(null) }
    var runMessages by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    fun reset() {
        creating = false
        name = ""
        kind = TriggerKind.DAILY
        hour = 8
        minute = 0
        days = setOf(DayOfWeek.MONDAY)
        interval = 60
        actions.clear()
        actions.add(ActionDraft())
        problems = emptyList()
    }

    PageColumn {
        GlassCard {
            Text("What automations can do", style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
            MutedText("They run on their own, so they are limited to adding a task, adding a note or showing a notification. They cannot call, send messages, open apps or do anything that would need your approval. Android decides the exact moment, so a run can be a little late, and if the phone was off an automation runs once when it is next possible.")
        }
        if (!container.notifier.canNotify()) {
            GlassCard(accent = JarvisColors.Warning) {
                Text("Notifications are off, so notification automations will fail. Turn them on in Prefs > Manage access.", color = JarvisColors.Warning)
            }
        }
        if (container.automations.saveFailed) {
            GlassCard(accent = JarvisColors.Warning) {
                Text("Automations could not be saved to the phone's storage. Free up space; recent changes may be lost.", color = JarvisColors.Warning)
            }
        }

        if (!creating) {
            GlowButton("New automation", onClick = { creating = true }, modifier = Modifier.fillMaxWidth(), enabled = automations.size < AutomationRules.MAX_AUTOMATIONS)
        } else {
            SectionTitle(Glyph.BOLT, "New automation", "step by step")
            GlassCard {
                FormField("Name", name, { name = it }, maxLength = 60)
                MutedText("When should it run?")
                ChoiceChips(TriggerKind.entries, kind, { it.label }, { kind = it })
                when (kind) {
                    TriggerKind.DAILY -> GlassChip("At ${formatClock(hour, minute)}", onClick = { pickTime = true }, glyph = Glyph.CLOCK)
                    TriggerKind.WEEKLY -> {
                        ChoiceChips(
                            DayOfWeek.entries,
                            null,
                            { day -> (if (day in days) "[x] " else "") + day.name.take(3).lowercase().replaceFirstChar { it.uppercase() } },
                            { day -> days = if (day in days) days - day else days + day },
                        )
                        GlassChip("At ${formatClock(hour, minute)}", onClick = { pickTime = true }, glyph = Glyph.CLOCK)
                    }
                    TriggerKind.EVERY -> {
                        ChoiceChips(INTERVALS, interval, ::intervalLabel, { interval = it })
                        MutedText("Not more often than every ${AutomationRules.MIN_INTERVAL_MINUTES} minutes: Android does not reliably wake an app sooner.")
                    }
                }
                MutedText("What should it do? Up to ${AutomationRules.MAX_ACTIONS} actions, in order.")
                actions.forEachIndexed { index, draft ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceChips(ActionKind.entries, draft.kind, { it.label }, { draft.kind = it })
                        draft.kind.hint?.let { hint -> FormField(hint, draft.text, { draft.text = it }, maxLength = 200) }
                        if (actions.size > 1) GlassChip("Remove action ${index + 1}", onClick = { actions.removeAt(index) }, glyph = Glyph.TRASH)
                    }
                }
                if (actions.size < AutomationRules.MAX_ACTIONS) GlassChip("Add another action", onClick = { actions.add(ActionDraft()) })
                for (p in problems) Text(p, color = JarvisColors.Coral, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlowButton("Save automation", onClick = {
                        val trigger = when (kind) {
                            TriggerKind.DAILY -> AutoTrigger.Daily(hour, minute)
                            TriggerKind.WEEKLY -> AutoTrigger.Weekly(days, hour, minute)
                            TriggerKind.EVERY -> AutoTrigger.Every(interval)
                        }
                        when (val result = container.automations.create(name, trigger, actions.map { it.toAction() }, container.registry, zone)) {
                            is AutomationResult.Saved -> {
                                container.schedule.scheduleNext()
                                reset()
                            }
                            is AutomationResult.Rejected -> problems = result.problems
                        }
                    })
                    GlowButton("Cancel", onClick = { reset() }, subtle = true)
                }
            }
        }

        SectionTitle(Glyph.CLOCK, "Your automations", "${automations.size} of ${AutomationRules.MAX_AUTOMATIONS}")
        if (automations.isEmpty()) GlassCard { MutedText("None yet.") }
        for (a in automations) {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(a.name, style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
                        MutedText(a.trigger.describe())
                    }
                    SpectrumSwitch(a.enabled, onChange = {
                        container.automations.setEnabled(a.id, it, zone)
                        container.schedule.scheduleNext()
                    })
                }
                for (action in a.actions) MutedText("- " + describe(action))
                MutedText(
                    when {
                        !a.enabled -> "Paused."
                        a.nextRunMs != null -> "Next run: ${formatWhen(a.nextRunMs!!, zone)}"
                        else -> "No run scheduled."
                    },
                )
                a.lastRun?.let { run ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill(if (run.ok) "Last run ok" else "Last run failed", if (run.ok) JarvisColors.Good else JarvisColors.Coral)
                        MutedText("${formatWhen(run.atMs, zone)} - ${run.summary}", Modifier.weight(1f))
                    }
                }
                runMessages[a.id]?.let { MutedText(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip("Run now", onClick = {
                        scope.launch {
                            val outcome = container.runAutomationNow(a.id)
                            runMessages = runMessages + (a.id to (outcome?.let { (if (it.ok) "Ran: " else "Did not run: ") + it.summary } ?: "That automation no longer exists."))
                        }
                    }, glyph = Glyph.BOLT)
                    GlassChip("Delete", onClick = { deleting = a }, glyph = Glyph.TRASH)
                }
                if (a.history.size > 1) {
                    MutedText("Earlier runs")
                    for (run in a.history.dropLast(1).takeLast(3).reversed()) {
                        MutedText("${formatWhen(run.atMs, zone)}: ${if (run.ok) "ok" else "failed"} - ${run.summary}")
                    }
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
    deleting?.let { a ->
        ConfirmDialog("Delete \"${a.name}\"?", "It will stop running. Tasks and notes it already added stay.", "Delete", onConfirm = {
            container.automations.delete(a.id)
            container.schedule.scheduleNext()
            deleting = null
        }, onDismiss = { deleting = null })
    }
}

private fun describe(action: AutoAction): String {
    val kind = ActionKind.entries.firstOrNull { it.tool == action.tool }
    val detail = (action.parameters["text"] ?: action.parameters["title"])?.toString()?.trim('"').orEmpty()
    return if (kind == null) action.tool else if (detail.isEmpty()) kind.label else "${kind.label}: ${detail.take(80)}"
}

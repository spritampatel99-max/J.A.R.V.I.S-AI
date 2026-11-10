package com.jarvis.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.ai.core.security.AuditEvent
import com.jarvis.ai.core.security.Risk
import com.jarvis.ai.history.Conversation
import com.jarvis.ai.history.HistoryFilter
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

private enum class LogView(val title: String) { CHATS("Conversations"), ACTIONS("Action record") }

/**
 * Logs: the conversations stored on this phone, searchable, and the record of confirmed and risky actions. Everything
 * shown is what the app really stored. History is kept only on this phone, in a private file, with API-key-shaped text
 * removed; the person can delete one conversation or all of them.
 */
@Composable
fun LogsScreen(vm: AgentViewModel) {
    val conversations by vm.history.collectAsStateWithLifecycle()
    val audit by vm.auditEvents.collectAsStateWithLifecycle()
    var view by rememberSaveable { mutableStateOf(LogView.CHATS) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(view) { if (view == LogView.ACTIONS) vm.refreshAudit() }

    // The store is what filters; this list is only recomputed when the conversations, the search text or the filter change.
    val shown = remember(conversations, query, filter) { vm.findHistory(query, filter) }
    val voiceCount = remember(conversations) { conversations.count { it.usedVoice } }
    val size = remember(conversations) { vm.historyBytes() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Logs", style = MaterialTheme.typography.headlineMedium, color = JarvisColors.Ink, modifier = Modifier.padding(top = 8.dp))

        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LogView.entries.forEach { GlassChip(it.title, onClick = { view = it }, selected = view == it) }
        }

        when (view) {
            LogView.CHATS -> {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search conversations") },
                    singleLine = true,
                    leadingIcon = { GlyphIcon(Glyph.SEARCH, JarvisColors.Muted, size = 20.dp) },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                    colors = jarvisFieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Counter("Conversations", conversations.size.toString(), Modifier.weight(1f))
                    Counter("By voice", voiceCount.toString(), Modifier.weight(1f))
                    Counter("On phone", formatSize(size), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip("All", onClick = { filter = HistoryFilter.ALL }, selected = filter == HistoryFilter.ALL)
                    GlassChip("Voice", onClick = { filter = HistoryFilter.VOICE }, selected = filter == HistoryFilter.VOICE, glyph = Glyph.MIC)
                    GlassChip("Actions", onClick = { filter = HistoryFilter.ACTIONS }, selected = filter == HistoryFilter.ACTIONS, glyph = Glyph.BOLT)
                    Box(Modifier.weight(1f))
                    if (conversations.isNotEmpty()) GlassChip("Clear", onClick = { confirmClear = true }, glyph = Glyph.TRASH)
                }

                if (shown.isEmpty()) {
                    GlassCard {
                        Text(
                            if (conversations.isEmpty()) "Nothing here yet" else "No match",
                            style = MaterialTheme.typography.titleMedium,
                            color = JarvisColors.Ink,
                        )
                        Text(
                            if (conversations.isEmpty()) "Your conversations are saved on this phone only, and show up here after your first message."
                            else "No conversation matches that search and filter.",
                            style = MaterialTheme.typography.bodySmall,
                            color = JarvisColors.Muted,
                        )
                    }
                } else {
                    val groups = remember(shown) { groupByDay(shown) }
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = ScreenBottomPadding)) {
                        groups.forEach { (day, list) ->
                            item(key = "day-$day") { Text(day.uppercase(), style = MaterialTheme.typography.labelMedium, color = JarvisColors.Muted, modifier = Modifier.padding(start = 4.dp, top = 6.dp)) }
                            items(list, key = { it.id }) { c ->
                                ConversationCard(
                                    c,
                                    open = expanded == c.id,
                                    onToggle = { expanded = if (expanded == c.id) null else c.id },
                                    onDelete = {
                                        vm.deleteConversation(c.id)
                                        if (expanded == c.id) expanded = null
                                    },
                                )
                            }
                        }
                    }
                }
            }

            LogView.ACTIONS -> ActionRecord(audit)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Delete all conversations?") },
            text = { Text("This removes ${conversations.size} conversation${if (conversations.size == 1) "" else "s"} from this phone. It cannot be undone. The action record is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearHistory()
                    expanded = null
                    confirmClear = false
                }) { Text("Delete all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun Counter(label: String, value: String, modifier: Modifier) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), MaterialTheme.shapes.medium)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = JarvisColors.Cyan)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = JarvisColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ConversationCard(c: Conversation, open: Boolean, onToggle: () -> Unit, onDelete: () -> Unit) {
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    GlassCard(Modifier.clickable(role = Role.Button, onClick = onToggle)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(c.title.ifBlank { "Conversation" }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink, maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis)
            if (c.usedVoice) StatusPill("VOICE", JarvisColors.Lilac)
            if (c.toolRuns > 0) StatusPill("ACTION", JarvisColors.Cyan)
        }
        Text(
            "${time.format(Date(c.updatedMs))} · ${c.exchanges} message${if (c.exchanges == 1) "" else "s"}",
            style = MaterialTheme.typography.labelSmall,
            color = JarvisColors.Muted,
        )
        if (!open) {
            c.lastReply?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = JarvisColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        } else {
            c.messages.forEach { m ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        when (m.sender) {
                            "user" -> "YOU"
                            "assistant" -> (m.answeredBy ?: "JARVIS").uppercase()
                            else -> "SYSTEM"
                        } + if (m.viaVoice) " · VOICE" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (m.sender == "user") JarvisColors.Lilac else JarvisColors.Cyan,
                    )
                    Text(m.text, style = MaterialTheme.typography.bodySmall, color = JarvisColors.Ink)
                }
            }
            TextButton(onClick = onDelete) {
                GlyphIcon(Glyph.TRASH, MaterialTheme.colorScheme.error, size = 16.dp)
                Text("  Delete this conversation", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ActionRecord(events: List<AuditEvent>) {
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }
    Text(
        "Confirmations and every medium- or high-risk action, newest first. Low-risk actions are not listed.",
        style = MaterialTheme.typography.bodySmall,
        color = JarvisColors.Muted,
        modifier = Modifier.padding(bottom = 10.dp),
    )
    if (events.isEmpty()) {
        GlassCard {
            Text("Nothing yet", style = MaterialTheme.typography.titleMedium, color = JarvisColors.Ink)
            Text(
                "When the app asks you to confirm something, or runs a medium- or high-risk action, it is recorded here.",
                style = MaterialTheme.typography.bodySmall,
                color = JarvisColors.Muted,
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = ScreenBottomPadding)) {
        items(events) { event ->
            val accent = when (event.risk) {
                Risk.HIGH -> JarvisColors.Magenta
                Risk.MEDIUM -> JarvisColors.Warning
                else -> JarvisColors.Cyan
            }
            GlassCard(accent = accent) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill("${event.risk.name.lowercase()} risk", accent)
                    Text(event.kind.name.lowercase().replace('_', ' '), style = MaterialTheme.typography.labelMedium, color = JarvisColors.Muted)
                }
                Text(event.summary, style = MaterialTheme.typography.bodyMedium, color = JarvisColors.Ink)
                if (!event.detail.isNullOrBlank()) Text(event.detail.orEmpty(), style = MaterialTheme.typography.bodySmall, color = JarvisColors.Muted)
                Text(format.format(Date(event.timestampMs)), style = MaterialTheme.typography.labelSmall, color = JarvisColors.Muted)
            }
        }
    }
}

/** Groups conversations (already newest first) under "Today", "Yesterday" and a date. */
private fun groupByDay(list: List<Conversation>): List<Pair<String, List<Conversation>>> {
    val today = startOfDay(System.currentTimeMillis())
    val dayMs = 24L * 60 * 60 * 1000
    val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
    return list.groupBy { c ->
        val day = startOfDay(c.updatedMs)
        when (day) {
            today -> "Today"
            today - dayMs -> "Yesterday"
            else -> dateFormat.format(Date(c.updatedMs))
        }
    }.toList()
}

private fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}

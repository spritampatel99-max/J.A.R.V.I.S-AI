package com.jarvis.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.ai.AppContainer
import com.jarvis.ai.memory.MemoryCategory
import com.jarvis.ai.memory.MemoryEntry
import com.jarvis.ai.memory.MemoryScope

/**
 * Memory: what the person asked to keep. Nothing is saved unless they say so (a "remember that ..." in chat, or the form here), every
 * note can be edited or deleted, and the list shows which agent a note belongs to. Notes never leave the phone unless "Use memory in
 * chat" is on, or an agent that is allowed to see them is answering through a cloud provider.
 */
@Composable
fun MemoryScreen(container: AppContainer) {
    val entries by container.memory.entries.collectAsStateWithLifecycle()
    val agents by container.agents.agents.collectAsStateWithLifecycle()
    var useInChat by remember { mutableStateOf(container.settings.useMemoryInChat) }
    var draft by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(MemoryCategory.NOTE) }
    var message by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf<String?>(ALL) }
    var editing by remember { mutableStateOf<MemoryEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val agentNames = agents.associate { it.id to it.name }
    fun owner(entry: MemoryEntry): String = entry.agentId?.let { agentNames[it] ?: "A deleted agent" } ?: "General"
    val visible = entries.filter {
        when (filter) {
            ALL -> true
            GENERAL -> it.agentId == null
            else -> it.agentId == filter
        }
    }.sortedByDescending { it.updatedMs }

    PageColumn {
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Use memory in chat", style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
                    MutedText("When on, your general notes are given to the AI that answers, so it can use them. They are sent to that provider with the request. When off, they stay on the phone.")
                }
                SpectrumSwitch(useInChat, onChange = {
                    useInChat = it
                    container.settings.useMemoryInChat = it
                })
            }
        }

        SectionTitle(Glyph.STORAGE, "Add a note", "only what you choose")
        GlassCard {
            FormField("What should it remember?", draft, { draft = it }, singleLine = false, maxLength = MAX_NOTE, minLines = 2)
            ChoiceChips(MemoryCategory.entries, category, { it.label }, { category = it })
            GlowButton("Save to memory", onClick = {
                val saved = container.memory.save(null, category, draft)
                message = when {
                    saved == null -> "Not saved. The note was empty or looked like a secret key."
                    container.memory.saveFailed -> "Saved here, but the phone could not write it to storage. It may be lost when the app closes."
                    else -> "Saved."
                }
                if (saved != null) draft = ""
            }, enabled = draft.isNotBlank())
            message?.let { MutedText(it) }
            MutedText("You can also say \"remember that ...\" in chat. Keys and tokens are never kept.")
        }

        SectionTitle(Glyph.LOGS, "Saved notes", "${entries.size} of ${com.jarvis.ai.memory.MemoryStore.MAX_ENTRIES}")
        val owners = entries.mapNotNull { it.agentId }.distinct()
        if (owners.isNotEmpty()) {
            ChoiceChips(
                listOf<String?>(ALL, GENERAL) + owners,
                filter,
                { key -> when (key) { ALL -> "All"; GENERAL -> "General"; else -> agentNames[key] ?: "Deleted agent" } },
                { filter = it },
            )
        }
        if (container.memory.saveFailed) {
            GlassCard(accent = JarvisColors.Warning) {
                Text("Memory could not be saved to the phone's storage. Free up space; recent changes may be lost if the app closes.", color = JarvisColors.Warning)
            }
        }
        if (visible.isEmpty()) {
            GlassCard { MutedText(if (entries.isEmpty()) "Nothing saved yet. The assistant never saves anything on its own." else "No notes in this view.") }
        }
        for (entry in visible) {
            GlassCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(entry.category.label, JarvisColors.Cyan)
                    StatusPill(owner(entry), if (entry.agentId == null) JarvisColors.Muted else JarvisColors.Lilac)
                }
                Text(entry.text, color = JarvisColors.Ink)
                MutedText("Saved ${formatWhen(entry.createdMs)}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip("Edit", onClick = { editing = entry })
                    GlassChip("Delete", onClick = { container.memory.delete(entry.id) }, glyph = Glyph.TRASH)
                }
            }
        }

        if (entries.isNotEmpty()) {
            GlowButton("Delete all memory", onClick = { confirmClear = true }, subtle = true, modifier = Modifier.fillMaxWidth())
        }
    }

    editing?.let { entry ->
        var text by remember(entry.id) { mutableStateOf(entry.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit note") },
            text = { FormField("Note", text, { text = it }, singleLine = false, maxLength = MAX_NOTE, minLines = 2) },
            confirmButton = {
                TextButton(onClick = {
                    container.memory.edit(entry.id, text)
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
    if (confirmClear) {
        ConfirmDialog(
            "Delete all memory?",
            "This deletes every saved note, including the notes agents have kept. It cannot be undone.",
            "Delete all",
            onConfirm = {
                container.memory.clear(MemoryScope.Everything)
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )
    }
}

private const val ALL = "__all"
private const val GENERAL = "__general"
private const val MAX_NOTE = 1_000

package com.jarvis.ai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jarvis.ai.AppContainer
import com.jarvis.ai.android.knowledge.LibraryPhase
import com.jarvis.ai.core.common.ByteFormat
import com.jarvis.ai.knowledge.KnowledgePackSpec
import com.jarvis.ai.knowledge.KnowledgePacks
import com.jarvis.ai.knowledge.OfflineEncyclopedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Prefs > Offline library. The person chooses a Wikipedia snapshot to download (nothing starts by itself), or imports a .zim file they
 * already have, and can try a lookup to see that it really works on this phone.
 */
@Composable
fun OfflineLibraryScreen(container: AppContainer) {
    val library = container.library
    val state by library.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<KnowledgePackSpec?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var topic by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { library.refresh() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) library.import(uri)
    }

    PageColumn {
        val installed = state.installed
        SectionTitle(Glyph.STORAGE, "On this phone", if (installed != null && installed.problem == null) "ready" else "none")
        GlassCard(accent = if (installed != null && installed.problem == null) JarvisColors.Good else MaterialTheme.colorScheme.outline) {
            when {
                installed == null -> MutedText("No offline library yet. Download one below, or import a .zim file you already have.")
                installed.problem != null -> {
                    StatusPill("Cannot be used", JarvisColors.Warning)
                    Text(installed.fileName, style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
                    MutedText(installed.problem)
                }
                else -> {
                    StatusPill("Ready, works with no internet", JarvisColors.Good)
                    Text(installed.title ?: installed.fileName, style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
                    MutedText("${installed.fileName} · ${ByteFormat.format(installed.bytes)}")
                }
            }
            if (installed != null && !state.busy) GlowButton("Remove from this phone", subtle = true, onClick = { confirmDelete = true })
            state.message?.let { MutedText(it) }
        }

        if (state.busy) {
            SectionTitle(Glyph.CLOUD, "Working", state.busyWith)
            GlassCard(accent = MaterialTheme.colorScheme.primary) {
                Text(
                    when (state.phase) {
                        LibraryPhase.CHECKING -> "Checking…"
                        LibraryPhase.DOWNLOADING -> "Downloading from Kiwix"
                        LibraryPhase.COPYING -> "Copying into the app"
                        LibraryPhase.VERIFYING -> "Checking the file"
                        LibraryPhase.IDLE -> ""
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                val fraction = state.progress?.fraction
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                }
                state.progress?.let { p ->
                    MutedText(if (p.totalBytes != null) "${ByteFormat.format(p.bytesDone)} of ${ByteFormat.format(p.totalBytes!!)}" else ByteFormat.format(p.bytesDone))
                }
                GlowButton("Pause", subtle = true, onClick = { library.cancel() })
            }
        }

        SectionTitle(Glyph.GLOBE, "Download", "from Kiwix")
        for (pack in KnowledgePacks.all) {
            GlassCard {
                Text(pack.displayName, style = MaterialTheme.typography.titleMedium, color = JarvisColors.Ink)
                MutedText(pack.summary)
                val partial = state.partialBytes[pack.id]
                val isInstalled = installed != null && installed.problem == null && installed.fileName.equals(pack.fileName, ignoreCase = true)
                when {
                    isInstalled -> StatusPill("Installed", JarvisColors.Good)
                    partial != null && !state.busy -> {
                        MutedText("${ByteFormat.format(partial)} already downloaded.")
                        GlowButton("Resume", onClick = { confirm = pack })
                    }
                    !state.busy -> GlowButton("Download", onClick = { confirm = pack })
                }
            }
        }

        SectionTitle(Glyph.STORAGE, "Already have a library file?")
        GlassCard {
            MutedText(
                "Any .zim file from Kiwix works, for example a different Wikipedia or a topic library. Download it with your browser, then import it here. " +
                    "Importing copies the file into the app, so you need free space for both for a moment.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                GlowButton("Import a .zim file", subtle = true, onClick = { if (!state.busy) picker.launch(arrayOf("*/*")) })
                GlowButton("Open Kiwix library", subtle = true, onClick = { openWebPage(context, KnowledgePacks.LIBRARY_URL) })
            }
        }

        if (installed != null && installed.problem == null) {
            SectionTitle(Glyph.SEARCH, "Try it")
            GlassCard {
                MutedText("Look something up in the saved library. This is the same lookup \"search offline for ...\" uses.")
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("For example: black hole") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    colors = jarvisFieldColors(),
                )
                GlowButton("Look up", onClick = {
                    val encyclopedia = library.encyclopedia()
                    val wanted = topic.trim()
                    if (encyclopedia == null || wanted.isEmpty()) {
                        answer = "Type a topic first."
                    } else {
                        answer = "Looking…"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { encyclopedia.lookup(wanted) }
                            answer = when (result) {
                                is OfflineEncyclopedia.Result.Found -> "${result.title}\n${result.text}"
                                is OfflineEncyclopedia.Result.NotFound ->
                                    "No article called \"$wanted\"." + if (result.similar.isEmpty()) "" else " Close matches: ${result.similar.joinToString(", ")}."
                                is OfflineEncyclopedia.Result.Unreadable -> result.reason
                            }
                        }
                    }
                })
                answer?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = JarvisColors.Ink) }
            }
        }

        SectionTitle(Glyph.INFO, "Good to know")
        GlassCard {
            MutedText("It answers \"who is\", \"what is\" and \"tell me about\" questions from a saved copy of Wikipedia, with no internet. Say \"search offline for black hole\" to use it on purpose. It also steps in by itself when no AI service can be reached.")
            MutedText("It gives you the opening of the article, not a conversation. It cannot do maths, news, weather or anything that changes. The copy is a snapshot, so recent events and current office-holders may be out of date.")
            MutedText("Downloads are large. Use Wi-Fi. If the connection drops, Resume continues where it stopped. Kiwix replaces its files every few months; if a download says \"not found\", open the Kiwix library, get the current file, and import it.")
            MutedText(KnowledgePacks.LICENSE_NOTE)
        }
    }

    confirm?.let { pack ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Download ${pack.displayName}?") },
            text = { Text("This is about ${ByteFormat.format(pack.approxBytes)}. Use Wi-Fi if you can. It replaces any library you already have once the new one is checked. You can pause it and resume later.") },
            confirmButton = { TextButton(onClick = { library.download(pack); confirm = null }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Not now") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove the offline library?") },
            text = { Text("It is deleted from this phone. You can download it again later.") },
            confirmButton = { TextButton(onClick = { library.delete(); confirmDelete = false }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

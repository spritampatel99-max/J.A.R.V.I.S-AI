package com.jarvis.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.ai.android.device.AccessChecks
import com.jarvis.ai.android.device.AccessKey
import com.jarvis.ai.android.device.AccessKind
import com.jarvis.ai.android.device.AccessState

/** Pages that open on top of the Prefs tab, with a back arrow. */
enum class SubPage(val title: String) {
    MODELS("On-device models"),
    CLOUD("Cloud AI"),
    ACCESS("Access"),
    AGENTS("Agents"),
    MEMORY("Memory"),
    PLANNER("Planner"),
    AUTOMATIONS("Automations"),
    OFFLINE("Offline and readiness"),
    SECURITY("Security review"),
}

/**
 * Prefs, as in the Stitch design: voice, appearance, permissions, storage and about. Every control changes something real:
 * the voice settings are used by text-to-speech, the look settings by the orb and background, the permission chips read
 * Android's actual state, and the storage figures are the real size of the saved conversations.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrefsScreen(vm: AgentViewModel, appVersion: String, onOpen: (SubPage) -> Unit, onRunSetupAgain: () -> Unit) {
    val prefs by vm.uiPrefs.collectAsStateWithLifecycle()
    val conversations by vm.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    var showVoices by remember { mutableStateOf(false) }
    var confirmPurge by remember { mutableStateOf(false) }
    var rate by remember(prefs.speechRate) { mutableFloatStateOf(prefs.speechRate) }
    var flare by remember(prefs.flare) { mutableFloatStateOf(prefs.flare) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Prefs", style = MaterialTheme.typography.headlineMedium, color = JarvisColors.Ink)

        // ---- Voice -----------------------------------------------------------------------------
        SectionTitle(Glyph.MIC, "Voice", "text to speech")
        GlassCard {
            SettingRow("Spoken replies", "Read answers aloud, but only for requests you spoke. Typed requests are answered in text.") {
                SpectrumSwitch(prefs.speakReplies, onChange = { on -> vm.updateUi { it.copy(speakReplies = on) } })
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            SettingRow("Voice", prefs.voiceName ?: "Phone default") {
                GlassChip("Change", onClick = { showVoices = true })
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Speaking speed", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
                    Text("%.2fx".format(java.util.Locale.US, rate), style = MaterialTheme.typography.labelLarge, color = JarvisColors.Cyan)
                }
                Slider(
                    value = rate,
                    onValueChange = { rate = it },
                    onValueChangeFinished = { vm.updateUi { it.copy(speechRate = rate) } },
                    valueRange = 0.75f..1.5f,
                    colors = sliderColors(),
                )
            }
            GlowButton("Preview voice", onClick = vm::previewVoice, subtle = true)
        }

        // ---- Appearance --------------------------------------------------------------------------
        SectionTitle(Glyph.TUNE, "Appearance", "dark theme")
        GlassCard {
            SettingRow("Ambient dot grid", "A faint grid of dots behind every screen.") {
                SpectrumSwitch(prefs.ambient, onChange = { on -> vm.updateUi { it.copy(ambient = on) } })
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            SettingRow("Reduce motion", "Stops the orb, the drifting light and the voice wave from moving. Android's own \"remove animations\" setting does the same.") {
                SpectrumSwitch(prefs.reduceMotion, onChange = { on -> vm.updateUi { it.copy(reduceMotion = on) } })
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Orb glow", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
                    Text("${(flare * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = JarvisColors.Cyan)
                }
                Slider(
                    value = flare,
                    onValueChange = { flare = it },
                    onValueChangeFinished = { vm.updateUi { it.copy(flare = flare) } },
                    valueRange = 0.1f..1f,
                    colors = sliderColors(),
                )
            }
        }

        // ---- Permissions ---------------------------------------------------------------------------
        SectionTitle(Glyph.LOCK, "Permissions", "what Android allows")
        GlassCard {
            val keys = AccessKey.entries.filter { it.kind != AccessKind.UNAVAILABLE }
            val states = remember(refresh) { keys.associateWith { AccessChecks.state(context, it) } }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                keys.forEach { key ->
                    val on = states.getValue(key) != AccessState.NOT_ALLOWED
                    StatusPill(key.title + if (on) ": on" else ": off", if (on) JarvisColors.Good else JarvisColors.Warning)
                }
            }
            Text(
                "These are read from Android right now. Android has no one switch for everything, so each permission is asked for separately and you can say no to any of them.",
                style = MaterialTheme.typography.bodySmall,
                color = JarvisColors.Muted,
            )
            GlowButton("Manage access", onClick = { onOpen(SubPage.ACCESS) }, modifier = Modifier.fillMaxWidth())
        }

        // ---- Assistant ---------------------------------------------------------------------------
        SectionTitle(Glyph.SPARK, "Assistant", "yours, on this phone")
        GlassCard {
            NavRow("Agents", "Build your own assistants for a job, such as teaching, study or planning.", Glyph.PERSON) { onOpen(SubPage.AGENTS) }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            NavRow("Memory", "What you asked it to remember. See, edit or delete any of it.", Glyph.STORAGE) { onOpen(SubPage.MEMORY) }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            NavRow("Planner", "Tasks, notes and reminders.", Glyph.CALENDAR) { onOpen(SubPage.PLANNER) }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            NavRow("Automations", "Small jobs that run on a schedule: add a task, add a note or show a notification.", Glyph.BOLT) { onOpen(SubPage.AUTOMATIONS) }
        }

        // ---- Status ------------------------------------------------------------------------------
        SectionTitle(Glyph.SHIELD, "Status", "what works right now")
        GlassCard {
            NavRow("Offline and readiness", "What works without internet, checked on this phone.", Glyph.GLOBE) { onOpen(SubPage.OFFLINE) }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            NavRow("Security review", "Permissions, keys, saved data and confirmations. Says what it does not check.", Glyph.LOCK) { onOpen(SubPage.SECURITY) }
        }

        // ---- AI ----------------------------------------------------------------------------------
        SectionTitle(Glyph.CHIP, "AI", "models and providers")
        GlassCard {
            NavRow("On-device models", "Download or import a model that runs on this phone.", Glyph.STORAGE) { onOpen(SubPage.MODELS) }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            NavRow("Cloud AI", "API keys, models and the order they are tried in.", Glyph.CLOUD) { onOpen(SubPage.CLOUD) }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            NavRow("Run setup again", "Walk through the first-run setup. Your choices are kept.", Glyph.SPARK, onRunSetupAgain)
        }

        // ---- Storage ---------------------------------------------------------------------------
        SectionTitle(Glyph.STORAGE, "Storage", "on this phone")
        GlassCard {
            val bytes = remember(conversations) { vm.historyBytes() }
            SettingRow(
                "Saved conversations",
                "${conversations.size} conversation${if (conversations.size == 1) "" else "s"}, ${formatSize(bytes)}. Kept only on this phone; text that looks like an API key is removed before saving.",
            ) {}
            GlowButton("Delete all conversations", onClick = { confirmPurge = true }, enabled = conversations.isNotEmpty(), subtle = true)
        }

        // ---- About ---------------------------------------------------------------------------------
        SectionTitle(Glyph.INFO, "About")
        GlassCard {
            Text("J.A.R.V.I.S. for Android $appVersion", style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
            Text(
                "No account. The app sends data only to a cloud AI provider you added a key for, and downloads a model only when you tap Download. " +
                    "Speech recognition is done by Android; depending on your phone it may use its provider's servers.",
                style = MaterialTheme.typography.bodySmall,
                color = JarvisColors.Muted,
            )
        }
    }

    if (showVoices) {
        val choices = remember { vm.voiceChoices() }
        AlertDialog(
            onDismissRequest = { showVoices = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Voice") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    VoiceOption("Phone default", prefs.voiceName == null) {
                        vm.updateUi { it.copy(voiceName = null) }
                        showVoices = false
                    }
                    if (choices.isEmpty()) {
                        Text(
                            "No other offline voices were found for your language. Install more in Android's text-to-speech settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = JarvisColors.Muted,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    choices.forEach { choice ->
                        VoiceOption(choice.label, prefs.voiceName == choice.name) {
                            vm.updateUi { it.copy(voiceName = choice.name) }
                            showVoices = false
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showVoices = false }) { Text("Close") } },
        )
    }

    if (confirmPurge) {
        AlertDialog(
            onDismissRequest = { confirmPurge = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Delete all conversations?") },
            text = { Text("This removes every saved conversation from this phone. It cannot be undone. The action record is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearHistory()
                    confirmPurge = false
                }) { Text("Delete all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmPurge = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = JarvisColors.Muted)
        }
        trailing()
    }
}

@Composable
internal fun NavRow(title: String, subtitle: String, glyph: Glyph, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlyphIcon(glyph, JarvisColors.Cyan, size = 22.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = JarvisColors.Muted)
        }
        GlyphIcon(Glyph.CHEVRON_RIGHT, JarvisColors.Muted, size = 18.dp)
    }
}

@Composable
private fun VoiceOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (selected) GlyphIcon(Glyph.CHECK, JarvisColors.Cyan, size = 18.dp) else androidx.compose.foundation.layout.Spacer(Modifier.padding(9.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (selected) JarvisColors.Cyan else JarvisColors.Ink)
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Color.White,
    activeTrackColor = JarvisColors.Violet,
    inactiveTrackColor = Color(0xFF1C243C),
)

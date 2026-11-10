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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Home, as in the Stitch design: the living orb, a state pill, the voice wave and one big mic button. Every word and
 * movement here comes from real state: the recogniser's loudness drives the orb while listening, the executor's current
 * step is shown while thinking, and the last reply is the real last reply.
 */
@Composable
fun HomeScreen(
    vm: AgentViewModel,
    onOpenChat: () -> Unit,
    onRun: (String) -> Unit,
    onPrefill: (String) -> Unit,
) {
    val voice by vm.voice.collectAsStateWithLifecycle()
    val chat by vm.chatState.collectAsStateWithLifecycle()
    val prefs by vm.uiPrefs.collectAsStateWithLifecycle()
    val toggleMic = rememberMicToggle(vm)

    val state = when {
        voice.listening -> VoiceState.LISTENING
        voice.speaking -> VoiceState.SPEAKING
        chat.running -> VoiceState.THINKING
        else -> VoiceState.IDLE
    }
    val headline = when (state) {
        VoiceState.LISTENING -> voice.partial.ifBlank { "Listening…" }
        VoiceState.THINKING -> chat.currentStep ?: "Working on it…"
        VoiceState.SPEAKING -> "Speaking…"
        VoiceState.IDLE -> "How can I help?"
    }
    val hint = when (state) {
        VoiceState.LISTENING -> "Tap the mic again when you are done."
        VoiceState.THINKING -> "Anything that needs your approval will ask you here."
        VoiceState.SPEAKING -> "Tap the button to stop."
        VoiceState.IDLE ->
            if (voice.recognizerAvailable) "Tap the mic and speak, or type in the Chat tab." else "This phone has no speech recogniser. Type in the Chat tab."
    }
    val lastReply = chat.items.lastOrNull { it.sender != Sender.USER }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = ScreenBottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HeroOrb(state, voice.level, diameter = 280.dp)

        StatusPill(state.label.uppercase(), state.colors.first())
        VoiceWave(state, voice.level)

        Text(
            headline,
            style = MaterialTheme.typography.headlineLarge,
            color = JarvisColors.Ink,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = JarvisColors.Muted, textAlign = TextAlign.Center)

        voice.message?.let { message ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(JarvisColors.Coral.copy(alpha = 0.12f))
                    .border(1.dp, JarvisColors.Coral.copy(alpha = 0.45f), MaterialTheme.shapes.medium)
                    .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = JarvisColors.Coral)
                Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClick = vm::dismissVoiceMessage)
                        .semantics { contentDescription = "Dismiss" },
                    contentAlignment = Alignment.Center,
                ) { GlyphIcon(Glyph.CLOSE, JarvisColors.Coral, size = 16.dp) }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            GlyphButton(Glyph.CHAT, onClick = onOpenChat, description = "Open chat")
            MicButton(state, enabled = state != VoiceState.THINKING && voice.recognizerAvailable, onClick = toggleMic)
            GlyphButton(
                if (prefs.speakReplies) Glyph.VOLUME else Glyph.VOLUME_OFF,
                onClick = { vm.updateUi { it.copy(speakReplies = !it.speakReplies) } },
                tint = if (prefs.speakReplies) JarvisColors.Cyan else JarvisColors.Muted,
                description = if (prefs.speakReplies) "Spoken replies are on. Tap to mute." else "Spoken replies are off. Tap to turn on.",
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            GlassChip("Battery", onClick = { onRun("How much battery do I have") }, glyph = Glyph.BOLT)
            GlassChip("Flashlight", onClick = { onRun("Turn on the flashlight") }, glyph = Glyph.FLASHLIGHT)
            GlassChip("Call…", onClick = { onPrefill("Call ") }, glyph = Glyph.CALL)
        }

        lastReply?.let { reply ->
            GlassCard(Modifier.clickable(role = Role.Button, onClick = onOpenChat), accent = if (reply.sender == Sender.SYSTEM) MaterialTheme.colorScheme.error else null) {
                Text("LAST REPLY", style = MaterialTheme.typography.labelSmall, color = JarvisColors.Muted)
                Text(reply.text, style = MaterialTheme.typography.bodyMedium, color = JarvisColors.Ink, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The large round mic button. While listening or speaking it becomes a stop button. */
@Composable
private fun MicButton(state: VoiceState, enabled: Boolean, onClick: () -> Unit) {
    val active = state == VoiceState.LISTENING || state == VoiceState.SPEAKING
    Box(
        Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(if (active) SolidColor(JarvisColors.Magenta) else JarvisColors.spectrum())
            .semantics {
                contentDescription = when (state) {
                    VoiceState.LISTENING -> "Stop listening"
                    VoiceState.SPEAKING -> "Stop speaking"
                    else -> "Start listening"
                }
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(if (active) Glyph.STOP else Glyph.MIC, Color.White, size = 32.dp, strokeWidth = 2f)
    }
}

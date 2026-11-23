package com.jarvis.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.ai.voice.ElevenVoice

/**
 * Setup step "Voice": optional. The phone's own voice always works. With the person's own ElevenLabs key, short replies can be spoken in
 * an ElevenLabs voice instead. Nothing is sent to ElevenLabs until a key is saved and a voice is chosen, and what is private (messages,
 * notifications, screen content) is never sent.
 */
@Composable
internal fun VoiceStep(vm: AgentViewModel) {
    val state by vm.eleven.collectAsStateWithLifecycle()
    val prefs by vm.uiPrefs.collectAsStateWithLifecycle()
    var keyInput by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Voice", style = MaterialTheme.typography.headlineSmall, color = colors.primary)
        Text(
            "JARVIS always has a voice: the one built into your phone, which works offline. This step is optional. " +
                "If you have an ElevenLabs account, you can add your own key and hear a more natural voice, including Hindi.",
            style = MaterialTheme.typography.bodyMedium,
        )
        VoiceNote(
            "What leaves your phone",
            "Only when you switch ElevenLabs on: the short replies JARVIS is about to say aloud are sent to ElevenLabs to be turned into speech, " +
                "so its privacy policy applies. Your messages, notifications and what is on your screen are never sent: those are always " +
                "spoken by the phone's own voice. Long replies (over 600 characters) and anything while you are offline also use the phone's voice. " +
                "Your key is stored encrypted on this phone and is sent only to ElevenLabs.",
        )

        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (state.hasKey) "Key saved - enter a new one to replace it" else "ElevenLabs API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = MaterialTheme.shapes.medium,
            colors = jarvisFieldColors(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = keyInput.isNotBlank(),
                onClick = {
                    vm.saveElevenKey(keyInput.trim())
                    keyInput = ""
                },
            ) { Text("Save key") }
            if (state.hasKey) {
                OutlinedButton(enabled = !state.busy, onClick = { vm.loadElevenVoices() }) { Text("Load my voices") }
                TextButton(onClick = { vm.removeElevenKey() }) { Text("Remove key") }
            }
        }

        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
        if (state.busy) Text("Contacting ElevenLabs…", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)

        if (state.hasKey && state.voices.isEmpty() && prefs.elevenVoiceName != null) {
            Text(
                "Chosen voice: ${prefs.elevenVoiceName}",
                style = MaterialTheme.typography.titleSmall,
            )
        }
        if (state.voices.isNotEmpty()) {
            Text("Choose a voice", style = MaterialTheme.typography.titleSmall)
            state.voices.take(MAX_VOICES_SHOWN).forEach { voice ->
                VoiceRow(voice, selected = voice.id == prefs.elevenVoiceId, onPick = { vm.chooseElevenVoice(voice) })
            }
        }

        if (state.hasKey && prefs.elevenVoiceId != null) {
            OutlinedButton(enabled = !state.busy, onClick = { vm.testElevenVoice() }) { Text("Hear this voice") }
            Text(
                "This sends one short fixed sentence to ElevenLabs.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Speak replies with this voice", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(checked = prefs.elevenEnabled, onCheckedChange = { vm.setElevenEnabled(it) })
            }
        }
        Text(
            "You can change any of this later in Prefs. If ElevenLabs cannot be reached, JARVIS uses the phone's voice instead of staying silent.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun VoiceRow(voice: ElevenVoice, selected: Boolean, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onPick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(voice.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp))
    }
}

@Composable
private fun VoiceNote(title: String, body: String) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val MAX_VOICES_SHOWN = 30

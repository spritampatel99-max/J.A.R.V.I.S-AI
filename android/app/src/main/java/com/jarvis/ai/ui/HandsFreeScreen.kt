package com.jarvis.ai.ui

import android.Manifest
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.ai.AppContainer
import kotlinx.coroutines.launch
import com.jarvis.ai.data.WakeEngine
import com.jarvis.ai.android.voice.VoskModelState
import com.jarvis.ai.android.device.AccessChecks
import com.jarvis.ai.android.device.AccessKey
import com.jarvis.ai.android.device.AccessState
import com.jarvis.ai.android.voice.HandsFreeService
import com.jarvis.ai.android.voice.HandsFreeStatus

/**
 * Prefs > Hands-free. Turns the always-on "Jarvis" listener on and off, stores the Picovoice AccessKey it needs, and says
 * plainly what it costs and what it cannot do. Every status shown here is the service's own report, not a guess.
 */
@Composable
fun HandsFreeScreen(container: AppContainer) {
    val context = LocalContext.current
    val status by HandsFreeService.state.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    val micAllowed = remember(refresh) { AccessChecks.state(context, AccessKey.MICROPHONE) == AccessState.ALLOWED }
    val overlayAllowed = remember(refresh) { AccessChecks.state(context, AccessKey.OVERLAY) == AccessState.ALLOWED }
    var hasKey by remember(refresh) { mutableStateOf(container.keys.has(HandsFreeService.ACCESS_KEY_NAME)) }
    var keyInput by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val listening = status is HandsFreeStatus.Waiting || status is HandsFreeStatus.Busy

    var engine by remember { mutableStateOf(container.settings.wakeEngine) }
    val voskState by container.voskModel.state.collectAsStateWithLifecycle()
    val heard by HandsFreeService.heardText.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun startNow() {
        if (engine == WakeEngine.PORCUPINE && !container.keys.has(HandsFreeService.ACCESS_KEY_NAME)) {
            note = "Enter your Picovoice AccessKey below first."
            return
        }
        if (engine == WakeEngine.VOSK && !container.voskModel.isInstalled()) {
            note = "Download the offline wake-word model below first."
            return
        }
        note = null
        container.settings.handsFreeOn = true
        HandsFreeService.start(context)
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh++
        if (granted) startNow() else note = "Hands-free needs the microphone. Allow it in Prefs > Access."
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }

    PageColumn {
        GlassCard(accent = if (listening) JarvisColors.Good else MaterialTheme.colorScheme.outline) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Listen for \"Jarvis\"", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = JarvisColors.Ink)
                SpectrumSwitch(
                    checked = listening,
                    onChange = { wantOn ->
                        if (!wantOn) {
                            container.settings.handsFreeOn = false
                            HandsFreeService.stop(context)
                        } else if (!micAllowed) {
                            AccessChecks.markAsked(context, AccessKey.MICROPHONE)
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            startNow()
                        }
                    },
                )
            }
            when (val s = status) {
                HandsFreeStatus.Off -> StatusPill("Off", JarvisColors.Muted)
                HandsFreeStatus.Waiting -> StatusPill("Waiting for \"Jarvis\"", JarvisColors.Good)
                HandsFreeStatus.Busy -> StatusPill("Working on a request", JarvisColors.Cyan)
                is HandsFreeStatus.Problem -> {
                    StatusPill("Problem", JarvisColors.Coral)
                    Text(s.reason, style = MaterialTheme.typography.bodySmall, color = JarvisColors.Coral)
                }
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = JarvisColors.Warning) }
            MutedText(
                "Say \"Jarvis\", wait for the beep, then ask: \"open WhatsApp\", \"call Asha\", \"what is the weather in Delhi\". " +
                    "Jarvis answers in the chat and by voice, without sending you to a web page.",
            )
        }

        SectionTitle(Glyph.MIC, "Wake-word listener", engine.label)
        GlassCard {
            ChoiceChips(
                options = WakeEngine.entries,
                selected = engine,
                label = { it.label },
                onSelect = { chosen ->
                    if (listening) {
                        note = "Turn hands-free off before changing the listener."
                    } else {
                        engine = chosen
                        container.settings.wakeEngine = chosen
                    }
                },
            )
            MutedText(
                if (engine == WakeEngine.VOSK) {
                    "Runs fully on this phone with no account and no key. It listens the whole time, so it uses more battery than Picovoice, " +
                        "and it can sometimes mishear another word as \"Jarvis\"."
                } else {
                    "Picovoice Porcupine: lighter on battery and more accurate, but it needs your own free AccessKey and contacts Picovoice to check it."
                },
            )
        }

        if (engine == WakeEngine.VOSK) {
            SectionTitle(Glyph.STORAGE, "Offline listener model", when (voskState) {
                VoskModelState.Installed -> "installed"
                is VoskModelState.Downloading -> "downloading"
                VoskModelState.Installing -> "installing"
                else -> "needed"
            })
            GlassCard {
                MutedText(
                    "A small English speech model (about 40 MB, Apache-2.0, from alphacephei.com). It downloads only when you tap the button, " +
                        "over https. There is no published checksum to check it against, so it is not verified beyond being a valid model. " +
                        "Use Wi-Fi.",
                )
                when (val v = voskState) {
                    is VoskModelState.Downloading -> MutedText("Downloading: ${formatSize(v.bytesDone)}" + (v.totalBytes?.let { " of ${formatSize(it)}" } ?: ""))
                    VoskModelState.Installing -> MutedText("Unpacking…")
                    is VoskModelState.Failed -> Text(v.reason, style = MaterialTheme.typography.bodySmall, color = JarvisColors.Coral)
                    else -> Unit
                }
                if (voskState == VoskModelState.Installed) {
                    GlowButton("Delete model", subtle = true, onClick = {
                        if (listening) HandsFreeService.stop(context)
                        container.voskModel.delete()
                    })
                    if (listening) MutedText("Last heard: " + heard.ifBlank { "nothing yet" })
                } else {
                    GlowButton(
                        "Download model",
                        enabled = voskState !is VoskModelState.Downloading && voskState != VoskModelState.Installing,
                        onClick = { scope.launch { container.voskModel.download() } },
                    )
                }
            }
        }

        if (engine == WakeEngine.PORCUPINE) {
        SectionTitle(Glyph.LOCK, "Picovoice AccessKey", if (hasKey) "saved" else "needed")
        GlassCard {
            MutedText(
                "The word \"Jarvis\" is detected on this phone by Picovoice Porcupine. It needs a free personal AccessKey from console.picovoice.ai. " +
                    "The key is stored encrypted on this phone. Picovoice may need internet to check the key when the listener starts. " +
                    "Check Picovoice's current terms for the limits of a free key.",
            )
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (hasKey) "Key saved - enter a new one to replace it" else "AccessKey") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = MaterialTheme.shapes.medium,
                colors = jarvisFieldColors(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlowButton("Save key", enabled = keyInput.isNotBlank(), onClick = {
                    val stored = container.keys.put(HandsFreeService.ACCESS_KEY_NAME, keyInput.trim())
                    note = if (stored) null else "Could not store the key securely on this device."
                    keyInput = ""
                    hasKey = container.keys.has(HandsFreeService.ACCESS_KEY_NAME)
                })
                GlowButton("Open Picovoice console", subtle = true, onClick = { openWebPage(context, "https://console.picovoice.ai/") })
            }
            if (hasKey) {
                GlowButton("Remove key", subtle = true, onClick = {
                    container.keys.remove(HandsFreeService.ACCESS_KEY_NAME)
                    container.settings.handsFreeOn = false
                    if (listening) HandsFreeService.stop(context)
                    hasKey = false
                })
            }
        }
        }

        SectionTitle(Glyph.SHIELD, "Over other apps", if (overlayAllowed) "allowed" else "off")
        GlassCard {
            MutedText(
                "Allow \"Display over other apps\" so a small card shows what Jarvis hears and answers while another app is open, " +
                    "and so \"open WhatsApp\" works when JARVIS 3.0 is not on screen. Without it, opening apps by voice works only while JARVIS 3.0 is open.",
            )
            GlowButton(if (overlayAllowed) "Manage in Settings" else "Allow", subtle = overlayAllowed, onClick = {
                val page = AccessChecks.settingsIntent(context, AccessKey.OVERLAY) ?: AccessChecks.appSettingsIntent(context)
                try {
                    settingsLauncher.launch(page)
                } catch (e: ActivityNotFoundException) {
                    settingsLauncher.launch(AccessChecks.appSettingsIntent(context))
                }
            })
        }

        SectionTitle(Glyph.INFO, "Good to know")
        GlassCard {
            MutedText("It listens for the word \"Jarvis\" only. \"Hello Jarvis\" and \"hey Jarvis\" work because they contain it. Other greetings on their own (for example \"hyee\") are not detected.")
            MutedText("Keeping the microphone open uses battery. Turn it off when you do not need it. A notification stays on screen while it is on, with a Stop button.")
            MutedText("Android does not start it again by itself after a restart or if the system closes the app. Open JARVIS 3.0 and switch it on again.")
            MutedText(
                "Calls by voice: Jarvis reads back who it will call, then waits 3 seconds. Say \"stop\" or tap Cancel to stop it; otherwise it calls. " +
                    "This applies only to a call you spoke, matched by the app's own command patterns. Anything an AI planned, and anything typed, still needs a tap.",
            )
            MutedText("Another voice (a TV, a person nearby) that says \"Jarvis\" can start a request. The 3-second window is there for that reason.")
        }
    }
}

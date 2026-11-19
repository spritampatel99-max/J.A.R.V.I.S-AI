package com.jarvis.ai.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.jarvis.ai.AppContainer
import com.jarvis.ai.ai.tools.builtin.InstalledApp
import com.jarvis.ai.android.device.AccessChecks
import com.jarvis.ai.android.device.AccessKey
import com.jarvis.ai.android.device.AccessState
import com.jarvis.ai.android.device.AndroidAppLauncher
import com.jarvis.ai.android.screen.ScreenHub

/**
 * Prefs > Phone control. Shows whether the accessibility and notification services are really running (not just switched on),
 * opens the Android pages that switch them on, says what they can and cannot do, and keeps the person's own list of apps that
 * are never read or controlled.
 */
@Composable
fun ScreenAccessScreen(container: AppContainer) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }

    val screenOn = remember(refresh) { AccessChecks.state(context, AccessKey.ACCESSIBILITY) == AccessState.ALLOWED }
    val notesOn = remember(refresh) { AccessChecks.state(context, AccessKey.NOTIFICATION_ACCESS) == AccessState.ALLOWED }
    // "Switched on" is what Settings says; "running" is whether Android has actually connected the service to this app.
    val screenRunning = remember(refresh) { ScreenHub.accessibility != null }
    val notesRunning = remember(refresh) { ScreenHub.notifications != null }

    var blocked by remember { mutableStateOf(container.settings.screenBlocklist) }
    var filter by remember { mutableStateOf("") }
    val apps by produceState(emptyList<InstalledApp>()) {
        value = AndroidAppLauncher(context, container.foreground).launchable()
    }

    fun open(key: AccessKey) {
        val page = AccessChecks.settingsIntent(context, key) ?: AccessChecks.appSettingsIntent(context)
        try {
            settingsLauncher.launch(page)
        } catch (e: ActivityNotFoundException) {
            settingsLauncher.launch(AccessChecks.appSettingsIntent(context))
        }
    }

    PageColumn {
        SectionTitle(Glyph.DEVICE, "Read and steer the screen", if (screenRunning) "running" else if (screenOn) "starting" else "off")
        GlassCard(accent = if (screenRunning) JarvisColors.Good else MaterialTheme.colorScheme.outline) {
            StatusPill(
                when {
                    screenRunning -> "Running"
                    screenOn -> "Switched on, not connected yet"
                    else -> "Off"
                },
                if (screenRunning) JarvisColors.Good else JarvisColors.Warning,
            )
            MutedText(
                "Say \"read my screen\", \"scroll down\", \"go back\", \"go home\", \"open recent apps\", \"lock the phone\" or \"take a screenshot\". " +
                    "It reads the text on the screen in front and presses Android's own navigation buttons. It cannot tap or type inside other apps.",
            )
            GlowButton(if (screenOn) "Manage in Settings" else "Turn on in Settings", subtle = screenOn, onClick = { open(AccessKey.ACCESSIBILITY) })
            MutedText(
                "If Android greys the switch out because the app was installed from a file, open Settings > Apps > J.A.R.V.I.S., tap the three dots " +
                    "and choose \"Allow restricted settings\", then switch it on.",
            )
        }

        SectionTitle(Glyph.BELL, "Read and clear notifications", if (notesRunning) "running" else if (notesOn) "starting" else "off")
        GlassCard(accent = if (notesRunning) JarvisColors.Good else MaterialTheme.colorScheme.outline) {
            StatusPill(
                when {
                    notesRunning -> "Running"
                    notesOn -> "Switched on, not connected yet"
                    else -> "Off"
                },
                if (notesRunning) JarvisColors.Good else JarvisColors.Warning,
            )
            MutedText(
                "Say \"read my notifications\" or \"clear my notifications\". Ongoing notifications such as music, ones you marked secret, " +
                    "and banking, payment and password apps are left out.",
            )
            GlowButton(if (notesOn) "Manage in Settings" else "Turn on in Settings", subtle = notesOn, onClick = { open(AccessKey.NOTIFICATION_ACCESS) })
        }

        SectionTitle(Glyph.SHIELD, "Never read these apps", "${blocked.size} added")
        GlassCard {
            MutedText(
                "Banking, payment, authenticator and password apps, and J.A.R.V.I.S. itself, are always left alone, whatever you add here. " +
                    "That built-in list goes by app name, so add any other app you want kept private.",
            )
            if (blocked.isEmpty()) {
                MutedText("You have not added any apps.")
            } else {
                for (packageName in blocked.sorted()) {
                    val label = apps.firstOrNull { it.packageName == packageName }?.label ?: packageName
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = JarvisColors.Ink)
                        GlowButton("Remove", subtle = true, onClick = {
                            blocked = blocked - packageName
                            container.settings.screenBlocklist = blocked
                        })
                    }
                }
            }
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Find an app to add") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                colors = jarvisFieldColors(),
            )
            val matches = if (filter.isBlank()) {
                emptyList()
            } else {
                apps.filter { it.packageName !in blocked && it.label.contains(filter.trim(), ignoreCase = true) }.take(6)
            }
            for (app in matches) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(app.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = JarvisColors.Ink)
                    GlowButton("Add", onClick = {
                        blocked = blocked + app.packageName
                        container.settings.screenBlocklist = blocked
                        filter = ""
                    })
                }
            }
        }

        SectionTitle(Glyph.INFO, "Good to know")
        GlassCard {
            MutedText("What it reads is shown in the chat and spoken. It is not saved in the log, and it is not sent to any AI. The chat history on this phone may still keep the reply text, so clear the history if that matters.")
            MutedText("Text on another app's screen is treated as untrusted. It can never approve anything: after reading it, any step that changes something asks for a tap.")
            MutedText("Spoken navigation and notification commands you say yourself run straight away. Anything the AI planned, and any call, still follows the confirmation rules.")
            MutedText("Android does not let any app tap or type inside other apps without a far wider accessibility power. This app deliberately does not request it.")
        }
    }
}

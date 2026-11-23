package com.jarvis.ai.ui

import android.Manifest
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.ai.AppContainer
import com.jarvis.ai.android.device.AccessChecks
import com.jarvis.ai.android.device.AccessKey
import com.jarvis.ai.android.device.AccessState
import com.jarvis.ai.android.screen.ScreenHub
import com.jarvis.ai.android.voice.BluetoothAudio

/**
 * Prefs > Bluetooth. When a Bluetooth headset or speaker is connected, JARVIS can read new notifications aloud. Everything here is off
 * until the person turns it on, and each status shown is read from Android, not assumed.
 */
@Composable
fun BluetoothScreen(container: AppContainer) {
    val context = LocalContext.current
    val ui by container.settings.ui.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    val nearbyAllowed = remember(refresh) { AccessChecks.state(context, AccessKey.BLUETOOTH) == AccessState.ALLOWED }
    val notesAllowed = remember(refresh) { AccessChecks.state(context, AccessKey.NOTIFICATION_ACCESS) == AccessState.ALLOWED }
    val notesRunning = remember(refresh) { ScreenHub.notifications != null }
    val device = remember(refresh) { BluetoothAudio.connected(context)?.let { BluetoothAudio.name(it) ?: "a Bluetooth device" } }
    var devices by remember(refresh) { mutableStateOf(container.settings.bluetoothDevices.joinToString(", ")) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }

    fun openNotificationAccess() {
        val page = AccessChecks.settingsIntent(context, AccessKey.NOTIFICATION_ACCESS) ?: AccessChecks.appSettingsIntent(context)
        try {
            settingsLauncher.launch(page)
        } catch (e: ActivityNotFoundException) {
            settingsLauncher.launch(AccessChecks.appSettingsIntent(context))
        }
    }

    PageColumn {
        SectionTitle(Glyph.MIC, "Bluetooth companion", if (device != null) "connected: $device" else "no Bluetooth audio connected")
        GlassCard {
            SettingRow(
                "Read new notifications aloud",
                "While a Bluetooth headset or speaker is connected, a new notification is read out through it. Banking, payment and password apps are never read.",
            ) {
                SpectrumSwitch(ui.announceNotifications, onChange = { on -> container.settings.updateUi { it.copy(announceNotifications = on) } })
            }
            HorizontalDivider(color = JarvisColors.Edge.copy(alpha = 0.08f))
            SettingRow(
                "Say what it says",
                "Off: only the app and who it is from are said, not the words.",
            ) {
                SpectrumSwitch(ui.announceReadText, onChange = { on -> container.settings.updateUi { it.copy(announceReadText = on) } })
            }
            HorizontalDivider(color = JarvisColors.Edge.copy(alpha = 0.08f))
            SettingRow(
                "Read the words while the phone is locked",
                "Off: if your lock screen would hide a notification's words, only the app is named.",
            ) {
                SpectrumSwitch(ui.announceLockedText, onChange = { on -> container.settings.updateUi { it.copy(announceLockedText = on) } })
            }
        }

        SectionTitle(Glyph.SHIELD, "What this needs", null)
        GlassCard(accent = if (notesRunning) JarvisColors.Good else MaterialTheme.colorScheme.outline) {
            StatusPill(
                when {
                    notesRunning -> "Notification access: running"
                    notesAllowed -> "Notification access: switched on, not connected yet"
                    else -> "Notification access: off"
                },
                if (notesRunning) JarvisColors.Good else JarvisColors.Warning,
            )
            MutedText("Reading notifications aloud needs Notification access, the same switch as \"read my notifications\".")
            if (!notesRunning) GlowButton("Open Notification access", onClick = ::openNotificationAccess, subtle = true)
            HorizontalDivider(color = JarvisColors.Edge.copy(alpha = 0.08f))
            StatusPill(
                if (nearbyAllowed) "Nearby devices: allowed" else "Nearby devices: not allowed",
                if (nearbyAllowed) JarvisColors.Good else JarvisColors.Warning,
            )
            MutedText(
                "Lets JARVIS notice the moment a device connects, to say how many notifications are waiting and, if Hands-free is on, offer a \"Start listening\" button. " +
                    "Without it, notifications are still read aloud as they arrive.",
            )
            if (!nearbyAllowed) {
                GlowButton(
                    "Allow Nearby devices",
                    onClick = {
                        AccessChecks.markAsked(context, AccessKey.BLUETOOTH)
                        permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    },
                    subtle = true,
                )
            }
        }

        SectionTitle(Glyph.TUNE, "Only these devices", "optional")
        GlassCard {
            MutedText("Leave empty to react to any Bluetooth audio device. Otherwise list names separated by commas, as the phone shows them.")
            OutlinedTextField(
                value = devices,
                onValueChange = { devices = it.take(200) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Device names") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                colors = jarvisFieldColors(),
            )
            GlowButton(
                "Save",
                onClick = { container.settings.bluetoothDevices = devices.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet() },
                subtle = true,
            )
            if (device != null) {
                GlowButton(
                    "Add $device",
                    onClick = {
                        val next = container.settings.bluetoothDevices + device
                        container.settings.bluetoothDevices = next
                        devices = next.joinToString(", ")
                    },
                    subtle = true,
                )
            }
        }

        SectionTitle(Glyph.INFO, "Honest limits", null)
        GlassCard {
            MutedText(
                "Android 14 and later do not let any app start the microphone by itself when a Bluetooth device connects. " +
                    "So when Hands-free is on, JARVIS shows a \"Start listening\" notification: one tap and it listens for \"Jarvis\". " +
                    "Reading notifications aloud does not need the microphone and works without that tap. " +
                    "Android may stop background apps to save battery; if reading stops, allow JARVIS to run in the background in Prefs > Access.",
            )
        }
    }
}

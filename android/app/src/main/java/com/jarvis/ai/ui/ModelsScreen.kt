package com.jarvis.ai.ui

import android.content.Context
import android.net.ConnectivityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.jarvis.ai.android.models.ModelManager
import com.jarvis.ai.android.models.ModelPhase
import com.jarvis.ai.android.models.ModelState
import com.jarvis.ai.core.common.ByteFormat
import com.jarvis.ai.data.SecureKeyStore
import com.jarvis.ai.models.InstallSource
import com.jarvis.ai.models.ModelCatalog
import com.jarvis.ai.models.ModelSpec
import com.jarvis.ai.models.ModelTier

/** The Models tab: install, resume, import and remove the on-device AI models. */
@Composable
fun ModelsScreen(manager: ModelManager, keys: SecureKeyStore) {
    val states by manager.states.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { manager.refresh() }
    LaunchedEffect(Unit) { manager.refresh() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AppearIn {
            GlassCard {
                Text("AI that lives on your phone", style = MaterialTheme.typography.titleMedium)
                Text(
                    "A model is downloaded once and stored privately inside the app. Sizes and checksums come from Hugging Face, " +
                        "the file is checked before it counts as installed, and nothing downloads until you tap Download.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Honest status: installing works now. Answering your chat messages with an installed model needs the " +
                        "on-device engine, which is the next step, so an installed model is not active yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JarvisColors.Warning,
                )
            }
        }
        ModelCatalog.all.forEachIndexed { index, spec ->
            val state = states[spec.id]
            if (state != null) {
                AppearIn(delayMillis = 80 + index * 90) { ModelCard(state, manager, keys) }
            }
        }
    }
}

@Composable
private fun ModelCard(state: ModelState, manager: ModelManager, keys: SecureKeyStore) {
    val spec = state.spec
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    var confirmDownload by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) manager.import(spec.id, uri)
    }

    GlassCard(accent = if (state.installed != null) JarvisColors.Good else colors.primary) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(spec.displayName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            StatusPill(if (spec.tier == ModelTier.LIGHT) "Light" else "Advanced", colors.secondary)
        }
        Text(spec.category, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Text(spec.capabilityNote, style = MaterialTheme.typography.bodyMedium)
        Text("Best for: ${spec.bestFor.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Text("Download size: ${state.size.display()}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Text("Licence: ${spec.license}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)

        val installed = state.installed
        when {
            state.busy -> BusyBlock(state, onCancel = { manager.cancel(spec.id) })

            installed != null -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusPill("Installed", JarvisColors.Good)
                    StatusPill(
                        if (installed.checksumVerified) "Checksum verified" else "Not verified",
                        if (installed.checksumVerified) JarvisColors.Good else JarvisColors.Warning,
                    )
                }
                Text(
                    "${ByteFormat.format(installed.sizeBytes)} on this phone, " +
                        if (installed.source == InstallSource.IMPORTED) "imported from a file you chose." else "downloaded from Hugging Face.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                GlowButton("Remove from phone", onClick = { confirmDelete = true }, subtle = true)
            }

            else -> {
                if (state.partialBytes > 0) {
                    Text(
                        "Paused at ${ByteFormat.format(state.partialBytes)}. Resume continues from there.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlowButton(if (state.partialBytes > 0) "Resume" else "Download", onClick = { confirmDownload = true })
                    GlowButton("Import file", onClick = { picker.launch(arrayOf("*/*")) }, subtle = true)
                }
                if (state.partialBytes > 0) {
                    GlowButton("Discard partial download", onClick = { manager.delete(spec.id) }, subtle = true)
                }
                if (spec.gated) HfTokenPanel(keys, spec)
            }
        }

        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = if (state.isError) colors.error else colors.onSurfaceVariant)
        }
    }

    if (confirmDownload) {
        val metered = remember { isMetered(context) }
        AlertDialog(
            onDismissRequest = { confirmDownload = false },
            title = { Text("Download ${spec.displayName}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Size: ${state.size.display()}. The exact size is read from Hugging Face first, and the phone needs that much free storage.")
                    if (metered) {
                        Text("You are on a connection that may be metered. A download this large can use a lot of mobile data.", color = JarvisColors.Warning)
                    } else {
                        Text("Wi-Fi is recommended.")
                    }
                    Text("You can pause it and resume later. Nothing else is sent from your phone.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDownload = false
                    manager.download(spec.id)
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { confirmDownload = false }) { Text("Not now") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove ${spec.displayName}?") },
            text = { Text("The model file is deleted from this phone. You can download it again later.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    manager.delete(spec.id)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun BusyBlock(state: ModelState, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val progress = state.progress
    val fraction = progress?.fraction
    val label = when (state.phase) {
        ModelPhase.CHECKING -> "Checking the file on Hugging Face…"
        ModelPhase.DOWNLOADING -> "Downloading"
        ModelPhase.COPYING -> "Copying into the app"
        ModelPhase.VERIFYING -> "Checking the checksum"
        ModelPhase.IDLE -> ""
    }
    Text(label, style = MaterialTheme.typography.titleSmall, color = colors.primary)
    if (fraction != null) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
            color = colors.primary,
            trackColor = colors.surfaceContainerHighest,
        )
    } else {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = colors.primary,
            trackColor = colors.surfaceContainerHighest,
        )
    }
    if (progress != null) {
        val total = progress.totalBytes
        val percent = fraction?.let { " · ${(it * 100).toInt()}%" }.orEmpty()
        Text(
            if (total != null) "${ByteFormat.format(progress.bytesDone)} of ${ByteFormat.format(total)}$percent"
            else ByteFormat.format(progress.bytesDone),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
        )
    }
    GlowButton("Cancel", onClick = onCancel, subtle = true)
}

@Composable
private fun HfTokenPanel(keys: SecureKeyStore, spec: ModelSpec) {
    val context = LocalContext.current
    var hasToken by remember { mutableStateOf(keys.has(AppContainer.HF_TOKEN_KEY)) }
    var input by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${spec.displayName} is gated by its publisher", style = MaterialTheme.typography.titleSmall, color = colors.secondary)
        Text(
            "To download it you must accept its licence on Hugging Face and give this app a read-only access token. " +
                "If you would rather not, download the file yourself and use Import file.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Text("1. Open the model page, sign in and accept the licence.", style = MaterialTheme.typography.bodySmall)
        GlowButton("Open model page", onClick = { openWebPage(context, "https://huggingface.co/${spec.repo}") }, subtle = true)
        Text("2. Create an access token with Read permission.", style = MaterialTheme.typography.bodySmall)
        GlowButton("Open token page", onClick = { openWebPage(context, "https://huggingface.co/settings/tokens") }, subtle = true)
        Text(
            "3. Paste it here. It is stored encrypted on this phone and sent only to Hugging Face.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (hasToken) "Token saved - paste a new one to replace it" else "Hugging Face token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = MaterialTheme.shapes.medium,
            colors = jarvisFieldColors(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlowButton("Save token", enabled = input.isNotBlank(), onClick = {
                val stored = keys.put(AppContainer.HF_TOKEN_KEY, input.trim())
                hasToken = keys.has(AppContainer.HF_TOKEN_KEY)
                note = if (stored) "Token saved." else "Could not store the token securely on this device."
                input = ""
            })
            if (hasToken) {
                GlowButton("Remove token", subtle = true, onClick = {
                    keys.remove(AppContainer.HF_TOKEN_KEY)
                    hasToken = false
                    note = "Token removed."
                })
            }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
    }
}

private fun isMetered(context: Context): Boolean = try {
    (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
} catch (e: SecurityException) {
    true
}

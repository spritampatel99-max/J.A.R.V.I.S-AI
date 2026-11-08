package com.jarvis.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.ai.ai.providers.ProviderId
import com.jarvis.ai.core.security.AuditEvent
import com.jarvis.ai.core.security.ConfirmationRequest
import com.jarvis.ai.core.security.Risk
import java.text.DateFormat
import java.util.Date

private val TAB_TITLES = listOf("Chat", "Providers", "Activity")

@Composable
fun JarvisApp(vm: AgentViewModel, onRunSetupAgain: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val pending by vm.pendingConfirmation.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                TAB_TITLES.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = {
                            tab = index
                            if (index == 2) vm.refreshAudit()
                        },
                        icon = {},
                        label = { Text(title) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> ChatScreen(vm)
                1 -> ProvidersScreen(vm, onRunSetupAgain = onRunSetupAgain)
                else -> ActivityScreen(vm)
            }
        }
    }

    pending?.let { request -> ConfirmationDialog(request, onAllow = { vm.approve(request.id) }, onDeny = { vm.deny(request.id) }) }
}

/**
 * The only place a sensitive action can be approved. The text shown comes from the tool's
 * own description of the validated arguments, never from model output. Tapping outside or
 * pressing back counts as "Deny".
 */
@Composable
private fun ConfirmationDialog(request: ConfirmationRequest, onAllow: () -> Unit, onDeny: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text(if (request.risk == Risk.HIGH) "Confirm sensitive action" else "Confirm action") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(request.summary)
                Text(
                    "Risk level: ${request.risk.name.lowercase()}. This request expires if you do not answer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = onAllow) { Text("Allow") } },
        dismissButton = { TextButton(onClick = onDeny) { Text("Deny") } },
    )
}

// ---- Chat ----------------------------------------------------------------------------------

@Composable
private fun ChatScreen(vm: AgentViewModel) {
    val state by vm.chatState.collectAsStateWithLifecycle()
    val active by vm.activeProvider.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.items.size) {
        if (state.items.isNotEmpty()) listState.animateScrollToItem(state.items.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            "J.A.R.V.I.S.",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "AI provider: ${active?.displayName ?: "none used yet"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        if (state.items.isEmpty()) {
            Text(
                "Try: \"turn on the flashlight, set an alarm for 6 AM tomorrow and open YouTube\". " +
                    "Add an API key under Providers first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.items, key = { it.id }) { item -> MessageBubble(item) }
            }
        }

        if (state.running) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            Text(
                state.currentStep ?: "Working…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask or command…") },
                enabled = !state.running,
                maxLines = 4,
            )
            if (state.running) {
                OutlinedButton(onClick = vm::cancel) { Text("Stop") }
            } else {
                Button(
                    onClick = {
                        vm.send(input)
                        input = ""
                    },
                    enabled = input.isNotBlank(),
                ) { Text("Send") }
            }
        }
    }
}

@Composable
private fun MessageBubble(item: ChatItem) {
    val colors = MaterialTheme.colorScheme
    val (container, label) = when (item.sender) {
        Sender.USER -> colors.surfaceVariant to "You"
        Sender.ASSISTANT -> colors.surface to (item.provider?.displayName ?: "Jarvis")
        Sender.SYSTEM -> colors.surface to "System"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = container,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (item.sender == Sender.SYSTEM) colors.error else colors.primary,
            )
            Text(item.text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
        }
    }
}

// ---- Providers -------------------------------------------------------------------------------

@Composable
internal fun ProvidersScreen(vm: AgentViewModel, showTitle: Boolean = true, onRunSetupAgain: (() -> Unit)? = null) {
    val state by vm.settingsState.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showTitle) Text("AI providers", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            "They are tried top to bottom until one answers. Keys are encrypted with the Android Keystore and never " +
                "written to logs. A key stored on a phone can still be extracted by malware or on a rooted device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        state.providers.forEachIndexed { index, row ->
            ProviderCard(
                row = row,
                canMoveUp = index > 0,
                canMoveDown = index < state.providers.lastIndex,
                vm = vm,
            )
        }

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Also ask before medium-risk actions", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Setting an alarm, for example. Sensitive actions always ask, whatever this is set to.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.confirmMedium, onCheckedChange = vm::setConfirmMedium)
        }

        if (onRunSetupAgain != null) {
            HorizontalDivider()
            Text("Setup", style = MaterialTheme.typography.titleSmall)
            Text(
                "Walk through the setup again. Your earlier choices are kept and you can change any of them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onRunSetupAgain) { Text("Run setup again") }
        }
    }
}

@Composable
private fun ProviderCard(
    row: ProviderRow,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    vm: AgentViewModel,
) {
    var keyInput by remember(row.id) { mutableStateOf("") }
    var modelInput by remember(row.id, row.model) { mutableStateOf(row.model) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.id.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (row.configured) "Ready" else "Not configured",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { vm.move(row.id, -1) }, enabled = canMoveUp) { Text("Up") }
                TextButton(onClick = { vm.move(row.id, 1) }, enabled = canMoveDown) { Text("Down") }
            }

            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (row.hasKey) "Key saved - enter a new one to replace it" else "API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )

            OutlinedTextField(
                value = modelInput,
                onValueChange = { modelInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        when (row.id) {
                            ProviderId.OPENROUTER -> "Models (comma-separated, tried in order)"
                            ProviderId.OPENAI -> "Model (required: enter the model you want to use)"
                            else -> "Model"
                        },
                    )
                },
                singleLine = row.id != ProviderId.OPENROUTER,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (keyInput.isNotBlank()) vm.saveKey(row.id, keyInput)
                    vm.saveModel(row.id, modelInput)
                    keyInput = ""
                }) { Text("Save") }
                if (row.hasKey) {
                    OutlinedButton(onClick = { vm.removeKey(row.id) }) { Text("Remove key") }
                }
            }
        }
    }
}

// ---- Activity / audit ---------------------------------------------------------------------------

@Composable
private fun ActivityScreen(vm: AgentViewModel) {
    val events by vm.auditEvents.collectAsStateWithLifecycle()
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            "Activity log",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "Confirmations and every medium- or high-risk action, newest first. Low-risk actions are not listed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (events.isEmpty()) {
            Text("Nothing yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(events) { event -> AuditRow(event, format.format(Date(event.timestampMs))) }
            }
        }
    }
}

@Composable
private fun AuditRow(event: AuditEvent, time: String) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(10.dp)) {
            Text(
                "${event.kind.name.lowercase().replace('_', ' ')} - ${event.risk.name.lowercase()} risk",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(event.summary, style = MaterialTheme.typography.bodyMedium)
            if (!event.detail.isNullOrBlank()) {
                Text(event.detail.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

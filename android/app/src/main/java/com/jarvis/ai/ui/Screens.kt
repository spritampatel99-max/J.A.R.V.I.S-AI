package com.jarvis.ai.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.ai.AppContainer
import com.jarvis.ai.ai.providers.ProviderId
import com.jarvis.ai.ai.providers.RemoteModel
import com.jarvis.ai.core.security.AuditEvent
import com.jarvis.ai.core.security.ConfirmationRequest
import com.jarvis.ai.core.security.Risk
import java.text.DateFormat
import java.util.Date

// ---- The app shell -------------------------------------------------------------------------------------------

@Composable
fun JarvisApp(vm: AgentViewModel, container: AppContainer, onRunSetupAgain: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val pending by vm.pendingConfirmation.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = Color.Transparent,
        bottomBar = {
            JarvisTabBar(
                selected = tab,
                onSelect = { index ->
                    tab = index
                    if (index == AppTab.LOG.ordinal) vm.refreshAudit()
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            AnimatedContent(
                targetState = tab,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    (fadeIn(tween(280, delayMillis = 60)) + slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it / 24 }) togetherWith
                        fadeOut(tween(120))
                },
                label = "tab",
            ) { page ->
                when (page) {
                    AppTab.CHAT.ordinal -> ChatScreen(vm)
                    AppTab.MODELS.ordinal -> ModelsScreen(container.modelManager, container.keys)
                    AppTab.CLOUD.ordinal -> ProvidersScreen(vm, onRunSetupAgain = onRunSetupAgain)
                    AppTab.ACCESS.ordinal -> AccessScreen()
                    else -> ActivityScreen(vm)
                }
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
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDeny,
        containerColor = colors.surfaceContainerHigh,
        title = {
            Text(
                if (request.risk == Risk.HIGH) "Confirm sensitive action" else "Confirm action",
                color = if (request.risk == Risk.HIGH) colors.error else colors.onSurface,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(request.summary)
                Text(
                    "Risk level: ${request.risk.name.lowercase()}. This request expires if you do not answer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        },
        confirmButton = { GlowButton("Allow", onClick = onAllow) },
        dismissButton = { TextButton(onClick = onDeny) { Text("Deny") } },
    )
}

// ---- Chat ----------------------------------------------------------------------------------------------------

/** Phrases that the app runs itself, with no cloud AI. Each one is a real command the built-in matcher understands. */
private val SUGGESTIONS = listOf(
    "Turn on the flashlight",
    "Turn off the flashlight",
    "Open YouTube",
    "Set an alarm for 7 AM",
    "How much battery do I have",
)

@Composable
private fun ChatScreen(vm: AgentViewModel) {
    val state by vm.chatState.collectAsStateWithLifecycle()
    val active by vm.activeProvider.collectAsStateWithLifecycle()
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }
    val anyProvider = settings.providers.any { it.configured }
    val newestId = state.items.lastOrNull()?.id

    LaunchedEffect(state.items.size) {
        if (state.items.isNotEmpty()) listState.animateScrollToItem(state.items.lastIndex)
    }

    fun submit() {
        if (input.isNotBlank() && !state.running) {
            vm.send(input)
            input = ""
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PulseOrb(active = state.running, diameter = 56.dp)
            Column {
                Text("J.A.R.V.I.S.", style = MaterialTheme.typography.headlineMedium, color = colors.primary)
                Text(
                    when {
                        active != null -> "Last AI reply: ${active?.displayName}"
                        anyProvider -> "Cloud AI is set up"
                        else -> "Built-in commands only. No cloud AI set up yet."
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        if (state.items.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppearIn {
                    GlassCard {
                        Text("At your service", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Tap a command to run it, or type your own. These commands run on the phone itself, " +
                                "so they work with no internet and no AI key.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                SUGGESTIONS.forEachIndexed { index, text ->
                    AppearIn(delayMillis = 120 + index * 70) {
                        CommandChip(text, onClick = { vm.send(text) })
                    }
                }
                if (!anyProvider) {
                    AppearIn(delayMillis = 120 + SUGGESTIONS.size * 70) {
                        GlassCard(accent = JarvisColors.Warning) {
                            Text("Free-form chat needs a cloud AI", style = MaterialTheme.typography.titleSmall, color = JarvisColors.Warning)
                            Text(
                                "Questions and longer plans are answered by an AI provider. Add a key in the Cloud tab. " +
                                    "An on-device model can be installed in the Models tab, but chatting with it is not built yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.items, key = { it.id }) { item ->
                    MessageBubble(item, animate = item.id == newestId, modifier = Modifier.animateItem())
                }
            }
        }

        if (state.running) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TypingDots()
                Text(
                    state.currentStep ?: "Working…",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask or command…") },
                maxLines = 4,
                shape = MaterialTheme.shapes.extraLarge,
                colors = jarvisFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            SendButton(
                canSend = input.isNotBlank(),
                running = state.running,
                onSend = ::submit,
                onStop = vm::cancel,
            )
        }
    }
}

@Composable
private fun CommandChip(text: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceContainer.copy(alpha = 0.9f))
            .border(1.dp, colors.primary.copy(alpha = 0.3f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(">", style = MaterialTheme.typography.labelMedium, color = colors.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
    }
}

@Composable
private fun SendButton(canSend: Boolean, running: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val usable = canSend || running
    val scale by animateFloatAsState(
        targetValue = if (usable) 1f else 0.9f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "sendScale",
    )
    val arrow = colors.onPrimary
    Box(
        Modifier
            .size(52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (usable) 1f else 0.45f
            }
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(colors.primary, colors.secondary)))
            .semantics { contentDescription = if (running) "Stop" else "Send" }
            .clickable(enabled = usable, role = Role.Button, onClick = if (running) onStop else onSend),
        contentAlignment = Alignment.Center,
    ) {
        if (running) {
            Box(Modifier.size(16.dp).background(arrow, RoundedCornerShape(3.dp)))
        } else {
            Canvas(Modifier.size(24.dp)) { drawSendArrow(arrow) }
        }
    }
}

@Composable
private fun MessageBubble(item: ChatItem, animate: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val appear = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (appear.value < 1f) appear.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
    }

    val isUser = item.sender == Sender.USER
    val isSystem = item.sender == Sender.SYSTEM
    val shape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = if (isUser) 20.dp else 6.dp,
        bottomEnd = if (isUser) 6.dp else 20.dp,
    )
    val label = when (item.sender) {
        Sender.USER -> "You"
        Sender.ASSISTANT -> item.source ?: item.provider?.displayName ?: "Jarvis"
        Sender.SYSTEM -> "System"
    }
    val fill = when {
        isUser -> Brush.linearGradient(listOf(colors.primaryContainer, colors.secondaryContainer))
        isSystem -> Brush.linearGradient(listOf(colors.error.copy(alpha = 0.14f), colors.error.copy(alpha = 0.08f)))
        else -> Brush.linearGradient(listOf(colors.surfaceContainerHigh, colors.surfaceContainer))
    }
    val edge = when {
        isUser -> colors.primary.copy(alpha = 0.35f)
        isSystem -> colors.error.copy(alpha = 0.45f)
        else -> colors.outline
    }

    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * 24.dp.toPx()
            },
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Column(
            Modifier
                .widthIn(max = 330.dp)
                .clip(shape)
                .background(fill)
                .border(1.dp, edge, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    isSystem -> colors.error
                    isUser -> colors.onPrimaryContainer
                    else -> colors.primary
                },
            )
            Text(item.text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
        }
    }
}

// ---- Cloud providers ---------------------------------------------------------------------------------------------

/** Providers that try several models in order, so a picked model is added to the list instead of replacing it. */
private fun isPool(id: ProviderId): Boolean = id == ProviderId.GROQ || id == ProviderId.OPENROUTER

private fun addToPool(current: String, model: String): String {
    val parts = current.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    return if (model in parts) parts.joinToString(",") else (parts + model).joinToString(",")
}

@Composable
internal fun ProvidersScreen(vm: AgentViewModel, showTitle: Boolean = true, onRunSetupAgain: (() -> Unit)? = null) {
    val state by vm.settingsState.collectAsStateWithLifecycle()
    val lists by vm.modelLists.collectAsStateWithLifecycle()
    val tests by vm.providerTests.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (showTitle) Text("CLOUD AI", style = MaterialTheme.typography.headlineSmall, color = colors.primary)
        AppearIn {
            GlassCard {
                Text("Optional online AI", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Providers are tried top to bottom until one answers. Keys are encrypted with the Android Keystore and never " +
                        "written to logs. A key stored on a phone can still be extracted by malware or on a rooted device. " +
                        "Model names change often, so use Load models to see what your own key can use, then Test.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }

        state.providers.forEachIndexed { index, row ->
            AppearIn(delayMillis = 80 + index * 80) {
                ProviderCard(
                    row = row,
                    canMoveUp = index > 0,
                    canMoveDown = index < state.providers.lastIndex,
                    modelList = lists[row.id],
                    test = tests[row.id],
                    vm = vm,
                )
            }
        }

        HorizontalDivider(color = colors.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Also ask before medium-risk actions", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Setting an alarm, for example. Sensitive actions always ask, whatever this is set to.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.confirmMedium,
                onCheckedChange = vm::setConfirmMedium,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onPrimary,
                    checkedTrackColor = colors.primary,
                    uncheckedThumbColor = colors.onSurfaceVariant,
                    uncheckedTrackColor = colors.surfaceContainerHighest,
                    uncheckedBorderColor = colors.outline,
                ),
            )
        }

        if (onRunSetupAgain != null) {
            HorizontalDivider(color = colors.outlineVariant)
            Text("Setup", style = MaterialTheme.typography.titleSmall)
            Text(
                "Walk through the setup again. Your earlier choices are kept and you can change any of them.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            GlowButton("Run setup again", onClick = onRunSetupAgain, subtle = true)
        }
    }
}

@Composable
private fun ProviderCard(
    row: ProviderRow,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    modelList: ModelListState?,
    test: ProviderTestState?,
    vm: AgentViewModel,
) {
    val colors = MaterialTheme.colorScheme
    var keyInput by remember(row.id) { mutableStateOf("") }
    var modelInput by remember(row.id, row.model) { mutableStateOf(row.model) }
    var showPicker by remember(row.id) { mutableStateOf(false) }

    /** Saves what is typed so Test and Load models use exactly what the user sees. */
    fun commit() {
        if (keyInput.isNotBlank()) vm.saveKey(row.id, keyInput)
        vm.saveModel(row.id, modelInput)
        keyInput = ""
    }

    GlassCard(accent = if (row.configured) JarvisColors.Good else colors.outline) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.id.displayName, style = MaterialTheme.typography.titleLarge)
                StatusPill(
                    if (row.configured) "Key and model saved" else "Not configured",
                    if (row.configured) JarvisColors.Good else colors.onSurfaceVariant,
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
            shape = MaterialTheme.shapes.medium,
            colors = jarvisFieldColors(),
        )

        OutlinedTextField(
            value = modelInput,
            onValueChange = { modelInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(
                    when (row.id) {
                        ProviderId.OPENROUTER -> "Models (comma-separated, tried in order)"
                        ProviderId.GROQ -> "Models (comma-separated, tried in order)"
                        ProviderId.OPENAI -> "Model (required: enter the model you want to use)"
                        else -> "Model"
                    },
                )
            },
            singleLine = !isPool(row.id),
            shape = MaterialTheme.shapes.medium,
            colors = jarvisFieldColors(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlowButton("Save", onClick = { commit() })
            GlowButton("Load models", subtle = true, onClick = {
                vm.loadModels(row.id, keyInput)
                showPicker = true
            })
            GlowButton("Test", subtle = true, enabled = test?.running != true, onClick = {
                commit()
                vm.testProvider(row.id)
            })
        }
        if (row.hasKey) {
            TextButton(onClick = { vm.removeKey(row.id) }) { Text("Remove key", color = colors.error) }
        }

        if (test != null) {
            if (test.running) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TypingDots()
                    Text("Sending one small test request…", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            } else if (test.message != null) {
                Text(
                    test.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (test.ok == true) JarvisColors.Good else colors.error,
                )
            }
        }
    }

    if (showPicker) {
        ModelPickerDialog(
            provider = row.id,
            state = modelList ?: ModelListState(loading = true),
            pool = isPool(row.id),
            onPick = { id ->
                modelInput = if (isPool(row.id)) addToPool(modelInput, id) else id
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun ModelPickerDialog(
    provider: ProviderId,
    state: ModelListState,
    pool: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var filter by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceContainerHigh,
        title = { Text("${provider.displayName} models") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val failure = state.error
                when {
                    state.loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TypingDots()
                        Text("Asking ${provider.displayName} which models your key can use…", style = MaterialTheme.typography.bodySmall)
                    }
                    failure != null -> Text(failure, style = MaterialTheme.typography.bodyMedium, color = colors.error)
                    state.models.isEmpty() -> Text("No chat models were returned for this key.", style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        Text(
                            if (pool) "Tap a model to add it to the list. They are tried in order. Press Save afterwards."
                            else "Tap a model to use it. Press Save afterwards.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Filter") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            colors = jarvisFieldColors(),
                        )
                        val shown = state.models.filter { filter.isBlank() || it.id.contains(filter.trim(), ignoreCase = true) }
                        LazyColumn(Modifier.heightIn(max = 300.dp)) {
                            items(shown, key = { it.id }) { model -> ModelRow(model, onClick = { onPick(model.id) }) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ModelRow(model: RemoteModel, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(model.id, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = colors.onSurface)
            val label = model.label
            if (label != null && label != model.id) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        if (model.free) StatusPill("free", JarvisColors.Good)
    }
}

// ---- Activity / audit ----------------------------------------------------------------------------------------------

@Composable
private fun ActivityScreen(vm: AgentViewModel) {
    val events by vm.auditEvents.collectAsStateWithLifecycle()
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }
    val colors = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("ACTIVITY LOG", style = MaterialTheme.typography.headlineSmall, color = colors.primary, modifier = Modifier.padding(top = 12.dp))
        Text(
            "Confirmations and every medium- or high-risk action, newest first. Low-risk actions are not listed.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )
        if (events.isEmpty()) {
            AppearIn {
                GlassCard {
                    Text("Nothing yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "When the app asks you to confirm something, or runs a medium- or high-risk action, it is recorded here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(events) { event -> AuditRow(event, format.format(Date(event.timestampMs))) }
            }
        }
    }
}

@Composable
private fun AuditRow(event: AuditEvent, time: String) {
    val colors = MaterialTheme.colorScheme
    val accent = when (event.risk) {
        Risk.HIGH -> colors.error
        Risk.MEDIUM -> JarvisColors.Warning
        else -> colors.primary
    }
    GlassCard(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("${event.risk.name.lowercase()} risk", accent)
            Text(
                event.kind.name.lowercase().replace('_', ' '),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
        }
        Text(event.summary, style = MaterialTheme.typography.bodyMedium)
        if (!event.detail.isNullOrBlank()) {
            Text(event.detail.orEmpty(), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Text(time, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

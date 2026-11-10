package com.jarvis.ai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.ai.AppContainer
import com.jarvis.ai.ai.providers.ProviderId
import com.jarvis.ai.ai.providers.RemoteModel

// ---- The app shell -------------------------------------------------------------------------------------------

/** Space the floating tab bar needs at the bottom of every tab, so the last item can scroll clear of it. */
val ScreenBottomPadding = 108.dp

@Composable
private fun keyboardOpen(): Boolean = WindowInsets.ime.getBottom(LocalDensity.current) > 0

@Composable
fun JarvisApp(vm: AgentViewModel, container: AppContainer, onRunSetupAgain: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(AppTab.HOME.ordinal) }
    var sub by rememberSaveable { mutableStateOf<SubPage?>(null) }
    val pending by vm.pendingConfirmation.collectAsStateWithLifecycle()
    val imeOpen = keyboardOpen()

    fun open(target: AppTab) {
        sub = null
        tab = target.ordinal
        if (target == AppTab.LOGS) vm.refreshAudit()
    }

    BackHandler(enabled = sub != null) { sub = null }
    BackHandler(enabled = sub == null && tab != AppTab.HOME.ordinal) { tab = AppTab.HOME.ordinal }

    Box(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            val current = sub
            if (current == null) AppHeader(onProfile = { open(AppTab.PREFS) }) else SubHeader(current.title, onBack = { sub = null })

            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = tab to sub,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        (fadeIn(tween(280, delayMillis = 60)) + slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it / 24 }) togetherWith
                            fadeOut(tween(120))
                    },
                    label = "page",
                ) { (page, subPage) ->
                    when {
                        subPage == SubPage.MODELS -> ModelsScreen(container.modelManager, container.keys)
                        subPage == SubPage.CLOUD -> ProvidersScreen(vm, showTitle = false)
                        subPage == SubPage.ACCESS -> AccessScreen()
                        page == AppTab.HOME.ordinal -> HomeScreen(
                            vm,
                            onOpenChat = { open(AppTab.CHAT) },
                            onRun = { text ->
                                vm.send(text)
                                open(AppTab.CHAT)
                            },
                            onPrefill = { text ->
                                vm.setDraft(text)
                                open(AppTab.CHAT)
                            },
                        )
                        page == AppTab.CHAT.ordinal -> ChatScreen(vm, onOpenPrefs = { open(AppTab.PREFS) })
                        page == AppTab.TOOLS.ordinal -> ToolsScreen(
                            container,
                            onTry = { text ->
                                vm.setDraft(text)
                                open(AppTab.CHAT)
                            },
                        )
                        page == AppTab.LOGS.ordinal -> LogsScreen(vm)
                        else -> PrefsScreen(vm, container.appVersion, onOpen = { sub = it }, onRunSetupAgain = onRunSetupAgain)
                    }
                }
            }
        }

        // The tab bar is hidden while the keyboard is up and on sub-pages, which have their own back arrow.
        if (sub == null && !imeOpen) {
            FloatingNav(selected = tab, onSelect = { open(AppTab.entries[it]) }, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }

    pending?.let { request -> ConfirmationOverlay(request, onAllow = { vm.approve(request.id) }, onDeny = { vm.deny(request.id) }) }
}

/** The bar shown on pages that open from Prefs: a back arrow and the page name. */
@Composable
private fun SubHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlyphButton(Glyph.ARROW_LEFT, onClick = onBack, size = 44.dp, description = "Back")
        Text(title, style = MaterialTheme.typography.headlineSmall, color = JarvisColors.Ink)
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

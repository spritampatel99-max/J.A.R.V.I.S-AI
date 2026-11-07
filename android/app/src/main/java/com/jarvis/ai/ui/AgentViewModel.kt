package com.jarvis.ai.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jarvis.ai.AppContainer
import com.jarvis.ai.ai.executor.ExecutionListener
import com.jarvis.ai.ai.planner.PlanResult
import com.jarvis.ai.ai.planner.PlanStep
import com.jarvis.ai.ai.providers.ChatMessage
import com.jarvis.ai.ai.providers.ChatRole
import com.jarvis.ai.ai.providers.ProviderId
import com.jarvis.ai.core.security.AuditEvent
import com.jarvis.ai.core.security.ConfirmationRequest
import com.jarvis.ai.core.security.SecretRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Sender { USER, ASSISTANT, SYSTEM }

data class ChatItem(
    val id: Long,
    val sender: Sender,
    val text: String,
    /** Which AI provider produced this reply, when it came from one. */
    val provider: ProviderId? = null,
)

data class ChatState(
    val items: List<ChatItem> = emptyList(),
    val running: Boolean = false,
    val currentStep: String? = null,
)

data class ProviderRow(
    val id: ProviderId,
    val hasKey: Boolean,
    val configured: Boolean,
    val model: String,
)

data class SettingsState(
    val providers: List<ProviderRow> = emptyList(),
    val confirmMedium: Boolean = false,
    /** One-line message about the last settings change (for example a key that could not be stored). */
    val notice: String? = null,
)

class AgentViewModel(private val container: AppContainer) : ViewModel() {

    private val chat = MutableStateFlow(ChatState())
    val chatState: StateFlow<ChatState> = chat.asStateFlow()

    private val settingsFlow = MutableStateFlow(buildSettings(null))
    val settingsState: StateFlow<SettingsState> = settingsFlow.asStateFlow()

    private val auditFlow = MutableStateFlow<List<AuditEvent>>(emptyList())
    val auditEvents: StateFlow<List<AuditEvent>> = auditFlow.asStateFlow()

    /** The native confirmation the user must answer, or null. */
    val pendingConfirmation: StateFlow<ConfirmationRequest?> = container.gate.pending

    /** The provider that answered most recently. */
    val activeProvider: StateFlow<ProviderId?> = container.llm.activeProvider

    private var nextId = 1L
    private var runJob: Job? = null

    init {
        refreshAudit()
    }

    // ---- conversation ------------------------------------------------------------------

    fun send(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty() || chat.value.running) return

        val history = chat.value.items
            .filter { it.sender != Sender.SYSTEM }
            .map { ChatMessage(if (it.sender == Sender.USER) ChatRole.USER else ChatRole.ASSISTANT, it.text) }

        append(Sender.USER, text)
        chat.update { it.copy(running = true, currentStep = "Thinking…") }

        runJob = viewModelScope.launch {
            try {
                handle(text, history)
            } catch (e: CancellationException) {
                append(Sender.SYSTEM, "Cancelled.")
                throw e
            } catch (e: Exception) {
                append(Sender.SYSTEM, "Something went wrong (${e.javaClass.simpleName}): ${SecretRedactor.redactPatterns(e.message.orEmpty()).take(200)}")
            } finally {
                chat.update { it.copy(running = false, currentStep = null) }
                refreshAudit()
            }
        }
    }

    fun cancel() {
        runJob?.cancel()
    }

    private suspend fun handle(text: String, history: List<ChatMessage>) {
        when (val result = container.planner.plan(text, history)) {
            is PlanResult.Reply -> append(Sender.ASSISTANT, result.text, result.provider)
            is PlanResult.NeedsClarification -> append(Sender.ASSISTANT, result.question, result.provider)
            is PlanResult.Failed -> append(Sender.SYSTEM, result.reason)
            is PlanResult.Ready -> {
                val listener = object : ExecutionListener {
                    override fun onStepStarted(step: PlanStep) {
                        chat.update { it.copy(currentStep = step.description) }
                    }

                    override fun onReplan(attempt: Int) {
                        chat.update { it.copy(currentStep = "Re-planning…") }
                    }
                }
                val report = container.executor.execute(result.plan, listener)
                // The report is built from recorded outcomes only; no model summarises it.
                append(Sender.ASSISTANT, report.summary(), result.provider)
            }
        }
    }

    // ---- confirmation (called only from the native dialog) -----------------------------------

    fun approve(requestId: String) {
        container.gate.approve(requestId)
    }

    fun deny(requestId: String) {
        container.gate.deny(requestId)
    }

    // ---- providers & settings ---------------------------------------------------------------

    fun saveKey(id: ProviderId, key: String) {
        val stored = container.keys.put(AppContainer.keyName(id), key)
        refreshSettings(if (stored) null else "Could not store the key securely on this device.")
    }

    fun removeKey(id: ProviderId) {
        container.keys.remove(AppContainer.keyName(id))
        refreshSettings(null)
    }

    fun saveModel(id: ProviderId, model: String) {
        container.settings.setModel(id, model)
        refreshSettings(null)
    }

    fun move(id: ProviderId, delta: Int) {
        val order = container.settings.providerOrder.toMutableList()
        val from = order.indexOf(id)
        val to = (from + delta).coerceIn(0, order.lastIndex)
        if (from < 0 || from == to) return
        order.add(to, order.removeAt(from))
        container.settings.providerOrder = order
        refreshSettings(null)
    }

    fun setConfirmMedium(enabled: Boolean) {
        container.settings.confirmMediumActions = enabled
        refreshSettings(null)
    }

    fun refreshAudit() {
        auditFlow.value = container.audit.recent(100)
    }

    private fun refreshSettings(message: String?) {
        settingsFlow.value = buildSettings(message)
    }

    private fun buildSettings(message: String?): SettingsState = SettingsState(
        providers = container.settings.providerOrder.map { id ->
            ProviderRow(
                id = id,
                hasKey = container.keys.has(AppContainer.keyName(id)),
                configured = container.isConfigured(id),
                model = container.settings.model(id),
            )
        },
        confirmMedium = container.settings.confirmMediumActions,
        notice = message,
    )

    private fun append(sender: Sender, text: String, provider: ProviderId? = null) {
        val item = ChatItem(nextId++, sender, text, provider)
        chat.update { it.copy(items = it.items + item) }
    }
}

class AgentViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AgentViewModel::class.java)) { "Unknown ViewModel ${modelClass.name}" }
        return AgentViewModel(container) as T
    }
}

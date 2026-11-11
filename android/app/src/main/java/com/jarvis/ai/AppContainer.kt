package com.jarvis.ai

import android.content.Context
import com.jarvis.ai.ai.executor.ExecutionPolicy
import com.jarvis.ai.ai.executor.Executor
import com.jarvis.ai.ai.fallback.FallbackLlmClient
import com.jarvis.ai.ai.planner.Planner
import com.jarvis.ai.ai.providers.AnthropicProvider
import com.jarvis.ai.ai.providers.GeminiProvider
import com.jarvis.ai.ai.providers.HttpClients
import com.jarvis.ai.ai.providers.JsonHttp
import com.jarvis.ai.ai.providers.LlmProvider
import com.jarvis.ai.ai.providers.ModelLister
import com.jarvis.ai.ai.providers.OkHttpTransport
import com.jarvis.ai.ai.providers.OpenAiCompatibleProvider
import com.jarvis.ai.ai.providers.ProviderId
import com.jarvis.ai.ai.providers.ProviderSettings
import com.jarvis.ai.ai.tools.ToolRegistry
import com.jarvis.ai.ai.tools.builtin.BuiltinTools
import com.jarvis.ai.ai.tools.builtin.DevicePorts
import com.jarvis.ai.android.device.AndroidAlarmLauncher
import com.jarvis.ai.android.device.AndroidAppSize
import com.jarvis.ai.android.device.AndroidDeviceAnalyzer
import com.jarvis.ai.android.device.AndroidContactsPort
import com.jarvis.ai.android.device.AndroidCallPort
import com.jarvis.ai.android.device.AndroidDeviceInfoReader
import com.jarvis.ai.android.device.AndroidPermissionChecker
import com.jarvis.ai.android.device.AndroidTorchController
import com.jarvis.ai.android.device.AndroidUrlOpener
import com.jarvis.ai.android.device.ForegroundTracker
import com.jarvis.ai.agents.AgentRuntime
import com.jarvis.ai.agents.AgentStore
import com.jarvis.ai.android.device.AccessChecks
import com.jarvis.ai.android.device.AccessKey
import com.jarvis.ai.android.device.AccessState
import com.jarvis.ai.android.device.AndroidStatus
import com.jarvis.ai.android.models.ModelManager
import com.jarvis.ai.android.schedule.AndroidNotifier
import com.jarvis.ai.android.schedule.AndroidWakeScheduler
import com.jarvis.ai.android.schedule.ScheduleEngine
import com.jarvis.ai.automation.AutomationRunner
import com.jarvis.ai.automation.AutomationStore
import com.jarvis.ai.core.common.SystemTimeSource
import com.jarvis.ai.core.security.ConfirmationGate
import com.jarvis.ai.core.security.FileAuditLog
import com.jarvis.ai.core.security.Risk
import com.jarvis.ai.core.security.SecretRedactor
import com.jarvis.ai.data.AtomicTextStorage
import com.jarvis.ai.data.HistoryFileStorage
import com.jarvis.ai.data.InstalledModelsFileStorage
import com.jarvis.ai.data.SecureKeyStore
import com.jarvis.ai.data.SetupFileStorage
import com.jarvis.ai.data.SettingsStore
import com.jarvis.ai.device.Reading
import com.jarvis.ai.history.ConversationStore
import com.jarvis.ai.memory.MemoryStore
import com.jarvis.ai.offline.OfflineReadiness
import com.jarvis.ai.offline.OfflineReport
import com.jarvis.ai.offline.OfflineSelfTest
import com.jarvis.ai.offline.OfflineSnapshot
import com.jarvis.ai.offline.SelfTestResult
import com.jarvis.ai.models.InstalledModels
import com.jarvis.ai.models.ModelAdvisor
import com.jarvis.ai.models.ModelCatalog
import com.jarvis.ai.models.ModelInstaller
import com.jarvis.ai.models.ModelMetadataClient
import com.jarvis.ai.models.OkHttpDownloadSource
import com.jarvis.ai.productivity.ProductivityStore
import com.jarvis.ai.productivity.ProductivityTools
import com.jarvis.ai.security.SecurityReport
import com.jarvis.ai.security.SecurityReview
import com.jarvis.ai.security.SecuritySnapshot
import com.jarvis.ai.setup.SetupRepository
import com.jarvis.ai.setup.StorageCalculator
import com.jarvis.ai.voice.VoiceController
import java.io.File

/**
 * Manual dependency injection: one place that wires the real Android pieces into the
 * platform-independent core. Providers read their settings at call time, so changing a
 * key or model in the UI takes effect immediately without rebuilding anything.
 */
class AppContainer(context: Context, val foreground: ForegroundTracker) {

    private val appContext = context.applicationContext

    val keys = SecureKeyStore(appContext)
    val settings = SettingsStore(appContext)

    private val redactor = SecretRedactor {
        ProviderId.entries.mapNotNull { keys.get(keyName(it)) } + listOfNotNull(keys.get(HF_TOKEN_KEY))
    }

    private val transport = OkHttpTransport(HttpClients.cloud(), redactor)
    private val cloudHttp = JsonHttp(transport, redactor)

    // ---- setup wizard (Phase 3) ----------------------------------------------------------------
    val deviceAnalyzer = AndroidDeviceAnalyzer(appContext)
    val setup = SetupRepository(SetupFileStorage(appContext))
    val modelAdvisor = ModelAdvisor()
    val storageCalculator = StorageCalculator()
    val modelMetadata = ModelMetadataClient(transport)

    // ---- on-device models: download, import, installed list ------------------------------------------
    val modelsDir = File(appContext.filesDir, "models")
    val installedModels = InstalledModels(InstalledModelsFileStorage(appContext), modelsDir)
    val modelManager = ModelManager(
        context = appContext,
        metadata = modelMetadata,
        installer = ModelInstaller(OkHttpDownloadSource()),
        installed = installedModels,
        modelsDir = modelsDir,
        hfToken = { keys.get(HF_TOKEN_KEY) },
    )

    /** Size of the installed app, read once; it does not change while the app runs. */
    val baseAppSize: Reading<Long> by lazy { AndroidAppSize.read(appContext) }

    val appVersion: String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }

    fun providerSettings(id: ProviderId): ProviderSettings = ProviderSettings(
        apiKey = keys.get(keyName(id)),
        model = settings.model(id),
    )

    private val gemini = GeminiProvider(cloudHttp, { providerSettings(ProviderId.GEMINI) })

    private val groq = OpenAiCompatibleProvider(
        id = ProviderId.GROQ,
        http = cloudHttp,
        endpoint = "https://api.groq.com/openai/v1/chat/completions",
        settings = { providerSettings(ProviderId.GROQ) },
    )

    private val openRouter = OpenAiCompatibleProvider(
        id = ProviderId.OPENROUTER,
        http = cloudHttp,
        endpoint = "https://openrouter.ai/api/v1/chat/completions",
        settings = { providerSettings(ProviderId.OPENROUTER) },
        extraHeaders = mapOf("X-Title" to "J.A.R.V.I.S. Android"),
    )

    // OpenAI documents max_tokens as deprecated (and unsupported by its reasoning models), and some
    // models accept only the default temperature, so this instance uses max_completion_tokens and
    // sends no temperature.
    private val openAi = OpenAiCompatibleProvider(
        id = ProviderId.OPENAI,
        http = cloudHttp,
        endpoint = "https://api.openai.com/v1/chat/completions",
        settings = { providerSettings(ProviderId.OPENAI) },
        maxTokensField = "max_completion_tokens",
        sendTemperature = false,
    )

    private val anthropic = AnthropicProvider(cloudHttp, { providerSettings(ProviderId.ANTHROPIC) })

    private val providersById: Map<ProviderId, LlmProvider> = mapOf(
        ProviderId.GEMINI to gemini,
        ProviderId.GROQ to groq,
        ProviderId.OPENROUTER to openRouter,
        ProviderId.OPENAI to openAi,
        ProviderId.ANTHROPIC to anthropic,
    )

    fun isConfigured(id: ProviderId): Boolean = providersById.getValue(id).isConfigured()

    fun provider(id: ProviderId): LlmProvider = providersById.getValue(id)

    /** Asks a provider which models the given key can use. */
    val modelLister = ModelLister(cloudHttp)

    val llm = FallbackLlmClient(providers = { settings.providerOrder.mapNotNull { providersById[it] } })

    private val ports = DevicePorts(
        torch = AndroidTorchController(appContext),
        alarms = AndroidAlarmLauncher(appContext, foreground),
        urls = AndroidUrlOpener(appContext, foreground),
        deviceInfo = AndroidDeviceInfoReader(appContext),
        contacts = AndroidContactsPort(appContext),
        calls = AndroidCallPort(appContext),
    )

    /** The conversation history shown in the Logs tab. Stored in a private file, redacted of keys, deletable by the person. */
    val history = ConversationStore(HistoryFileStorage(appContext), SystemTimeSource)

    /**
     * Speech in and out. Created on first use, which is always on the main thread (the UI), as its recogniser requires.
     */
    val voice: VoiceController by lazy { VoiceController(appContext) { settings.ui.value } }

    // ---- memory, planner, automations, agents (Phases 7-9) ---------------------------------------------
    // Each keeps its data in one small private file on this phone. Nothing here is sent anywhere by itself.
    val memory = MemoryStore(AtomicTextStorage(appContext, "memory.json"), SystemTimeSource)
    val productivity = ProductivityStore(AtomicTextStorage(appContext, "planner.json"), SystemTimeSource)
    val automations = AutomationStore(AtomicTextStorage(appContext, "automations.json"), SystemTimeSource)
    private val wake = AndroidWakeScheduler(appContext)
    val notifier = AndroidNotifier(appContext)
    val status = AndroidStatus(appContext)

    /** What the assistant can do: the phone tools plus the planner and reminder tools. */
    val registry = ToolRegistry(BuiltinTools.create(ports) + ProductivityTools.create(productivity, wake, notifier))

    val agents = AgentStore(AtomicTextStorage(appContext, "agents.json"), SystemTimeSource, registry, memory)
    val agentRuntime = AgentRuntime(registry, memory, SystemTimeSource)

    val planner = Planner(llm, registry)
    val gate = ConfirmationGate()
    val audit = FileAuditLog(File(appContext.filesDir, "audit.jsonl"), redactor = redactor)

    /** Reads which Android permissions are granted right now. The executor and the Tools tab both use it. */
    val permissions = AndroidPermissionChecker(appContext)

    val executor = Executor(
        registry = registry,
        permissions = permissions,
        gate = gate,
        auditLog = audit,
        replanner = planner,
        policy = { ExecutionPolicy(confirmAt = if (settings.confirmMediumActions) Risk.MEDIUM else Risk.HIGH) },
    )

    /** Runs automations unattended: allow-listed low-risk tools only, and nothing that needs a confirmation. */
    private val automationRunner = AutomationRunner(automations, registry, permissions, audit, SystemTimeSource)

    /** Delivers due reminders, runs due automations and asks Android for the next wake-up. */
    val schedule = ScheduleEngine(productivity, automations, automationRunner, notifier, wake, SystemTimeSource)

    /** "Run now" from the Automations screen. Runs the same restricted way, and never moves the schedule. */
    suspend fun runAutomationNow(id: String) = automationRunner.runNow(id)

    // ---- readiness and security reviews -------------------------------------------------------------------

    /** True only when an on-device inference engine is part of this build. It is not yet, so downloaded models cannot answer. */
    val localEngineBuilt: Boolean = false

    private fun cloudKeyCount(): Int = ProviderId.entries.count { isConfigured(it) }

    fun offlineSnapshot(selfTest: List<SelfTestResult>? = null): OfflineSnapshot = OfflineSnapshot(
        connectivity = status.connectivity(),
        localEngineBuilt = localEngineBuilt,
        installedLocalModels = installedModels.all().map { ModelCatalog.spec(it.id).displayName },
        offlineSpeechRecognition = status.offlineSpeechRecognition(),
        speechRecognitionAvailable = status.speechRecognitionAvailable(),
        textToSpeechAvailable = status.textToSpeechAvailable(),
        cloudKeyCount = cloudKeyCount(),
        notificationsAllowed = notifier.canNotify(),
        selfTest = selfTest,
    )

    fun offlineReport(selfTest: List<SelfTestResult>? = null): OfflineReport = OfflineReadiness.assess(offlineSnapshot(selfTest))

    /** Writes and reads back two scratch files in the app's cache, so the check uses the same code path as real saving. */
    fun runOfflineSelfTest(): List<SelfTestResult> {
        val planner = AtomicTextStorage(appContext.cacheDir, "selftest-planner.json")
        val memory = AtomicTextStorage(appContext.cacheDir, "selftest-memory.json")
        return try {
            OfflineSelfTest.run(planner, memory, SystemTimeSource)
        } finally {
            planner.delete()
            memory.delete()
        }
    }

    fun securityReport(): SecurityReport {
        val granted = AccessKey.entries
            .filter { it.manifestPermission != null && AccessChecks.state(appContext, it) == AccessState.ALLOWED }
            .mapNotNull { it.manifestPermission }
            .toSet()
        // A permission counts as used when a tool asks for it, or when the Access screen says a feature in this build uses it today.
        val needed = registry.all().flatMap { it.spec.requiredPermissions }.toSet() +
            AccessKey.entries.filter { it.activeNow }.mapNotNull { it.manifestPermission }
        val failures = buildList {
            if (history.saveFailed) add("chat history")
            if (memory.saveFailed) add("memory")
            if (productivity.saveFailed) add("planner")
            if (automations.saveFailed) add("automations")
            if (agents.saveFailed) add("agents")
        }
        return SecurityReview.review(
            SecuritySnapshot(
                grantedPermissions = granted,
                neededPermissions = needed,
                cloudKeyCount = cloudKeyCount(),
                historyConversations = history.conversations.value.size,
                historyBytes = history.sizeBytes(),
                memory = memory.entries.value,
                agents = agents.agents.value,
                automations = automations.automations.value,
                registry = registry,
                audit = audit.recent(500),
                saveFailures = failures,
                notificationsAllowed = notifier.canNotify(),
                pendingReminders = productivity.state.value.reminders.count { !it.delivered },
            ),
        )
    }

    companion object {
        fun keyName(id: ProviderId): String = "api_key_${id.name}"

        /** Hugging Face access token for gated models. Stored encrypted like the AI keys; never logged. */
        const val HF_TOKEN_KEY = "hf_access_token"
    }
}

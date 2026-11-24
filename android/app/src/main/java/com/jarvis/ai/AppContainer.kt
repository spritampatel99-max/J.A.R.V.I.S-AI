package com.jarvis.ai

import android.content.Context
import com.jarvis.ai.ai.executor.ExecutionPolicy
import com.jarvis.ai.ai.executor.Executor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.jarvis.ai.ai.fallback.FallbackLlmClient
import com.jarvis.ai.ai.local.LocalEngine
import com.jarvis.ai.ai.router.RoutedLlmClient
import com.jarvis.ai.ai.router.RoutingSettings
import com.jarvis.ai.android.local.LocalEngines
import com.jarvis.ai.ai.fallback.RetryPolicy
import com.jarvis.ai.ai.planner.Planner
import com.jarvis.ai.ai.providers.AnthropicProvider
import com.jarvis.ai.ai.providers.GeminiProvider
import com.jarvis.ai.ai.providers.HttpClients
import com.jarvis.ai.ai.providers.JsonHttp
import com.jarvis.ai.ai.providers.LlmProvider
import com.jarvis.ai.ai.providers.CustomEndpoint
import com.jarvis.ai.ai.providers.ModelLister
import com.jarvis.ai.ai.providers.OkHttpTransport
import com.jarvis.ai.ai.providers.OpenAiCompatibleProvider
import com.jarvis.ai.ai.providers.ProviderId
import com.jarvis.ai.ai.providers.ProviderSettings
import com.jarvis.ai.ai.tools.ToolRegistry
import com.jarvis.ai.ai.tools.builtin.BuiltinTools
import com.jarvis.ai.ai.tools.builtin.DevicePorts
import com.jarvis.ai.ai.tools.screen.GuardedNotifications
import com.jarvis.ai.ai.tools.screen.GuardedScreenControl
import com.jarvis.ai.ai.tools.screen.ScreenGuard
import com.jarvis.ai.ai.tools.screen.ScreenTools
import com.jarvis.ai.android.device.AndroidAlarmLauncher
import com.jarvis.ai.android.device.AndroidAppLauncher
import com.jarvis.ai.android.device.AndroidAppSize
import com.jarvis.ai.android.device.AndroidDeviceAnalyzer
import com.jarvis.ai.android.device.AndroidContactsPort
import com.jarvis.ai.android.device.AndroidCallPort
import com.jarvis.ai.android.device.AndroidCameraLauncher
import com.jarvis.ai.android.device.AndroidEmailPort
import com.jarvis.ai.android.device.AndroidMediaKeys
import com.jarvis.ai.android.device.AndroidMessagingPort
import com.jarvis.ai.android.device.AndroidTimerLauncher
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
import com.jarvis.ai.android.knowledge.AndroidClusterDecoder
import com.jarvis.ai.android.knowledge.KnowledgeLibrary
import com.jarvis.ai.android.models.ModelManager
import com.jarvis.ai.android.screen.AndroidNotificationPort
import com.jarvis.ai.android.screen.AndroidRawScreen
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
import com.jarvis.ai.knowledge.OfflineTools
import com.jarvis.ai.web.GeminiGroundedSearch
import com.jarvis.ai.web.WeatherClient
import com.jarvis.ai.web.WebTools
import com.jarvis.ai.web.WikipediaClient
import com.jarvis.ai.security.SecurityReport
import com.jarvis.ai.security.SecurityReview
import com.jarvis.ai.security.SecuritySnapshot
import com.jarvis.ai.setup.SetupRepository
import com.jarvis.ai.setup.StorageCalculator
import com.jarvis.ai.ui.AgentViewModel
import com.jarvis.ai.voice.VoiceController
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.LocalDate

/**
 * Manual dependency injection: one place that wires the real Android pieces into the
 * platform-independent core. Providers read their settings at call time, so changing a
 * key or model in the UI takes effect immediately without rebuilding anything.
 */
class AppContainer(context: Context, val foreground: ForegroundTracker) {

    val appContext = context.applicationContext

    val keys = SecureKeyStore(appContext)
    val settings = SettingsStore(appContext)

    private val redactor = SecretRedactor {
        ProviderId.entries.mapNotNull { keys.get(keyName(it)) } + listOfNotNull(keys.get(HF_TOKEN_KEY), keys.get(ELEVEN_KEY))
    }

    private val transport = OkHttpTransport(HttpClients.cloud(), redactor)
    private val cloudHttp = JsonHttp(transport, redactor)

    // ---- ElevenLabs voice (optional, the person's own key) ---------------------------------------------
    /** Turns short, non-private replies into speech with the person's ElevenLabs key. The key is read from the secure store on every call. */
    val elevenClient = com.jarvis.ai.voice.ElevenLabsClient(
        http = transport,
        audio = com.jarvis.ai.android.voice.OkHttpAudioFetcher(HttpClients.cloud()),
        key = { keys.get(ELEVEN_KEY) },
        redactor = redactor,
    )
    val elevenPlayer = com.jarvis.ai.android.voice.ElevenPlayer(appContext)

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

    /** The offline encyclopedia (a Kiwix Wikipedia file) on this phone, if the person downloaded or imported one. */
    val library = KnowledgeLibrary(
        context = appContext,
        installer = ModelInstaller(OkHttpDownloadSource()),
        dir = File(appContext.filesDir, "knowledge"),
        decoder = AndroidClusterDecoder(),
    )

    /** Size of the installed app, read once; it does not change while the app runs. */
    val baseAppSize: Reading<Long> by lazy { AndroidAppSize.read(appContext) }

    val appVersion: String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }

    /** Reads the public "latest release" page of the project. Looks only; the person downloads and installs. */
    val updateClient = com.jarvis.ai.update.ReleaseClient(transport, UPDATE_REPO)

    fun providerSettings(id: ProviderId): ProviderSettings = ProviderSettings(
        apiKey = keys.get(keyName(id)),
        model = settings.model(id),
        endpoint = if (id == ProviderId.FREELLM) settings.endpoint(id) else "",
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
        extraHeaders = mapOf("X-Title" to "JARVIS 3.0 Android"),
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

    // Mistral speaks the OpenAI chat-completions format (POST /v1/chat/completions, Bearer key, max_tokens).
    private val mistral = OpenAiCompatibleProvider(
        id = ProviderId.MISTRAL,
        http = cloudHttp,
        endpoint = "https://api.mistral.ai/v1/chat/completions",
        settings = { providerSettings(ProviderId.MISTRAL) },
    )

    // "FreeLLM / own endpoint": an OpenAI-compatible service at an https address the person types in. Nothing is built in,
    // the key is optional, and an address that CustomEndpoint refuses leaves the provider unconfigured.
    private val freeLlm = OpenAiCompatibleProvider(
        id = ProviderId.FREELLM,
        http = cloudHttp,
        endpoint = "",
        settings = { providerSettings(ProviderId.FREELLM) },
        endpointSource = { CustomEndpoint.chatUrl(providerSettings(ProviderId.FREELLM).endpoint) },
        requireKey = false,
        // An unknown gateway may reject the JSON-mode field, so it is not sent; the planner reads JSON out of plain text, as it does for Claude.
        supportsJsonMode = false,
    )

    private val anthropic = AnthropicProvider(cloudHttp, { providerSettings(ProviderId.ANTHROPIC) })

    private val providersById: Map<ProviderId, LlmProvider> = mapOf(
        ProviderId.GEMINI to gemini,
        ProviderId.GROQ to groq,
        ProviderId.OPENROUTER to openRouter,
        ProviderId.OPENAI to openAi,
        ProviderId.ANTHROPIC to anthropic,
        ProviderId.MISTRAL to mistral,
        ProviderId.FREELLM to freeLlm,
    )

    fun isConfigured(id: ProviderId): Boolean = providersById.getValue(id).isConfigured()

    fun provider(id: ProviderId): LlmProvider = providersById.getValue(id)

    /** Asks a provider which models the given key can use. */
    val modelLister = ModelLister(cloudHttp)

    /**
     * Built for a person waiting on the answer: a provider that times out is not asked again (the next one is tried at once), and
     * if one has not answered after [HEDGE_AFTER_MS] the next configured provider is asked too and the first answer wins.
     */
    val llm = FallbackLlmClient(
        providers = { settings.providerOrder.mapNotNull { providersById[it] } },
        retry = RetryPolicy(maxAttemptsPerProvider = 2, baseDelayMs = 300L, maxDelayMs = 2_000L, retryTimeouts = false),
        hedgeAfterMs = HEDGE_AFTER_MS,
    )

    /** The on-device engine. Null in the default build, which has none (see LocalEngines); then every answer comes from the cloud. */
    val localEngine: LocalEngine? = LocalEngines.create(appContext)

    /**
     * Picks on-device or cloud for each request by the person's AI mode. The planner and the agent chats ask this; its cloud route is
     * [llm] itself, so provider order, retries and hedging are unchanged.
     */
    val routedLlm = RoutedLlmClient(
        engine = localEngine,
        installedModels = {
            installedModels.all().mapNotNull { model -> installedModels.fileFor(model.id)?.let { model.id to it.absolutePath } }.toMap()
        },
        cloud = llm,
        cloudConfigured = { cloudKeyCount() > 0 },
        settings = { RoutingSettings(mode = settings.aiMode, online = isOnline()) },
    )

    private fun isOnline(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private val ports = DevicePorts(
        torch = AndroidTorchController(appContext),
        alarms = AndroidAlarmLauncher(appContext, foreground),
        urls = AndroidUrlOpener(appContext, foreground),
        deviceInfo = AndroidDeviceInfoReader(appContext),
        contacts = AndroidContactsPort(appContext),
        calls = AndroidCallPort(appContext, foreground),
        apps = AndroidAppLauncher(appContext, foreground),
        messaging = AndroidMessagingPort(appContext, foreground),
        timers = AndroidTimerLauncher(appContext, foreground),
        media = AndroidMediaKeys(appContext),
        email = AndroidEmailPort(appContext, foreground),
        camera = AndroidCameraLauncher(appContext, foreground),
        settings = com.jarvis.ai.android.device.AndroidPhoneSettings(appContext, foreground),
        access = com.jarvis.ai.android.device.AndroidAccessReporter(appContext),
        whatsappSender = object : com.jarvis.ai.ai.tools.builtin.WhatsAppSender {
            override val enabled: Boolean get() = settings.whatsappSendOn

            // Same guarded control as every other screen tool, so the package blocklist applies; only WhatsApp windows qualify.
            override suspend fun pressSend(expected: String) = screenControl.sendComposed(expected, ScreenTools.WHATSAPP_PACKAGES)
        },
    )

    /** Work that must outlive any one screen (the assistant's requests, the hands-free service). Runs on the main thread. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The conversation history shown in the Logs tab. Stored in a private file, redacted of keys, deletable by the person. */
    val history = ConversationStore(HistoryFileStorage(appContext), SystemTimeSource)

    /** The offline speech model that the no-key wake word needs. */
    val voskModel = com.jarvis.ai.android.voice.VoskModelStore(
        appContext,
        // The cloud client gives up after 45 seconds and refuses redirects; a 40 MB file needs neither limit. It still allows https only.
        HttpClients.cloud().newBuilder()
            .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build(),
    )

    /** Saves conversations as text files in a folder or file the person picked, so they show up in the file manager. */
    val archiver = com.jarvis.ai.android.files.ChatArchiver(appContext, settings, history)

    /** The one assistant. Every screen and the hands-free service share it, so spoken and typed requests land in the same chat. */
    val assistant: AgentViewModel by lazy { AgentViewModel(this) }

    /**
     * Speech in and out. Created on first use, which is always on the main thread (the UI), as its recogniser requires.
     */
    val voice: VoiceController by lazy {
        VoiceController(
            appContext,
            { settings.ui.value },
            com.jarvis.ai.android.voice.ElevenSpeaker(elevenClient, { keys.get(ELEVEN_KEY) != null }, appContext, elevenPlayer),
        )
    }

    // ---- memory, planner, automations, agents (Phases 7-9) ---------------------------------------------
    // Each keeps its data in one small private file on this phone. Nothing here is sent anywhere by itself.
    val memory = MemoryStore(AtomicTextStorage(appContext, "memory.json"), SystemTimeSource)
    val productivity = ProductivityStore(AtomicTextStorage(appContext, "planner.json"), SystemTimeSource)
    val automations = AutomationStore(AtomicTextStorage(appContext, "automations.json"), SystemTimeSource)
    private val wake = AndroidWakeScheduler(appContext)
    val notifier = AndroidNotifier(appContext)
    val status = AndroidStatus(appContext)

    /**
     * Reading and steering the phone through Android's own accessibility and notification access. The guard sits in front of
     * the Android code, so it cannot be skipped: JARVIS 3.0 itself, banking, payment and password apps and the person's own
     * list are never read, and system settings and permission dialogs are never acted on.
     */
    private val screenGuard = ScreenGuard(appContext.packageName) { settings.screenBlocklist }

    /** Decides what is read aloud when a notification arrives while Bluetooth audio is connected. Uses the same guard, so banking and password apps stay silent. */
    val announcer = com.jarvis.ai.voice.NotificationAnnouncer(screenGuard) { System.currentTimeMillis() }
    private val screenControl = GuardedScreenControl(AndroidRawScreen(), screenGuard, actionsOn = { settings.tapAndTypeOn })
    private val guardedNotifications = GuardedNotifications(AndroidNotificationPort(), screenGuard)

    /** What the assistant can do: the phone tools, the planner and reminder tools, the phone-control tools and the read-only internet lookups. */
    val registry = ToolRegistry(
        BuiltinTools.create(ports) +
            ScreenTools.create(screenControl, guardedNotifications, apps = ports.apps, urls = ports.urls) +
            listOfNotNull(
                ports.apps?.let { launcher ->
                    com.jarvis.ai.ai.agent.OperatePhoneTool(
                        enabled = { settings.phoneOperatorOn && settings.tapAndTypeOn },
                        agent = com.jarvis.ai.ai.agent.PhoneAgent(
                            screen = screenControl,
                            apps = launcher,
                            brain = com.jarvis.ai.ai.agent.LlmAgentBrain(routedLlm),
                            approver = com.jarvis.ai.ai.agent.StepApprover { summary -> approveOperatorStep(summary) },
                        ),
                    )
                },
            ) +
            OfflineTools.create { library.encyclopedia() } +
            listOf(com.jarvis.ai.update.CheckForUpdatesTool(updateClient, appVersion)) +
            ProductivityTools.create(productivity, wake, notifier) +
            WebTools.create(
                weather = WeatherClient(transport),
                wiki = WikipediaClient(transport),
                temperatureUnit = {
                    if (androidx.core.text.util.LocalePreferences.getTemperatureUnit() == androidx.core.text.util.LocalePreferences.TemperatureUnit.FAHRENHEIT) {
                        com.jarvis.ai.web.TemperatureUnit.FAHRENHEIT
                    } else {
                        com.jarvis.ai.web.TemperatureUnit.CELSIUS
                    }
                },
                grounded = GeminiGroundedSearch(transport, { providerSettings(ProviderId.GEMINI) }, { LocalDate.now().toString() }),
            ),
    )

    val agents = AgentStore(AtomicTextStorage(appContext, "agents.json"), SystemTimeSource, registry, memory)
    val agentRuntime = AgentRuntime(registry, memory, SystemTimeSource)

    val planner = Planner(routedLlm, registry, addressing = { com.jarvis.ai.ui.Addressing.promptValue(appContext) })
    val gate = ConfirmationGate()

    /**
     * One press the phone operator wants to make that is on the "ask first" list. It goes through the same native confirmation as every other
     * sensitive action: the person sees the app, the exact control and what was typed, and only their tap approves it.
     */
    private suspend fun approveOperatorStep(summary: String): Boolean {
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(summary.toByteArray()).joinToString("") { "%02x".format(it) }
        return gate.request("operate_phone_step", summary, Risk.HIGH, fingerprint) is com.jarvis.ai.core.security.ConfirmationDecision.Approved
    }
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
    /** The facts about whether Android will wake the app, shared by the Automations screen and the nightly review. */
    fun scheduleFindings(): List<com.jarvis.ai.automation.HealthFinding> {
        val now = System.currentTimeMillis()
        return com.jarvis.ai.automation.ScheduleHealth.check(
            com.jarvis.ai.automation.ScheduleFacts(
                nowMs = now,
                exactAlarmsAllowed = com.jarvis.ai.android.device.AccessChecks.state(appContext, com.jarvis.ai.android.device.AccessKey.EXACT_ALARMS) != com.jarvis.ai.android.device.AccessState.NOT_ALLOWED,
                notificationsAllowed = notifier.canNotify(),
                batteryUnrestricted = com.jarvis.ai.android.device.AccessChecks.state(appContext, com.jarvis.ai.android.device.AccessKey.BATTERY) != com.jarvis.ai.android.device.AccessState.NOT_ALLOWED,
                nextDueMs = listOfNotNull(automations.nextWakeMs(), productivity.nextReminderMs(now)).minOrNull(),
                lastWakeMs = settings.lastWakeMs,
            ),
        )
    }

    val nightlyReview = com.jarvis.ai.android.schedule.NightlyReviewJob(settings, audit, updateClient, notifier, appVersion, findings = ::scheduleFindings)

    val schedule = ScheduleEngine(productivity, automations, automationRunner, notifier, wake, SystemTimeSource, onWake = { settings.lastWakeMs = it }, nightly = nightlyReview)

    /** "Run now" from the Automations screen. Runs the same restricted way, and never moves the schedule. */
    suspend fun runAutomationNow(id: String) = automationRunner.runNow(id)

    // ---- readiness and security reviews -------------------------------------------------------------------

    /** True only when an on-device inference engine is part of this build. The default build has none (see LocalEngines), so downloaded models cannot answer there. */
    val localEngineBuilt: Boolean = LocalEngines.AVAILABLE

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
        private const val HEDGE_AFTER_MS = 6_000L

        /** Where releases are published. The update check reads this project's public "latest release" and nothing else. */
        const val UPDATE_REPO = "spritampatel99-max/J.A.R.V.I.S-AI"

        fun keyName(id: ProviderId): String = "api_key_${id.name}"

        /** Hugging Face access token for gated models. Stored encrypted like the AI keys; never logged. */
        const val HF_TOKEN_KEY = "hf_access_token"

        /** The person's ElevenLabs key. Stored encrypted like the AI keys; sent only in the `xi-api-key` header; never logged. */
        const val ELEVEN_KEY = "elevenlabs_api_key"
    }
}

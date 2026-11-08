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
import com.jarvis.ai.android.device.AndroidDeviceInfoReader
import com.jarvis.ai.android.device.AndroidPermissionChecker
import com.jarvis.ai.android.device.AndroidTorchController
import com.jarvis.ai.android.device.AndroidUrlOpener
import com.jarvis.ai.android.device.ForegroundTracker
import com.jarvis.ai.core.security.ConfirmationGate
import com.jarvis.ai.core.security.FileAuditLog
import com.jarvis.ai.core.security.Risk
import com.jarvis.ai.core.security.SecretRedactor
import com.jarvis.ai.data.SecureKeyStore
import com.jarvis.ai.data.SetupFileStorage
import com.jarvis.ai.data.SettingsStore
import com.jarvis.ai.device.Reading
import com.jarvis.ai.models.ModelAdvisor
import com.jarvis.ai.models.ModelMetadataClient
import com.jarvis.ai.setup.SetupRepository
import com.jarvis.ai.setup.StorageCalculator
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

    private val redactor = SecretRedactor { ProviderId.entries.mapNotNull { keys.get(keyName(it)) } }

    private val transport = OkHttpTransport(HttpClients.cloud(), redactor)
    private val cloudHttp = JsonHttp(transport, redactor)

    // ---- setup wizard (Phase 3) ----------------------------------------------------------------
    val deviceAnalyzer = AndroidDeviceAnalyzer(appContext)
    val setup = SetupRepository(SetupFileStorage(appContext))
    val modelAdvisor = ModelAdvisor()
    val storageCalculator = StorageCalculator()
    val modelMetadata = ModelMetadataClient(transport)

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

    val llm = FallbackLlmClient(providers = { settings.providerOrder.mapNotNull { providersById[it] } })

    private val ports = DevicePorts(
        torch = AndroidTorchController(appContext),
        alarms = AndroidAlarmLauncher(appContext, foreground),
        urls = AndroidUrlOpener(appContext, foreground),
        deviceInfo = AndroidDeviceInfoReader(appContext),
    )

    val registry = ToolRegistry(BuiltinTools.create(ports))
    val planner = Planner(llm, registry)
    val gate = ConfirmationGate()
    val audit = FileAuditLog(File(appContext.filesDir, "audit.jsonl"), redactor = redactor)

    val executor = Executor(
        registry = registry,
        permissions = AndroidPermissionChecker(appContext),
        gate = gate,
        auditLog = audit,
        replanner = planner,
        policy = { ExecutionPolicy(confirmAt = if (settings.confirmMediumActions) Risk.MEDIUM else Risk.HIGH) },
    )

    companion object {
        fun keyName(id: ProviderId): String = "api_key_${id.name}"
    }
}

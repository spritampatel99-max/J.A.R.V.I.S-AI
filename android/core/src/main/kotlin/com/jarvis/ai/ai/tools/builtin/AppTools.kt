package com.jarvis.ai.ai.tools.builtin

import com.jarvis.ai.ai.tools.ParamSpec
import com.jarvis.ai.ai.tools.ParamType
import com.jarvis.ai.ai.tools.Tool
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolError
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.ToolSpec
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.core.security.Risk
import java.util.Locale

/** An installed app that has a launcher icon. */
data class InstalledApp(val label: String, val packageName: String)

/** What the platform can do with apps. Opening an app is all this allows; it never acts inside another app. */
interface AppLauncher {
    /** Every app on the phone that has a launcher icon. */
    suspend fun launchable(): List<InstalledApp>

    fun launch(packageName: String): LaunchResult
}

sealed interface AppMatch {
    data class One(val app: InstalledApp) : AppMatch

    data class Several(val apps: List<InstalledApp>) : AppMatch

    data object None : AppMatch
}

/** Finds an installed app from a spoken or typed name. "whats app", "WhatsApp" and "whatsapp" are the same name. */
object AppMatching {
    private fun key(text: String): String =
        text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun find(query: String, apps: List<InstalledApp>): AppMatch {
        val wanted = key(query)
        if (wanted.length < 2) return AppMatch.None
        val keyed = apps.map { it to key(it.label) }.filter { it.second.isNotEmpty() }

        fun pick(matches: List<InstalledApp>): AppMatch? = when {
            matches.isEmpty() -> null
            matches.size == 1 -> AppMatch.One(matches.single())
            else -> AppMatch.Several(matches.distinctBy { it.packageName }.take(MAX_CHOICES))
        }

        pick(keyed.filter { it.second == wanted }.map { it.first })?.let { return it }
        pick(keyed.filter { it.second.startsWith(wanted) }.map { it.first })?.let { return it }
        // A longer spoken name that contains the app's whole name ("google maps app" -> "Maps") is accepted only for longer app names.
        pick(keyed.filter { it.second.length >= 4 && wanted.contains(it.second) }.map { it.first })?.let { return it }
        pick(keyed.filter { wanted.length >= 3 && it.second.contains(wanted) }.map { it.first })?.let { return it }
        return AppMatch.None
    }

    const val MAX_CHOICES = 4
}

class OpenAppTool(private val launcher: AppLauncher) : Tool {
    override val spec = ToolSpec(
        name = "open_app",
        description = "Open an installed app by its name, for example WhatsApp, Camera, Settings or Maps. It only opens the app; it cannot tap or type inside it.",
        params = listOf(ParamSpec("name", ParamType.STRING, "The app's name as the person says it", maxLength = 60)),
        risk = Risk.LOW,
        timeoutMs = 10_000L,
        resultFormat = "HANDED_OFF: Android started the app; what it shows next is not observable.",
    )

    override fun describe(args: ToolArgs): String = "Open ${args.string("name").take(60)}"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val name = args.string("name").trim()
        return when (val match = AppMatching.find(name, launcher.launchable())) {
            AppMatch.None -> ToolResult.Failure(ToolError.Failed("I could not find an app called \"${name.take(40)}\" on this phone."))
            is AppMatch.Several -> ToolResult.Failure(
                ToolError.NeedsInput("Which app do you mean: ${match.apps.joinToString(", ") { it.label }}?"),
            )
            is AppMatch.One -> when (val result = launcher.launch(match.app.packageName)) {
                LaunchResult.Launched -> ToolResult.Success(
                    message = "Opened ${match.app.label} (Android started it).",
                    verification = Verification.HANDED_OFF,
                )
                is LaunchResult.NoHandler -> ToolResult.Failure(ToolError.Unavailable("${match.app.label} cannot be opened from here."))
                is LaunchResult.Failed -> ToolResult.Failure(ToolError.Failed(result.reason))
            }
        }
    }
}

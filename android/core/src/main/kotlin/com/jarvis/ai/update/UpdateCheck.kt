package com.jarvis.ai.update

import com.jarvis.ai.ai.providers.HttpTransport
import com.jarvis.ai.ai.providers.ProviderError
import com.jarvis.ai.ai.tools.Tool
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolError
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.ToolSpec
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.core.security.Risk

sealed interface UpdateOutcome {
    /** The newest published release, which may or may not be newer than what is installed. */
    data class Latest(val release: ReleaseInfo) : UpdateOutcome

    /** The project has no published release yet. */
    data object NoRelease : UpdateOutcome

    data class Unavailable(val reason: String) : UpdateOutcome
}

/** Reads the public "latest release" of one GitHub project. It sends no account, no key and nothing about the phone but the app name. */
class ReleaseClient(private val transport: HttpTransport, private val repo: String) {
    suspend fun latest(): UpdateOutcome {
        if (!REPO.matches(repo)) return UpdateOutcome.Unavailable("The update source is not set up correctly.")
        val result = try {
            transport.get(
                "https://api.github.com/repos/$repo/releases/latest",
                mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "JARVIS-3.0-Android"),
            )
        } catch (e: ProviderError) {
            return UpdateOutcome.Unavailable("I could not reach GitHub to look for updates.")
        }
        return when {
            result.code == 404 -> UpdateOutcome.NoRelease
            result.code !in 200..299 -> UpdateOutcome.Unavailable("GitHub answered ${result.code}, so I could not check.")
            else -> ReleaseParser.parse(result.body)?.let { UpdateOutcome.Latest(it) } ?: UpdateOutcome.NoRelease
        }
    }

    private companion object {
        val REPO = Regex("""[A-Za-z0-9_.-]{1,60}/[A-Za-z0-9_.-]{1,100}""")
    }
}

/**
 * "Check for updates". It only looks; it never downloads or installs. The release notes are the publisher's text and are not shown here,
 * only the version number and the page, so nothing from outside can reach the chat or a model through this tool.
 */
class CheckForUpdatesTool(private val client: ReleaseClient, private val currentVersion: String) : Tool {
    override val spec = ToolSpec(
        name = "check_for_updates",
        description = "Check whether a newer version of JARVIS 3.0 has been published. It only looks; the person downloads and installs it themselves.",
        params = emptyList(),
        risk = Risk.LOW,
        timeoutMs = 25_000L,
        resultFormat = "CONFIRMED: the newest published version number and its download page, or that the app is up to date.",
    )

    override fun describe(args: ToolArgs): String = "Check for a newer version of JARVIS 3.0"

    override suspend fun execute(args: ToolArgs): ToolResult = when (val outcome = client.latest()) {
        is UpdateOutcome.Unavailable -> ToolResult.Failure(ToolError.Unavailable(outcome.reason))
        UpdateOutcome.NoRelease -> ToolResult.Success("No release has been published yet, so there is nothing newer to get. You have $currentVersion.", Verification.CONFIRMED, answer = true)
        is UpdateOutcome.Latest -> {
            val newer = UpdateRules.newer(currentVersion, outcome.release)
            if (newer == null) {
                ToolResult.Success("You are up to date. You have $currentVersion; the newest published version is ${outcome.release.version}.", Verification.CONFIRMED, answer = true)
            } else {
                ToolResult.Success(
                    "Version ${newer.version} is available (you have $currentVersion). Download page: ${newer.pageUrl}. " +
                        "Open the app's Nightly review to get it; you install it yourself and Android asks you to confirm.",
                    Verification.CONFIRMED,
                    answer = true,
                )
            }
        }
    }
}

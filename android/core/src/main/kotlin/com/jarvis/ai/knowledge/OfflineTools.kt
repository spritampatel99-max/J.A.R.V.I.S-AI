package com.jarvis.ai.knowledge

import com.jarvis.ai.ai.tools.ParamSpec
import com.jarvis.ai.ai.tools.ParamType
import com.jarvis.ai.ai.tools.Tool
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolError
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.ToolSpec
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.core.security.Risk
import com.jarvis.ai.web.safeLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object OfflineTools {
    const val OFFLINE_LOOKUP = "offline_lookup"

    /** [library] is read at call time, so a library downloaded or removed a moment ago is noticed without restarting. */
    fun create(library: () -> OfflineEncyclopedia?): List<Tool> = listOf(OfflineLookupTool(library))
}

class OfflineLookupTool(private val library: () -> OfflineEncyclopedia?) : Tool {
    override val spec = ToolSpec(
        name = OfflineTools.OFFLINE_LOOKUP,
        description = "Look up a person, place, thing or concept in the encyclopedia saved on this phone. It works with no internet. " +
            "Use it only when the person asks for the offline library, or when the internet is not available. Otherwise use wiki_lookup or web_answer.",
        params = listOf(ParamSpec("topic", ParamType.STRING, "What to look up, for example Taj Mahal", maxLength = 100)),
        risk = Risk.LOW,
        timeoutMs = 15_000L,
        resultFormat = "The opening of the encyclopedia article, marked as outside content.",
    )

    override fun describe(args: ToolArgs): String = "Look up \"${args.string("topic").take(60)}\" in the offline library"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val topic = args.string("topic")
        val lib = library() ?: return ToolResult.Failure(
            ToolError.Unavailable("The offline library is not installed. Open Prefs > Offline library to download it."),
        )
        return when (val found = withContext(Dispatchers.IO) { lib.lookup(topic) }) {
            is OfflineEncyclopedia.Result.Found -> ToolResult.Success(
                "${found.title}: ${found.text}\n(Source: ${lib.sourceName}, a copy saved on this phone; it may be out of date)",
                Verification.CONFIRMED,
                untrusted = true,
                answer = true,
            )
            is OfflineEncyclopedia.Result.NotFound -> ToolResult.Failure(
                ToolError.Failed(
                    "The offline library has no article called \"${safeLabel(topic, 40)}\"." +
                        if (found.similar.isEmpty()) "" else " Close matches: ${found.similar.joinToString(", ")}.",
                ),
            )
            is OfflineEncyclopedia.Result.Unreadable -> ToolResult.Failure(ToolError.Unavailable(found.reason))
        }
    }
}

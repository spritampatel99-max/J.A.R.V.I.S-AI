package com.jarvis.ai.ai.tools.screen

import com.jarvis.ai.ai.tools.ParamSpec
import com.jarvis.ai.ai.tools.ParamType
import com.jarvis.ai.ai.tools.Tool
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolError
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.ToolSpec
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.ai.tools.WebLinks
import com.jarvis.ai.ai.tools.builtin.LaunchResult
import com.jarvis.ai.ai.tools.builtin.UrlOpener
import com.jarvis.ai.core.security.Risk

/**
 * Picks the video to press from YouTube's search results. A result row is a playable video when the app describes it the way it
 * describes every video (a length such as "6 minutes, 22 seconds", or the words "play video"); advertisements, Shorts shelves, channel
 * and playlist rows are passed over. The pick is made by this code and never by the model or by anything the page says.
 */
object YoutubeResults {
    val PACKAGES = setOf("com.google.android.youtube")

    private val LENGTH = Regex(
        """\d+\s*(?:hours?|hrs?|minutes?|mins?|seconds?|secs?)\b|\d+\s*(?:घंटे?|मिनट|सेकंड)|\b\d{1,2}:\d{2}(?::\d{2})?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val PLAY = Regex("""play video|वीडियो चलाएं""", RegexOption.IGNORE_CASE)
    private val AD = Regex("""\bsponsored\b|\badvertisement\b|\bad\s*[·•]|^ad\b|विज्ञापन""", RegexOption.IGNORE_CASE)
    private val NOT_A_VIDEO = Regex("""^(?:shorts|playlist|mix|channel|people also watched|related searches)$""", RegexOption.IGNORE_CASE)

    fun isPlayable(row: ScreenRow): Boolean {
        val texts = row.texts
        if (texts.isEmpty()) return false
        if (texts.any { AD.containsMatchIn(it) }) return false
        if (texts.any { NOT_A_VIDEO.matches(it) }) return false
        return texts.any { PLAY.containsMatchIn(it) || LENGTH.containsMatchIn(it) }
    }

    /** What to call the video: the part of its description before the length, or its first line. */
    fun titleOf(row: ScreenRow): String {
        val described = row.parts.map { it.description.trim() }.filter { it.isNotEmpty() }.maxByOrNull { it.length }
        val source = described ?: row.parts.map { it.text.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val cut = LENGTH.find(source)?.range?.first?.takeIf { it > 0 } ?: source.length
        return source.substring(0, cut).trim().trimEnd('-', '–', '—', '·', ',', ' ').take(80).ifBlank { "the first video" }
    }
}

/**
 * "Open YouTube and play Tum Hi Ho." Opens YouTube's search results for the words, waits for them to appear, and presses the first
 * real video (not an advert). Pressing needs Accessibility and the person's own tap-and-type switch; without them the search is still
 * opened and the person is told what is missing. Android does not report whether the video then started, so the result says only
 * what was pressed.
 */
class PlayOnYoutubeTool(
    private val urls: UrlOpener,
    private val screen: ScreenControl,
    private val pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : Tool {
    override val spec = ToolSpec(
        name = ScreenTools.PLAY_ON_YOUTUBE,
        description = "Open YouTube, search for a song or video, and press the first video result so it plays. " +
            "Use it for \"play Tum Hi Ho on YouTube\". To only look at results without playing, use open_youtube.",
        params = listOf(ParamSpec("query", ParamType.STRING, "The song or video to play, as the person says it", maxLength = 200)),
        risk = Risk.MEDIUM,
        timeoutMs = 45_000L,
        resultFormat = "HANDED_OFF: the search was opened and the first video result was pressed; whether it started playing is not reported back.",
    )

    override fun describe(args: ToolArgs): String = "Play \"${args.string("query").trim().take(80)}\" on YouTube"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val query = args.string("query").trim()
        if (query.isEmpty()) return ToolResult.Failure(ToolError.InvalidArguments("Tell me what to play."))
        when (val opened = urls.open(WebLinks.youtubeSearchUrl(query))) {
            LaunchResult.Launched -> Unit
            is LaunchResult.NoHandler -> return ToolResult.Failure(ToolError.Unavailable("No app or browser can open YouTube here."))
            is LaunchResult.Failed -> return ToolResult.Failure(ToolError.Failed(opened.reason))
        }
        val searched = "Opened the YouTube search for \"${query.take(80)}\""
        if (!screen.enabled) {
            return ToolResult.Success(
                "$searched, but I cannot press play: Accessibility is off. Turn it on in Prefs > Access, and switch on Tap and type there.",
                Verification.HANDED_OFF,
            )
        }
        if (!screen.awaitFront(YoutubeResults.PACKAGES, FRONT_WAIT_MS)) {
            return ToolResult.Success(
                "$searched, but the YouTube app did not come to the front (it may have opened in a browser), so I did not press anything.",
                Verification.HANDED_OFF,
            )
        }

        // The results take a moment to load after the app opens.
        var title: String? = null
        var attempts = 0
        while (title == null && attempts < RESULT_TRIES) {
            pause(RESULT_POLL_MS)
            attempts++
            when (val rows = screen.rows()) {
                is ScreenRows.Found -> if (rows.app.packageName in YoutubeResults.PACKAGES) {
                    rows.rows.firstOrNull(YoutubeResults::isPlayable)?.let { title = YoutubeResults.titleOf(it) }
                }
                is ScreenRows.OffLimits -> return ToolResult.Failure(ToolError.Unsupported("I do not read or control ${rows.app}."))
                ScreenRows.NotEnabled -> break
                ScreenRows.Empty -> Unit
            }
        }
        val chosen = title ?: return ToolResult.Success(
            "$searched, but I could not find a video in the results to press. Say \"read the screen\" to hear what is showing.",
            Verification.HANDED_OFF,
        )
        return when (val pressed = screen.pressRow(YoutubeResults.PACKAGES, YoutubeResults::isPlayable)) {
            is ScreenAct.Done -> ToolResult.Success(
                "$searched and pressed the first video, \"$chosen\". Android does not tell me whether it started playing.",
                Verification.HANDED_OFF,
            )
            ScreenAct.NotEnabled -> ToolResult.Success("$searched, but Accessibility is off, so I did not press play.", Verification.HANDED_OFF)
            is ScreenAct.OffLimits -> ToolResult.Failure(ToolError.Unsupported("I do not read or control ${pressed.app}."))
            is ScreenAct.Failed -> ToolResult.Success("$searched, but I did not press play: ${pressed.reason}", Verification.HANDED_OFF)
        }
    }

    private companion object {
        const val FRONT_WAIT_MS = 10_000L
        const val RESULT_POLL_MS = 800L
        const val RESULT_TRIES = 10
    }
}

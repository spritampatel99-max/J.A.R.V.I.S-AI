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

/** Plain-words durations for confirmations and replies: 90 -> "1 minute 30 seconds". */
internal object DurationWords {
    fun of(totalSeconds: Int): String {
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        val parts = buildList {
            if (hours > 0) add("$hours hour${if (hours == 1) "" else "s"}")
            if (minutes > 0) add("$minutes minute${if (minutes == 1) "" else "s"}")
            if (seconds > 0) add("$seconds second${if (seconds == 1) "" else "s"}")
        }
        return parts.joinToString(" ")
    }
}

class SetTimerTool(private val timers: TimerLauncher) : Tool {
    override val spec = ToolSpec(
        name = "set_timer",
        description = "Start a countdown timer in the phone's Clock app, for example 10 minutes is 600 seconds.",
        params = listOf(
            ParamSpec("seconds", ParamType.INT, "How long the timer runs, in seconds (1 to 86400)", min = 1, max = MAX_SECONDS),
            ParamSpec("label", ParamType.STRING, "A short name for the timer", required = false, maxLength = 40),
        ),
        risk = Risk.LOW,
        timeoutMs = 10_000L,
        resultFormat = "HANDED_OFF: Android asked the Clock app to start the timer; whether it started is not observable.",
    )

    override fun describe(args: ToolArgs): String = "Start a ${DurationWords.of(args.int("seconds"))} timer"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val seconds = args.int("seconds")
        if (seconds !in 1..MAX_SECONDS) return ToolResult.Failure(ToolError.InvalidArguments("A timer can run from 1 second to 24 hours."))
        val label = args.stringOrNull("label")?.filter { !it.isISOControl() }?.trim()?.takeIf { it.isNotEmpty() }
        return when (val result = timers.start(seconds, label)) {
            LaunchResult.Launched -> ToolResult.Success(
                "Asked the Clock app to start a ${DurationWords.of(seconds)} timer. Android does not tell this app whether it started; check the Clock app if you are unsure.",
                Verification.HANDED_OFF,
                mapOf("seconds" to seconds.toString()),
            )
            is LaunchResult.NoHandler -> ToolResult.Failure(ToolError.Unavailable("This phone has no Clock app that can start a timer."))
            is LaunchResult.Failed -> ToolResult.Failure(ToolError.Failed(result.reason))
        }
    }

    companion object {
        const val MAX_SECONDS = 86_400
    }
}

class MediaControlTool(private val media: MediaKeys) : Tool {
    override val spec = ToolSpec(
        name = "media_control",
        description = "Control whatever music or video is playing: play, pause, next, previous, stop, or the volume. " +
            "It sends the same buttons as headphones do; it cannot choose a song.",
        params = listOf(
            ParamSpec("action", ParamType.ENUM, "What to do", enumValues = MediaAction.entries.map { it.name.lowercase(Locale.ROOT) }),
            ParamSpec("steps", ParamType.INT, "How many volume steps (1 to 5); only for volume_up and volume_down", required = false, min = 1, max = MAX_STEPS),
        ),
        risk = Risk.LOW,
        timeoutMs = 5_000L,
        resultFormat = "HANDED_OFF for the media buttons (Android does not say whether an app reacted); CONFIRMED for volume, with the levels Android reported.",
    )

    override fun describe(args: ToolArgs): String = "Media: ${action(args.string("action")).spoken}"

    private fun action(name: String): MediaAction = MediaAction.valueOf(name.uppercase(Locale.ROOT))

    override suspend fun execute(args: ToolArgs): ToolResult {
        val action = action(args.string("action"))
        val steps = (args.intOrNull("steps") ?: 1).coerceIn(1, MAX_STEPS)
        return when (val result = media.perform(action, steps)) {
            MediaResult.Sent -> ToolResult.Success(
                "Sent \"${action.spoken}\" to the phone's media controls. Android does not say whether an app reacted; if nothing was playing, nothing happened.",
                Verification.HANDED_OFF,
            )
            is MediaResult.Volume -> when {
                result.after == result.before ->
                    ToolResult.Success(
                        "The volume did not change: it is already at ${if (action == MediaAction.VOLUME_UP) "the maximum" else "the minimum"} (${result.after} of ${result.max}).",
                        Verification.CONFIRMED,
                    )
                else -> ToolResult.Success(
                    "Media volume is now ${result.after} of ${result.max} (was ${result.before}), as reported by Android.",
                    Verification.CONFIRMED,
                    mapOf("volume" to result.after.toString(), "max" to result.max.toString()),
                )
            }
            is MediaResult.Failed -> ToolResult.Failure(ToolError.Failed(result.reason))
        }
    }

    companion object {
        const val MAX_STEPS = 5
    }
}

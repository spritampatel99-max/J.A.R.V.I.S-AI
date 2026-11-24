package com.jarvis.ai.voice

import java.util.Locale

/**
 * Words that control the voice itself, not the phone: "stop talking" and "say that again".
 *
 * They are matched as whole sentences only, so "stop the alarm" or "repeat after me" are not caught here. They never reach an AI
 * model and never start a tool: stopping the speech and reading the last reply again are the only things they can do.
 */
object VoiceControls {

    enum class Control {
        /** Stop speaking and do nothing else. */
        QUIET,

        /** Read the last reply out again. */
        REPEAT,
    }

    private val QUIET = Regex(
        """^(?:(?:ok|okay|alright|yes|please|hey) )*(?:stop|stop (?:it|that|talking|speaking|reading|now)|be quiet|quiet|hush|shush|silence|enough|that(?:'s| is) enough|""" +
            """that will do|never ?mind|forget it|cancel that|ok(?:ay)? that(?:'s| is) enough|ruko|ruk jao|chup|chup raho|bas|bas karo|bas ho gaya)(?: please| now)?$""",
        RegexOption.IGNORE_CASE,
    )

    private val REPEAT = Regex(
        """^(?:(?:ok|okay|alright|please|hey) )*(?:repeat(?: that| it| the (?:last )?(?:answer|reply))?|say (?:that|it) again|say again|come again|""" +
            """what did you say|what was that|once more|again please|dobara bolo|phir se bolo)(?: please)?$""",
        RegexOption.IGNORE_CASE,
    )

    fun match(raw: String): Control? {
        val text = raw.trim().trimEnd('.', '!', '?', ',', ' ').replace(Regex("""\s+"""), " ").lowercase(Locale.ROOT)
        if (text.isEmpty() || text.length > MAX_LENGTH) return null
        return when {
            QUIET.matches(text) -> Control.QUIET
            REPEAT.matches(text) -> Control.REPEAT
            else -> null
        }
    }

    /**
     * What the model is told about the reply it gave that the person then interrupted: that they may not have heard all of it, so a
     * short follow-up ("shorter", "in Hindi", "no, the other one") changes that reply instead of starting a new topic.
     */
    const val INTERRUPTED_NOTE =
        " [The person interrupted this reply while it was being read aloud, so they may not have heard all of it. Their next message may correct, shorten or change it.]"

    /** True when an interruption this recent should still be told to the model; an old one is forgotten. */
    fun interruptionStillRelevant(interruptedAtMs: Long, nowMs: Long): Boolean = interruptedAtMs > 0 && nowMs - interruptedAtMs in 0..RELEVANT_MS

    private const val RELEVANT_MS = 90_000L
    private const val MAX_LENGTH = 60
}

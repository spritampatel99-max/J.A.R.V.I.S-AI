package com.jarvis.ai.voice

/** Plain-language text for the speech recogniser's error codes (the values of `SpeechRecognizer.ERROR_*`). */
object VoiceErrors {
    const val ERROR_SPEECH_TIMEOUT = 6
    const val ERROR_NO_MATCH = 7
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9

    fun message(code: Int): String = when (code) {
        1 -> "The speech service timed out. Check the connection and try again."
        2 -> "Speech recognition needs a network connection on this phone, and none is available."
        3 -> "The microphone could not be used. Another app may be using it."
        4 -> "The speech service had a problem. Try again."
        5 -> "Listening was interrupted. Try again."
        ERROR_SPEECH_TIMEOUT -> "I did not hear anything. Tap the mic and speak once it says Listening."
        ERROR_NO_MATCH -> "I could not make out what you said. Try again, closer to the microphone."
        8 -> "The speech service is busy. Wait a moment and try again."
        ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access is off. Allow it in the Prefs tab."
        10 -> "Too many requests to the speech service. Wait a moment and try again."
        11 -> "The speech service disconnected. Try again."
        12 -> "This phone's speech service does not support that language."
        13 -> "That language is not downloaded for offline speech. Connect to the internet, or add it in Android's settings."
        else -> "Speech recognition failed (code $code)."
    }

    /** True for the ordinary "you said nothing / it was unclear" cases, which are shown as a hint, not as an error. */
    fun isRoutine(code: Int): Boolean = code == ERROR_SPEECH_TIMEOUT || code == ERROR_NO_MATCH
}

/** Prepares a reply for text-to-speech: no markup, no links read letter by letter, and nothing from outside sources read aloud. */
object SpeechText {
    private val UNTRUSTED_BLOCK = Regex("""<untrusted_data[\s\S]*?</untrusted_data>""", RegexOption.IGNORE_CASE)
    private val LINK = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val MARKUP = Regex("""[*_`#>~|\[\]{}✔✘➜⊘⛔]""")
    private val SPACES = Regex("""\s+""")

    /** @return the text to speak, or null when there is nothing worth saying. */
    fun forSpeaking(reply: String, maxChars: Int = 400): String? {
        var text = reply
            .replace(UNTRUSTED_BLOCK, " That content came from outside the app; it is on screen. ")
            .replace(LINK, " a link ")
            .replace(MARKUP, " ")
            .replace(SPACES, " ")
            .trim()
        if (text.isEmpty()) return null
        if (text.length > maxChars) {
            val cut = text.substring(0, maxChars)
            val sentenceEnd = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
            text = if (sentenceEnd >= maxChars / 2) cut.substring(0, sentenceEnd + 1) else cut.trimEnd() + "…"
        }
        return text
    }
}

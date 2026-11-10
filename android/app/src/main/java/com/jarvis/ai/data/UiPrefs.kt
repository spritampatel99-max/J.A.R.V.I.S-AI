package com.jarvis.ai.data

/**
 * Look and voice preferences shown in the Prefs tab.
 *
 * [speakReplies] only ever applies to requests the person made by voice; typed requests are answered in text.
 */
data class UiPrefs(
    /** The faint dot grid in the background. */
    val ambient: Boolean = true,
    /** Turns off every looping animation (the orb, the background drift, the waveform). */
    val reduceMotion: Boolean = false,
    /** How bright the orb glows, 0.1 to 1. */
    val flare: Float = 0.85f,
    val speakReplies: Boolean = true,
    /** Text-to-speech speed, 0.75 to 1.5. */
    val speechRate: Float = 1.0f,
    /** Name of the chosen text-to-speech voice, or null for the phone's default. */
    val voiceName: String? = null,
) {
    fun sanitised() = copy(
        flare = flare.coerceIn(0.1f, 1f),
        speechRate = speechRate.coerceIn(0.75f, 1.5f),
        voiceName = voiceName?.takeIf { it.isNotBlank() },
    )
}

package com.jarvis.ai.data

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** The detector that listens for the word "Jarvis" while hands-free is on. */
enum class WakeEngine(val label: String) {
    VOSK("Offline, no key"),
    PORCUPINE("Picovoice (needs key)"),
}

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
    /** When on, speech is only ever recognised by an on-device recogniser; with none installed, listening says so instead of falling back. */
    val onDeviceSpeechOnly: Boolean = false,
    /** Light, dark, or follow the phone. */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Use the phone's wallpaper colours (Material You) for the accent colours instead of the JARVIS spectrum. */
    val dynamicColor: Boolean = false,
    /** Speak replies in the person's ElevenLabs voice. Needs their own key and a chosen voice; private content never uses it. */
    val elevenEnabled: Boolean = false,
    val elevenVoiceId: String? = null,
    val elevenVoiceName: String? = null,
    /** While a Bluetooth audio device is connected, read new notifications aloud on the phone's own voice. */
    val announceNotifications: Boolean = false,
    /** When announcing, also say what the notification says. Off, only who it is from is said. */
    val announceReadText: Boolean = true,
    /** Read the words of notifications even while the phone is locked and the lock screen would hide them. Off: only the app is named then. */
    val announceLockedText: Boolean = false,
) {
    fun sanitised() = copy(
        flare = flare.coerceIn(0.1f, 1f),
        speechRate = speechRate.coerceIn(0.75f, 1.5f),
        voiceName = voiceName?.takeIf { it.isNotBlank() },
        elevenVoiceId = elevenVoiceId?.takeIf { com.jarvis.ai.voice.ElevenLabsApi.VOICE_ID.matches(it) },
        elevenVoiceName = elevenVoiceName?.takeIf { it.isNotBlank() }?.take(40),
    )
}

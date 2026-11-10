package com.jarvis.ai.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.jarvis.ai.data.UiPrefs
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the voice layer is doing right now, for the Home screen and the chat input. */
data class VoiceUi(
    val listening: Boolean = false,
    val speaking: Boolean = false,
    /** Microphone loudness 0..1 while listening. Real data from the recogniser. */
    val level: Float = 0f,
    /** Words recognised so far in the current session. */
    val partial: String = "",
    /** A one-line problem for the person, or null. */
    val message: String? = null,
    val recognizerAvailable: Boolean = true,
)

data class VoiceChoice(val name: String, val label: String)

/**
 * Speech in and out, through the phone's own services.
 *
 * Listening is push-to-talk: one session per tap, ending when the person stops talking. The recogniser is Android's
 * [SpeechRecognizer]; on Android 13 and later the on-device one is used when the phone has it. Otherwise the phone's
 * default recogniser is used, which may send the sound to its provider. This class never records, stores or forwards audio.
 *
 * Every method must be called on the main thread.
 */
class VoiceController(context: Context, private val prefs: () -> UiPrefs) {

    private val appContext = context.applicationContext
    private val flow = MutableStateFlow(VoiceUi(recognizerAvailable = SpeechRecognizer.isRecognitionAvailable(appContext)))
    val state: StateFlow<VoiceUi> = flow.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var onFinal: ((String) -> Unit)? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** A reply asked for before the speech engine finished starting; it is spoken as soon as the engine is ready. */
    private var pendingSpeech: String? = null

    // ---- listening ------------------------------------------------------------------------------------------------

    fun startListening(onFinal: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            flow.update { it.copy(message = "This phone has no speech recogniser installed.", recognizerAvailable = false) }
            return
        }
        stopSpeaking()
        destroyRecognizer()
        this.onFinal = onFinal

        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        val created = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext) else SpeechRecognizer.createSpeechRecognizer(appContext)
        created.setRecognitionListener(listener)
        recognizer = created

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            if (onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        flow.update { it.copy(listening = true, level = 0f, partial = "", message = null) }
        created.startListening(intent)
    }

    /** Ends the session and still delivers what was heard so far. */
    fun stopListening() {
        recognizer?.stopListening()
    }

    /** Ends the session and discards it. */
    fun cancelListening() {
        onFinal = null
        destroyRecognizer()
        flow.update { it.copy(listening = false, level = 0f, partial = "") }
    }

    private fun destroyRecognizer() {
        recognizer?.destroy()
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onEndOfSpeech() {
            flow.update { it.copy(level = 0f) }
        }

        override fun onRmsChanged(rmsdB: Float) {
            // The recogniser reports roughly -2 to 10 dB; smooth it so the bars do not flicker.
            val target = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            flow.update { it.copy(level = it.level * 0.6f + target * 0.4f) }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) flow.update { it.copy(partial = text) }
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            val deliver = onFinal
            onFinal = null
            destroyRecognizer()
            flow.update { it.copy(listening = false, level = 0f, partial = "", message = if (text.isEmpty()) "I did not catch anything. Try again." else null) }
            if (text.isNotEmpty()) deliver?.invoke(text)
        }

        override fun onError(error: Int) {
            onFinal = null
            destroyRecognizer()
            val message = if (VoiceErrors.isRoutine(error)) "I did not catch anything. Try again." else VoiceErrors.message(error)
            flow.update { it.copy(listening = false, level = 0f, partial = "", message = message) }
        }
    }

    /** Shows a one-line note under the orb, for something the voice layer itself did not detect (for example a missing permission). */
    fun showMessage(text: String) {
        flow.update { it.copy(message = text) }
    }

    fun clearMessage() {
        flow.update { it.copy(message = null) }
    }

    // ---- speaking -------------------------------------------------------------------------------------------------

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                pendingSpeech?.let { speakNow(it) }
                pendingSpeech = null
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        flow.update { it.copy(speaking = true) }
                    }

                    override fun onDone(utteranceId: String?) {
                        flow.update { it.copy(speaking = false) }
                    }

                    @Deprecated("Required override on older API levels")
                    override fun onError(utteranceId: String?) {
                        flow.update { it.copy(speaking = false) }
                    }
                })
            } else {
                flow.update { it.copy(message = "Text-to-speech is not available on this phone.") }
            }
        }
    }

    /** Reads a reply aloud. Web content and markup are stripped first; nothing is spoken if only that remains. */
    fun speak(reply: String) {
        val text = SpeechText.forSpeaking(reply) ?: return
        ensureTts()
        if (!ttsReady) {
            pendingSpeech = text
            return
        }
        speakNow(text)
    }

    private fun speakNow(text: String) {
        val engine = tts ?: return
        val settings = prefs()
        engine.setSpeechRate(settings.speechRate)
        settings.voiceName?.let { wanted ->
            engine.voices?.firstOrNull { it.name == wanted }?.let { engine.voice = it }
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-reply")
    }

    fun stopSpeaking() {
        pendingSpeech = null
        tts?.stop()
        flow.update { it.copy(speaking = false) }
    }

    /** Voices installed for the phone's own language, for the picker in Prefs. Empty until the engine has started. */
    fun voices(): List<VoiceChoice> {
        ensureTts()
        if (!ttsReady) return emptyList()
        val language = Locale.getDefault().language
        return tts?.voices.orEmpty()
            .filter { it.locale.language == language && !it.isNetworkConnectionRequired }
            .sortedBy { it.name }
            .map { VoiceChoice(it.name, it.locale.getDisplayName(Locale.getDefault()) + " · " + it.name) }
    }

    fun shutdown() {
        destroyRecognizer()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}

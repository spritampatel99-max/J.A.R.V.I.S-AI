package com.jarvis.ai.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.jarvis.ai.android.voice.ElevenSpeaker
import com.jarvis.ai.data.UiPrefs
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 * [SpeechRecognizer]; the on-device one is used when the phone has it (always answerable on Android 14 and later). Otherwise the phone's
 * default recogniser is used, which may send the sound to its provider. This class never records, stores or forwards audio.
 *
 * Every method must be called on the main thread.
 */
class VoiceController(context: Context, private val prefs: () -> UiPrefs, private val eleven: ElevenSpeaker? = null) {

    private val appContext = context.applicationContext
    private val flow = MutableStateFlow(VoiceUi(recognizerAvailable = SpeechRecognizer.isRecognitionAvailable(appContext)))
    val state: StateFlow<VoiceUi> = flow.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var onFinal: ((String) -> Unit)? = null
    private var onNothing: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private var utteranceCounter = 0
    private var currentUtterance: String? = null
    private val whenSpoken = HashMap<String, () -> Unit>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val elevenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var elevenJob: Job? = null

    /** Called when an ElevenLabs reply is stopped before it ends, so whoever waits for the speech to finish is never left waiting. */
    private var elevenDone: (() -> Unit)? = null

    /** A reply asked for before the speech engine finished starting; it is spoken as soon as the engine is ready. */
    private var pendingSpeech: Pair<String, (() -> Unit)?>? = null

    // ---- listening ------------------------------------------------------------------------------------------------

    /**
     * Starts one listening session. [onFinal] gets the words that were heard. [onNothing] runs instead when the session ends
     * with nothing usable (silence, an error, no recogniser), so a caller that is waiting never hangs. Neither runs when the
     * session is ended with [cancelListening].
     */
    fun startListening(onFinal: (String) -> Unit, onNothing: (() -> Unit)? = null) {
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        if (prefs().onDeviceSpeechOnly && !onDevice) {
            flow.update {
                it.copy(message = "This phone has no on-device speech recogniser. Turn off \"On-device speech only\" in Prefs to use the phone's default one.")
            }
            onNothing?.invoke()
            return
        }
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(appContext)) {
            flow.update { it.copy(message = "This phone has no speech recogniser installed.", recognizerAvailable = false) }
            onNothing?.invoke()
            return
        }
        stopSpeaking()
        destroyRecognizer()
        this.onFinal = onFinal
        this.onNothing = onNothing

        val created = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext) else SpeechRecognizer.createSpeechRecognizer(appContext)
        created.setRecognitionListener(listener)
        recognizer = created

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            // Follows the app's own language (Android's per-app language setting), so Hindi is recognised as Hindi.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
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
        onNothing = null
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
            val nothing = onNothing
            onFinal = null
            onNothing = null
            destroyRecognizer()
            flow.update { it.copy(listening = false, level = 0f, partial = "", message = if (text.isEmpty()) "I did not catch anything. Try again." else null) }
            if (text.isNotEmpty()) deliver?.invoke(text) else nothing?.invoke()
        }

        override fun onError(error: Int) {
            val nothing = onNothing
            onFinal = null
            onNothing = null
            destroyRecognizer()
            val message = if (VoiceErrors.isRoutine(error)) "I did not catch anything. Try again." else VoiceErrors.message(error)
            flow.update { it.copy(listening = false, level = 0f, partial = "", message = message) }
            nothing?.invoke()
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
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        flow.update { it.copy(speaking = true) }
                    }

                    override fun onDone(utteranceId: String?) {
                        finished(utteranceId)
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        finished(utteranceId)
                    }

                    @Deprecated("Required override on older API levels")
                    override fun onError(utteranceId: String?) {
                        finished(utteranceId)
                    }
                })
                pendingSpeech?.let { speakNow(it.first, it.second) }
                pendingSpeech = null
            } else {
                flow.update { it.copy(message = "Text-to-speech is not available on this phone.") }
                // Whoever was waiting for the reply to be spoken must not wait forever.
                pendingSpeech?.second?.let { main.post(it) }
                pendingSpeech = null
                flow.update { it.copy(speaking = false) }
            }
        }
    }

    /**
     * Reads a reply aloud. Web content and markup are stripped first; nothing is spoken if only that remains.
     * [onDone] runs on the main thread once the speech has ended, been stopped, or failed, and also at once when there is
     * nothing to say, so a caller that waits for it is never left waiting.
     */
    fun speak(reply: String, onDone: (() -> Unit)? = null, private: Boolean = false) {
        val text = SpeechText.forSpeaking(reply)
        if (text == null) {
            onDone?.let { main.post(it) }
            return
        }
        // Marked as speaking at once, so anything watching the voice state does not see a quiet gap before the engine starts.
        flow.update { it.copy(speaking = true) }
        // A new reply replaces one that is still being fetched or played.
        stopEleven()
        val settings = prefs()
        val route = SpeechRoute.choose(
            text = text,
            private = private,
            enabled = settings.elevenEnabled,
            hasKey = eleven?.hasKey() == true,
            voiceId = settings.elevenVoiceId,
            online = eleven?.online() == true,
        )
        if (route == SpeechRoute.ELEVENLABS && eleven != null && settings.elevenVoiceId != null) {
            speakWithEleven(eleven, settings.elevenVoiceId, text, onDone)
        } else {
            speakOnPhone(text, onDone)
        }
    }

    /**
     * Reads text that is the person's own private content (a message, a notification, what is on the screen). It is only ever spoken by the
     * phone's own voice and never sent to ElevenLabs.
     */
    fun speakPrivately(reply: String, onDone: (() -> Unit)? = null) = speak(reply, onDone, private = true)

    private fun speakOnPhone(text: String, onDone: (() -> Unit)?) {
        ensureTts()
        if (!ttsReady) {
            pendingSpeech = text to onDone
            return
        }
        speakNow(text, onDone)
    }

    private fun speakWithEleven(backend: ElevenSpeaker, voiceId: String, text: String, onDone: (() -> Unit)?) {
        val id = "jarvis-${utteranceCounter++}"
        currentUtterance = id
        elevenDone = onDone
        elevenJob = elevenScope.launch {
            when (val result = backend.client.speak(voiceId, text)) {
                is ElevenOutcome.Ok -> {
                    if (currentUtterance != id) return@launch
                    backend.player.play(result.value) { played ->
                        if (currentUtterance != id) return@play
                        if (played) {
                            finishEleven(id)
                        } else {
                            // The audio could not be played: say it on the phone's own voice instead of staying silent.
                            elevenDone = null
                            flow.update { it.copy(message = "I could not play the ElevenLabs voice, so I used the phone's voice.") }
                            speakOnPhone(text, onDone)
                        }
                    }
                }
                is ElevenOutcome.Failed -> {
                    if (currentUtterance != id) return@launch
                    elevenDone = null
                    // Never silent because of a network or account problem: the phone's voice speaks, and the reason is shown.
                    flow.update { it.copy(message = result.message + " I used the phone's voice instead.") }
                    speakOnPhone(text, onDone)
                }
            }
        }
    }

    private fun finishEleven(id: String) {
        val callback = elevenDone
        elevenDone = null
        if (currentUtterance == id) {
            currentUtterance = null
            flow.update { it.copy(speaking = false) }
        }
        callback?.invoke()
    }

    /** Stops an ElevenLabs reply that is being fetched or played, and tells whoever was waiting for it. */
    private fun stopEleven() {
        elevenJob?.cancel()
        elevenJob = null
        eleven?.player?.stop()
        val callback = elevenDone
        elevenDone = null
        callback?.let { main.post(it) }
    }

    private fun speakNow(text: String, onDone: (() -> Unit)?) {
        val engine = tts ?: return
        val settings = prefs()
        engine.setSpeechRate(settings.speechRate)
        settings.voiceName?.let { wanted ->
            engine.voices?.firstOrNull { it.name == wanted }?.let { engine.voice = it }
        }
        val id = "jarvis-${utteranceCounter++}"
        currentUtterance = id
        if (onDone != null) whenSpoken[id] = onDone
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) finished(id)
    }

    /** Called from the speech engine's own thread. */
    private fun finished(utteranceId: String?) {
        main.post {
            val callback = utteranceId?.let { whenSpoken.remove(it) }
            // A flushed utterance reports its end after the next one has started; only the current one may clear "speaking".
            if (utteranceId == currentUtterance || currentUtterance == null) {
                currentUtterance = null
                flow.update { it.copy(speaking = false) }
            }
            callback?.invoke()
        }
    }

    fun stopSpeaking() {
        stopEleven()
        pendingSpeech?.second?.let { main.post(it) }
        pendingSpeech = null
        currentUtterance = null
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
        stopEleven()
        destroyRecognizer()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        whenSpoken.clear()
    }
}

package com.jarvis.ai.android.voice

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jarvis.ai.AppContainer
import com.jarvis.ai.JarvisApplication
import com.jarvis.ai.data.WakeEngine
import com.jarvis.ai.ui.MainActivity
import com.jarvis.ai.ui.Sender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** What the hands-free service is doing, for the Prefs page. */
sealed interface HandsFreeStatus {
    data object Off : HandsFreeStatus

    /** Listening for the word "Jarvis" and nothing else. */
    data object Waiting : HandsFreeStatus

    /** A request is being heard, worked on or spoken. */
    data object Busy : HandsFreeStatus

    data class Problem(val reason: String) : HandsFreeStatus
}

/**
 * Always-on "Jarvis" listening.
 *
 * While this service runs, a small on-device wake-word detector (Picovoice Porcupine, keyword "Jarvis") listens to the
 * microphone. It does not recognise speech, store audio or send anything anywhere except that Picovoice checks the
 * AccessKey the person typed in. When it hears the word, the detector is paused (two things cannot share the microphone),
 * a beep plays, and the ordinary speech recogniser takes one request. The request then goes through the very same assistant
 * as typed chat: same tools, same checks, same confirmation rules. When the assistant is done and quiet, the detector resumes.
 *
 * Android rules this obeys: a microphone service must be started while the app is on screen, shows a permanent notification
 * with a Stop button, and is not restarted by Android after a reboot or if it is killed (the person opens the app and turns
 * it on again).
 */
class HandsFreeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var container: AppContainer

    private var wake: WakeDetector? = null
    private var wakeRunning = false
    private var overlay: HandsFreeOverlay? = null
    private var controller: Job? = null
    private var hideJob: Job? = null

    /** True from the wake word until a reply that is not a question has been given, so "Which one?" is answered without saying "Jarvis" again. */
    private var armedForFollowUp = false
    private var followedUpAt = -1L

    /** The overlay keeps showing a reply until this time, so the status line does not wipe it. */
    private var holdOverlayUntilMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        container = (application as JarvisApplication).container
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            container.settings.handsFreeOn = false
            stopHandsFree()
            return START_NOT_STICKY
        }
        createChannel()
        // Always enter the foreground first, even when listening is already running: every startForegroundService() call must be answered.
        if (!enterForeground()) return START_NOT_STICKY
        if (controller?.isActive == true) return START_NOT_STICKY

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return fail("Allow the microphone first (Prefs > Access).")
        }
        val detector: WakeDetector = when (container.settings.wakeEngine) {
            WakeEngine.PORCUPINE -> buildPorcupine() ?: return START_NOT_STICKY
            WakeEngine.VOSK -> buildVosk() ?: return START_NOT_STICKY
        }
        wake = detector

        overlay = HandsFreeOverlay(this).also { card -> card.onCancel = { cancelPending() } }
        startController()
        status.value = HandsFreeStatus.Waiting
        return START_NOT_STICKY
    }

    /** @return the detector, or null after reporting why it could not be made. */
    private fun buildPorcupine(): WakeDetector? {
        val accessKey = container.keys.get(ACCESS_KEY_NAME)
        if (accessKey.isNullOrBlank()) {
            fail("Enter your Picovoice AccessKey first (Prefs > Hands-free), or switch to the offline listener that needs no key.")
            return null
        }
        return try {
            PorcupineWake(
                PorcupineManager.Builder()
                    .setAccessKey(accessKey)
                    .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                    .setSensitivity(SENSITIVITY)
                    .setErrorCallback { e -> main.post { fail("The wake-word listener stopped: ${e.message.orEmpty().take(120)}") } }
                    .build(applicationContext, PorcupineManagerCallback { main.post { onWakeWord() } }),
            )
        } catch (e: PorcupineException) {
            // The message never contains the key. Typical causes: a wrong AccessKey, no internet to check it, or the free limit.
            fail("The wake-word listener could not start: ${e.message.orEmpty().take(160)}")
            null
        } catch (e: RuntimeException) {
            fail("The wake-word listener could not start (${e.javaClass.simpleName}).")
            null
        }
    }

    private fun buildVosk(): WakeDetector? {
        val model = container.voskModel.open()
        if (model == null) {
            fail("Download the offline wake-word model first (Prefs > Hands-free > Offline listener).")
            return null
        }
        return try {
            VoskWake(
                model = model,
                onWake = { main.post { onWakeWord() } },
                onHeard = { text -> lastHeard.value = text },
                onProblem = { reason -> main.post { fail(reason) } },
            )
        } catch (e: RuntimeException) {
            fail("The offline wake-word listener could not start (${e.javaClass.simpleName}).")
            null
        } catch (e: UnsatisfiedLinkError) {
            fail("The offline wake-word library did not load on this phone.")
            null
        }
    }

    // ---- the loop -------------------------------------------------------------------------------------------------

    private fun startController() {
        val voice = container.voice
        val assistant = container.assistant

        // The detector only listens while nothing else is using the microphone or the assistant.
        controller = scope.launch {
            combine(voice.state, assistant.chatState, assistant.countdown, assistant.pendingConfirmation, container.settings.ui) { v, c, k, p, ui ->
                when {
                    v.listening || c.running || k != null -> Activity.BUSY
                    // While a reply is read aloud the wake word stays on, so "Jarvis" can interrupt it. Not while a confirmation is waiting
                    // (its read-back must not be cut off by a voice), and not for a reply that contains the word itself.
                    v.speaking -> if (ui.bargeIn && p == null && !lastReplyContainsWakeWord(c)) Activity.SPEAKING else Activity.BUSY
                    else -> Activity.IDLE
                }
            }.distinctUntilChanged().collectLatest { activity ->
                when (activity) {
                    Activity.BUSY -> {
                        pauseWake()
                        status.value = HandsFreeStatus.Busy
                    }
                    Activity.SPEAKING -> resumeWake()
                    Activity.IDLE -> {
                        delay(RESUME_DELAY_MS)
                        if (!followUpIfAsked()) resumeWake()
                    }
                }
            }
        }

        // What the floating card says while something is happening.
        scope.launch {
            combine(voice.state, assistant.chatState, assistant.countdown, assistant.pendingConfirmation) { v, c, k, p ->
                when {
                    k != null -> "Going ahead in ${k.secondsLeft}…  Say \"stop\" or tap Cancel." to true
                    // Anything the person did not say as a built-in call still needs a tap, and only the app can show that card.
                    p != null -> "Needs your OK: ${p.summary.take(120)}. Open JARVIS 3.0 to answer, or cancel here." to true
                    v.listening -> (v.partial.ifBlank { "Listening…" }) to false
                    c.running -> (c.currentStep ?: "Thinking…") to false
                    else -> null
                }
            }.distinctUntilChanged().collect { shown ->
                if (shown != null) {
                    hideJob?.cancel()
                    overlay?.show(shown.first, cancellable = shown.second)
                } else if (System.currentTimeMillis() >= holdOverlayUntilMs) {
                    hideJob?.cancel()
                    hideJob = scope.launch {
                        delay(STATUS_LINGER_MS)
                        overlay?.hide()
                    }
                }
            }
        }

        // The answer, shown for as long as it takes to read.
        scope.launch {
            assistant.chatState.collect { chat ->
                val last = chat.items.lastOrNull() ?: return@collect
                if (last.sender == Sender.USER || !last.viaVoice || last.id <= lastShownReply) return@collect
                lastShownReply = last.id
                val visibleMs = (REPLY_MIN_MS + last.text.length * REPLY_PER_CHAR_MS).coerceAtMost(REPLY_MAX_MS)
                holdOverlayUntilMs = System.currentTimeMillis() + visibleMs
                hideJob?.cancel()
                overlay?.show(last.text.take(400), cancellable = false)
                hideJob = scope.launch {
                    delay(visibleMs)
                    overlay?.hide()
                }
            }
        }
    }

    private var lastShownReply = -1L

    private enum class Activity { IDLE, SPEAKING, BUSY }

    private fun lastReplyContainsWakeWord(chat: com.jarvis.ai.ui.ChatState): Boolean =
        chat.items.lastOrNull { it.sender != Sender.USER }?.text?.contains("jarvis", ignoreCase = true) == true

    private fun isBusy(): Boolean {
        val v = container.voice.state.value
        val c = container.assistant.chatState.value
        return v.listening || v.speaking || c.running || container.assistant.countdown.value != null
    }

    /** True while a reply is being read aloud and nothing else is going on, so the wake word may cut it short. */
    private fun canInterruptSpeech(): Boolean {
        val v = container.voice.state.value
        val c = container.assistant.chatState.value
        return container.settings.ui.value.bargeIn && v.speaking && !v.listening && !c.running &&
            container.assistant.countdown.value == null && container.assistant.pendingConfirmation.value == null &&
            !lastReplyContainsWakeWord(c)
    }

    private fun onWakeWord() {
        val interrupting = canInterruptSpeech()
        if (isBusy() && !interrupting) return
        pauseWake()
        if (interrupting) container.assistant.interruptSpeech()
        beep()
        armedForFollowUp = true
        container.assistant.listen(onNothing = {
            armedForFollowUp = false
            if (!isBusy()) resumeWake()
        })
    }

    /** After a reply that ends in a question, listens once more without needing the wake word. */
    private fun followUpIfAsked(): Boolean {
        if (!armedForFollowUp) return false
        val last = container.assistant.chatState.value.items.lastOrNull()
        val asked = last != null && last.sender == Sender.ASSISTANT && last.viaVoice &&
            last.text.trimEnd().endsWith("?") && last.id > followedUpAt
        if (!asked) {
            armedForFollowUp = false
            return false
        }
        followedUpAt = last!!.id
        pauseWake()
        beep()
        container.assistant.listen(onNothing = {
            armedForFollowUp = false
            if (!isBusy()) resumeWake()
        })
        return true
    }

    private fun cancelPending() {
        // The Cancel button on the floating card is a tap by the person, the same as Cancel in the app. It can only deny.
        container.assistant.pendingConfirmation.value?.let { container.assistant.deny(it.id) }
    }

    private fun resumeWake() {
        val detector = wake ?: return
        if (wakeRunning) return
        try {
            detector.start()
            wakeRunning = true
            status.value = HandsFreeStatus.Waiting
        } catch (e: WakeException) {
            fail("The wake-word listener could not resume: ${e.message.orEmpty().take(120)}")
        }
    }

    private fun pauseWake() {
        val detector = wake ?: return
        if (!wakeRunning) return
        wakeRunning = false
        detector.stop()
    }

    private fun beep() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 120)
            main.postDelayed({ tone.release() }, 400)
        } catch (e: RuntimeException) {
            // No sound; the card or the orb still shows that it is listening.
        }
    }

    // ---- stopping and failing ---------------------------------------------------------------------------------------

    private fun fail(reason: String): Int {
        status.value = HandsFreeStatus.Problem(reason)
        container.settings.handsFreeOn = false
        stopHandsFree(keepStatus = true)
        return START_NOT_STICKY
    }

    private fun stopHandsFree(keepStatus: Boolean = false) {
        controller?.cancel()
        pauseWake()
        wake?.release()
        wake = null
        overlay?.hide()
        overlay = null
        if (!keepStatus) status.value = HandsFreeStatus.Off
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        controller?.cancel()
        hideJob?.cancel()
        scope.cancel()
        pauseWake()
        wake?.release()
        wake = null
        overlay?.hide()
        container.voice.cancelListening()
        if (status.value !is HandsFreeStatus.Problem) status.value = HandsFreeStatus.Off
        super.onDestroy()
    }

    // ---- notification -----------------------------------------------------------------------------------------------

    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        true
    } catch (e: SecurityException) {
        fail("Android did not allow a microphone service (${e.javaClass.simpleName}). Allow the microphone and open the app first.")
        false
    } catch (e: IllegalStateException) {
        // Android refuses to start a microphone service while the app is in the background.
        fail("Android only lets hands-free start while the app is on screen. Open the app and turn it on there.")
        false
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Hands-free listening", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Shown while JARVIS 3.0 is listening for the word \"Jarvis\""
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, HandsFreeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("JARVIS 3.0 is listening")
            .setContentText("Say \"Jarvis\", then your request. Only the word \"Jarvis\" is detected until you say it.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "Stop listening", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.jarvis.ai.action.HANDS_FREE_STOP"

        /** Name of the Picovoice AccessKey in the encrypted key store. */
        const val ACCESS_KEY_NAME = "picovoice_access_key"

        private const val CHANNEL_ID = "hands_free"
        private const val NOTIFICATION_ID = 4102
        private const val SENSITIVITY = 0.65f
        private const val RESUME_DELAY_MS = 600L
        private const val STATUS_LINGER_MS = 1_500L
        private const val REPLY_MIN_MS = 3_000L
        private const val REPLY_PER_CHAR_MS = 45L
        private const val REPLY_MAX_MS = 14_000L

        private val status = MutableStateFlow<HandsFreeStatus>(HandsFreeStatus.Off)

        private val lastHeard = MutableStateFlow("")

        /** What the offline listener last made out (a few words), shown on the Hands-free page so the person can see whether it hears them. */
        val heardText: StateFlow<String> = lastHeard.asStateFlow()

        /** Shown on the Prefs > Hands-free page. */
        val state: StateFlow<HandsFreeStatus> = status.asStateFlow()

        val isRunning: Boolean get() = status.value is HandsFreeStatus.Waiting || status.value is HandsFreeStatus.Busy

        /**
         * Must be called while the app is on screen: Android refuses to start a microphone service from the background.
         * @return true if Android accepted the start; false if it was refused (the reason is in [state]), so the caller does not record "on".
         */
        fun start(context: Context): Boolean {
            // Checked HERE, before Android is asked to start a foreground service: a service that was started as foreground and then stops itself
            // without having entered the foreground (which is what happens when the microphone is not allowed) crashes the whole app.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                status.value = HandsFreeStatus.Problem("Allow the microphone first (Prefs > Access), then turn hands-free on.")
                return false
            }
            status.value = HandsFreeStatus.Waiting
            return try {
                ContextCompat.startForegroundService(context, Intent(context, HandsFreeService::class.java))
                true
            } catch (e: IllegalStateException) {
                // Android 12+ throws here when the app is not on screen (ForegroundServiceStartNotAllowedException).
                status.value = HandsFreeStatus.Problem("Android only lets hands-free start while the app is on screen. Open the app and turn it on there.")
                false
            } catch (e: SecurityException) {
                status.value = HandsFreeStatus.Problem("Android did not allow a microphone service (${e.javaClass.simpleName}). Allow the microphone first.")
                false
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, HandsFreeService::class.java).setAction(ACTION_STOP))
            } catch (e: IllegalStateException) {
                // The app is in the background and the service is not running, so there is nothing to stop.
                status.value = HandsFreeStatus.Off
            }
        }
    }
}

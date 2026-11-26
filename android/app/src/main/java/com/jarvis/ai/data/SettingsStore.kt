package com.jarvis.ai.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.jarvis.ai.ai.providers.ProviderDefaults
import com.jarvis.ai.ai.providers.ProviderId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Non-secret settings. Keys never go here; see [SecureKeyStore]. */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        dropRetiredDefaults()
    }

    /** Fallback order. Unknown or missing providers are repaired so every provider is always present exactly once. */
    var providerOrder: List<ProviderId>
        get() {
            val saved = prefs.getString(KEY_ORDER, null)
                ?.split(',')
                ?.mapNotNull { name -> ProviderId.entries.firstOrNull { it.name == name.trim() } }
                .orEmpty()
                .distinct()
            return saved + ProviderId.DEFAULT_ORDER.filter { it !in saved }
        }
        set(value) {
            prefs.edit { putString(KEY_ORDER, value.distinct().joinToString(",") { it.name }) }
        }

    /** Where answers come from: this phone, the cloud, or both. Manual is not offered, so a saved Manual falls back to the default. */
    var aiMode: com.jarvis.ai.ai.router.AiMode
        get() = com.jarvis.ai.ai.router.AiMode.entries.firstOrNull { it.name == prefs.getString(KEY_AI_MODE, null) && it != com.jarvis.ai.ai.router.AiMode.MANUAL }
            ?: com.jarvis.ai.ai.router.AiMode.LOCAL_FIRST
        set(value) {
            prefs.edit { putString(KEY_AI_MODE, value.name) }
        }

    fun model(id: ProviderId): String =
        prefs.getString(modelKey(id), null)?.takeIf { it.isNotBlank() } ?: ProviderDefaults.modelFor(id)

    fun setModel(id: ProviderId, model: String) {
        prefs.edit { if (model.isBlank()) remove(modelKey(id)) else putString(modelKey(id), model.trim()) }
    }

    /** The https address typed for the "own endpoint" provider. Only that provider reads it; it is not a secret, a token belongs in the key field. */
    fun endpoint(id: ProviderId): String = prefs.getString(endpointKey(id), null).orEmpty()

    fun setEndpoint(id: ProviderId, value: String) {
        prefs.edit { if (value.isBlank()) remove(endpointKey(id)) else putString(endpointKey(id), value.trim()) }
    }

    private val uiFlow = MutableStateFlow(readUi())

    /** Look and voice preferences. */
    val ui: StateFlow<UiPrefs> = uiFlow.asStateFlow()

    fun updateUi(change: (UiPrefs) -> UiPrefs) {
        val next = change(uiFlow.value).sanitised()
        prefs.edit {
            putBoolean(KEY_AMBIENT, next.ambient)
            putBoolean(KEY_REDUCE_MOTION, next.reduceMotion)
            putFloat(KEY_FLARE, next.flare)
            putBoolean(KEY_SPEAK, next.speakReplies)
            putFloat(KEY_RATE, next.speechRate)
            putBoolean(KEY_ON_DEVICE_ONLY, next.onDeviceSpeechOnly)
            putString(KEY_THEME, next.themeMode.name)
            putBoolean(KEY_DYNAMIC, next.dynamicColor)
            putBoolean(KEY_ELEVEN_ON, next.elevenEnabled)
            putBoolean(KEY_ANNOUNCE, next.announceNotifications)
            putBoolean(KEY_ANNOUNCE_TEXT, next.announceReadText)
            putBoolean(KEY_ANNOUNCE_LOCKED, next.announceLockedText)
            putBoolean(KEY_BARGE_IN, next.bargeIn)
            if (next.elevenVoiceId == null) remove(KEY_ELEVEN_VOICE) else putString(KEY_ELEVEN_VOICE, next.elevenVoiceId)
            if (next.elevenVoiceName == null) remove(KEY_ELEVEN_VOICE_NAME) else putString(KEY_ELEVEN_VOICE_NAME, next.elevenVoiceName)
            if (next.voiceName == null) remove(KEY_VOICE) else putString(KEY_VOICE, next.voiceName)
        }
        uiFlow.value = next
    }

    private fun readUi() = UiPrefs(
        ambient = prefs.getBoolean(KEY_AMBIENT, true),
        reduceMotion = prefs.getBoolean(KEY_REDUCE_MOTION, false),
        flare = prefs.getFloat(KEY_FLARE, 0.85f),
        speakReplies = prefs.getBoolean(KEY_SPEAK, true),
        speechRate = prefs.getFloat(KEY_RATE, 1.0f),
        voiceName = prefs.getString(KEY_VOICE, null),
        onDeviceSpeechOnly = prefs.getBoolean(KEY_ON_DEVICE_ONLY, false),
        themeMode = ThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY_THEME, null) } ?: ThemeMode.SYSTEM,
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC, false),
        elevenEnabled = prefs.getBoolean(KEY_ELEVEN_ON, false),
        elevenVoiceId = prefs.getString(KEY_ELEVEN_VOICE, null),
        elevenVoiceName = prefs.getString(KEY_ELEVEN_VOICE_NAME, null),
        announceNotifications = prefs.getBoolean(KEY_ANNOUNCE, false),
        announceReadText = prefs.getBoolean(KEY_ANNOUNCE_TEXT, true),
        announceLockedText = prefs.getBoolean(KEY_ANNOUNCE_LOCKED, false),
        bargeIn = prefs.getBoolean(KEY_BARGE_IN, true),
    ).sanitised()

    /**
     * When on, the notes saved in Memory are given to the AI that answers, so it can use them. They go to the provider along with the
     * request. When off, memory stays on the phone and is never sent.
     */
    var useMemoryInChat: Boolean
        get() = prefs.getBoolean(KEY_USE_MEMORY, true)
        set(value) {
            prefs.edit { putBoolean(KEY_USE_MEMORY, value) }
        }

    /**
     * Whether the person turned hands-free listening on. Android does not let a microphone service start by itself after a
     * restart, so this only says what they last chose; the service is started from the screen.
     */
    var handsFreeOn: Boolean
        get() = prefs.getBoolean(KEY_HANDS_FREE, false)
        set(value) {
            prefs.edit { putBoolean(KEY_HANDS_FREE, value) }
        }

    /**
     * Apps the person never wants read or controlled through the screen tools, on top of the built-in banking, payment and
     * password-app rules. Package names. Stored on this phone only.
     */
    var screenBlocklist: Set<String>
        get() = prefs.getStringSet(KEY_SCREEN_BLOCKLIST, emptySet())?.toSet().orEmpty()
        set(value) {
            prefs.edit { putStringSet(KEY_SCREEN_BLOCKLIST, value.toSet()) }
        }

    /**
     * Whether JARVIS may press buttons and type in other apps (accessibility "click" and "set text" on a control found by its
     * name). Off until the person switches it on. Banking, payment, password apps and JARVIS itself stay off limits either way.
     */
    var tapAndTypeOn: Boolean
        get() = prefs.getBoolean(KEY_TAP_TYPE, false)
        set(value) {
            prefs.edit { putBoolean(KEY_TAP_TYPE, value) }
        }

    /**
     * Whether JARVIS presses Send itself in WhatsApp after a WhatsApp message was confirmed (by a tap, or by the spoken read-back
     * and countdown). Off until the person switches it on. Send is only pressed if the box holds exactly the approved words.
     */
    var whatsappSendOn: Boolean
        get() = prefs.getBoolean(KEY_WA_SEND, false)
        set(value) {
            prefs.edit { putBoolean(KEY_WA_SEND, value) }
        }

    /**
     * Whether JARVIS may carry out a whole task inside other apps by looking at the screen and pressing controls, one step at a time. Off until
     * the person switches it on, because the text on the screen is sent to the AI that decides each step. It asks before anything critical
     * and never pays; banking, payment and password apps stay off limits either way.
     */
    var phoneOperatorOn: Boolean
        get() = prefs.getBoolean(KEY_PHONE_OPERATOR, false)
        set(value) {
            prefs.edit { putBoolean(KEY_PHONE_OPERATOR, value) }
        }

    /**
     * The nightly 10 pm review: looks for a newer release (at most every 10 days), turns repeated failures and setting problems into
     * suggestions, and notifies. It changes nothing by itself. Off until the person switches it on; switching it on starts counting from now,
     * so the first review is tonight at 10 pm, not an immediate one.
     */
    var nightlyOn: Boolean
        get() = prefs.getBoolean(KEY_NIGHTLY, false)
        set(value) {
            val wasOn = prefs.getBoolean(KEY_NIGHTLY, false)
            prefs.edit {
                putBoolean(KEY_NIGHTLY, value)
                if (value && !wasOn) putLong(KEY_NIGHTLY_LAST, System.currentTimeMillis())
            }
        }

    var nightlyLastRunMs: Long
        get() = prefs.getLong(KEY_NIGHTLY_LAST, 0L)
        set(value) {
            prefs.edit { putLong(KEY_NIGHTLY_LAST, value) }
        }

    /** When the release page was last read successfully, or null if never. */
    var updateLastCheckMs: Long?
        get() = prefs.getLong(KEY_UPDATE_CHECK, -1L).takeIf { it >= 0 }
        set(value) {
            prefs.edit { if (value == null) remove(KEY_UPDATE_CHECK) else putLong(KEY_UPDATE_CHECK, value) }
        }

    /** The newest release found, as stored by ReleaseStore. */
    var knownRelease: String?
        get() = prefs.getString(KEY_KNOWN_RELEASE, null)
        set(value) {
            prefs.edit { putString(KEY_KNOWN_RELEASE, value) }
        }

    /** The suggestions from the last review that the person has not answered yet, as stored by NightlyReview. */
    var nightlyProposals: String?
        get() = prefs.getString(KEY_PROPOSALS, null)
        set(value) {
            prefs.edit { putString(KEY_PROPOSALS, value) }
        }

    /** Suggestion ids the person answered "leave as it is" to; they are not suggested again. */
    var leftAsIs: Set<String>
        get() = prefs.getStringSet(KEY_LEFT_AS_IS, emptySet())?.toSet().orEmpty()
        set(value) {
            prefs.edit { putStringSet(KEY_LEFT_AS_IS, value.toSet()) }
        }

    /** When on, MEDIUM-risk actions (for example setting an alarm) also ask first. HIGH always asks. */
    var confirmMediumActions: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM_MEDIUM, false)
        set(value) {
            prefs.edit { putBoolean(KEY_CONFIRM_MEDIUM, value) }
        }

    /**
     * One-time clean-up: earlier versions pre-filled model names that providers have since
     * retired, and pressing Save stored them. Anything that still equals such a value is removed
     * so the current default applies. Values the user typed themselves are never touched.
     */
    private fun dropRetiredDefaults() {
        if (prefs.getBoolean(KEY_RETIRED_DROPPED, false)) return
        prefs.edit {
            for (id in ProviderId.entries) {
                val saved = prefs.getString(modelKey(id), null) ?: continue
                if (ProviderDefaults.isRetiredDefault(id, saved)) remove(modelKey(id))
            }
            putBoolean(KEY_RETIRED_DROPPED, true)
        }
    }

    /** The last time the app woke to do scheduled work (reminders and automations), or null. Used only to explain missed runs. */
    var lastWakeMs: Long?
        get() = prefs.getLong(KEY_LAST_WAKE, -1L).takeIf { it >= 0 }
        set(value) {
            if (value != null) prefs.edit { putLong(KEY_LAST_WAKE, value) }
        }

    /** Which detector listens for "Jarvis". Vosk needs no account; Porcupine needs the person's own Picovoice AccessKey. */
    var wakeEngine: WakeEngine
        get() = WakeEngine.entries.firstOrNull { it.name == prefs.getString(KEY_WAKE_ENGINE, null) } ?: WakeEngine.VOSK
        set(value) = prefs.edit { putString(KEY_WAKE_ENGINE, value.name) }

    /**
     * Names of the Bluetooth audio devices JARVIS reacts to. Empty means any Bluetooth audio device. Names only, kept on this phone.
     */
    var bluetoothDevices: Set<String>
        get() = prefs.getStringSet(KEY_BT_DEVICES, emptySet())?.toSet().orEmpty()
        set(value) {
            prefs.edit { putStringSet(KEY_BT_DEVICES, value.map { it.trim().take(60) }.filter { it.isNotEmpty() }.toSet()) }
        }

    /** The folder the person chose for auto-saving conversations (an Android document-tree address), or null when auto-save is off. */
    var archiveFolder: String?
        get() = prefs.getString(KEY_ARCHIVE, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit { if (value.isNullOrBlank()) remove(KEY_ARCHIVE) else putString(KEY_ARCHIVE, value) }
        }

    private fun modelKey(id: ProviderId) = "model_${id.name}"

    private fun endpointKey(id: ProviderId) = "endpoint_${id.name}"

    private companion object {
        const val PREFS_NAME = "jarvis_settings"
        const val KEY_ORDER = "provider_order"
        const val KEY_AI_MODE = "ai_mode"
        const val KEY_CONFIRM_MEDIUM = "confirm_medium_actions"
        const val KEY_USE_MEMORY = "use_memory_in_chat"
        const val KEY_HANDS_FREE = "hands_free_on"
        const val KEY_SCREEN_BLOCKLIST = "screen_blocklist"
        const val KEY_AMBIENT = "ui_ambient"
        const val KEY_REDUCE_MOTION = "ui_reduce_motion"
        const val KEY_FLARE = "ui_flare"
        const val KEY_SPEAK = "voice_speak_replies"
        const val KEY_RATE = "voice_rate"
        const val KEY_VOICE = "voice_name"
        const val KEY_WAKE_ENGINE = "wake_engine"
        const val KEY_ARCHIVE = "chat_archive_folder"
        const val KEY_LAST_WAKE = "schedule_last_wake_ms"
        const val KEY_ON_DEVICE_ONLY = "voice_on_device_only"
        const val KEY_ELEVEN_ON = "eleven_enabled"
        const val KEY_ELEVEN_VOICE = "eleven_voice_id"
        const val KEY_ELEVEN_VOICE_NAME = "eleven_voice_name"
        const val KEY_ANNOUNCE = "bt_announce_notifications"
        const val KEY_ANNOUNCE_TEXT = "bt_announce_read_text"
        const val KEY_ANNOUNCE_LOCKED = "bt_announce_locked_text"
        const val KEY_BARGE_IN = "voice_barge_in"
        const val KEY_BT_DEVICES = "bt_device_allowlist"
        const val KEY_TAP_TYPE = "screen_tap_and_type_on"
        const val KEY_WA_SEND = "whatsapp_press_send_on"
        const val KEY_PHONE_OPERATOR = "phone_operator_on"
        const val KEY_NIGHTLY = "nightly_review_on"
        const val KEY_NIGHTLY_LAST = "nightly_review_last_ms"
        const val KEY_UPDATE_CHECK = "update_last_check_ms"
        const val KEY_KNOWN_RELEASE = "update_known_release"
        const val KEY_PROPOSALS = "nightly_proposals"
        const val KEY_LEFT_AS_IS = "nightly_left_as_is"
        const val KEY_THEME = "ui_theme_mode"
        const val KEY_DYNAMIC = "ui_dynamic_color"
        const val KEY_RETIRED_DROPPED = "retired_model_defaults_dropped_v1"
    }
}

package com.jarvis.ai.data

import android.content.Context
import android.content.SharedPreferences
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
            prefs.edit().putString(KEY_ORDER, value.distinct().joinToString(",") { it.name }).apply()
        }

    fun model(id: ProviderId): String =
        prefs.getString(modelKey(id), null)?.takeIf { it.isNotBlank() } ?: ProviderDefaults.modelFor(id)

    fun setModel(id: ProviderId, model: String) {
        val editor = prefs.edit()
        if (model.isBlank()) editor.remove(modelKey(id)) else editor.putString(modelKey(id), model.trim())
        editor.apply()
    }

    private val uiFlow = MutableStateFlow(readUi())

    /** Look and voice preferences. */
    val ui: StateFlow<UiPrefs> = uiFlow.asStateFlow()

    fun updateUi(change: (UiPrefs) -> UiPrefs) {
        val next = change(uiFlow.value).sanitised()
        prefs.edit()
            .putBoolean(KEY_AMBIENT, next.ambient)
            .putBoolean(KEY_REDUCE_MOTION, next.reduceMotion)
            .putFloat(KEY_FLARE, next.flare)
            .putBoolean(KEY_SPEAK, next.speakReplies)
            .putFloat(KEY_RATE, next.speechRate)
            .apply { if (next.voiceName == null) remove(KEY_VOICE) else putString(KEY_VOICE, next.voiceName) }
            .apply()
        uiFlow.value = next
    }

    private fun readUi() = UiPrefs(
        ambient = prefs.getBoolean(KEY_AMBIENT, true),
        reduceMotion = prefs.getBoolean(KEY_REDUCE_MOTION, false),
        flare = prefs.getFloat(KEY_FLARE, 0.85f),
        speakReplies = prefs.getBoolean(KEY_SPEAK, true),
        speechRate = prefs.getFloat(KEY_RATE, 1.0f),
        voiceName = prefs.getString(KEY_VOICE, null),
    ).sanitised()

    /**
     * When on, the notes saved in Memory are given to the AI that answers, so it can use them. They go to the provider along with the
     * request. When off, memory stays on the phone and is never sent.
     */
    var useMemoryInChat: Boolean
        get() = prefs.getBoolean(KEY_USE_MEMORY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_USE_MEMORY, value).apply()
        }

    /** When on, MEDIUM-risk actions (for example setting an alarm) also ask first. HIGH always asks. */
    var confirmMediumActions: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM_MEDIUM, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CONFIRM_MEDIUM, value).apply()
        }

    /**
     * One-time clean-up: earlier versions pre-filled model names that providers have since
     * retired, and pressing Save stored them. Anything that still equals such a value is removed
     * so the current default applies. Values the user typed themselves are never touched.
     */
    private fun dropRetiredDefaults() {
        if (prefs.getBoolean(KEY_RETIRED_DROPPED, false)) return
        val editor = prefs.edit()
        for (id in ProviderId.entries) {
            val saved = prefs.getString(modelKey(id), null) ?: continue
            if (ProviderDefaults.isRetiredDefault(id, saved)) editor.remove(modelKey(id))
        }
        editor.putBoolean(KEY_RETIRED_DROPPED, true).apply()
    }

    private fun modelKey(id: ProviderId) = "model_${id.name}"

    private companion object {
        const val PREFS_NAME = "jarvis_settings"
        const val KEY_ORDER = "provider_order"
        const val KEY_CONFIRM_MEDIUM = "confirm_medium_actions"
        const val KEY_USE_MEMORY = "use_memory_in_chat"
        const val KEY_AMBIENT = "ui_ambient"
        const val KEY_REDUCE_MOTION = "ui_reduce_motion"
        const val KEY_FLARE = "ui_flare"
        const val KEY_SPEAK = "voice_speak_replies"
        const val KEY_RATE = "voice_rate"
        const val KEY_VOICE = "voice_name"
        const val KEY_RETIRED_DROPPED = "retired_model_defaults_dropped_v1"
    }
}

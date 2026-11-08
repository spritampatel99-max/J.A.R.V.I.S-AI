package com.jarvis.ai.data

import android.content.Context
import android.content.SharedPreferences
import com.jarvis.ai.ai.providers.ProviderDefaults
import com.jarvis.ai.ai.providers.ProviderId

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
        const val KEY_RETIRED_DROPPED = "retired_model_defaults_dropped_v1"
    }
}

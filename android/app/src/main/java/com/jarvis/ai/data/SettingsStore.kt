package com.jarvis.ai.data

import android.content.Context
import android.content.SharedPreferences
import com.jarvis.ai.ai.providers.ProviderDefaults
import com.jarvis.ai.ai.providers.ProviderId

/** Non-secret settings. Keys never go here; see [SecureKeyStore]. */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    private fun modelKey(id: ProviderId) = "model_${id.name}"

    private companion object {
        const val PREFS_NAME = "jarvis_settings"
        const val KEY_ORDER = "provider_order"
        const val KEY_CONFIRM_MEDIUM = "confirm_medium_actions"
    }
}

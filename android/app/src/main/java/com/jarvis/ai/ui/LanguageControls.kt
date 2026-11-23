package com.jarvis.ai.ui

import android.app.GrammaticalInflectionManager
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** What the person asked for in "how should I address you"; stored by Android per app (API 34), not by this app. */
enum class Addressing(val label: String, val gender: Int, val promptValue: String?) {
    NOT_SET("No preference", Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED, null),
    FEMININE("Feminine", Configuration.GRAMMATICAL_GENDER_FEMININE, "feminine"),
    MASCULINE("Masculine", Configuration.GRAMMATICAL_GENDER_MASCULINE, "masculine"),
    NEUTRAL("Neutral", Configuration.GRAMMATICAL_GENDER_NEUTRAL, null),
    ;

    companion object {
        /** The current choice; "No preference" when Android reports anything else. */
        fun current(context: Context): Addressing {
            val manager = context.getSystemService(GrammaticalInflectionManager::class.java) ?: return NOT_SET
            val gender = manager.applicationGrammaticalGender
            return entries.firstOrNull { it.gender == gender } ?: NOT_SET
        }

        /** Read by the planner for each request; only "feminine" and "masculine" ever reach the prompt. */
        fun promptValue(context: Context): String? = current(context).promptValue
    }
}

private val LANGUAGES = listOf("" to "Follow phone", "en" to "English", "hi" to "हिन्दी")

/**
 * The app's language and how it addresses the person, using Android's own per-app settings (Android 13 and 14 APIs). Changing the
 * language restarts the screen; it also changes the language the speech recogniser listens for. Most on-screen text is still English:
 * only the parts moved into Android string resources (the app name and the accessibility description so far) are translated.
 */
@Composable
fun LanguageControls() {
    val context = LocalContext.current
    val locales = remember { context.getSystemService(LocaleManager::class.java) }
    var language by remember { mutableStateOf(locales?.applicationLocales?.takeIf { !it.isEmpty }?.get(0)?.language.orEmpty()) }
    var addressing by remember { mutableStateOf(Addressing.current(context)) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Language", style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
        ChoiceChips(
            options = LANGUAGES.map { it.first },
            selected = language,
            label = { code -> LANGUAGES.first { it.first == code }.second },
            onSelect = { code ->
                language = code
                locales?.applicationLocales = if (code.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(code)
            },
        )
        Text("How should I address you in Hindi?", style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
        ChoiceChips(
            options = Addressing.entries.toList(),
            selected = addressing,
            label = { it.label },
            onSelect = { choice ->
                addressing = choice
                context.getSystemService(GrammaticalInflectionManager::class.java)?.setRequestedApplicationGrammaticalGender(choice.gender)
            },
        )
        Text(
            "The language also sets what the microphone listens for. Most screens are still in English; the rest is being moved over.",
            style = MaterialTheme.typography.bodySmall,
            color = JarvisColors.Muted,
        )
    }
}

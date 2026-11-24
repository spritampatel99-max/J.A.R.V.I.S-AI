package com.jarvis.ai.ai.tools.builtin

import com.jarvis.ai.ai.tools.ContactChoice
import com.jarvis.ai.ai.tools.ContactResolver
import com.jarvis.ai.ai.tools.ToolError

/** One contact and one number, chosen without guessing, or the reason there is none. Shared by the call and message tools. */
internal sealed interface ContactLookup {
    data class Found(val name: String, val number: String, val label: String) : ContactLookup

    data class Stop(val error: ToolError) : ContactLookup

    companion object {
        /**
         * Looks [query] up in the contacts. Several people or several numbers become a question, never a pick; a missing
         * Contacts permission is reported as that, never as "no such person".
         */
        suspend fun resolve(contacts: ContactsPort, query: String): ContactLookup {
            var found = search(contacts, query) ?: return Stop(ToolError.Failed("Contacts could not be searched."))
            var label: String? = null
            if (found is ContactSearch.Found && found.contacts.isEmpty()) {
                // "mom mobile": the last word may name the number type rather than be part of the name.
                val (namePart, labelPart) = ContactResolver.splitTrailingLabel(query)
                if (labelPart != null) {
                    found = search(contacts, namePart) ?: return Stop(ToolError.Failed("Contacts could not be searched."))
                    label = labelPart
                }
            }
            if (found is ContactSearch.Found && found.contacts.isEmpty()) {
                similarNames(contacts, query)?.let { return Stop(ToolError.NeedsInput(it)) }
            }
            return when (found) {
                ContactSearch.PermissionMissing -> Stop(
                    ToolError.PermissionDenied(setOf(CallPermissions.READ_CONTACTS), "Contacts access is off. Allow it in the Access tab and ask again."),
                )
                is ContactSearch.Failed -> Stop(ToolError.Failed(found.reason))
                is ContactSearch.Found -> {
                    val queryForMatching = if (label != null) ContactResolver.splitTrailingLabel(query).first else query
                    when (val choice = ContactResolver.choose(queryForMatching, found.contacts, label)) {
                        is ContactChoice.Chosen -> Found(choice.name, choice.number, choice.label)
                        is ContactChoice.Ask -> Stop(ToolError.NeedsInput(choice.question))
                        is ContactChoice.NoMatch -> Stop(ToolError.Unavailable(choice.message))
                    }
                }
            }
        }

        /**
         * "Rohit Kumar" finds no contact, but "Rohit" is saved. Each word of the name is searched on its own and whatever turns up is offered
         * as a question. It is never chosen for the person: a first name alone could be the wrong Rohit.
         */
        private suspend fun similarNames(contacts: ContactsPort, query: String): String? {
            val words = query.trim().split(Regex("""\s+""")).map { it.filter { c -> c.isLetterOrDigit() } }.filter { it.length >= MIN_WORD }.distinct().take(MAX_WORDS)
            if (words.size < 2) return null
            val names = LinkedHashMap<String, String>()
            for (word in words) {
                val result = search(contacts, word) as? ContactSearch.Found ?: continue
                result.contacts.forEach { names.putIfAbsent(it.id, it.name.trim().take(40)) }
            }
            val list = names.values.filter { it.isNotEmpty() }.distinct()
            if (list.isEmpty()) return null
            val shown = list.take(MAX_SUGGESTIONS).joinToString(", ") + if (list.size > MAX_SUGGESTIONS) " and more" else ""
            return "I could not find a contact called \"${query.trim().take(60)}\". Did you mean: $shown? Say the full name of the one you mean."
        }

        private const val MIN_WORD = 3
        private const val MAX_WORDS = 3
        private const val MAX_SUGGESTIONS = 5

        private suspend fun search(contacts: ContactsPort, query: String): ContactSearch? =
            try {
                contacts.search(query)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
    }
}

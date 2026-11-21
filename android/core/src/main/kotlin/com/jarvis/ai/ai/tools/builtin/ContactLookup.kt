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

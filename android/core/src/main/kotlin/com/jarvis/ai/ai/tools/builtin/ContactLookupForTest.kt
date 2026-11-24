package com.jarvis.ai.ai.tools.builtin

import com.jarvis.ai.ai.tools.ToolError

/** A tiny window onto [ContactLookup] (which is internal) so tests can see what it decides. Not used by the app. */
object ContactLookupForTest {
    suspend fun resolve(contacts: ContactsPort, query: String): String = when (val r = ContactLookup.resolve(contacts, query)) {
        is ContactLookup.Found -> "FOUND:${r.name}"
        is ContactLookup.Stop -> (if (r.error is ToolError.NeedsInput) "ASK:" else "STOP:") + r.error.message
    }
}

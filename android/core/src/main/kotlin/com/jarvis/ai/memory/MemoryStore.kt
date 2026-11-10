package com.jarvis.ai.memory

import com.jarvis.ai.core.common.IdGenerator
import com.jarvis.ai.core.common.TextStorage
import com.jarvis.ai.core.common.TimeSource
import com.jarvis.ai.core.common.bool
import com.jarvis.ai.core.common.long
import com.jarvis.ai.core.common.objs
import com.jarvis.ai.core.common.parseJson
import com.jarvis.ai.core.common.str
import com.jarvis.ai.core.security.SecretRedactor
import com.jarvis.ai.core.security.UntrustedContent
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class MemoryCategory(val label: String) {
    PREFERENCE("Preference"),
    FACT("Fact about me"),
    PROJECT("Ongoing project"),
    NOTE("Note"),
    CONVERSATION("Conversation reference"),
    ROUTINE("Approved routine"),
}

/**
 * One thing the person chose to keep. [agentId] is null for global memory; otherwise the entry belongs to that one agent
 * and is never shown to another agent or to the main assistant.
 */
data class MemoryEntry(
    val id: String,
    val agentId: String?,
    val category: MemoryCategory,
    val text: String,
    val createdMs: Long,
    val updatedMs: Long,
)

/** Something the app noticed that could be remembered. It is NOT stored: only the person approving it saves it. */
data class MemorySuggestion(val id: String, val agentId: String?, val category: MemoryCategory, val text: String)

/** Which entries a request may touch. */
sealed interface MemoryScope {
    /** Global memory only. */
    data object Global : MemoryScope

    /** One agent's own memory only. */
    data class Agent(val agentId: String) : MemoryScope

    /** Everything (for the review screen only). */
    data object Everything : MemoryScope
}

/**
 * The person's local memory. Rules that the tests pin down:
 *  - Nothing is saved except by [save] (an explicit act) or by approving a [MemorySuggestion]. Suggestions are kept in
 *    memory only, are never written to storage, and can be switched off entirely.
 *  - Text is redacted of API-key-shaped strings first, and a note that is nothing but a secret is refused.
 *  - Global memory and each agent's memory are separate; [forModel] never returns another agent's entries.
 *  - It can be searched, edited, deleted per entry or per scope, and cleared completely.
 */
class MemoryStore(
    private val storage: TextStorage,
    private val time: TimeSource,
    private val redact: (String) -> String = SecretRedactor::redactPatterns,
) {
    private val ids = IdGenerator(time, "mem")
    private val lock = Any()

    private val entriesFlow = MutableStateFlow(emptyList<MemoryEntry>())
    val entries: StateFlow<List<MemoryEntry>> = entriesFlow.asStateFlow()

    private val suggestionsFlow = MutableStateFlow(emptyList<MemorySuggestion>())
    val suggestions: StateFlow<List<MemorySuggestion>> = suggestionsFlow.asStateFlow()

    /** When false, [suggest] does nothing. */
    @Volatile
    var suggestionsEnabled: Boolean = true
        private set

    /** True when the last save to storage failed. The memory still works in this session. */
    @Volatile
    var saveFailed: Boolean = false
        private set

    init {
        load()
    }

    // ---- saving, editing, deleting ------------------------------------------------------------

    /** Saves something the person asked to keep. Returns null when the text is empty or only a secret. */
    fun save(agentId: String?, category: MemoryCategory, text: String): MemoryEntry? = synchronized(lock) {
        val clean = clean(text) ?: return null
        val now = time.nowMs()
        val entry = MemoryEntry(ids.next(), agentId, category, clean, now, now)
        commit((entriesFlow.value + entry).takeLast(MAX_ENTRIES))
        entry
    }

    fun edit(id: String, text: String): MemoryEntry? = synchronized(lock) {
        val clean = clean(text) ?: return null
        val old = entriesFlow.value.firstOrNull { it.id == id } ?: return null
        val updated = old.copy(text = clean, updatedMs = time.nowMs())
        commit(entriesFlow.value.map { if (it.id == id) updated else it })
        updated
    }

    fun delete(id: String) = synchronized(lock) {
        commit(entriesFlow.value.filterNot { it.id == id })
    }

    /** Removes every entry in [scope]. [MemoryScope.Everything] clears all memory. */
    fun clear(scope: MemoryScope) = synchronized(lock) {
        commit(entriesFlow.value.filterNot { matches(it, scope) })
    }

    // ---- suggestions: never saved until approved -----------------------------------------------

    fun suggest(agentId: String?, category: MemoryCategory, text: String): MemorySuggestion? = synchronized(lock) {
        if (!suggestionsEnabled) return null
        val clean = clean(text) ?: return null
        if (suggestionsFlow.value.any { it.text == clean && it.agentId == agentId }) return null
        val suggestion = MemorySuggestion(ids.next(), agentId, category, clean)
        suggestionsFlow.value = (suggestionsFlow.value + suggestion).takeLast(MAX_SUGGESTIONS)
        suggestion
    }

    /** Saves a suggestion, optionally with the person's own wording. */
    fun approve(suggestionId: String, editedText: String? = null): MemoryEntry? = synchronized(lock) {
        val suggestion = suggestionsFlow.value.firstOrNull { it.id == suggestionId } ?: return null
        suggestionsFlow.value = suggestionsFlow.value.filterNot { it.id == suggestionId }
        save(suggestion.agentId, suggestion.category, editedText ?: suggestion.text)
    }

    fun dismiss(suggestionId: String) = synchronized(lock) {
        suggestionsFlow.value = suggestionsFlow.value.filterNot { it.id == suggestionId }
    }

    fun setSuggestionsEnabled(enabled: Boolean) = synchronized(lock) {
        suggestionsEnabled = enabled
        if (!enabled) suggestionsFlow.value = emptyList()
        commit(entriesFlow.value)
    }

    // ---- reading ---------------------------------------------------------------------------------

    fun search(query: String, scope: MemoryScope = MemoryScope.Everything): List<MemoryEntry> {
        val needle = query.trim()
        return entriesFlow.value
            .filter { matches(it, scope) }
            .filter { needle.isEmpty() || it.text.contains(needle, ignoreCase = true) || it.category.label.contains(needle, ignoreCase = true) }
            .sortedByDescending { it.updatedMs }
    }

    /**
     * The memory a model may see for one request, as a block of data. [agentId] null is the main assistant: global memory
     * only. An agent gets its own memory and, only when [includeGlobal] is true, the global memory. Never another agent's.
     * The block is marked as untrusted data because a note may contain text that was copied from the web.
     */
    fun forModel(agentId: String?, includeGlobal: Boolean = true, maxChars: Int = 1_500): String {
        val visible = entriesFlow.value.filter { it.agentId == null && (agentId == null || includeGlobal) || (agentId != null && it.agentId == agentId) }
        if (visible.isEmpty()) return ""
        val body = StringBuilder()
        for (entry in visible.sortedByDescending { it.updatedMs }) {
            val line = "- [${entry.category.label}] ${entry.text.replace('\n', ' ')}\n"
            if (body.length + line.length > maxChars) break
            body.append(line)
        }
        if (body.isEmpty()) return ""
        return UntrustedContent.wrap("user-approved memory", body.toString().trimEnd())
    }

    // ---- storage -----------------------------------------------------------------------------------

    private fun matches(entry: MemoryEntry, scope: MemoryScope): Boolean = when (scope) {
        MemoryScope.Global -> entry.agentId == null
        is MemoryScope.Agent -> entry.agentId == scope.agentId
        MemoryScope.Everything -> true
    }

    private fun clean(text: String): String? {
        val redacted = redact(text.trim()).take(MAX_TEXT).trim()
        if (redacted.isEmpty()) return null
        // Nothing left but redaction markers means the whole note was a secret.
        if (redacted.replace(SecretRedactor.REPLACEMENT, "").isBlank()) return null
        return redacted
    }

    private fun commit(next: List<MemoryEntry>) {
        entriesFlow.value = next
        saveFailed = try {
            storage.write(
                buildJsonObject {
                    put("suggestionsEnabled", suggestionsEnabled)
                    put("entries", buildJsonArray {
                        for (e in next) add(buildJsonObject {
                            put("id", e.id)
                            e.agentId?.let { put("agentId", it) }
                            put("category", e.category.name)
                            put("text", e.text)
                            put("createdMs", e.createdMs)
                            put("updatedMs", e.updatedMs)
                        })
                    })
                }.toString(),
            )
            false
        } catch (e: IOException) {
            true
        }
    }

    private fun load() {
        val root = parseJson(storage.read()) as? JsonObject ?: return
        suggestionsEnabled = root.bool("suggestionsEnabled") ?: true
        entriesFlow.value = root.objs("entries").mapNotNull { o ->
            val category = MemoryCategory.entries.firstOrNull { it.name == o.str("category") } ?: return@mapNotNull null
            MemoryEntry(
                id = o.str("id") ?: return@mapNotNull null,
                agentId = o.str("agentId"),
                category = category,
                text = o.str("text") ?: return@mapNotNull null,
                createdMs = o.long("createdMs") ?: 0L,
                updatedMs = o.long("updatedMs") ?: 0L,
            )
        }
    }

    companion object {
        const val MAX_ENTRIES = 500
        const val MAX_SUGGESTIONS = 20
        const val MAX_TEXT = 1_000
    }
}

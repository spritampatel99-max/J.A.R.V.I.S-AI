package com.jarvis.ai.history

import com.jarvis.ai.core.common.TimeSource
import com.jarvis.ai.core.security.SecretRedactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

data class StoredMessage(
    /** "user", "assistant" or "system". */
    val sender: String,
    val text: String,
    val atMs: Long,
    /** True when the person spoke this request instead of typing it. */
    val viaVoice: Boolean = false,
    /** Who produced an assistant reply, for example a provider name, an on-device model, or "Built-in command". */
    val answeredBy: String? = null,
)

data class Conversation(
    val id: String,
    val startedMs: Long,
    val updatedMs: Long,
    val title: String,
    val messages: List<StoredMessage>,
    /** How many tool runs (a call, an alarm, the flashlight) this conversation caused. */
    val toolRuns: Int = 0,
) {
    val exchanges: Int get() = messages.count { it.sender == "user" }
    val usedVoice: Boolean get() = messages.any { it.viaVoice }
    val lastReply: String? get() = messages.lastOrNull { it.sender == "assistant" }?.text
}

/** Where the history is kept. The app backs this with a private file; tests use memory. */
interface HistoryStorage {
    fun read(): String?
    fun write(text: String)
}

/** Which conversations to show. */
enum class HistoryFilter { ALL, VOICE, ACTIONS }

/**
 * The conversation history kept on the phone, which the Logs tab shows and the person can delete at any time.
 *
 * Text is passed through the secret redactor before it is stored, so an API key pasted into a message is not kept.
 * The history is capped (conversations, messages per conversation, text length) so it cannot grow without limit.
 */
class ConversationStore(
    private val storage: HistoryStorage,
    private val time: TimeSource,
    private val redact: (String) -> String = { SecretRedactor.redactPatterns(it) },
) {
    private val lock = Any()
    private val state = MutableStateFlow(load())

    /** Newest first. */
    val conversations: StateFlow<List<Conversation>> = state.asStateFlow()

    /** Adds a message, starting a new conversation when [conversationId] is null or unknown. Returns the conversation's id. */
    fun append(conversationId: String?, sender: String, text: String, viaVoice: Boolean = false, answeredBy: String? = null): String =
        synchronized(lock) {
            val now = time.nowMs()
            val message = StoredMessage(sender, redact(text).take(MAX_TEXT), now, viaVoice, answeredBy?.take(80))
            val current = state.value
            val existing = current.firstOrNull { it.id == conversationId }
            val updated = if (existing == null) {
                Conversation(
                    id = freshId(now, current),
                    startedMs = now,
                    updatedMs = now,
                    title = titleFrom(message.text),
                    messages = listOf(message),
                )
            } else {
                existing.copy(updatedMs = now, messages = (existing.messages + message).takeLast(MAX_MESSAGES))
            }
            commit((listOf(updated) + current.filter { it.id != updated.id }).take(MAX_CONVERSATIONS))
            updated.id
        }

    fun recordToolRun(conversationId: String) = synchronized(lock) {
        val current = state.value
        val existing = current.firstOrNull { it.id == conversationId } ?: return@synchronized
        commit(current.map { if (it.id == conversationId) existing.copy(toolRuns = existing.toolRuns + 1) else it })
    }

    fun delete(conversationId: String) = synchronized(lock) {
        commit(state.value.filter { it.id != conversationId })
    }

    fun clear() = synchronized(lock) {
        commit(emptyList())
    }

    /** Conversations matching [filter] and containing [query] (case-insensitive) in the title or any message. */
    fun find(query: String, filter: HistoryFilter = HistoryFilter.ALL): List<Conversation> {
        val needle = query.trim().lowercase()
        return state.value.filter { c ->
            val inFilter = when (filter) {
                HistoryFilter.ALL -> true
                HistoryFilter.VOICE -> c.usedVoice
                HistoryFilter.ACTIONS -> c.toolRuns > 0
            }
            inFilter && (needle.isEmpty() || c.title.lowercase().contains(needle) || c.messages.any { it.text.lowercase().contains(needle) })
        }
    }

    /** Size of what is stored, in bytes, as written to storage. */
    fun sizeBytes(): Long = encode(state.value).toByteArray().size.toLong()

    /** True when the last attempt to save to storage failed; the history is then only in memory until the next successful save. */
    @Volatile
    var saveFailed: Boolean = false
        private set

    private fun commit(next: List<Conversation>) {
        state.value = next
        saveFailed = try {
            storage.write(encode(next))
            false
        } catch (e: java.io.IOException) {
            true
        }
    }

    private fun load(): List<Conversation> {
        val raw = storage.read() ?: return emptyList()
        return try {
            (Json.parseToJsonElement(raw) as? JsonArray).orEmpty().mapNotNull(::conversationFrom)
        } catch (e: kotlinx.serialization.SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    // The JSON is written and read by hand (like the rest of :core), so no serialization compiler plugin is needed.
    // Anything in a stored entry that cannot be read drops just that entry, never the whole history.
    private fun encode(list: List<Conversation>): String = buildJsonArray {
        for (c in list) add(buildJsonObject {
            put("id", c.id)
            put("startedMs", c.startedMs)
            put("updatedMs", c.updatedMs)
            put("title", c.title)
            put("toolRuns", c.toolRuns)
            put("messages", buildJsonArray {
                for (m in c.messages) add(buildJsonObject {
                    put("sender", m.sender)
                    put("text", m.text)
                    put("atMs", m.atMs)
                    put("viaVoice", m.viaVoice)
                    m.answeredBy?.let { put("answeredBy", it) }
                })
            })
        })
    }.toString()

    private fun conversationFrom(element: JsonElement): Conversation? {
        val o = element as? JsonObject ?: return null
        return Conversation(
            id = o.str("id") ?: return null,
            startedMs = o.num("startedMs") ?: 0L,
            updatedMs = o.num("updatedMs") ?: 0L,
            title = o.str("title").orEmpty(),
            messages = (o["messages"] as? JsonArray).orEmpty().mapNotNull(::messageFrom),
            toolRuns = (o.num("toolRuns") ?: 0L).toInt(),
        )
    }

    private fun messageFrom(element: JsonElement): StoredMessage? {
        val o = element as? JsonObject ?: return null
        return StoredMessage(
            sender = o.str("sender") ?: return null,
            text = o.str("text") ?: return null,
            atMs = o.num("atMs") ?: 0L,
            viaVoice = (o["viaVoice"] as? JsonPrimitive)?.contentOrNull == "true",
            answeredBy = o.str("answeredBy"),
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.num(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun freshId(now: Long, existing: List<Conversation>): String {
        var n = now
        while (existing.any { it.id == "c$n" }) n++
        return "c$n"
    }

    private fun titleFrom(text: String): String {
        val line = text.trim().lineSequence().firstOrNull().orEmpty().trim()
        return if (line.length <= MAX_TITLE) line else line.take(MAX_TITLE - 1).trimEnd() + "…"
    }

    companion object {
        const val MAX_CONVERSATIONS = 200
        const val MAX_MESSAGES = 200
        const val MAX_TEXT = 4_000
        const val MAX_TITLE = 60
    }
}

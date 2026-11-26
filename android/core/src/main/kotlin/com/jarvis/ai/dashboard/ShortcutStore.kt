package com.jarvis.ai.dashboard

import com.jarvis.ai.ai.executor.ExecutionReport
import com.jarvis.ai.automation.RetrySave
import com.jarvis.ai.core.common.TextStorage
import com.jarvis.ai.core.common.TimeSource
import com.jarvis.ai.core.common.bool
import com.jarvis.ai.core.common.long
import com.jarvis.ai.core.common.parseJson
import com.jarvis.ai.core.common.str
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One thing the person asks for often, in their own words. */
data class Shortcut(val text: String, val count: Int, val lastUsedMs: Long)

/** What may be counted, and what must never be. */
object ShortcutRules {
    const val MIN_LENGTH = 3
    const val MAX_LENGTH = 80
    const val MAX_ITEMS = 40

    /** How many shortcuts the home screen shows. */
    const val SHOWN = 6

    /** A request must be made this many times before it becomes a shortcut, so a one-off never does. */
    const val MIN_COUNT = 2

    /** A request not made for this long stops being offered. */
    const val STALE_MS = 60L * 24 * 60 * 60 * 1000

    private val SENSITIVE = Regex(
        """(?:\d[ \-]?){5,}|@|://|www\.|\b(?:password|passcode|passphrase|pin|otp|cvv|cvc|upi|ifsc|aadhaar|aadhar|pan|secret|token|api ?key|credential|credentials|login|log in|sign in|bank|card number|account number)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The text to count, or null if it must not be kept: too short or long, or something that looks like a number, address, link or secret.
     * Looking sensitive is enough to leave it out; a missed shortcut costs nothing, a kept secret would.
     */
    fun normalize(raw: String): String? {
        val text = raw.trim().replace(Regex("""\s+"""), " ").trimEnd('.', '!', '?', ',', ';', ' ')
        if (text.length !in MIN_LENGTH..MAX_LENGTH) return null
        if (SENSITIVE.containsMatchIn(text)) return null
        return text
    }

    fun key(text: String): String = text.lowercase(Locale.ROOT)

    /**
     * Only a request that really worked counts: every task ended well, and nothing in the answer was private (messages, the screen, notifications).
     * A request that failed, was refused or turned down is never a shortcut.
     */
    fun countable(report: ExecutionReport): Boolean = report.allSucceeded && !report.containsPrivate
}

/**
 * Counts what the person asks for, on this phone only, so the home screen can offer their most common requests as shortcuts. It is a small
 * private file. Nothing is sent anywhere, and it can be switched off and cleared at any time. A shortcut only runs the same words through the
 * same pipeline as typing them, so every permission check and confirmation still applies.
 */
class ShortcutStore(private val storage: TextStorage, private val time: TimeSource) : RetrySave {
    private val lock = Any()

    @Volatile
    var loadProblem: String? = null
        private set

    @Volatile
    override var saveFailed: Boolean = false
        private set

    private data class State(val learning: Boolean, val items: List<Shortcut>)

    private val flow = MutableStateFlow(load())
    private val learningFlow = MutableStateFlow(flow.value.learning)
    private val itemsFlow = MutableStateFlow(flow.value.items.sortedByDescending { it.lastUsedMs })

    /** Whether requests are being counted. On unless the person turned it off. */
    val learning: StateFlow<Boolean> = learningFlow.asStateFlow()

    /** Everything counted, most recent first. Used by the screen that shows what is kept. */
    val all: StateFlow<List<Shortcut>> = itemsFlow.asStateFlow()

    /** The shortcuts to offer now: asked at least [ShortcutRules.MIN_COUNT] times and not stale, most asked first. */
    fun top(limit: Int = ShortcutRules.SHOWN): List<Shortcut> = synchronized(lock) {
        val now = time.nowMs()
        flow.value.items
            .filter { it.count >= ShortcutRules.MIN_COUNT && now - it.lastUsedMs <= ShortcutRules.STALE_MS }
            .sortedWith(compareByDescending<Shortcut> { it.count }.thenByDescending { it.lastUsedMs }.thenBy { it.text })
            .take(limit)
    }

    /** Counts a request that has just finished. Returns true if it was counted. */
    fun record(requestText: String, report: ExecutionReport): Boolean = synchronized(lock) {
        if (!flow.value.learning) return false
        if (!ShortcutRules.countable(report)) return false
        val text = ShortcutRules.normalize(requestText) ?: return false
        val key = ShortcutRules.key(text)
        val now = time.nowMs()
        val items = flow.value.items
        val existing = items.firstOrNull { ShortcutRules.key(it.text) == key }
        val next = if (existing != null) {
            items.map { if (it === existing) it.copy(count = it.count + 1, lastUsedMs = now) else it }
        } else {
            val added = items + Shortcut(text, 1, now)
            if (added.size <= ShortcutRules.MAX_ITEMS) added else added.sortedWith(compareByDescending<Shortcut> { it.count }.thenByDescending { it.lastUsedMs }).take(ShortcutRules.MAX_ITEMS)
        }
        commit(State(true, next))
        true
    }

    fun setLearning(on: Boolean) = synchronized(lock) { commit(flow.value.copy(learning = on)) }

    fun remove(text: String) = synchronized(lock) {
        val key = ShortcutRules.key(text)
        commit(flow.value.copy(items = flow.value.items.filterNot { ShortcutRules.key(it.text) == key }))
    }

    /** Forgets everything that was counted. The on/off choice is kept. */
    fun clear() = synchronized(lock) { commit(flow.value.copy(items = emptyList())) }

    override fun retrySave(): Boolean = synchronized(lock) {
        commit(flow.value)
        !saveFailed
    }

    private fun commit(next: State) {
        flow.value = next
        learningFlow.value = next.learning
        itemsFlow.value = next.items.sortedByDescending { it.lastUsedMs }
        saveFailed = try {
            storage.write(encode(next))
            false
        } catch (e: Exception) {
            true
        }
    }

    private fun load(): State {
        val raw = storage.read()
        if (raw.isNullOrBlank()) return State(true, emptyList())
        val root = parseJson(raw) as? JsonObject
        if (root == null) {
            storage.backupCorrupt(raw)
            loadProblem = "The saved shortcuts could not be read, so counting starts again. A copy of the file was kept."
            return State(true, emptyList())
        }
        val array = root["items"] as? JsonArray ?: JsonArray(emptyList())
        val items = array.mapNotNull { (it as? JsonObject)?.let(::shortcutFrom) }
        return State(root.bool("learning") ?: true, items.take(ShortcutRules.MAX_ITEMS))
    }

    private fun shortcutFrom(o: JsonObject): Shortcut? {
        // Re-checked on load, so a hand-edited or old file can never put a sensitive-looking line on the home screen.
        val text = ShortcutRules.normalize(o.str("text") ?: return null) ?: return null
        val count = o.long("count")?.toInt()?.coerceIn(1, 1_000_000) ?: return null
        return Shortcut(text, count, o.long("last") ?: return null)
    }

    private fun encode(s: State): String = buildJsonObject {
        put("learning", s.learning)
        put("items", buildJsonArray {
            for (i in s.items) add(buildJsonObject {
                put("text", i.text)
                put("count", i.count)
                put("last", i.lastUsedMs)
            })
        })
    }.toString()
}

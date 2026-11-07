package com.jarvis.ai.core.security

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class AuditKind {
    CONFIRMATION_REQUESTED,
    CONFIRMATION_APPROVED,
    CONFIRMATION_DENIED,
    CONFIRMATION_EXPIRED,

    /** The arguments changed between approval and execution; the action was not run. */
    CONFIRMATION_MISMATCH,
    EXECUTED,
    FAILED,

    /** Refused before running (for example, a tool name the registry does not know). */
    BLOCKED,
}

data class AuditEvent(
    val timestampMs: Long,
    val kind: AuditKind,
    val tool: String,
    val risk: Risk,
    val summary: String,
    val detail: String? = null,
)

/** Record of sensitive operations and confirmation decisions. */
interface AuditLog {
    fun record(event: AuditEvent)

    /** Newest first. */
    fun recent(limit: Int = 100): List<AuditEvent>
}

class InMemoryAuditLog(private val maxEntries: Int = 500) : AuditLog {
    private val lock = Any()
    private val entries = ArrayDeque<AuditEvent>()

    override fun record(event: AuditEvent) {
        synchronized(lock) {
            entries.addLast(event)
            while (entries.size > maxEntries) entries.removeFirst()
        }
    }

    override fun recent(limit: Int): List<AuditEvent> =
        synchronized(lock) { entries.toList().asReversed().take(limit) }
}

/**
 * Append-only JSON-lines audit log, capped at [maxEntries]. Text is passed through
 * [redactor] before it is written so a key echoed in an error message cannot reach disk.
 *
 * Write failures do not crash the caller; they are exposed via [lastWriteError] so the
 * diagnostics screen can show them, and the entry stays available in memory.
 */
class FileAuditLog(
    private val file: File,
    private val maxEntries: Int = 1_000,
    private val redactor: SecretRedactor = SecretRedactor(),
) : AuditLog {

    private val lock = Any()
    private val entries = ArrayDeque<AuditEvent>()

    @Volatile
    var lastWriteError: String? = null
        private set

    @Volatile
    var corruptLinesSkipped: Int = 0
        private set

    init {
        loadExisting()
    }

    override fun record(event: AuditEvent) {
        val safe = event.copy(
            summary = redactor.redact(event.summary),
            detail = event.detail?.let { redactor.redact(it) },
        )
        synchronized(lock) {
            entries.addLast(safe)
            var trimmed = false
            while (entries.size > maxEntries) {
                entries.removeFirst()
                trimmed = true
            }
            try {
                file.parentFile?.mkdirs()
                if (trimmed) {
                    file.writeText(entries.joinToString("\n", postfix = "\n") { encode(it) })
                } else {
                    file.appendText(encode(safe) + "\n")
                }
                lastWriteError = null
            } catch (e: IOException) {
                lastWriteError = e.message ?: e.javaClass.simpleName
            }
        }
    }

    override fun recent(limit: Int): List<AuditEvent> =
        synchronized(lock) { entries.toList().asReversed().take(limit) }

    private fun loadExisting() {
        if (!file.exists()) return
        val lines = try {
            file.readLines()
        } catch (e: IOException) {
            lastWriteError = e.message ?: e.javaClass.simpleName
            return
        }
        var skipped = 0
        for (line in lines) {
            if (line.isBlank()) continue
            val event = decode(line)
            if (event == null) skipped++ else entries.addLast(event)
        }
        corruptLinesSkipped = skipped
        while (entries.size > maxEntries) entries.removeFirst()
    }

    private fun encode(event: AuditEvent): String = buildJsonObject {
        put("t", event.timestampMs)
        put("k", event.kind.name)
        put("tool", event.tool)
        put("risk", event.risk.name)
        put("summary", event.summary)
        if (event.detail != null) put("detail", event.detail)
    }.toString()

    private fun decode(line: String): AuditEvent? {
        return try {
            val obj = Json.parseToJsonElement(line).jsonObject
            AuditEvent(
                timestampMs = obj["t"]?.jsonPrimitive?.longOrNull ?: return null,
                kind = AuditKind.valueOf(obj["k"]?.jsonPrimitive?.contentOrNull ?: return null),
                tool = obj["tool"]?.jsonPrimitive?.contentOrNull ?: return null,
                risk = Risk.valueOf(obj["risk"]?.jsonPrimitive?.contentOrNull ?: return null),
                summary = obj["summary"]?.jsonPrimitive?.contentOrNull ?: return null,
                detail = obj["detail"]?.jsonPrimitive?.contentOrNull,
            )
        } catch (e: IllegalArgumentException) {
            // Covers malformed JSON (SerializationException), wrong element types and unknown enum names.
            null
        }
    }
}

package com.jarvis.ai.ai.tools

import com.jarvis.ai.core.security.Risk
import java.security.MessageDigest

enum class ParamType { STRING, INT, BOOLEAN, ENUM }

data class ParamSpec(
    val name: String,
    val type: ParamType,
    val description: String,
    val required: Boolean = true,
    /** Allowed values for [ParamType.ENUM] (matched case-insensitively, stored as written here). */
    val enumValues: List<String> = emptyList(),
    val min: Int? = null,
    val max: Int? = null,
    val maxLength: Int = 500,
) {
    init {
        require(name.matches(NAME_PATTERN)) { "Parameter name '$name' must be lower_snake_case" }
        require(type != ParamType.ENUM || enumValues.isNotEmpty()) { "Enum parameter '$name' needs enumValues" }
    }

    private companion object {
        val NAME_PATTERN = Regex("[a-z][a-z0-9_]*")
    }
}

/** Everything the system needs to know about a tool, declared in code, never by the model. */
data class ToolSpec(
    val name: String,
    val description: String,
    val params: List<ParamSpec>,
    val risk: Risk,
    /** Android permission strings that must be granted for the tool to run. */
    val requiredPermissions: Set<String> = emptySet(),
    val timeoutMs: Long = 10_000L,
    /** Automatic retries for retryable failures. Ignored (forced to 0) for HIGH-risk tools. */
    val maxRetries: Int = 0,
    /** What a successful result's message/data contain; documentation for tool authors. */
    val resultFormat: String,
) {
    init {
        require(name.matches(NAME_PATTERN)) { "Tool name '$name' must be lower_snake_case" }
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        require(maxRetries >= 0) { "maxRetries must not be negative" }
        require(params.map { it.name }.toSet().size == params.size) { "Duplicate parameter names in '$name'" }
    }

    private companion object {
        val NAME_PATTERN = Regex("[a-z][a-z0-9_]*")
    }
}

/** Arguments that have already passed [ArgValidator]; values are String, Int or Boolean. */
class ToolArgs(raw: Map<String, Any>) {
    private val values: Map<String, Any> = raw.toMap()

    fun string(name: String): String =
        values[name] as? String ?: throw IllegalStateException("Argument '$name' is missing or not text")

    fun stringOrNull(name: String): String? = values[name] as? String

    fun int(name: String): Int =
        values[name] as? Int ?: throw IllegalStateException("Argument '$name' is missing or not a number")

    fun intOrNull(name: String): Int? = values[name] as? Int

    fun boolean(name: String): Boolean =
        values[name] as? Boolean ?: throw IllegalStateException("Argument '$name' is missing or not true/false")

    fun booleanOrNull(name: String): Boolean? = values[name] as? Boolean

    fun asMap(): Map<String, Any> = values

    /**
     * Stable SHA-256 of (tool, arguments). Confirmation is bound to this value, so what
     * the user approved is exactly what runs. Length-prefixing keeps different argument
     * sets from colliding through separator characters.
     */
    fun fingerprint(toolName: String): String {
        val canonical = buildString {
            append(toolName.length).append(':').append(toolName)
            for (key in values.keys.sorted()) {
                val value = values.getValue(key).toString()
                append('|').append(key.length).append(':').append(key)
                append('=').append(value.length).append(':').append(value)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

enum class Verification {
    /** The device state was read back or the system confirmed it (for example the torch callback). */
    CONFIRMED,

    /** The request was delivered to another app/system component; the outcome cannot be observed. */
    HANDED_OFF,
}

sealed interface ToolError {
    val message: String

    /** Worth trying again automatically. */
    val retryable: Boolean get() = false

    /** A different plan could plausibly succeed. */
    val replannable: Boolean get() = true

    data class InvalidArguments(override val message: String) : ToolError

    data class PermissionDenied(val permissions: Set<String>, override val message: String) : ToolError {
        override val replannable: Boolean get() = false
    }

    /** Hardware or target app is missing / busy. */
    data class Unavailable(override val message: String) : ToolError {
        override val replannable: Boolean get() = false
    }

    /** A platform limitation, for example something Android does not let an ordinary app do. */
    data class Unsupported(override val message: String) : ToolError {
        override val replannable: Boolean get() = false
    }

    data class Timeout(val afterMs: Long) : ToolError {
        override val message: String get() = "Timed out after ${afterMs / 1000.0}s"
        override val retryable: Boolean get() = true
    }

    data class Failed(override val message: String, override val retryable: Boolean = false) : ToolError
}

sealed interface ToolResult {
    data class Success(
        /** Written by the tool; it must not claim more than [verification] supports. */
        val message: String,
        val verification: Verification,
        val data: Map<String, String> = emptyMap(),
        /** True when [message]/[data] contain text from outside (web, files, messages). */
        val untrusted: Boolean = false,
    ) : ToolResult

    data class Failure(val error: ToolError) : ToolResult
}

interface Tool {
    val spec: ToolSpec

    /**
     * One line describing exactly what will happen, built from the validated arguments.
     * This is what the confirmation dialog shows; it must never repeat model-written text.
     */
    fun describe(args: ToolArgs): String

    suspend fun execute(args: ToolArgs): ToolResult
}

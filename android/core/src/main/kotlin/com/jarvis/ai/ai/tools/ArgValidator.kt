package com.jarvis.ai.ai.tools

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

sealed interface ValidationResult {
    data class Valid(val args: ToolArgs) : ValidationResult

    data class Invalid(val errors: List<String>) : ValidationResult
}

/**
 * Checks model-written parameters against the tool's declared schema.
 * Unknown keys are ignored (they never reach the tool); missing required values, wrong
 * types, out-of-range numbers and over-long text are rejected with a readable message
 * that is also fed back to the planner when it has to revise a plan.
 */
object ArgValidator {

    fun validate(spec: ToolSpec, raw: JsonObject): ValidationResult {
        val errors = mutableListOf<String>()
        val clean = LinkedHashMap<String, Any>()

        for (param in spec.params) {
            val element = raw[param.name]
            if (element == null || element is JsonNull) {
                if (param.required) errors += "Missing required parameter '${param.name}'"
                continue
            }
            val primitive = element as? JsonPrimitive
            if (primitive == null) {
                errors += "Parameter '${param.name}' must be a single value"
                continue
            }
            when (param.type) {
                ParamType.STRING -> {
                    val text = primitive.content.trim()
                    when {
                        text.isEmpty() && param.required -> errors += "Parameter '${param.name}' must not be empty"
                        // An optional value the model left blank counts as "not provided".
                        text.isEmpty() -> Unit
                        text.length > param.maxLength ->
                            errors += "Parameter '${param.name}' is too long (max ${param.maxLength} characters)"
                        text.any { it == '\u0000' } -> errors += "Parameter '${param.name}' contains invalid characters"
                        else -> clean[param.name] = text
                    }
                }
                ParamType.INT -> {
                    val number = primitive.intOrNull
                    when {
                        number == null -> errors += "Parameter '${param.name}' must be a whole number"
                        param.min != null && number < param.min ->
                            errors += "Parameter '${param.name}' must be at least ${param.min}"
                        param.max != null && number > param.max ->
                            errors += "Parameter '${param.name}' must be at most ${param.max}"
                        else -> clean[param.name] = number
                    }
                }
                ParamType.BOOLEAN -> {
                    val flag = primitive.booleanOrNull
                    if (flag == null) {
                        errors += "Parameter '${param.name}' must be true or false"
                    } else {
                        clean[param.name] = flag
                    }
                }
                ParamType.ENUM -> {
                    val text = primitive.content.trim()
                    val match = param.enumValues.firstOrNull { it.equals(text, ignoreCase = true) }
                    if (match == null) {
                        errors += "Parameter '${param.name}' must be one of: ${param.enumValues.joinToString(", ")}"
                    } else {
                        clean[param.name] = match
                    }
                }
            }
        }

        return if (errors.isEmpty()) ValidationResult.Valid(ToolArgs(clean)) else ValidationResult.Invalid(errors)
    }
}

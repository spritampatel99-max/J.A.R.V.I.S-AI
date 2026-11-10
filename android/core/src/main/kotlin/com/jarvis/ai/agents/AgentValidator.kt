package com.jarvis.ai.agents

import com.jarvis.ai.ai.tools.ToolRegistry
import com.jarvis.ai.core.security.Risk
import com.jarvis.ai.core.security.SecretRedactor

/**
 * Checks an agent before it is saved. An agent may only use tools that
 *  - exist in this build,
 *  - are LOW risk (anything that needs a confirmation cannot be given to an agent), and
 *  - belong to one of the features the person chose.
 * It also cannot carry a secret key in any text field, and its extra rules can only add caution, never remove it.
 */
object AgentValidator {
    const val MAX_NAME = 40
    const val MIN_NAME = 2
    const val MAX_PURPOSE = 600
    const val MIN_PURPOSE = 10
    const val MAX_DESCRIPTION = 300
    const val MAX_INSTRUCTIONS = 1_500
    const val MAX_GREETING = 200
    const val MAX_SHORT = 60
    const val MAX_AUDIENCE = 80
    const val MAX_ANSWERS = 20
    const val MAX_ANSWER = 300
    const val MAX_FEATURES = 30
    const val MAX_RULES = 10
    const val MAX_RULE = 200

    fun validate(config: AgentConfig, registry: ToolRegistry): List<String> {
        val problems = mutableListOf<String>()

        fun text(label: String, value: String, min: Int, max: Int, multiline: Boolean = false) {
            val v = value.trim()
            if (v.length < min) problems += "$label must be at least $min characters."
            if (v.length > max) problems += "$label must be at most $max characters."
            if (value.any { it.isISOControl() && !(multiline && (it == '\n' || it == '\r' || it == '\t')) }) problems += "$label contains characters that cannot be used."
            if (SecretRedactor.redactPatterns(value) != value) problems += "$label contains something that looks like a secret key. Remove it."
        }

        text("The name", config.name, MIN_NAME, MAX_NAME)
        text("The purpose", config.purpose, MIN_PURPOSE, MAX_PURPOSE, multiline = true)
        text("The description", config.description, 0, MAX_DESCRIPTION, multiline = true)
        text("The instructions", config.instructions, 0, MAX_INSTRUCTIONS, multiline = true)
        text("The greeting", config.greeting, 0, MAX_GREETING)
        text("The language", config.language, 0, MAX_SHORT)
        text("The personality", config.personality, 0, MAX_SHORT)
        text("The tone", config.tone, 0, MAX_SHORT)
        text("The audience", config.audience, 0, MAX_AUDIENCE)
        text("The icon", config.icon, 0, 30)

        if (config.answers.size > MAX_ANSWERS) problems += "At most $MAX_ANSWERS answers."
        for ((k, v) in config.answers) {
            if (k.length > 40 || k.any { !(it.isLetterOrDigit() || it == '_') }) problems += "An answer has an invalid key."
            if (v.length > MAX_ANSWER) problems += "An answer is longer than $MAX_ANSWER characters."
            if (v.any { it.isISOControl() && it != '\n' }) problems += "An answer contains characters that cannot be used."
            if (SecretRedactor.redactPatterns(v) != v) problems += "An answer contains something that looks like a secret key. Remove it."
        }

        if (config.features.size > MAX_FEATURES) problems += "At most $MAX_FEATURES features."
        if (config.features.toSet().size != config.features.size) problems += "A feature is listed twice."
        for (id in config.features) {
            val feature = AgentBlueprint.feature(id)
            when {
                feature == null -> problems += "Unknown feature '${id.take(30)}'."
                !feature.selectable -> problems += "\"${feature.name}\" is not built in this version and cannot be selected."
            }
        }

        val allowed = AgentBlueprint.toolsFor(config.features)
        for (tool in config.enabledTools) {
            val t = registry.get(tool)
            when {
                t == null -> problems += "The tool '${tool.take(30)}' is not available in this build."
                t.spec.risk != Risk.LOW -> problems += "The tool '${tool.take(30)}' needs your confirmation, so it cannot be given to an agent."
                tool !in allowed -> problems += "The tool '${tool.take(30)}' does not belong to any chosen feature."
            }
        }

        if (config.extraSafetyRules.size > MAX_RULES) problems += "At most $MAX_RULES extra rules."
        for (rule in config.extraSafetyRules) {
            if (rule.isBlank() || rule.length > MAX_RULE) problems += "An extra rule must be 1 to $MAX_RULE characters."
            if (SecretRedactor.redactPatterns(rule) != rule) problems += "An extra rule contains something that looks like a secret key. Remove it."
        }
        return problems.distinct()
    }

    /** The tools an agent with these features should have, kept only if they exist and are LOW risk right now. */
    fun toolsFor(features: List<String>, registry: ToolRegistry): Set<String> =
        AgentBlueprint.toolsFor(features).filter { registry.get(it)?.spec?.risk == Risk.LOW }.toSet()
}

package com.jarvis.ai.ai.tools

/** The complete set of things the assistant can do. Anything not registered cannot run. */
class ToolRegistry(tools: List<Tool>) {
    private val byName: Map<String, Tool>

    init {
        val map = LinkedHashMap<String, Tool>()
        for (tool in tools) {
            val previous = map.put(tool.spec.name, tool)
            require(previous == null) { "Duplicate tool name '${tool.spec.name}'" }
        }
        byName = map
    }

    fun get(name: String): Tool? = byName[name]

    fun all(): List<Tool> = byName.values.toList()

    /** Human/LLM-readable list of tools and their parameters, used in the planner prompt. */
    fun promptCatalog(): String = buildString {
        for (tool in all()) {
            val spec = tool.spec
            appendLine("- ${spec.name}: ${spec.description}")
            if (spec.params.isEmpty()) {
                appendLine("    parameters: none")
            } else {
                for (p in spec.params) {
                    append("    ${p.name} (${typeLabel(p)}, ${if (p.required) "required" else "optional"}): ${p.description}")
                    appendLine()
                }
            }
        }
    }

    private fun typeLabel(p: ParamSpec): String = when (p.type) {
        ParamType.STRING -> "text"
        ParamType.INT -> {
            val range = when {
                p.min != null && p.max != null -> " ${p.min}-${p.max}"
                p.min != null -> " >= ${p.min}"
                p.max != null -> " <= ${p.max}"
                else -> ""
            }
            "whole number$range"
        }
        ParamType.BOOLEAN -> "true/false"
        ParamType.ENUM -> "one of: ${p.enumValues.joinToString("|")}"
    }
}

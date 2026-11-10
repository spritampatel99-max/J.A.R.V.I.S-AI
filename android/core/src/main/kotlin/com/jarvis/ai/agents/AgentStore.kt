package com.jarvis.ai.agents

import com.jarvis.ai.ai.tools.ToolRegistry
import com.jarvis.ai.core.common.IdGenerator
import com.jarvis.ai.core.common.TextStorage
import com.jarvis.ai.core.common.TimeSource
import com.jarvis.ai.core.common.long
import com.jarvis.ai.core.common.obj
import com.jarvis.ai.core.common.parseJson
import com.jarvis.ai.core.common.str
import com.jarvis.ai.core.common.strings
import com.jarvis.ai.memory.MemoryScope
import com.jarvis.ai.memory.MemoryStore
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface AgentResult {
    data class Saved(val agent: AgentConfig) : AgentResult
    data class Rejected(val problems: List<String>) : AgentResult
}

/**
 * The person's agents. Only the person's own taps reach this class: no tool in any registry can create, change, approve or enable an
 * agent, so a model can never grant itself or another agent anything.
 *
 * A new agent is a DRAFT and is only used for real after [approve]. Deleting an agent also deletes the memory that belongs to it.
 */
class AgentStore(
    private val storage: TextStorage,
    private val time: TimeSource,
    private val registry: ToolRegistry,
    private val memory: MemoryStore?,
) {
    private val lock = Any()
    private val ids = IdGenerator(time, "agent")
    private val flow = MutableStateFlow(load())

    val agents: StateFlow<List<AgentConfig>> = flow.asStateFlow()

    @Volatile
    var saveFailed: Boolean = false
        private set

    fun get(id: String): AgentConfig? = flow.value.firstOrNull { it.id == id }

    /** Saves a new agent as a draft. The id, version, status and dates in [draft] are ignored. */
    fun createDraft(draft: AgentConfig): AgentResult = synchronized(lock) {
        val now = time.nowMs()
        val config = draft.copy(
            id = ids.next(),
            version = 1,
            status = AgentStatus.DRAFT,
            createdMs = now,
            updatedMs = now,
            lastUsedMs = null,
            enabledTools = AgentValidator.toolsFor(draft.features, registry),
            name = draft.name.trim(),
        )
        val problems = problemsFor(config, ignoreId = config.id)
        if (flow.value.size >= MAX_AGENTS) return AgentResult.Rejected(problems + "At most $MAX_AGENTS agents. Delete one first.")
        if (problems.isNotEmpty()) return AgentResult.Rejected(problems)
        commit(flow.value + config)
        AgentResult.Saved(config)
    }

    /** Replaces the editable parts of an agent. The status stays as it was; a draft stays a draft. */
    fun update(id: String, edited: AgentConfig): AgentResult = synchronized(lock) {
        val existing = get(id) ?: return AgentResult.Rejected(listOf("That agent no longer exists."))
        val config = edited.copy(
            id = existing.id,
            version = existing.version + 1,
            status = existing.status,
            createdMs = existing.createdMs,
            updatedMs = time.nowMs(),
            lastUsedMs = existing.lastUsedMs,
            enabledTools = AgentValidator.toolsFor(edited.features, registry),
            name = edited.name.trim(),
        )
        val problems = problemsFor(config, ignoreId = id)
        if (problems.isNotEmpty()) return AgentResult.Rejected(problems)
        commit(flow.value.map { if (it.id == id) config else it })
        AgentResult.Saved(config)
    }

    /** The person's approval of a draft: "Approve and save". The only way a draft becomes usable. */
    fun approve(id: String): Boolean = synchronized(lock) {
        val existing = get(id) ?: return false
        if (existing.status != AgentStatus.DRAFT) return false
        commit(flow.value.map { if (it.id == id) it.copy(status = AgentStatus.ACTIVE, updatedMs = time.nowMs()) else it })
        true
    }

    /** Disable or enable an agent that has already been approved. A draft cannot be enabled this way. */
    fun setEnabled(id: String, enabled: Boolean): Boolean = synchronized(lock) {
        val existing = get(id) ?: return false
        if (existing.status == AgentStatus.DRAFT) return false
        val status = if (enabled) AgentStatus.ACTIVE else AgentStatus.DISABLED
        commit(flow.value.map { if (it.id == id) it.copy(status = status, updatedMs = time.nowMs()) else it })
        true
    }

    fun rename(id: String, newName: String): AgentResult = synchronized(lock) {
        val existing = get(id) ?: return AgentResult.Rejected(listOf("That agent no longer exists."))
        update(id, existing.copy(name = newName))
    }

    /** A copy as a new draft. Memory and last-used date are not copied: the copy starts empty. */
    fun duplicate(id: String): AgentResult = synchronized(lock) {
        val existing = get(id) ?: return AgentResult.Rejected(listOf("That agent no longer exists."))
        var name = "${existing.name.take(AgentValidator.MAX_NAME - 7)} copy"
        var n = 2
        while (flow.value.any { it.name.equals(name, ignoreCase = true) }) name = "${existing.name.take(AgentValidator.MAX_NAME - 9)} copy ${n++}"
        createDraft(existing.copy(name = name))
    }

    fun markUsed(id: String) = synchronized(lock) {
        commit(flow.value.map { if (it.id == id) it.copy(lastUsedMs = time.nowMs()) else it })
    }

    /** The configuration as text the person can save or share. It holds no memory, no history and no keys. */
    fun export(id: String): String? = get(id)?.let { encodeOne(it).toString() }

    /** Deletes the agent and everything it remembered. The screen asks the person to confirm before calling this. */
    fun delete(id: String): Boolean = synchronized(lock) {
        if (get(id) == null) return false
        memory?.clear(MemoryScope.Agent(id))
        commit(flow.value.filter { it.id != id })
        true
    }

    private fun problemsFor(config: AgentConfig, ignoreId: String): List<String> {
        val problems = AgentValidator.validate(config, registry).toMutableList()
        if (flow.value.any { it.id != ignoreId && it.name.equals(config.name, ignoreCase = true) }) problems += "You already have an agent with that name."
        return problems
    }

    private fun commit(next: List<AgentConfig>) {
        flow.value = next
        saveFailed = try {
            storage.write(buildJsonArray { for (a in next) add(encodeOne(a)) }.toString())
            false
        } catch (e: IOException) {
            true
        }
    }

    private fun load(): List<AgentConfig> = (parseJson(storage.read()) as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::decodeOne) }

    private fun encodeOne(a: AgentConfig): JsonObject = buildJsonObject {
        put("id", a.id)
        put("version", a.version)
        put("name", a.name)
        put("purpose", a.purpose)
        put("description", a.description)
        put("category", a.category.name)
        put("instructions", a.instructions)
        put("features", buildJsonArray { a.features.forEach { add(JsonPrimitive(it)) } })
        put("answers", buildJsonObject { a.answers.forEach { (k, v) -> put(k, v) } })
        put("greeting", a.greeting)
        put("icon", a.icon)
        put("language", a.language)
        put("personality", a.personality)
        put("tone", a.tone)
        put("detail", a.detail.name)
        put("audience", a.audience)
        put("model", a.model.name)
        put("network", a.network.name)
        put("enabledTools", buildJsonArray { a.enabledTools.sorted().forEach { add(JsonPrimitive(it)) } })
        put("memory", a.memory.name)
        put("extraSafetyRules", buildJsonArray { a.extraSafetyRules.forEach { add(JsonPrimitive(it)) } })
        put("status", a.status.name)
        put("createdMs", a.createdMs)
        put("updatedMs", a.updatedMs)
        a.lastUsedMs?.let { put("lastUsedMs", it) }
    }

    // Anything unreadable falls back to the most restrictive choice: no memory, on this phone only, disabled, no tools.
    private fun decodeOne(o: JsonObject): AgentConfig? {
        val answers = o.obj("answers")?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content } }?.toMap().orEmpty()
        return AgentConfig(
            id = o.str("id") ?: return null,
            version = (o.long("version") ?: 1L).toInt(),
            name = o.str("name") ?: return null,
            purpose = o.str("purpose").orEmpty(),
            description = o.str("description").orEmpty(),
            category = AgentCategory.entries.firstOrNull { it.name == o.str("category") } ?: AgentCategory.CUSTOM,
            instructions = o.str("instructions").orEmpty(),
            features = o.strings("features"),
            answers = answers,
            greeting = o.str("greeting").orEmpty(),
            icon = o.str("icon").orEmpty(),
            language = o.str("language").orEmpty(),
            personality = o.str("personality").orEmpty(),
            tone = o.str("tone").orEmpty(),
            detail = ResponseDetail.entries.firstOrNull { it.name == o.str("detail") } ?: ResponseDetail.BALANCED,
            audience = o.str("audience").orEmpty(),
            model = ModelPreference.entries.firstOrNull { it.name == o.str("model") } ?: ModelPreference.ROUTER_DEFAULT,
            network = NetworkPolicy.entries.firstOrNull { it.name == o.str("network") } ?: NetworkPolicy.LOCAL_ONLY,
            enabledTools = o.strings("enabledTools").toSet(),
            memory = MemoryPolicy.entries.firstOrNull { it.name == o.str("memory") } ?: MemoryPolicy.NONE,
            extraSafetyRules = o.strings("extraSafetyRules"),
            status = AgentStatus.entries.firstOrNull { it.name == o.str("status") } ?: AgentStatus.DISABLED,
            createdMs = o.long("createdMs") ?: 0L,
            updatedMs = o.long("updatedMs") ?: 0L,
            lastUsedMs = o.long("lastUsedMs"),
        )
    }

    companion object {
        const val MAX_AGENTS = 20
    }
}

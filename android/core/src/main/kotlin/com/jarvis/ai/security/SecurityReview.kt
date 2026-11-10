package com.jarvis.ai.security

import com.jarvis.ai.agents.AgentConfig
import com.jarvis.ai.agents.AgentStatus
import com.jarvis.ai.agents.MemoryPolicy
import com.jarvis.ai.agents.NetworkPolicy
import com.jarvis.ai.ai.tools.ToolRegistry
import com.jarvis.ai.automation.Automation
import com.jarvis.ai.automation.AutomationRules
import com.jarvis.ai.core.security.AuditEvent
import com.jarvis.ai.core.security.AuditKind
import com.jarvis.ai.core.security.Risk
import com.jarvis.ai.core.security.SecretRedactor
import com.jarvis.ai.memory.MemoryEntry

enum class Severity(val label: String) { INFO("For your information"), ATTENTION("Worth a look"), WARNING("Fix this") }

data class Finding(val id: String, val severity: Severity, val title: String, val detail: String, val action: String?)

/** Everything the review looks at, read from the live app. Nothing here is a secret value: keys are only counted. */
data class SecuritySnapshot(
    val grantedPermissions: Set<String>,
    /** Permissions that something in the app uses today (tools, voice, notifications). */
    val neededPermissions: Set<String>,
    val cloudKeyCount: Int,
    val historyConversations: Int,
    val historyBytes: Long,
    val memory: List<MemoryEntry>,
    val agents: List<AgentConfig>,
    val automations: List<Automation>,
    val registry: ToolRegistry,
    val audit: List<AuditEvent>,
    /** Names of stores whose last save failed. */
    val saveFailures: List<String>,
    val notificationsAllowed: Boolean,
    val pendingReminders: Int,
)

data class SecurityReport(
    val findings: List<Finding>,
    /** What was looked at, so the person knows what "nothing found" covers. */
    val checked: List<String>,
    /** What this review cannot see. Shown with every report so a clean result is not read as more than it is. */
    val notChecked: List<String>,
) {
    val warnings: Int get() = findings.count { it.severity == Severity.WARNING }
    val attention: Int get() = findings.count { it.severity == Severity.ATTENTION }
}

/**
 * A review of this app's own settings and data. It looks only at things the app can see and reports what it found. It is not a malware scan
 * and does not rate the phone as "secure"; the report says what it did not check.
 */
object SecurityReview {

    val CHECKED = listOf(
        "Permissions granted to this app and whether anything uses them",
        "Cloud AI keys saved (counted, never shown)",
        "Saved chat history, memory notes, agents and automations",
        "Confirmation history for sensitive actions",
        "Whether saving to the phone's storage is working",
    )

    val NOT_CHECKED = listOf(
        "Other apps on the phone",
        "Android's own security settings and updates",
        "The network you are connected to",
        "What a cloud provider does with messages once it receives them",
    )

    fun review(s: SecuritySnapshot): SecurityReport {
        val f = mutableListOf<Finding>()

        val unused = (s.grantedPermissions - s.neededPermissions).sorted()
        if (unused.isNotEmpty()) {
            f += Finding(
                "unused_permissions", Severity.ATTENTION, "Permissions nothing uses",
                "Granted but not used by any feature right now: ${unused.joinToString(", ") { it.substringAfterLast('.') }}.",
                "Turn them off in Android settings > Apps > J.A.R.V.I.S. > Permissions.",
            )
        }

        if (s.cloudKeyCount > 0) {
            f += Finding(
                "cloud_keys", Severity.INFO, "Cloud AI keys",
                "${s.cloudKeyCount} cloud key(s) are saved on this phone. Messages you send to the AI go to the provider that answers.",
                "Remove keys you no longer use in Prefs > Cloud AI.",
            )
        }

        if (s.historyConversations > 0) {
            f += Finding(
                "history", Severity.INFO, "Chat history",
                "${s.historyConversations} conversation(s) are saved on this phone (${s.historyBytes / 1024} KB). Pasted keys are removed before saving.",
                "You can delete one or all of them in Logs.",
            )
        }

        val secretLike = s.memory.count { SecretRedactor.redactPatterns(it.text) != it.text }
        if (secretLike > 0) {
            f += Finding(
                "memory_secrets", Severity.WARNING, "Memory notes that look like keys",
                "$secretLike saved note(s) look like they contain a secret key or token.",
                "Open Memory, review the notes and delete them.",
            )
        }

        val liveAgents = s.agents.filter { it.status == AgentStatus.ACTIVE }
        val cloudWithMemory = liveAgents.filter { it.network == NetworkPolicy.CLOUD_ALLOWED && it.memory != MemoryPolicy.NONE }
        if (cloudWithMemory.isNotEmpty()) {
            f += Finding(
                "agent_memory_cloud", Severity.INFO, "Agents that share memory with a cloud provider",
                "${cloudWithMemory.joinToString(", ") { it.name }}: the notes an agent can see are sent to the provider with each request.",
                "Set the agent to \"No memory\" or \"On this phone only\" in its settings.",
            )
        }
        val overreaching = s.agents.filter { agent ->
            agent.enabledTools.any { name -> val t = s.registry.get(name); t == null || t.spec.risk != Risk.LOW }
        }
        if (overreaching.isNotEmpty()) {
            f += Finding(
                "agent_tools", Severity.WARNING, "Agents with tools they should not have",
                "${overreaching.joinToString(", ") { it.name }} lists a tool that is missing or needs confirmation. The app does not give it to the agent.",
                "Edit and save the agent again, or delete it.",
            )
        }

        val blocked = s.automations.filter { a ->
            a.enabled && AutomationRules.validate(a.name, a.trigger, a.actions, s.registry).isNotEmpty()
        }
        if (blocked.isNotEmpty()) {
            f += Finding(
                "automation_blocked", Severity.ATTENTION, "Automations that will not run",
                "${blocked.joinToString(", ") { it.name }} no longer pass the rules for unattended actions, so they are skipped.",
                "Edit or delete them in Automations.",
            )
        }
        val running = s.automations.count { it.enabled }
        if (running > 0) {
            f += Finding(
                "automations", Severity.INFO, "Automations",
                "$running automation(s) run without you. They can only add a task, add a note or show a notification.",
                null,
            )
        }

        val mismatches = s.audit.count { it.kind == AuditKind.CONFIRMATION_MISMATCH }
        if (mismatches > 0) {
            f += Finding(
                "confirmation_mismatch", Severity.ATTENTION, "Approvals that did not match",
                "$mismatches time(s) an approval did not match the action about to run, and the action was stopped.",
                "See Logs for what was blocked.",
            )
        }
        val approved = s.audit.count { it.kind == AuditKind.CONFIRMATION_APPROVED }
        val denied = s.audit.count { it.kind == AuditKind.CONFIRMATION_DENIED || it.kind == AuditKind.CONFIRMATION_EXPIRED }
        if (approved + denied > 0) {
            f += Finding("confirmations", Severity.INFO, "Confirmations", "Recently you approved $approved and declined or let expire $denied sensitive action(s).", null)
        }

        if (s.saveFailures.isNotEmpty()) {
            f += Finding(
                "save_failures", Severity.ATTENTION, "Saving is failing",
                "The phone could not save: ${s.saveFailures.joinToString(", ")}. Recent changes may be lost if the app closes.",
                "Free up storage space on the phone.",
            )
        }

        if (!s.notificationsAllowed && (s.pendingReminders > 0 || s.automations.any { it.enabled })) {
            f += Finding(
                "notifications_off", Severity.ATTENTION, "Notifications are off",
                "You have reminders or automations that show notifications, but Android is not letting this app show them.",
                "Turn notifications on in Prefs > Manage access.",
            )
        }

        return SecurityReport(f.sortedByDescending { it.severity.ordinal }, CHECKED, NOT_CHECKED)
    }
}

package com.jarvis.ai

import com.jarvis.ai.agents.AgentCategory
import com.jarvis.ai.agents.AgentConfig
import com.jarvis.ai.agents.AgentStatus
import com.jarvis.ai.agents.MemoryPolicy
import com.jarvis.ai.agents.ModelPreference
import com.jarvis.ai.agents.NetworkPolicy
import com.jarvis.ai.agents.ResponseDetail
import com.jarvis.ai.ai.tools.ParamSpec
import com.jarvis.ai.ai.tools.ParamType
import com.jarvis.ai.ai.tools.ToolRegistry
import com.jarvis.ai.automation.AutoAction
import com.jarvis.ai.automation.AutoTrigger
import com.jarvis.ai.automation.Automation
import com.jarvis.ai.core.common.TextStorage
import com.jarvis.ai.core.security.AuditEvent
import com.jarvis.ai.core.security.AuditKind
import com.jarvis.ai.core.security.Risk
import com.jarvis.ai.memory.MemoryCategory
import com.jarvis.ai.memory.MemoryEntry
import com.jarvis.ai.offline.CapabilityState
import com.jarvis.ai.offline.Connectivity
import com.jarvis.ai.offline.OfflineReadiness
import com.jarvis.ai.offline.OfflineSelfTest
import com.jarvis.ai.offline.OfflineSnapshot
import com.jarvis.ai.offline.SelfTestResult
import com.jarvis.ai.security.SecurityReview
import com.jarvis.ai.security.SecuritySnapshot
import com.jarvis.ai.security.Severity
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class OfflineAndSecurityTest {

    private class Disk(var text: String? = null, var fail: Boolean = false) : TextStorage {
        override fun read() = text
        override fun write(text: String) {
            if (fail) throw IOException("full")
            this.text = text
        }
    }

    // ---- offline ----

    private fun snap(
        connectivity: Connectivity = Connectivity.OFFLINE,
        engine: Boolean = false,
        models: List<String> = emptyList(),
        keys: Int = 1,
        offlineSpeech: Boolean? = false,
        recogniser: Boolean = true,
        tts: Boolean = true,
        notifications: Boolean = true,
        selfTest: List<SelfTestResult>? = null,
    ) = OfflineSnapshot(connectivity, engine, models, offlineSpeech, recogniser, tts, keys, notifications, selfTest)

    private fun cap(s: OfflineSnapshot, id: String) = OfflineReadiness.assess(s).capabilities.first { it.id == id }

    @Test fun `without the local engine there is no AI chat offline and the report says so`() {
        val local = cap(snap(models = listOf("Gemma 3 1B")), "local_ai")
        assertEquals(CapabilityState.NOT_BUILT, local.state)
        assertTrue(local.detail.contains("not built"))
    }

    @Test fun `an installed model does not count as working while the engine is missing`() {
        val report = OfflineReadiness.assess(snap(models = listOf("Gemma 3 1B"), keys = 0))
        assertFalse(report.aiAnswersPossible)
    }

    @Test fun `with an engine and a model local AI works`() {
        val s = snap(engine = true, models = listOf("Gemma 3 1B"))
        assertEquals(CapabilityState.WORKS, cap(s, "local_ai").state)
        assertTrue(OfflineReadiness.assess(s).aiAnswersPossible)
    }

    @Test fun `an engine without a model is unavailable`() {
        assertEquals(CapabilityState.UNAVAILABLE, cap(snap(engine = true), "local_ai").state)
    }

    @Test fun `cloud AI depends on a key and on connectivity`() {
        assertEquals(CapabilityState.UNAVAILABLE, cap(snap(keys = 0, connectivity = Connectivity.ONLINE), "cloud_ai").state)
        assertEquals(CapabilityState.UNAVAILABLE, cap(snap(connectivity = Connectivity.OFFLINE), "cloud_ai").state)
        assertEquals(CapabilityState.WORKS, cap(snap(connectivity = Connectivity.ONLINE), "cloud_ai").state)
        assertEquals(CapabilityState.LIMITED, cap(snap(connectivity = Connectivity.UNKNOWN), "cloud_ai").state)
    }

    @Test fun `commands, planner and reminders work offline`() {
        val s = snap()
        assertEquals(CapabilityState.WORKS, cap(s, "commands").state)
        assertEquals(CapabilityState.WORKS, cap(s, "planner").state)
        assertEquals(CapabilityState.WORKS, cap(s, "automations").state)
    }

    @Test fun `notifications off limits reminders`() {
        assertEquals(CapabilityState.LIMITED, cap(snap(notifications = false), "automations").state)
    }

    @Test fun `web research and document search are listed as not built`() {
        val s = snap()
        assertEquals(CapabilityState.NOT_BUILT, cap(s, "web").state)
        assertEquals(CapabilityState.NOT_BUILT, cap(s, "knowledge").state)
    }

    @Test fun `voice reflects what Android reported`() {
        assertEquals(CapabilityState.UNAVAILABLE, cap(snap(recogniser = false), "voice_in").state)
        assertEquals(CapabilityState.WORKS, cap(snap(offlineSpeech = true), "voice_in").state)
        assertEquals(CapabilityState.LIMITED, cap(snap(offlineSpeech = false), "voice_in").state)
        assertEquals(CapabilityState.LIMITED, cap(snap(offlineSpeech = null), "voice_in").state)
        assertEquals(CapabilityState.UNAVAILABLE, cap(snap(tts = false), "voice_out").state)
    }

    @Test fun `a failed storage check is reported`() {
        val failed = listOf(SelfTestResult("planner", "Planner storage", false, "The phone could not write the file."))
        val c = cap(snap(selfTest = failed), "planner")
        assertEquals(CapabilityState.UNAVAILABLE, c.state)
        assertTrue(c.detail.contains("could not write"))
    }

    @Test fun `the headline is honest when offline`() {
        val h = OfflineReadiness.assess(snap()).headline
        assertTrue(h.contains("offline", ignoreCase = true))
        assertTrue(h.contains("AI chat needs internet"))
    }

    @Test fun `the message for an unanswerable chat says why`() {
        assertTrue(OfflineReadiness.aiUnavailableMessage(snap()).contains("offline"))
        assertTrue(OfflineReadiness.aiUnavailableMessage(snap(keys = 0, connectivity = Connectivity.ONLINE)).contains("No AI provider"))
        assertTrue(OfflineReadiness.aiUnavailableMessage(snap(connectivity = Connectivity.ONLINE)).contains("could not reach"))
    }

    @Test fun `the self test really writes and reads back`() {
        val planner = Disk()
        val memory = Disk()
        val results = OfflineSelfTest.run(planner, memory, FakeTime())
        assertTrue(results.all { it.passed }, results.toString())
        assertTrue(planner.text!!.contains("offline self-test"))
        assertTrue(memory.text!!.contains("offline self-test"))
    }

    @Test fun `the self test fails when the phone cannot write`() {
        val results = OfflineSelfTest.run(Disk(fail = true), Disk(), FakeTime())
        val planner = results.first { it.id == "planner" }
        assertFalse(planner.passed)
        assertTrue(results.first { it.id == "memory" }.passed)
    }

    @Test fun `the self test fails when what was written cannot be read back`() {
        val lossy = object : TextStorage {
            override fun read(): String? = null
            override fun write(text: String) {}
        }
        assertFalse(OfflineSelfTest.run(lossy, lossy, FakeTime()).first { it.id == "planner" }.passed)
    }

    // ---- security ----

    private val registry = ToolRegistry(listOf(FakeTool("add_task", Risk.LOW, listOf(ParamSpec("title", ParamType.STRING, "t"))), FakeTool("call_contact", Risk.HIGH)))

    private fun agent(name: String = "Helper", tools: Set<String> = emptySet(), net: NetworkPolicy = NetworkPolicy.LOCAL_ONLY, mem: MemoryPolicy = MemoryPolicy.NONE, status: AgentStatus = AgentStatus.ACTIVE) =
        AgentConfig("a1", 1, name, "purpose of agent", "", AgentCategory.CUSTOM, "", emptyList(), emptyMap(), "", "", "", "", "", ResponseDetail.BALANCED, "", ModelPreference.ROUTER_DEFAULT, net, tools, mem, emptyList(), status, 0, 0, null)

    private fun automation(tool: String = "add_task", enabled: Boolean = true) =
        Automation("x", "Auto", AutoTrigger.Daily(8, 0), listOf(AutoAction(tool, buildJsonObject { put("title", "t") })), enabled, 0, 1, emptyList())

    private fun snapshot(
        granted: Set<String> = emptySet(),
        needed: Set<String> = emptySet(),
        keys: Int = 0,
        memory: List<MemoryEntry> = emptyList(),
        agents: List<AgentConfig> = emptyList(),
        automations: List<Automation> = emptyList(),
        audit: List<AuditEvent> = emptyList(),
        failures: List<String> = emptyList(),
        notifications: Boolean = true,
        reminders: Int = 0,
    ) = SecuritySnapshot(granted, needed, keys, 0, 0, memory, agents, automations, registry, audit, failures, notifications, reminders)

    private fun ids(s: SecuritySnapshot) = SecurityReview.review(s).findings.map { it.id }

    private fun event(kind: AuditKind) = AuditEvent(1, kind, "t", Risk.HIGH, "s")

    @Test fun `a clean phone has no findings but the report still says what it did not check`() {
        val r = SecurityReview.review(snapshot())
        assertTrue(r.findings.isEmpty())
        assertTrue(r.notChecked.any { it.contains("Other apps") })
        assertTrue(r.checked.isNotEmpty())
    }

    @Test fun `permissions that nothing uses are listed`() {
        val r = SecurityReview.review(snapshot(granted = setOf("android.permission.READ_CONTACTS", "android.permission.CAMERA"), needed = setOf("android.permission.CAMERA")))
        val f = r.findings.single { it.id == "unused_permissions" }
        assertTrue(f.detail.contains("READ_CONTACTS"))
        assertFalse(f.detail.contains("CAMERA"))
    }

    @Test fun `keys are counted and never shown`() {
        val f = SecurityReview.review(snapshot(keys = 2)).findings.single { it.id == "cloud_keys" }
        assertTrue(f.detail.contains("2 cloud key"))
    }

    @Test fun `memory that looks like a key is a warning and the text is not repeated`() {
        val secret = "AIza" + "x".repeat(30)
        val entry = MemoryEntry("m1", null, MemoryCategory.NOTE, "my key is $secret", 0, 0)
        val r = SecurityReview.review(snapshot(memory = listOf(entry, MemoryEntry("m2", null, MemoryCategory.NOTE, "buy milk", 0, 0))))
        val f = r.findings.single { it.id == "memory_secrets" }
        assertEquals(Severity.WARNING, f.severity)
        assertTrue(f.detail.startsWith("1 saved"))
        assertFalse(r.findings.any { it.detail.contains(secret) })
    }

    @Test fun `an agent that shares memory with the cloud is mentioned, a local one is not`() {
        assertTrue("agent_memory_cloud" in ids(snapshot(agents = listOf(agent(net = NetworkPolicy.CLOUD_ALLOWED, mem = MemoryPolicy.OWN_ONLY)))))
        assertFalse("agent_memory_cloud" in ids(snapshot(agents = listOf(agent(net = NetworkPolicy.LOCAL_ONLY, mem = MemoryPolicy.OWN_ONLY)))))
        assertFalse("agent_memory_cloud" in ids(snapshot(agents = listOf(agent(net = NetworkPolicy.CLOUD_ALLOWED, mem = MemoryPolicy.NONE)))))
        assertFalse("agent_memory_cloud" in ids(snapshot(agents = listOf(agent(net = NetworkPolicy.CLOUD_ALLOWED, mem = MemoryPolicy.OWN_ONLY, status = AgentStatus.DISABLED)))))
    }

    @Test fun `an agent listing a tool that needs confirmation or is missing is a warning`() {
        assertEquals(Severity.WARNING, SecurityReview.review(snapshot(agents = listOf(agent(tools = setOf("call_contact"))))).findings.single { it.id == "agent_tools" }.severity)
        assertTrue("agent_tools" in ids(snapshot(agents = listOf(agent(tools = setOf("nonexistent"))))))
        assertFalse("agent_tools" in ids(snapshot(agents = listOf(agent(tools = setOf("add_task"))))))
    }

    @Test fun `an automation that no longer passes the rules is flagged`() {
        assertTrue("automation_blocked" in ids(snapshot(automations = listOf(automation(tool = "call_contact")))))
        assertFalse("automation_blocked" in ids(snapshot(automations = listOf(automation(tool = "call_contact", enabled = false)))))
    }

    @Test fun `running automations are mentioned with what they can do`() {
        val f = SecurityReview.review(snapshot(automations = listOf(automation()))).findings.single { it.id == "automations" }
        assertTrue(f.detail.contains("add a task"))
    }

    @Test fun `confirmation mismatches are surfaced and counted`() {
        val r = SecurityReview.review(snapshot(audit = listOf(event(AuditKind.CONFIRMATION_MISMATCH), event(AuditKind.CONFIRMATION_MISMATCH), event(AuditKind.CONFIRMATION_APPROVED), event(AuditKind.CONFIRMATION_DENIED))))
        assertTrue(r.findings.single { it.id == "confirmation_mismatch" }.detail.startsWith("2 time"))
        assertTrue(r.findings.single { it.id == "confirmations" }.detail.contains("approved 1 and declined or let expire 1"))
    }

    @Test fun `failed saves are reported by name`() {
        assertTrue(SecurityReview.review(snapshot(failures = listOf("planner", "memory"))).findings.single { it.id == "save_failures" }.detail.contains("planner, memory"))
    }

    @Test fun `notifications off matters only when something needs them`() {
        assertFalse("notifications_off" in ids(snapshot(notifications = false)))
        assertTrue("notifications_off" in ids(snapshot(notifications = false, reminders = 1)))
        assertTrue("notifications_off" in ids(snapshot(notifications = false, automations = listOf(automation()))))
        assertFalse("notifications_off" in ids(snapshot(notifications = true, reminders = 1)))
    }

    @Test fun `findings are ordered with the most serious first`() {
        val secret = MemoryEntry("m", null, MemoryCategory.NOTE, "key AIza" + "y".repeat(30), 0, 0)
        val r = SecurityReview.review(snapshot(keys = 1, memory = listOf(secret), failures = listOf("memory")))
        assertEquals(Severity.WARNING, r.findings.first().severity)
        assertEquals(Severity.INFO, r.findings.last().severity)
        assertEquals(1, r.warnings)
        assertEquals(1, r.attention)
    }

    @Test fun `an agent with no tools is not a problem`() {
        assertNull(SecurityReview.review(snapshot(agents = listOf(agent()))).findings.firstOrNull { it.id == "agent_tools" })
    }
}

package com.jarvis.ai

import com.jarvis.ai.ai.tools.ParamSpec
import com.jarvis.ai.ai.tools.ParamType
import com.jarvis.ai.ai.tools.ToolRegistry
import com.jarvis.ai.automation.AutoAction
import com.jarvis.ai.automation.AutoTrigger
import com.jarvis.ai.automation.Automation
import com.jarvis.ai.automation.AutomationResult
import com.jarvis.ai.automation.AutomationRules
import com.jarvis.ai.automation.AutomationRunner
import com.jarvis.ai.automation.AutomationStore
import com.jarvis.ai.core.common.TextStorage
import com.jarvis.ai.core.security.InMemoryAuditLog
import com.jarvis.ai.core.security.Risk
import com.jarvis.ai.productivity.Notifier
import com.jarvis.ai.productivity.ProductivityStore
import com.jarvis.ai.productivity.ProductivityTools
import com.jarvis.ai.productivity.WakeResult
import com.jarvis.ai.productivity.WakeScheduler
import java.io.IOException
import java.time.DayOfWeek
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AutomationTest {

    private class Disk(var text: String? = null, var fail: Boolean = false) : TextStorage {
        override fun read() = text
        override fun write(text: String) {
            if (fail) throw IOException("full")
            this.text = text
        }
    }

    private class Wake : WakeScheduler {
        override fun schedule(atMs: Long) = WakeResult.Scheduled(exact = false)
        override fun cancel() {}
    }

    private class Notify(var allowed: Boolean = true) : Notifier {
        val shown = mutableListOf<String>()
        override fun canNotify() = allowed
        override fun notify(key: String, title: String, text: String): Boolean {
            if (allowed) shown += text
            return allowed
        }
    }

    private val zone = ZoneId.of("Asia/Kolkata")
    private val start = Instant.parse("2026-11-10T06:30:00Z") // Tue 12:00 IST
    private val time = FakeTime(start.toEpochMilli())
    private val clock = Clock.fixed(start, zone)
    private val disk = Disk()
    private val store = AutomationStore(disk, time)
    private val planner = ProductivityStore(Disk(), time)
    private val notifier = Notify()
    private val registry = ToolRegistry(ProductivityTools.create(planner, Wake(), notifier, clock))

    private fun at(text: String) = ZonedDateTime.parse(text)
    private fun note(text: String = "hello") = AutoAction(ProductivityTools.ADD_NOTE, buildJsonObject { put("text", text) })
    private fun create(name: String = "Morning", trigger: AutoTrigger = AutoTrigger.Daily(8, 0), actions: List<AutoAction> = listOf(note())) =
        store.create(name, trigger, actions, registry, zone)

    private fun createWith(trigger: AutoTrigger, vararg actions: AutoAction) = create("Morning", trigger, actions.toList())

    private fun saved(r: AutomationResult): Automation = (r as AutomationResult.Saved).automation

    // ---- triggers ----

    @Test fun `daily runs later today when the time has not passed`() {
        val next = AutoTrigger.Daily(18, 30).nextAfter(at("2026-11-10T12:00:00+05:30[Asia/Kolkata]"))
        assertEquals(at("2026-11-10T18:30:00+05:30[Asia/Kolkata]"), next)
    }

    @Test fun `daily runs tomorrow when the time has passed`() {
        val next = AutoTrigger.Daily(8, 0).nextAfter(at("2026-11-10T12:00:00+05:30[Asia/Kolkata]"))
        assertEquals(at("2026-11-11T08:00:00+05:30[Asia/Kolkata]"), next)
    }

    @Test fun `daily is strictly after the given moment`() {
        val exact = at("2026-11-10T08:00:00+05:30[Asia/Kolkata]")
        assertEquals(at("2026-11-11T08:00:00+05:30[Asia/Kolkata]"), AutoTrigger.Daily(8, 0).nextAfter(exact))
    }

    @Test fun `weekly picks the next chosen day`() {
        // Tuesday noon; Mon and Fri chosen.
        val next = AutoTrigger.Weekly(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), 9, 0).nextAfter(at("2026-11-10T12:00:00+05:30[Asia/Kolkata]"))
        assertEquals(at("2026-11-13T09:00:00+05:30[Asia/Kolkata]"), next)
    }

    @Test fun `weekly with only today chosen and the time passed goes to next week`() {
        val next = AutoTrigger.Weekly(setOf(DayOfWeek.TUESDAY), 9, 0).nextAfter(at("2026-11-10T12:00:00+05:30[Asia/Kolkata]"))
        assertEquals(at("2026-11-17T09:00:00+05:30[Asia/Kolkata]"), next)
    }

    @Test fun `weekly later today still runs today`() {
        val next = AutoTrigger.Weekly(setOf(DayOfWeek.TUESDAY), 20, 0).nextAfter(at("2026-11-10T12:00:00+05:30[Asia/Kolkata]"))
        assertEquals(at("2026-11-10T20:00:00+05:30[Asia/Kolkata]"), next)
    }

    @Test fun `every adds the interval`() {
        val from = at("2026-11-10T12:00:00+05:30[Asia/Kolkata]")
        assertEquals(from.plusMinutes(45), AutoTrigger.Every(45).nextAfter(from))
    }

    @Test fun `daily time that does not exist on a clock change still lands after now`() {
        val london = ZoneId.of("Europe/London")
        // 2026-03-29 01:30 does not exist in London (clocks jump 01:00 to 02:00).
        val from = ZonedDateTime.of(2026, 3, 28, 12, 0, 0, 0, london)
        val next = AutoTrigger.Daily(1, 30).nextAfter(from)
        assertTrue(next.isAfter(from))
        assertEquals(29, next.dayOfMonth)
    }

    @Test fun `descriptions read naturally`() {
        assertEquals("Every day at 08:05", AutoTrigger.Daily(8, 5).describe())
        assertEquals("Every 2 hours", AutoTrigger.Every(120).describe())
        assertEquals("Every 1 hour", AutoTrigger.Every(60).describe())
        assertEquals("Every 20 minutes", AutoTrigger.Every(20).describe())
        assertEquals("Mon, Fri at 09:00", AutoTrigger.Weekly(setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY), 9, 0).describe())
    }

    // ---- rules ----

    private fun problems(name: String = "Morning", trigger: AutoTrigger = AutoTrigger.Daily(8, 0), actions: List<AutoAction> = listOf(note()), reg: ToolRegistry = registry) =
        AutomationRules.validate(name, trigger, actions, reg)

    @Test fun `a valid automation has no problems`() = assertTrue(problems().isEmpty())

    @Test fun `short and long names are rejected`() {
        assertTrue(problems(name = "x").isNotEmpty())
        assertTrue(problems(name = "x".repeat(61)).isNotEmpty())
    }

    @Test fun `interval below fifteen minutes is rejected and fifteen is accepted`() {
        assertTrue(problems(trigger = AutoTrigger.Every(14)).isNotEmpty())
        assertTrue(problems(trigger = AutoTrigger.Every(15)).isEmpty())
        assertTrue(problems(trigger = AutoTrigger.Every(24 * 60 + 1)).isNotEmpty())
    }

    @Test fun `weekly needs a day and time must be valid`() {
        assertTrue(problems(trigger = AutoTrigger.Weekly(emptySet(), 9, 0)).isNotEmpty())
        assertTrue(problems(trigger = AutoTrigger.Daily(24, 0)).isNotEmpty())
        assertTrue(problems(trigger = AutoTrigger.Daily(7, 60)).isNotEmpty())
    }

    @Test fun `no actions and too many actions are rejected`() {
        assertTrue(problems(actions = emptyList()).isNotEmpty())
        assertTrue(problems(actions = List(6) { note() }).isNotEmpty())
        assertTrue(problems(actions = List(5) { note() }).isEmpty())
    }

    @Test fun `a tool that is not on the allow list is rejected even if it is low risk`() {
        val reg = ToolRegistry(listOf(FakeTool("flashlight", Risk.LOW)))
        val p = problems(actions = listOf(AutoAction("flashlight", JsonObject(emptyMap()))), reg = reg)
        assertTrue(p.any { it.contains("cannot run on its own") }, p.toString())
    }

    @Test fun `calls and messages cannot be automated`() {
        val p = problems(actions = listOf(AutoAction("call_contact", buildJsonObject { put("name", "mom") })))
        assertTrue(p.isNotEmpty())
    }

    @Test fun `an allow listed tool that is no longer low risk is rejected`() {
        val reg = ToolRegistry(listOf(FakeTool(ProductivityTools.ADD_NOTE, Risk.MEDIUM, listOf(ParamSpec("text", ParamType.STRING, "t")))))
        val p = problems(actions = listOf(note()), reg = reg)
        assertTrue(p.any { it.contains("confirmation") }, p.toString())
    }

    @Test fun `an allow listed tool missing from the build is rejected`() {
        val p = problems(reg = ToolRegistry(emptyList()))
        assertTrue(p.any { it.contains("not available") }, p.toString())
    }

    @Test fun `arguments are validated against the tool schema`() {
        val p = problems(actions = listOf(AutoAction(ProductivityTools.ADD_NOTE, JsonObject(emptyMap()))))
        assertTrue(p.isNotEmpty())
    }

    // ---- store ----

    @Test fun `creating stores the automation with its next run`() {
        val a = saved(create(trigger = AutoTrigger.Daily(18, 0)))
        assertTrue(a.enabled)
        assertEquals(at("2026-11-10T18:00:00+05:30[Asia/Kolkata]").toInstant().toEpochMilli(), a.nextRunMs)
        assertEquals(1, store.automations.value.size)
    }

    @Test fun `a rejected automation is not stored`() {
        val r = create(trigger = AutoTrigger.Every(5))
        assertIs<AutomationResult.Rejected>(r)
        assertTrue(store.automations.value.isEmpty())
        assertNull(disk.text)
    }

    @Test fun `automations survive a restart`() {
        val a = saved(createWith(AutoTrigger.Weekly(setOf(DayOfWeek.MONDAY), 9, 15), note("a"), note("b")))
        val again = AutomationStore(disk, time).automations.value.single()
        assertEquals(a, again)
    }

    @Test fun `every trigger kind round trips`() {
        saved(create("one", AutoTrigger.Daily(7, 5)))
        saved(create("two", AutoTrigger.Every(90)))
        val again = AutomationStore(disk, time).automations.value
        assertEquals(listOf(AutoTrigger.Daily(7, 5), AutoTrigger.Every(90)), again.map { it.trigger })
    }

    @Test fun `a damaged file reads as no automations`() {
        disk.text = "{not json"
        assertTrue(AutomationStore(disk, time).automations.value.isEmpty())
    }

    @Test fun `a damaged entry is dropped and the rest kept`() {
        saved(create("keep"))
        val good = disk.text!!.trim().removePrefix("[").removeSuffix("]")
        disk.text = "[$good,{\"id\":\"x\",\"name\":\"bad\",\"trigger\":{\"type\":\"nonsense\"},\"actions\":[]}]"
        assertEquals(listOf("keep"), AutomationStore(disk, time).automations.value.map { it.name })
    }

    @Test fun `pausing clears the next run and resuming schedules from now`() {
        val a = saved(create(trigger = AutoTrigger.Daily(8, 0)))
        store.setEnabled(a.id, false, zone)
        assertNull(store.automations.value.single().nextRunMs)
        assertNull(store.nextWakeMs())
        time.now += 3 * 24 * 3600_000L
        store.setEnabled(a.id, true, zone)
        val next = store.automations.value.single().nextRunMs
        assertNotNull(next)
        assertTrue(next > time.now)
    }

    @Test fun `due only lists enabled automations whose time has come`() {
        val a = saved(create("first", AutoTrigger.Daily(18, 0)))
        val b = saved(create("second", AutoTrigger.Daily(18, 0)))
        store.setEnabled(b.id, false, zone)
        assertTrue(store.due(time.now).isEmpty())
        time.now = a.nextRunMs!!
        assertEquals(listOf("first"), store.due(time.now).map { it.name })
    }

    @Test fun `next wake is the earliest enabled run`() {
        saved(create("late", AutoTrigger.Daily(22, 0)))
        val early = saved(create("early", AutoTrigger.Daily(13, 0)))
        assertEquals(early.nextRunMs, store.nextWakeMs())
    }

    @Test fun `after a long gap an automation is rescheduled once, strictly after now`() {
        val a = saved(create(trigger = AutoTrigger.Daily(8, 0)))
        time.now += 5 * 24 * 3600_000L // phone was off for five days
        store.recordRun(a.id, true, "ok", zone)
        val next = store.automations.value.single().nextRunMs!!
        assertTrue(next > time.now)
        assertTrue(next - time.now <= 24 * 3600_000L)
        assertTrue(store.due(time.now).isEmpty())
    }

    @Test fun `history keeps only the most recent runs`() {
        val a = saved(create(trigger = AutoTrigger.Every(15)))
        repeat(25) { time.now += 1000; store.recordRun(a.id, true, "run $it", zone) }
        val h = store.automations.value.single().history
        assertEquals(AutomationStore.MAX_HISTORY, h.size)
        assertEquals("run 24", h.last().summary)
    }

    @Test fun `update changes the actions and keeps the history`() {
        val a = saved(create())
        store.recordRun(a.id, true, "ok", zone)
        val r = store.update(a.id, "Renamed", AutoTrigger.Daily(9, 0), listOf(note("new")), registry, zone)
        val updated = saved(r)
        assertEquals("Renamed", updated.name)
        assertEquals(1, updated.history.size)
    }

    @Test fun `update with an invalid action is rejected and nothing changes`() {
        val a = saved(create())
        val r = store.update(a.id, "x", AutoTrigger.Daily(9, 0), listOf(AutoAction("call_contact", JsonObject(emptyMap()))), registry, zone)
        assertIs<AutomationResult.Rejected>(r)
        assertEquals("Morning", store.automations.value.single().name)
    }

    @Test fun `delete removes it and the limit is enforced`() {
        val a = saved(create())
        store.delete(a.id)
        assertTrue(store.automations.value.isEmpty())
        repeat(AutomationRules.MAX_AUTOMATIONS) { saved(create("n$it")) }
        assertIs<AutomationResult.Rejected>(create("one too many"))
    }

    @Test fun `a failed save is reported`() {
        disk.fail = true
        saved(create())
        assertTrue(store.saveFailed)
        disk.fail = false
        saved(create("again"))
        assertFalse(store.saveFailed)
    }

    // ---- runner ----

    private fun runner(reg: ToolRegistry = registry, stored: AutomationStore = store) =
        AutomationRunner(stored, reg, FakePermissions(), InMemoryAuditLog(), time) { zone }

    @Test fun `a due automation really runs its tools and records the result`() = runTest {
        val a = saved(createWith(AutoTrigger.Daily(18, 0), note("from automation")))
        time.now = a.nextRunMs!!
        val results = runner().runDue()
        assertEquals(1, results.size)
        assertTrue(results.single().ok, results.single().summary)
        assertEquals(listOf("from automation"), planner.state.value.notes.map { it.text })
        val saved = store.automations.value.single()
        assertEquals(1, saved.history.size)
        assertTrue(saved.nextRunMs!! > time.now)
    }

    @Test fun `nothing runs before its time`() = runTest {
        saved(create(trigger = AutoTrigger.Daily(18, 0)))
        assertTrue(runner().runDue().isEmpty())
        assertTrue(planner.state.value.notes.isEmpty())
    }

    @Test fun `a notification automation shows a notification`() = runTest {
        val a = saved(createWith(AutoTrigger.Every(30), AutoAction(ProductivityTools.SHOW_NOTIFICATION, buildJsonObject { put("text", "Stretch") })))
        time.now = a.nextRunMs!!
        assertTrue(runner().runDue().single().ok)
        assertEquals(listOf("Stretch"), notifier.shown)
    }

    @Test fun `with notifications off the run is recorded as failed, not as done`() = runTest {
        notifier.allowed = false
        val a = saved(createWith(AutoTrigger.Every(30), AutoAction(ProductivityTools.SHOW_NOTIFICATION, buildJsonObject { put("text", "Stretch") })))
        time.now = a.nextRunMs!!
        val r = runner().runDue().single()
        assertFalse(r.ok)
        assertTrue(r.summary.contains("notifications", ignoreCase = true), r.summary)
        assertFalse(store.automations.value.single().lastRun!!.ok)
    }

    @Test fun `a failing action is reported as failed and the next action still runs`() = runTest {
        notifier.allowed = false
        val a = saved(createWith(AutoTrigger.Every(30), AutoAction(ProductivityTools.SHOW_NOTIFICATION, buildJsonObject { put("text", "x") }), note("still saved")))
        time.now = a.nextRunMs!!
        assertFalse(runner().runDue().single().ok)
        assertEquals(listOf("still saved"), planner.state.value.notes.map { it.text })
    }

    @Test fun `a stored automation whose tool is no longer low risk does not run`() = runTest {
        val a = saved(createWith(AutoTrigger.Daily(18, 0), note("must not run")))
        val risky = FakeTool(ProductivityTools.ADD_NOTE, Risk.HIGH, listOf(ParamSpec("text", ParamType.STRING, "t")))
        time.now = a.nextRunMs!!
        val r = runner(reg = ToolRegistry(listOf(risky))).runDue().single()
        assertFalse(r.ok)
        assertTrue(r.summary.startsWith("Not run"), r.summary)
        assertEquals(0, risky.executions)
    }

    @Test fun `a hand edited file that names a call cannot run it`() = runTest {
        val call = FakeTool("call_contact", Risk.HIGH, listOf(ParamSpec("name", ParamType.STRING, "n")))
        val reg = ToolRegistry(ProductivityTools.create(planner, Wake(), notifier, clock) + call)
        val a = saved(create(trigger = AutoTrigger.Daily(18, 0)))
        disk.text = disk.text!!.replace("add_note", "call_contact").replace("\"text\"", "\"name\"")
        val tampered = AutomationStore(disk, time)
        time.now = a.nextRunMs!!
        val r = runner(reg, tampered).runDue().single()
        assertFalse(r.ok)
        assertEquals(0, call.executions)
    }

    @Test fun `run now runs without moving the schedule`() = runTest {
        val a = saved(createWith(AutoTrigger.Daily(18, 0), note("manual")))
        val r = runner().runNow(a.id)
        assertNotNull(r)
        assertTrue(r.ok)
        assertEquals(listOf("manual"), planner.state.value.notes.map { it.text })
        assertEquals(a.nextRunMs, store.automations.value.single().nextRunMs)
        assertEquals(1, store.automations.value.single().history.size)
    }

    @Test fun `run now on an unknown id does nothing`() = runTest {
        assertNull(runner().runNow("nope"))
    }

    @Test fun `a paused automation never runs`() = runTest {
        val a = saved(create(trigger = AutoTrigger.Daily(18, 0)))
        store.setEnabled(a.id, false, zone)
        time.now += 10 * 24 * 3600_000L
        assertTrue(runner().runDue().isEmpty())
    }
}

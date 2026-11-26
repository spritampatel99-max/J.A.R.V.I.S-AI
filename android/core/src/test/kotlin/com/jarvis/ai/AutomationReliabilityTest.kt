package com.jarvis.ai

import com.jarvis.ai.ai.tools.ParamSpec
import com.jarvis.ai.ai.tools.ParamType
import com.jarvis.ai.ai.tools.Tool
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolRegistry
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.ToolSpec
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.automation.AutoAction
import com.jarvis.ai.automation.AutoTrigger
import com.jarvis.ai.automation.AutomationResult
import com.jarvis.ai.automation.AutomationRunner
import com.jarvis.ai.automation.AutomationStore
import com.jarvis.ai.automation.RepeatMode
import com.jarvis.ai.automation.ScheduleEngine
import com.jarvis.ai.core.common.TextStorage
import com.jarvis.ai.core.security.InMemoryAuditLog
import com.jarvis.ai.core.security.Risk
import com.jarvis.ai.productivity.Notifier
import com.jarvis.ai.productivity.ProductivityStore
import com.jarvis.ai.productivity.ProductivityTools
import com.jarvis.ai.productivity.WakeResult
import com.jarvis.ai.productivity.WakeScheduler
import com.jarvis.ai.update.NightlyHook
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The scheduler, runner and stores under failure: a broken stage, a repeated alarm, a restart mid-run, a damaged or full disk. */
class AutomationReliabilityTest {

    private class Disk(var text: String? = null, var fail: Boolean = false) : TextStorage {
        val backups = mutableListOf<String>()
        override fun read() = text
        override fun write(text: String) {
            if (fail) throw IOException("full")
            this.text = text
        }
        override fun backupCorrupt(text: String) {
            backups += text
        }
    }

    private class Wake(var failNext: Boolean = false) : WakeScheduler {
        val requests = mutableListOf<Long>()
        var cancelled = 0
        override fun schedule(atMs: Long): WakeResult {
            if (failNext) throw SecurityException("no exact alarms")
            requests += atMs
            return WakeResult.Scheduled(exact = true)
        }
        override fun cancel() {
            cancelled++
        }
    }

    private class Notify(var throwing: Boolean = false) : Notifier {
        val shown = mutableListOf<String>()
        override fun canNotify() = true
        override fun notify(key: String, title: String, text: String): Boolean {
            if (throwing && text == "boom") throw IllegalStateException("notification service died")
            shown += text
            return true
        }
    }

    private val zone = ZoneId.of("Asia/Kolkata")
    private val start = Instant.parse("2026-11-10T03:30:00Z") // 09:00 IST
    private val time = FakeTime(start.toEpochMilli())
    private val clock = Clock.fixed(start, zone)
    private val autoDisk = Disk()
    private val planDisk = Disk()
    private val wake = Wake()
    private val notify = Notify()
    private val planner = ProductivityStore(planDisk, time)
    private val store = AutomationStore(autoDisk, time)
    private val registry = ToolRegistry(ProductivityTools.create(planner, wake, notify, clock))

    private fun runner(reg: ToolRegistry = registry, s: AutomationStore = store) =
        AutomationRunner(s, reg, FakePermissions(), InMemoryAuditLog(), time) { zone }

    private fun engine(r: AutomationRunner = runner(), nightly: NightlyHook? = null, p: ProductivityStore = planner, s: AutomationStore = store) =
        ScheduleEngine(p, s, r, notify, wake, time, nightly = nightly)

    private fun note(text: String = "hello") = AutoAction(ProductivityTools.ADD_NOTE, buildJsonObject { put("text", text) })

    private fun create(trigger: AutoTrigger, name: String = "Job", s: AutomationStore = store) =
        (s.create(name, trigger, listOf(note()), registry, zone) as AutomationResult.Saved).automation

    private val minute = 60_000L

    // ---- the scheduler never loses its next wake-up --------------------------------------------------------------------

    @Test fun `a reminder that cannot be shown does not stop the others or the next wake-up`() = runTest {
        planner.addReminder("boom", time.now + 1_000)
        planner.addReminder("fine", time.now + 2_000)
        planner.addReminder("later", time.now + 3_600_000)
        notify.throwing = true
        time.now += 5_000
        engine().tick()
        assertEquals(listOf("fine"), notify.shown)
        assertTrue(planner.state.value.reminders.filter { it.text != "later" }.all { it.delivered })
        assertEquals(false, planner.state.value.reminders.first { it.text == "boom" }.notified, "must not claim it was shown")
        assertTrue(wake.requests.isNotEmpty(), "the wake-up for the later reminder must be requested")
    }

    @Test fun `a failing nightly review does not stop automations or the next wake-up`() = runTest {
        val a = create(AutoTrigger.Daily(18, 0))
        time.now = a.nextRunMs!!
        val e = engine(nightly = object : NightlyHook {
            override fun nextMs(nowMs: Long) = null
            override suspend fun runIfDue(nowMs: Long) = throw IllegalStateException("boom")
        })
        e.tick()
        assertEquals(1, planner.state.value.notes.size, "the automation still ran")
        assertTrue(wake.requests.isNotEmpty())
        assertNotNull(e.lastProblem)
    }

    @Test fun `the next wake-up is requested before long work and again if that work is cut off`() = runTest {
        val a = create(AutoTrigger.Daily(18, 0))
        time.now = a.nextRunMs!!
        val e = engine(nightly = object : NightlyHook {
            override fun nextMs(nowMs: Long) = null
            override suspend fun runIfDue(nowMs: Long) = throw CancellationException("ran out of time")
        })
        assertFailsWith<CancellationException> { e.tick() }
        assertTrue(wake.requests.isNotEmpty(), "a cut-off tick must still leave a wake-up scheduled")
    }

    @Test fun `Android refusing the wake-up request never makes the tick throw`() = runTest {
        planner.addReminder("later", time.now + 3_600_000)
        wake.failNext = true
        val e = engine()
        e.tick()
        assertNotNull(e.lastProblem)
    }

    @Test fun `a clean tick clears the last problem`() = runTest {
        planner.addReminder("boom", time.now + 1_000)
        notify.throwing = true
        val e = engine()
        time.now += 2_000
        e.tick()
        assertNull(e.lastProblem, "a throwing notifier is handled per reminder, not a stage failure")
    }

    // ---- no duplicate runs ---------------------------------------------------------------------------------------------

    @Test fun `the same occurrence cannot be claimed twice`() {
        val a = create(AutoTrigger.Daily(18, 0))
        time.now = a.nextRunMs!!
        assertTrue(store.claim(a.id, zone))
        assertFalse(store.claim(a.id, zone), "already running")
        store.finishRun(a.id, true, "ok")
        assertFalse(store.claim(a.id, zone), "the schedule already moved past this occurrence")
    }

    @Test fun `run now cannot start a second copy of a run that is in progress`() {
        val a = create(AutoTrigger.Daily(18, 0))
        time.now = a.nextRunMs!!
        assertTrue(store.claim(a.id, zone))
        assertFalse(store.claim(a.id, zone, scheduled = false), "a manual run must not overlap one that is running")
        store.finishRun(a.id, true, "ok")
        assertTrue(store.claim(a.id, zone, scheduled = false), "after it ends a manual run is allowed")
    }

    @Test fun `a wake-up delivered twice runs the automation once`() = runTest {
        val a = create(AutoTrigger.Daily(18, 0))
        time.now = a.nextRunMs!!
        val r = runner()
        assertEquals(1, r.runDue().size)
        assertEquals(0, r.runDue().size)
        assertEquals(1, planner.state.value.notes.size)
        assertEquals(1, store.automations.value.single().history.size)
    }

    @Test fun `a paused automation cannot be claimed`() {
        val a = create(AutoTrigger.Daily(18, 0))
        store.setEnabled(a.id, false, zone)
        time.now = a.nextRunMs!! + 1
        assertFalse(store.claim(a.id, zone))
    }

    @Test fun `an automation that is not due yet cannot be claimed`() {
        val a = create(AutoTrigger.Daily(18, 0))
        assertFalse(store.claim(a.id, zone))
    }

    // ---- interrupted runs ----------------------------------------------------------------------------------------------

    @Test fun `a run found marked as running after a restart is recorded as interrupted and not run again`() = runTest {
        val a = create(AutoTrigger.Daily(18, 0))
        time.now = a.nextRunMs!!
        assertTrue(store.claim(a.id, zone)) // saved as running; then the process "dies" here
        val reopened = AutomationStore(autoDisk, time)
        val item = reopened.automations.value.single()
        assertNull(item.runningSinceMs)
        val last = item.history.last()
        assertTrue(last.interrupted)
        assertFalse(last.ok)
        assertEquals(0, runner(s = reopened).runDue().size, "must not run the same occurrence again")
        assertEquals(0, planner.state.value.notes.size)
    }

    @Test fun `a run cut off by cancellation is recorded as interrupted`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val hanging = object : Tool {
            override val spec = ToolSpec(
                ProductivityTools.ADD_NOTE, "x", listOf(ParamSpec("text", ParamType.STRING, "t", maxLength = 100)), Risk.LOW,
                timeoutMs = 600_000L, resultFormat = "x",
            )
            override fun describe(args: ToolArgs) = "x"
            override suspend fun execute(args: ToolArgs): ToolResult {
                gate.await()
                return ToolResult.Success("done", Verification.CONFIRMED)
            }
        }
        val reg = ToolRegistry(listOf(hanging))
        val a = (store.create("Job", AutoTrigger.Daily(18, 0), listOf(note()), reg, zone) as AutomationResult.Saved).automation
        time.now = a.nextRunMs!!
        val job = launch { runner(reg).runDue() }
        runCurrent()
        job.cancel()
        job.join()
        val item = store.automations.value.single()
        assertNull(item.runningSinceMs, "the mark must be cleared")
        assertTrue(item.history.last().interrupted)
    }

    @Test fun `interrupted is saved to disk and read back`() {
        val a = create(AutoTrigger.Daily(18, 0))
        time.now = a.nextRunMs!!
        store.claim(a.id, zone)
        store.finishRun(a.id, false, "cut", interrupted = true)
        assertTrue(AutomationStore(autoDisk, time).automations.value.single().history.last().interrupted)
    }

    // ---- repeat schedule -------------------------------------------------------------------------------------------------

    @Test fun `a fixed interval stays on its grid when a run starts late`() {
        val a = create(AutoTrigger.Every(30)) // first slot 09:30
        assertEquals(start.toEpochMilli() + 30 * minute, a.nextRunMs)
        time.now = a.nextRunMs!! + 5 * minute // woke 5 minutes late
        assertTrue(store.claim(a.id, zone))
        assertEquals(start.toEpochMilli() + 60 * minute, store.automations.value.single().nextRunMs, "next slot is 10:00, not 10:05")
    }

    @Test fun `slots missed while the phone was off are skipped and the next one is on the grid`() {
        val a = create(AutoTrigger.Every(30))
        time.now = start.toEpochMilli() + 200 * minute // 12:20
        assertTrue(store.claim(a.id, zone))
        assertEquals(start.toEpochMilli() + 210 * minute, store.automations.value.single().nextRunMs, "12:30")
    }

    @Test fun `after completion counts from the moment the run started`() {
        val a = create(AutoTrigger.Every(30, RepeatMode.AFTER_COMPLETION))
        time.now = a.nextRunMs!! + 5 * minute
        assertTrue(store.claim(a.id, zone))
        assertEquals(time.now + 30 * minute, store.automations.value.single().nextRunMs)
    }

    @Test fun `the repeat mode survives saving and loading and old files default to fixed`() {
        create(AutoTrigger.Every(45, RepeatMode.AFTER_COMPLETION))
        val back = AutomationStore(autoDisk, time).automations.value.single().trigger as AutoTrigger.Every
        assertEquals(RepeatMode.AFTER_COMPLETION, back.mode)
        autoDisk.text = autoDisk.text!!.replace(",\"mode\":\"AFTER_COMPLETION\"", "")
        assertEquals(RepeatMode.FIXED_INTERVAL, (AutomationStore(autoDisk, time).automations.value.single().trigger as AutoTrigger.Every).mode)
    }

    // ---- storage ---------------------------------------------------------------------------------------------------------

    @Test fun `a damaged automations file is backed up and reported, never silently emptied`() {
        val disk = Disk("{ this is not json")
        val s = AutomationStore(disk, time)
        assertTrue(s.automations.value.isEmpty())
        assertNotNull(s.loadProblem)
        assertEquals(listOf("{ this is not json"), disk.backups)
    }

    @Test fun `a damaged planner file is backed up and reported`() {
        val disk = Disk("[1,2,3]")
        val p = ProductivityStore(disk, time)
        assertNotNull(p.loadProblem)
        assertEquals(listOf("[1,2,3]"), disk.backups)
    }

    @Test fun `unreadable entries are counted, backed up and the rest is kept`() {
        val disk = Disk("""{"tasks":[{"id":"t1","title":"ok"},{"nope":1}],"notes":[],"reminders":[]}""")
        val p = ProductivityStore(disk, time)
        assertEquals(1, p.state.value.tasks.size)
        assertTrue(p.loadProblem!!.contains("1 saved item"))
        assertEquals(1, disk.backups.size)
    }

    @Test fun `a healthy or empty file reports no problem`() {
        assertNull(AutomationStore(Disk(), time).loadProblem)
        assertNull(ProductivityStore(Disk(), time).loadProblem)
        create(AutoTrigger.Daily(8, 0))
        assertNull(AutomationStore(autoDisk, time).loadProblem)
    }

    @Test fun `any write failure marks the data as unsaved and a retry saves it`() {
        planDisk.fail = true
        planner.addTask("buy milk")
        assertTrue(planner.saveFailed)
        assertNull(planDisk.text)
        planDisk.fail = false
        assertTrue(planner.retrySave())
        assertFalse(planner.saveFailed)
        assertTrue(planDisk.text!!.contains("buy milk"))
    }

    @Test fun `a wake-up retries a failed save by itself`() = runTest {
        autoDisk.fail = true
        create(AutoTrigger.Daily(8, 0))
        assertTrue(store.saveFailed)
        autoDisk.fail = false
        engine().tick()
        assertFalse(store.saveFailed)
        assertNotNull(autoDisk.text)
    }

    @Test fun `a failing storage that throws something other than IOException is still reported as unsaved`() {
        val odd = object : TextStorage {
            override fun read(): String? = null
            override fun write(text: String) = throw IllegalStateException("odd")
        }
        val p = ProductivityStore(odd, time)
        p.addNote("x")
        assertTrue(p.saveFailed)
    }

    @Test fun `the automation store also reports an unexpected write failure as unsaved`() {
        val odd = object : TextStorage {
            override fun read(): String? = null
            override fun write(text: String) = throw IllegalStateException("odd")
        }
        val s = AutomationStore(odd, time)
        create(AutoTrigger.Daily(8, 0), s = s)
        assertTrue(s.saveFailed)
    }

    @Test fun `the wake-up is already requested while the automations are still running`() = runTest {
        var requestedWhileRunning: Boolean? = null
        val probe = object : Tool {
            override val spec = ToolSpec(
                ProductivityTools.ADD_NOTE, "x", listOf(ParamSpec("text", ParamType.STRING, "t", maxLength = 100)), Risk.LOW, resultFormat = "x",
            )
            override fun describe(args: ToolArgs) = "x"
            override suspend fun execute(args: ToolArgs): ToolResult {
                requestedWhileRunning = wake.requests.isNotEmpty()
                return ToolResult.Success("done", Verification.CONFIRMED)
            }
        }
        val reg = ToolRegistry(listOf(probe))
        val a = (store.create("Job", AutoTrigger.Daily(18, 0), listOf(note()), reg, zone) as AutomationResult.Saved).automation
        time.now = a.nextRunMs!!
        engine(runner(reg)).tick()
        assertEquals(true, requestedWhileRunning, "if the process died during the run, Android would already hold the next wake-up")
    }

    // ---- the clock or the time zone changes ------------------------------------------------------------------------------

    private val tokyo = ZoneId.of("Asia/Tokyo")

    @Test fun `a daily automation follows a new time zone`() {
        val a = create(AutoTrigger.Daily(8, 0))
        val before = a.nextRunMs!!
        assertEquals(1, store.recomputeSchedules(tokyo))
        val after = store.automations.value.first().nextRunMs!!
        assertEquals(AutoTrigger.Daily(8, 0).nextAfter(Instant.ofEpochMilli(time.now).atZone(tokyo)).toInstant().toEpochMilli(), after)
        assertTrue(after != before, "8:00 in Tokyo is a different moment from 8:00 in India")
        assertTrue(after > time.now)
    }

    @Test fun `nothing changes when the zone did not change`() {
        create(AutoTrigger.Daily(8, 0))
        assertEquals(0, store.recomputeSchedules(zone))
    }

    @Test fun `an every-N-minutes automation is pulled in when the clock is set backwards`() {
        val a = create(AutoTrigger.Every(60))
        assertEquals(start.toEpochMilli() + 60 * minute, a.nextRunMs)
        time.now -= 3 * 60 * minute // clock set three hours back: the stored time is now four hours away
        assertEquals(1, store.recomputeSchedules(zone))
        assertEquals(time.now + 60 * minute, store.automations.value.first().nextRunMs)
        assertTrue(store.due(time.now).isEmpty(), "pulling it in must not make it run at once")
    }

    @Test fun `an every-N-minutes automation that is within one interval is left alone`() {
        val a = create(AutoTrigger.Every(60))
        time.now += 10 * minute
        assertEquals(0, store.recomputeSchedules(zone))
        assertEquals(a.nextRunMs, store.automations.value.first().nextRunMs)
    }

    @Test fun `a due automation is not skipped by a clock change`() {
        val a = create(AutoTrigger.Daily(8, 0))
        time.now = a.nextRunMs!! + 5 * minute // due, and the zone also changes
        assertEquals(0, store.recomputeSchedules(tokyo))
        assertEquals(listOf(a.id), store.due(time.now).map { it.id })
    }

    @Test fun `paused and running automations are not touched by a clock change`() {
        val paused = create(AutoTrigger.Daily(8, 0), "Paused")
        store.setEnabled(paused.id, false, zone)
        val running = create(AutoTrigger.Daily(9, 0), "Running")
        time.now = running.nextRunMs!!
        assertTrue(store.claim(running.id, zone))
        val movedOn = store.automations.value.first { it.id == running.id }.nextRunMs
        time.now -= 30 * minute
        store.recomputeSchedules(tokyo)
        assertNull(store.automations.value.first { it.id == paused.id }.nextRunMs)
        assertEquals(movedOn, store.automations.value.first { it.id == running.id }.nextRunMs)
    }

    @Test fun `a clock change tick schedules again and asks Android for the new wake-up`() = runTest {
        create(AutoTrigger.Daily(8, 0))
        var current = zone
        val e = ScheduleEngine(planner, store, runner(), notify, wake, time, zone = { current })
        e.tick()
        val first = wake.requests.last()
        current = tokyo
        e.tick(clockChanged = true)
        val expected = AutoTrigger.Daily(8, 0).nextAfter(Instant.ofEpochMilli(time.now).atZone(tokyo)).toInstant().toEpochMilli()
        assertEquals(expected, wake.requests.last())
        assertTrue(wake.requests.last() != first)
    }

    @Test fun `an ordinary tick does not move schedules`() = runTest {
        val a = create(AutoTrigger.Daily(8, 0))
        val e = ScheduleEngine(planner, store, runner(), notify, wake, time, zone = { tokyo })
        e.tick()
        assertEquals(a.nextRunMs, store.automations.value.first().nextRunMs)
    }
}

package com.jarvis.ai

import com.jarvis.ai.ai.tools.ArgValidator
import com.jarvis.ai.ai.tools.Tool
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.ValidationResult
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.core.common.TextStorage
import com.jarvis.ai.core.security.Risk
import com.jarvis.ai.productivity.Notifier
import com.jarvis.ai.productivity.Priority
import com.jarvis.ai.productivity.ProductivityStore
import com.jarvis.ai.productivity.ProductivityTools
import com.jarvis.ai.productivity.WakeResult
import com.jarvis.ai.productivity.WakeScheduler
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ProductivityTest {

    private class Disk(var text: String? = null, var fail: Boolean = false) : TextStorage {
        override fun read() = text
        override fun write(text: String) {
            if (fail) throw IOException("full")
            this.text = text
        }
    }

    private class Wake(var result: WakeResult = WakeResult.Scheduled(exact = false)) : WakeScheduler {
        val asked = mutableListOf<Long>()
        override fun schedule(atMs: Long): WakeResult { asked += atMs; return result }
        override fun cancel() {}
    }

    private class Notify(var allowed: Boolean = true) : Notifier {
        override fun canNotify() = allowed
        override fun notify(key: String, title: String, text: String) = allowed
    }

    // 2026-11-10 12:00 in India (UTC+05:30).
    private val zone = ZoneId.of("Asia/Kolkata")
    private val noon = Instant.parse("2026-11-10T06:30:00Z")
    private val clock = Clock.fixed(noon, zone)
    private val time = FakeTime(noon.toEpochMilli())
    private val disk = Disk()
    private val store = ProductivityStore(disk, time)
    private val wake = Wake()
    private val notify = Notify()
    private val tools: Map<String, Tool> = ProductivityTools.create(store, wake, notify, clock).associateBy { it.spec.name }

    private fun args(tool: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): ToolArgs {
        val spec = tools.getValue(tool).spec
        val json = buildJsonObject(build)
        val checked = ArgValidator.validate(spec, json)
        return (checked as ValidationResult.Valid).args
    }

    @Test
    fun allFourToolsAreLowRiskAndNeedNoAndroidPermission() {
        assertEquals(ProductivityTools.NAMES, tools.keys)
        for (tool in tools.values) {
            assertEquals(Risk.LOW, tool.spec.risk, tool.spec.name)
            assertTrue(tool.spec.requiredPermissions.isEmpty(), tool.spec.name)
        }
    }

    @Test
    fun tasksNotesAndRemindersSurviveARestart() {
        store.addTask("Buy milk", Priority.HIGH)
        store.addNote("idea")
        store.addReminder("call back", noon.toEpochMilli() + 60_000)
        val again = ProductivityStore(disk, time).state.value
        assertEquals(listOf("Buy milk"), again.tasks.map { it.title })
        assertEquals(Priority.HIGH, again.tasks.single().priority)
        assertEquals(listOf("idea"), again.notes.map { it.text })
        assertEquals(listOf("call back"), again.reminders.map { it.text })
    }

    @Test
    fun emptyTextAndSecretsAreHandled() {
        assertNull(store.addTask("   "))
        val key = "AIzaSyA1234567890abcdefghijklmnopqrstuvw"
        val note = assertNotNull(store.addNote("key $key"))
        assertFalse(note.text.contains(key))
    }

    @Test
    fun remindersMustBeInTheFutureAndWithinAYear() {
        val now = time.nowMs()
        assertNull(store.addReminder("past", now - 1))
        assertNull(store.addReminder("now", now))
        assertNull(store.addReminder("far", now + ProductivityStore.MAX_AHEAD_MS + 1))
        assertNotNull(store.addReminder("ok", now + 1))
    }

    @Test
    fun dueRemindersAreFoundDeliveredOnceAndNextIsTheEarliestFuture() {
        val now = time.nowMs()
        val a = assertNotNull(store.addReminder("a", now + 1_000))
        val b = assertNotNull(store.addReminder("b", now + 5_000))
        assertEquals(now + 1_000, store.nextReminderMs(now))
        assertTrue(store.dueReminders(now).isEmpty())

        assertEquals(listOf("a"), store.dueReminders(now + 2_000).map { it.text })
        store.markDelivered(a.id)
        assertTrue(store.dueReminders(now + 2_000).isEmpty(), "a delivered reminder is not delivered again")
        assertEquals(now + 5_000, store.nextReminderMs(now + 2_000))
        assertNull(store.nextReminderMs(now + 6_000))
        assertEquals(listOf(b.id), store.dueReminders(now + 6_000).map { it.id })
    }

    @Test
    fun overviewSplitsOverdueTodayAndOthersUsingTheLocalDay() {
        fun endOf(date: String) = java.time.LocalDate.parse(date).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        store.addTask("late", Priority.NORMAL, endOf("2026-11-09"))
        store.addTask("today low", Priority.LOW, endOf("2026-11-10"))
        store.addTask("today high", Priority.HIGH, endOf("2026-11-10"))
        store.addTask("someday")
        val done = assertNotNull(store.addTask("finished", Priority.NORMAL, endOf("2026-11-09")))
        store.setTaskDone(done.id, true)
        store.addReminder("soon", noon.toEpochMilli() + 3_600_000)
        store.addReminder("next week", noon.toEpochMilli() + 7 * 24 * 3_600_000L)

        val o = store.overview(noon.toEpochMilli(), zone)
        assertEquals(listOf("late"), o.overdue.map { it.title })
        assertEquals(listOf("today high", "today low"), o.dueToday.map { it.title })
        assertEquals(listOf("someday"), o.otherOpen.map { it.title })
        assertEquals(listOf("soon"), o.upcomingReminders.map { it.text })
        val text = o.text(zone)
        assertTrue(text.contains("Overdue (1)") && text.contains("13:00 soon"), text)
        assertFalse(text.contains("finished") || text.contains("next week"))
    }

    @Test
    fun anEmptyOverviewSaysSoPlainly() {
        assertTrue(store.overview(noon.toEpochMilli(), zone).text(zone).startsWith("Nothing planned"))
    }

    @Test
    fun addTaskToolStoresItWithTheDueDateAndReadsItBack() = runTest {
        val result = tools.getValue("add_task").execute(args("add_task") { put("title", "Pay rent"); put("priority", "high"); put("due_date", "2026-11-12") })
        val ok = assertIs<ToolResult.Success>(result)
        assertEquals(Verification.CONFIRMED, ok.verification)
        val task = store.state.value.tasks.single()
        assertEquals(Priority.HIGH, task.priority)
        assertEquals(java.time.LocalDate.parse("2026-11-12"), java.time.Instant.ofEpochMilli(task.dueMs!!).atZone(zone).toLocalDate())
    }

    @Test
    fun addTaskToolRejectsABadDate() = runTest {
        val result = tools.getValue("add_task").execute(args("add_task") { put("title", "x"); put("due_date", "12/11/2026") })
        assertIs<ToolResult.Failure>(result)
        assertTrue(store.state.value.tasks.isEmpty())
    }

    @Test
    fun theReminderToolUsesTheNextOccurrenceOfTheTimeAndAsksAndroidToWake() = runTest {
        val result = tools.getValue("add_reminder").execute(args("add_reminder") { put("text", "stand up"); put("hour", 9); put("minute", 0) })
        val ok = assertIs<ToolResult.Success>(result)
        // It is 12:00 on 10 Nov, so 09:00 means tomorrow.
        val saved = store.state.value.reminders.single()
        assertEquals(java.time.LocalDate.parse("2026-11-11"), Instant.ofEpochMilli(saved.atMs).atZone(zone).toLocalDate())
        assertEquals(listOf(saved.atMs), wake.asked)
        assertEquals(Verification.HANDED_OFF, ok.verification, "whether the alarm fires cannot be observed")
        assertTrue(ok.message.contains("late"), "an inexact wake-up is admitted: ${ok.message}")
    }

    @Test
    fun theReminderToolSaysWhenNotificationsAreOffOrTheWakeWasRefused() = runTest {
        notify.allowed = false
        wake.result = WakeResult.Unavailable("battery saver")
        val ok = assertIs<ToolResult.Success>(
            tools.getValue("add_reminder").execute(args("add_reminder") { put("text", "x"); put("hour", 23); put("minute", 0) }),
        )
        assertTrue(ok.message.contains("notifications are off"), ok.message)
        assertTrue(ok.message.contains("did not accept"), ok.message)
    }

    @Test
    fun anExactWakeIsNotDescribedAsLate() = runTest {
        wake.result = WakeResult.Scheduled(exact = true)
        val ok = assertIs<ToolResult.Success>(
            tools.getValue("add_reminder").execute(args("add_reminder") { put("text", "x"); put("hour", 23); put("minute", 0) }),
        )
        assertFalse(ok.message.contains("late"))
    }

    @Test
    fun aReminderOnAPastDateIsRefused() = runTest {
        val result = tools.getValue("add_reminder").execute(args("add_reminder") { put("text", "x"); put("hour", 8); put("minute", 0); put("date", "2026-11-01") })
        assertIs<ToolResult.Failure>(result)
        assertTrue(store.state.value.reminders.isEmpty())
        assertTrue(wake.asked.isEmpty())
    }

    @Test
    fun aFailedSaveIsAdmittedInTheResult() = runTest {
        disk.fail = true
        val ok = assertIs<ToolResult.Success>(tools.getValue("add_note").execute(args("add_note") { put("text", "hello") }))
        assertTrue(ok.message.contains("memory only"), ok.message)
    }

    @Test
    fun overviewToolReportsTheStoredPlan() = runTest {
        store.addTask("Water plants")
        val ok = assertIs<ToolResult.Success>(tools.getValue("daily_overview").execute(ToolArgs(emptyMap())))
        assertTrue(ok.message.contains("Water plants"))
        assertEquals(Verification.CONFIRMED, ok.verification)
        assertNotNull(JsonPrimitive("unused"))
    }

    @Test
    fun aReminderRemembersWhetherAndroidWouldShowIt() {
        val shown = assertNotNull(store.addReminder("shown", time.now + 1_000))
        val hidden = assertNotNull(store.addReminder("hidden", time.now + 2_000))
        store.markDelivered(shown.id)
        store.markDelivered(hidden.id, notified = false)
        val again = ProductivityStore(disk, time).state.value.reminders
        assertTrue(again.first { it.id == shown.id }.notified)
        assertFalse(again.first { it.id == hidden.id }.notified)
        assertTrue(again.all { it.delivered })
    }
}

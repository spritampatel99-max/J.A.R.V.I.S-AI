package com.jarvis.ai

import com.jarvis.ai.ai.executor.ExecutionReport
import com.jarvis.ai.ai.executor.StepOutcome
import com.jarvis.ai.ai.executor.StepStatus
import com.jarvis.ai.ai.planner.PlanStep
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.core.common.TextStorage
import com.jarvis.ai.dashboard.ShortcutRules
import com.jarvis.ai.dashboard.ShortcutStore
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

class ShortcutTest {
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

    private val day = 24L * 60 * 60 * 1000
    private val time = FakeTime(1_800_000_000_000)
    private val disk = Disk()
    private val store = ShortcutStore(disk, time)

    private fun step(i: Int = 1) = PlanStep(i, "flashlight", "Flashlight", JsonObject(emptyMap()), critical = false)

    private fun report(status: StepStatus = StepStatus.SUCCEEDED, private: Boolean = false, extra: List<StepStatus> = emptyList()): ExecutionReport {
        val outcomes = (listOf(status) + extra).mapIndexed { i, st ->
            StepOutcome(step(i + 1), st, "m", if (st == StepStatus.SUCCEEDED) Verification.CONFIRMED else null, 1, private = private && i == 0)
        }
        return ExecutionReport("goal", outcomes, 0)
    }

    private fun ask(text: String, times: Int = 1, r: ExecutionReport = report()) = repeat(times) { store.record(text, r) }

    // ---- what is counted ---------------------------------------------------------------------------------------------------

    @Test fun `a request becomes a shortcut only after it was made twice`() {
        ask("Turn on the flashlight")
        assertTrue(store.top().isEmpty(), "once is not a habit")
        ask("turn on the flashlight!")
        assertEquals(listOf("Turn on the flashlight"), store.top().map { it.text }, "capitals and the full stop do not make it a different request")
        assertEquals(2, store.top().single().count)
    }

    @Test fun `the most asked comes first and only six are shown`() {
        for (i in 1..8) ask("Request number $i", times = 2 + i)
        val top = store.top()
        assertEquals(6, top.size)
        assertEquals("Request number 8", top.first().text)
        assertEquals(listOf(10, 9, 8, 7, 6, 5), top.map { it.count })
    }

    @Test fun `equal counts show the more recent one first`() {
        ask("Alpha request", 2)
        time.now += 1000
        ask("Beta request", 2)
        assertEquals(listOf("Beta request", "Alpha request"), store.top().map { it.text })
    }

    @Test fun `a request that failed was refused or was only partly done is not counted`() {
        for (r in listOf(report(StepStatus.FAILED), report(StepStatus.DENIED), report(StepStatus.EXPIRED), report(StepStatus.BLOCKED), report(extra = listOf(StepStatus.FAILED)))) {
            assertFalse(store.record("Turn on the flashlight", r), r.outcomes.map { it.status }.toString())
        }
        assertTrue(store.all.value.isEmpty())
    }

    @Test fun `a private answer is never counted`() {
        assertFalse(store.record("Read my notifications", report(private = true)))
        assertTrue(store.all.value.isEmpty())
    }

    @Test fun `a report with nothing in it is not counted`() {
        assertFalse(store.record("Do something", ExecutionReport("g", emptyList(), 0)))
    }

    @Test fun `text that looks like a number address link or secret is never kept`() {
        val bad = listOf(
            "Call 9876543210", "my pin is 4821 remind me", "send otp to me", "Open https://example.com/x?token=abc", "mail bob@example.com",
            "what is my password", "pay by UPI", "bank balance", "note card number 4111 1111 1111 1111", "use api key now", "Sign in to the site",
        )
        for (t in bad) {
            assertNull(ShortcutRules.normalize(t), t)
            assertFalse(store.record(t, report()), t)
        }
        assertTrue(store.all.value.isEmpty())
    }

    @Test fun `ordinary requests are kept`() {
        for (t in listOf("Turn on the flashlight", "How much battery do I have", "Call mom", "What's the weather", "Start my morning routine")) assertNotNull(ShortcutRules.normalize(t), t)
    }

    @Test fun `very short and very long requests are left out`() {
        assertNull(ShortcutRules.normalize("hi"))
        assertNull(ShortcutRules.normalize("a".repeat(81)))
        assertNotNull(ShortcutRules.normalize("a".repeat(80)))
    }

    // ---- control -----------------------------------------------------------------------------------------------------------

    @Test fun `turning learning off stops counting and keeps what was there until cleared`() {
        ask("Turn on the flashlight", 2)
        store.setLearning(false)
        assertFalse(store.learning.value)
        assertFalse(store.record("Turn on the flashlight", report()))
        assertEquals(2, store.all.value.single().count, "nothing new was counted")
        store.clear()
        assertTrue(store.all.value.isEmpty())
        assertFalse(store.learning.value, "clearing does not switch it back on")
    }

    @Test fun `one shortcut can be removed`() {
        ask("Turn on the flashlight", 2)
        ask("Call mom", 2)
        store.remove("turn on the FLASHLIGHT")
        assertEquals(listOf("Call mom"), store.all.value.map { it.text })
    }

    @Test fun `a shortcut not used for two months is no longer offered`() {
        ask("Turn on the flashlight", 3)
        time.now += 59 * day
        assertEquals(1, store.top().size)
        time.now += 2 * day
        assertTrue(store.top().isEmpty())
        assertEquals(1, store.all.value.size, "it is still listed so the person can delete it")
    }

    @Test fun `the list is capped and keeps the most used`() {
        for (i in 1..ShortcutRules.MAX_ITEMS) ask("Request number $i", times = 3)
        ask("A brand new request")
        assertEquals(ShortcutRules.MAX_ITEMS, store.all.value.size)
        assertTrue(store.all.value.none { it.text == "A brand new request" }, "a one-off does not push out something used three times")
    }

    // ---- keeping the file --------------------------------------------------------------------------------------------------

    @Test fun `counts and the on-off choice survive a restart`() {
        ask("Turn on the flashlight", 3)
        store.setLearning(false)
        val again = ShortcutStore(disk, time)
        assertEquals(3, again.all.value.single().count)
        assertFalse(again.learning.value)
        assertNull(again.loadProblem)
    }

    @Test fun `a damaged file is kept aside and reported`() {
        disk.text = "[[["
        val s = ShortcutStore(disk, time)
        assertTrue(s.all.value.isEmpty())
        assertTrue(s.learning.value)
        assertTrue(s.loadProblem!!.contains("could not be read"))
        assertEquals(listOf("[[["), disk.backups)
    }

    @Test fun `a sensitive line in an old or edited file is dropped on load`() {
        disk.text = """{"learning":true,"items":[{"text":"Call 9876543210","count":5,"last":1800000000000},{"text":"Call mom","count":5,"last":1800000000000}]}"""
        val s = ShortcutStore(disk, time)
        assertEquals(listOf("Call mom"), s.all.value.map { it.text })
    }

    @Test fun `a failed write is remembered and retried`() {
        disk.fail = true
        ask("Turn on the flashlight", 2)
        assertTrue(store.saveFailed)
        disk.fail = false
        assertTrue(store.retrySave())
        assertEquals(2, ShortcutStore(disk, time).all.value.single().count)
    }
}

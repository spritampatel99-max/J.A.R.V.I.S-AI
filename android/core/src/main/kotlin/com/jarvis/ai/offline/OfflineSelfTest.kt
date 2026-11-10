package com.jarvis.ai.offline

import com.jarvis.ai.automation.AutoTrigger
import com.jarvis.ai.core.common.TextStorage
import com.jarvis.ai.core.common.TimeSource
import com.jarvis.ai.memory.MemoryCategory
import com.jarvis.ai.memory.MemoryStore
import com.jarvis.ai.productivity.ProductivityStore
import java.time.ZoneId
import java.time.ZonedDateTime

data class SelfTestResult(val id: String, val name: String, val passed: Boolean, val detail: String)

/**
 * Real round trips with no internet: write through the given storage, then read back with a fresh store. The app passes scratch files in its
 * private cache, so the check exercises the same file-writing path as the real data without touching it.
 */
object OfflineSelfTest {

    fun run(plannerScratch: TextStorage, memoryScratch: TextStorage, time: TimeSource, zone: ZoneId = ZoneId.systemDefault()): List<SelfTestResult> = listOf(
        check("planner", "Planner storage") {
            val first = ProductivityStore(plannerScratch, time)
            val note = first.addNote("offline self-test") ?: return@check "The note was not accepted."
            if (first.saveFailed) return@check "The phone could not write the file."
            val again = ProductivityStore(plannerScratch, time)
            if (again.state.value.notes.none { it.id == note.id && it.text == "offline self-test" }) "The note was not there after reading it back." else null
        },
        check("memory", "Memory storage") {
            val first = MemoryStore(memoryScratch, time)
            val entry = first.save(null, MemoryCategory.NOTE, "offline self-test") ?: return@check "The note was not accepted."
            if (first.saveFailed) return@check "The phone could not write the file."
            val again = MemoryStore(memoryScratch, time)
            if (again.entries.value.none { it.id == entry.id && it.text == "offline self-test" }) "The note was not there after reading it back." else null
        },
        check("schedule", "Schedule calculation") {
            val now = ZonedDateTime.now(zone)
            val next = AutoTrigger.Daily(now.hour, now.minute).nextAfter(now)
            if (!next.isAfter(now)) "The next run was not in the future." else null
        },
    )

    private inline fun check(id: String, name: String, body: () -> String?): SelfTestResult = try {
        val problem = body()
        SelfTestResult(id, name, problem == null, problem ?: "Passed")
    } catch (e: java.io.IOException) {
        SelfTestResult(id, name, false, "Storage error: ${e.javaClass.simpleName}")
    } catch (e: RuntimeException) {
        SelfTestResult(id, name, false, "Unexpected ${e.javaClass.simpleName}")
    }
}

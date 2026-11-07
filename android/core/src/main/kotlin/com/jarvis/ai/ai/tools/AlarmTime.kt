package com.jarvis.ai.ai.tools

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/**
 * Android's AlarmClock.ACTION_SET_ALARM takes an hour and minute and creates an alarm for
 * the NEXT time that clock time occurs. It cannot target a specific date. These helpers
 * work out which date that will be, so the tool can refuse (instead of silently setting
 * the wrong day) when the user asked for a different date.
 */
object AlarmTime {

    fun nextOccurrence(hour: Int, minute: Int, now: ZonedDateTime): ZonedDateTime {
        require(hour in 0..23) { "hour must be 0-23" }
        require(minute in 0..59) { "minute must be 0-59" }
        val candidate = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        return if (candidate.isAfter(now)) candidate else candidate.plusDays(1)
    }

    /** @return null when the alarm will land on the requested date (or none was requested). */
    fun dateMismatch(next: ZonedDateTime, requested: LocalDate?): String? {
        if (requested == null || next.toLocalDate() == requested) return null
        return "The system Clock app can only set the next occurrence of that time " +
            "(${next.toLocalDate()}), not $requested."
    }

    fun parseDate(raw: String): LocalDate? = try {
        LocalDate.parse(raw.trim())
    } catch (e: DateTimeParseException) {
        null
    }
}

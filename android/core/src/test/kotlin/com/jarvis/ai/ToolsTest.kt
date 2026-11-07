package com.jarvis.ai

import com.jarvis.ai.ai.tools.AlarmTime
import com.jarvis.ai.ai.tools.ArgValidator
import com.jarvis.ai.ai.tools.ParamSpec
import com.jarvis.ai.ai.tools.ParamType
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolRegistry
import com.jarvis.ai.ai.tools.ToolSpec
import com.jarvis.ai.ai.tools.ValidationResult
import com.jarvis.ai.ai.tools.WebLinks
import com.jarvis.ai.core.security.Risk
import java.net.URI
import java.net.URLDecoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ArgValidatorTest {
    private val spec = ToolSpec(
        name = "demo",
        description = "demo",
        params = listOf(
            ParamSpec("hour", ParamType.INT, "h", min = 0, max = 23),
            ParamSpec("label", ParamType.STRING, "l", required = false, maxLength = 10),
            ParamSpec("mode", ParamType.ENUM, "m", enumValues = listOf("on", "off")),
            ParamSpec("flag", ParamType.BOOLEAN, "f", required = false),
        ),
        risk = Risk.LOW,
        resultFormat = "x",
    )

    private fun valid(raw: JsonObject): ToolArgs = assertIs<ValidationResult.Valid>(ArgValidator.validate(spec, raw)).args
    private fun errors(raw: JsonObject): List<String> = assertIs<ValidationResult.Invalid>(ArgValidator.validate(spec, raw)).errors

    @Test
    fun acceptsCorrectArgumentsAndNormalisesEnums() {
        val args = valid(buildJsonObject { put("hour", 6); put("mode", "ON"); put("label", "  wake  ") })
        assertEquals(6, args.int("hour"))
        assertEquals("on", args.string("mode"))
        assertEquals("wake", args.string("label"))
        assertNull(args.booleanOrNull("flag"))
    }

    @Test
    fun reportsEveryProblemAtOnce() {
        val problems = errors(buildJsonObject { put("hour", 99); put("mode", "sideways") })
        assertEquals(2, problems.size)
        assertTrue(problems.any { it.contains("at most 23") })
        assertTrue(problems.any { it.contains("one of: on, off") })
    }

    @Test
    fun rejectsMissingRequiredAndWrongTypes() {
        assertTrue(errors(buildJsonObject { put("mode", "on") }).single().contains("Missing required parameter 'hour'"))
        assertTrue(errors(buildJsonObject { put("hour", "six"); put("mode", "on") }).single().contains("whole number"))
        assertTrue(errors(buildJsonObject { put("hour", 1.5); put("mode", "on") }).single().contains("whole number"))
        assertTrue(errors(buildJsonObject { put("hour", 1); put("mode", "on"); put("flag", "maybe") }).single().contains("true or false"))
    }

    @Test
    fun rejectsNestedValuesAndOverlongText() {
        val nested = buildJsonObject { put("hour", 1); put("mode", "on"); put("label", buildJsonArray { }) }
        assertTrue(errors(nested).single().contains("single value"))
        assertTrue(errors(buildJsonObject { put("hour", 1); put("mode", "on"); put("label", "x".repeat(11)) }).single().contains("too long"))
    }

    @Test
    fun blankOptionalValuesCountAsNotProvided() {
        val args = valid(buildJsonObject { put("hour", 1); put("mode", "on"); put("label", "   ") })
        assertNull(args.stringOrNull("label"))
        val withNull = valid(buildJsonObject { put("hour", 1); put("mode", "on"); put("label", JsonNull) })
        assertNull(withNull.stringOrNull("label"))
    }

    @Test
    fun unknownKeysNeverReachTheTool() {
        val args = valid(buildJsonObject { put("hour", 1); put("mode", "on"); put("shell", "rm -rf /") })
        assertFalse(args.asMap().containsKey("shell"))
    }
}

class ToolArgsFingerprintTest {
    @Test
    fun sameToolAndArgumentsGiveTheSameFingerprint() {
        val a = ToolArgs(mapOf("path" to "a.txt", "force" to true))
        val b = ToolArgs(mapOf("force" to true, "path" to "a.txt"))
        assertEquals(a.fingerprint("delete_file"), b.fingerprint("delete_file"))
    }

    @Test
    fun anyChangeToToolOrArgumentsChangesIt() {
        val base = ToolArgs(mapOf("path" to "a.txt")).fingerprint("delete_file")
        assertNotEquals(base, ToolArgs(mapOf("path" to "b.txt")).fingerprint("delete_file"))
        assertNotEquals(base, ToolArgs(mapOf("path" to "a.txt")).fingerprint("move_file"))
        assertNotEquals(base, ToolArgs(mapOf("path" to "a.txt", "extra" to 1)).fingerprint("delete_file"))
    }

    @Test
    fun separatorCharactersCannotMakeTwoDifferentCallsCollide() {
        val x = ToolArgs(mapOf("a" to "1|b=2")).fingerprint("t")
        val y = ToolArgs(mapOf("a" to "1", "b" to "2")).fingerprint("t")
        assertNotEquals(x, y)
    }
}

class ToolRegistryTest {
    @Test
    fun duplicateNamesAreRejected() {
        assertFailsWith<IllegalArgumentException> { ToolRegistry(listOf(FakeTool("same"), FakeTool("same"))) }
    }

    @Test
    fun onlyRegisteredToolsAreFound() {
        val registry = ToolRegistry(listOf(FakeTool("flashlight")))
        assertNotNull(registry.get("flashlight"))
        assertNull(registry.get("shell"))
    }

    @Test
    fun catalogListsToolsAndParameters() {
        val registry = ToolRegistry(
            listOf(FakeTool("set_alarm", params = listOf(ParamSpec("hour", ParamType.INT, "Hour", min = 0, max = 23)))),
        )
        val catalog = registry.promptCatalog()
        assertTrue(catalog.contains("set_alarm"))
        assertTrue(catalog.contains("hour (whole number 0-23, required)"))
    }

    @Test
    fun badSpecsFailFast() {
        assertFailsWith<IllegalArgumentException> { FakeTool("Not Snake Case") }
        assertFailsWith<IllegalArgumentException> { ParamSpec("Bad", ParamType.STRING, "x") }
        assertFailsWith<IllegalArgumentException> { ParamSpec("mode", ParamType.ENUM, "x") }
    }
}

class AlarmTimeTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 11, 6, h, m, 0, 0, zone)

    @Test
    fun laterTodayStaysToday() {
        val next = AlarmTime.nextOccurrence(18, 30, at(9, 17))
        assertEquals(LocalDate.of(2026, 11, 6), next.toLocalDate())
    }

    @Test
    fun earlierTimeRollsToTomorrow() {
        val next = AlarmTime.nextOccurrence(6, 0, at(9, 17))
        assertEquals(LocalDate.of(2026, 11, 7), next.toLocalDate())
        assertEquals(6, next.hour)
    }

    @Test
    fun exactlyNowRollsToTomorrow() {
        assertEquals(LocalDate.of(2026, 11, 7), AlarmTime.nextOccurrence(9, 17, at(9, 17)).toLocalDate())
    }

    @Test
    fun aDifferentRequestedDateIsReported() {
        val next = AlarmTime.nextOccurrence(6, 0, at(9, 17))
        assertNull(AlarmTime.dateMismatch(next, LocalDate.of(2026, 11, 7)))
        assertNull(AlarmTime.dateMismatch(next, null))
        assertNotNull(AlarmTime.dateMismatch(next, LocalDate.of(2026, 11, 9)))
    }

    @Test
    fun parsesOnlyIsoDates() {
        assertEquals(LocalDate.of(2026, 11, 7), AlarmTime.parseDate(" 2026-11-07 "))
        assertNull(AlarmTime.parseDate("tomorrow"))
        assertNull(AlarmTime.parseDate("07/11/2026"))
    }

    @Test
    fun invalidClockTimesAreRejected() {
        assertFailsWith<IllegalArgumentException> { AlarmTime.nextOccurrence(24, 0, at(9, 0)) }
        assertFailsWith<IllegalArgumentException> { AlarmTime.nextOccurrence(1, 60, at(9, 0)) }
    }
}

class WebLinksTest {
    @Test
    fun bareDomainsGetHttps() {
        assertEquals("https://example.com/", WebLinks.normalizeWebUrl("example.com")?.toString())
    }

    @Test
    fun keepsHttpAndHttpsAsGiven() {
        assertEquals("http://example.com/a", WebLinks.normalizeWebUrl("http://example.com/a")?.toString())
        assertEquals("https://example.com/a?b=1", WebLinks.normalizeWebUrl("https://example.com/a?b=1")?.toString())
    }

    @Test
    fun refusesEveryOtherScheme() {
        assertNull(WebLinks.normalizeWebUrl("javascript:alert(1)"))
        assertNull(WebLinks.normalizeWebUrl("file:///etc/passwd"))
        assertNull(WebLinks.normalizeWebUrl("content://contacts/people"))
        assertNull(WebLinks.normalizeWebUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(WebLinks.normalizeWebUrl("tel:+911234567890"))
    }

    @Test
    fun refusesCredentialsWhitespaceAndGarbage() {
        assertNull(WebLinks.normalizeWebUrl("https://bank.com@evil.example/login"))
        assertNull(WebLinks.normalizeWebUrl("example.com/a b"))
        assertNull(WebLinks.normalizeWebUrl("   "))
        assertNull(WebLinks.normalizeWebUrl("https://" + "a".repeat(2_100) + ".com"))
    }

    @Test
    fun youtubeQueryIsEncodedAsASingleParameter() {
        val uri = URI(WebLinks.youtubeSearchUrl("lofi music & chill=1"))
        assertEquals("www.youtube.com", uri.host)
        // The raw query must hold exactly one parameter: the "&" and "=" in the text are escaped.
        val rawParams = uri.rawQuery.split('&')
        assertEquals(1, rawParams.size)
        val (name, value) = rawParams.single().split('=', limit = 2)
        assertEquals("search_query", name)
        assertEquals("lofi music & chill=1", URLDecoder.decode(value, "UTF-8"))
    }
}

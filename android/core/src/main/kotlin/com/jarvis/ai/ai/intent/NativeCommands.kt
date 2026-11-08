package com.jarvis.ai.ai.intent

import com.jarvis.ai.ai.planner.PlanStep
import java.time.Clock
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Plain-language commands the app can run itself, with no AI model involved.
 *
 * This is the first stop of the router ("native tools first"): a request that is clearly one
 * of the built-in actions runs straight away, so flashlight, alarm, YouTube, website and
 * battery commands keep working with no provider configured, no internet and no local model.
 *
 * The matcher is deliberately strict. It only accepts a whole sentence of a known shape, so
 * "how do I set an alarm?" or "tell me about flashlights" is NOT treated as a command and goes
 * on to the language model. When a command is clear but a detail is missing in a way that
 * matters (6 AM or 6 PM?), it asks instead of guessing. Whatever it matches still goes through
 * the same executor as model-planned steps: argument validation, permission checks,
 * confirmation for sensitive actions, audit log and honest result reporting.
 */
object NativeCommands {

    sealed interface Match {
        /** Run these steps now. */
        data class Run(val steps: List<PlanStep>) : Match

        /** A command was recognised but needs one more detail; show this question. */
        data class Ask(val question: String) : Match
    }

    private sealed interface One {
        data class Step(val tool: String, val description: String, val params: JsonObject) : One

        data class Ask(val question: String) : One
    }

    /** @return null when the text is not a command this matcher knows (the model should handle it). */
    fun match(text: String, clock: Clock = Clock.systemDefaultZone()): Match? {
        val whole = clean(text)
        if (whole.isEmpty() || whole.length > MAX_LENGTH) return null

        single(whole, clock)?.let { return toMatch(listOf(it)) }

        val parts = SPLIT.split(whole).map { clean(it) }.filter { it.isNotEmpty() }
        if (parts.size < 2 || parts.size > MAX_PARTS) return null
        val matched = parts.map { single(it, clock) ?: return null }
        return toMatch(matched)
    }

    private fun toMatch(items: List<One>): Match {
        items.filterIsInstance<One.Ask>().firstOrNull()?.let { return Match.Ask(it.question) }
        val steps = items.filterIsInstance<One.Step>().mapIndexed { i, s ->
            PlanStep(index = i + 1, tool = s.tool, description = s.description, parameters = s.params, critical = false)
        }
        return Match.Run(steps)
    }

    private fun single(text: String, clock: Clock): One? =
        flashlight(text) ?: youtube(text) ?: alarm(text, clock) ?: website(text) ?: deviceInfo(text)

    // ---- flashlight --------------------------------------------------------------------------------

    private val FLASH_PATTERNS = listOf(
        Regex("""^(?:turn|switch|put) (on|off) (?:the |my )?(?:flash ?light|torch)$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:turn|switch) (?:the |my )?(?:flash ?light|torch) (on|off)$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:flash ?light|torch) (on|off)$""", RegexOption.IGNORE_CASE),
    )
    private val FLASH_ENABLE = Regex("""^(enable|disable) (?:the |my )?(?:flash ?light|torch)$""", RegexOption.IGNORE_CASE)

    private fun flashlight(text: String): One? {
        for (pattern in FLASH_PATTERNS) {
            val state = pattern.matchEntire(text)?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: continue
            return flash(state)
        }
        val word = FLASH_ENABLE.matchEntire(text)?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: return null
        return flash(if (word == "enable") "on" else "off")
    }

    private fun flash(state: String): One =
        One.Step("flashlight", "Turn the flashlight $state", buildJsonObject { put("state", state) })

    // ---- YouTube -------------------------------------------------------------------------------------

    private val YT_OPEN = Regex("""^(?:open|launch|start|go to) (?:the )?youtube(?: app)?$""", RegexOption.IGNORE_CASE)
    private val YT_SEARCH = listOf(
        Regex("""^(?:search|find|look up|look for) (?:for )?(.{1,200}?) (?:on|in) youtube$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:search|find|look up|look for) youtube for (.{1,200})$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:play|watch) (.{1,200}?) (?:on|in) youtube$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:open|launch) youtube (?:and )?(?:search|find|look up|look for) (?:for )?(.{1,200})$""", RegexOption.IGNORE_CASE),
    )

    private fun youtube(text: String): One? {
        if (YT_OPEN.matches(text)) return One.Step("open_youtube", "Open YouTube", JsonObject(emptyMap()))
        for (pattern in YT_SEARCH) {
            val query = pattern.matchEntire(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            return One.Step(
                "open_youtube",
                "Search YouTube for \"${query.take(80)}\"",
                buildJsonObject { put("query", query) },
            )
        }
        return null
    }

    // ---- alarm -----------------------------------------------------------------------------------------

    private val ALARM_AT = listOf(
        Regex("""^(?:set|create|add|make|start) (?:me )?(?:(?:an?|my|the) )?alarm (?:for|at|to|on) (.+)$""", RegexOption.IGNORE_CASE),
        Regex("""^alarm (?:for|at) (.+)$""", RegexOption.IGNORE_CASE),
        Regex("""^wake me(?: up)? (?:at|by) (.+)$""", RegexOption.IGNORE_CASE),
    )
    private val ALARM_IN = listOf(
        Regex("""^(?:set|create|add|make) (?:(?:an?|my) )?alarm (?:in|after) (\d{1,3}) (minutes?|mins?|hours?|hrs?)$""", RegexOption.IGNORE_CASE),
        Regex("""^wake me(?: up)? in (\d{1,3}) (minutes?|mins?|hours?|hrs?)$""", RegexOption.IGNORE_CASE),
    )
    private val CLOCK_TIME = Regex(
        """^(?:(tomorrow|today) )?(?:at |for )?(?:(noon|midnight)|(\d{1,2})(?:[:. ](\d{2}))?\s?(a\.?m\.?|p\.?m\.?)?(?: (?:in the |at )?(morning|afternoon|evening|night))?)(?: (tomorrow|today))?$""",
        RegexOption.IGNORE_CASE,
    )

    private fun alarm(text: String, clock: Clock): One? {
        for (pattern in ALARM_IN) {
            val groups = pattern.matchEntire(text)?.groupValues ?: continue
            val amount = groups[1].toIntOrNull() ?: return null
            val hours = groups[2].lowercase(Locale.ROOT).startsWith("h")
            val minutes = if (hours) amount * 60L else amount.toLong()
            if (minutes < 1 || minutes > MAX_RELATIVE_MINUTES) return null
            val target = ZonedDateTime.now(clock).plusMinutes(minutes)
            return alarmStep(target.hour, target.minute, null)
        }
        for (pattern in ALARM_AT) {
            val rest = pattern.matchEntire(text)?.groupValues?.get(1)?.trim() ?: continue
            return alarmAt(rest, clock)
        }
        return null
    }

    private fun alarmAt(rest: String, clock: Clock): One? {
        val g = CLOCK_TIME.matchEntire(rest)?.groupValues ?: return null
        val dayWord = (g[1].ifEmpty { g[7] }).lowercase(Locale.ROOT)
        val word = g[2].lowercase(Locale.ROOT)
        val hourText = g[3]
        val minute = if (g[4].isEmpty()) 0 else g[4].toInt()
        val marker = g[5].lowercase(Locale.ROOT).firstOrNull()
        val period = g[6].lowercase(Locale.ROOT)

        val hour24: Int
        var minute24 = minute
        when {
            word == "noon" -> { hour24 = 12; minute24 = 0 }
            word == "midnight" -> { hour24 = 0; minute24 = 0 }
            hourText.isEmpty() -> return null
            else -> {
                val hour = hourText.toInt()
                if (minute > 59) return null
                val partOfDay = when (period) {
                    "morning" -> 'a'
                    "afternoon", "evening", "night" -> 'p'
                    else -> null
                }
                val m = marker ?: partOfDay
                if (m != null) {
                    if (hour !in 1..12) return null
                    hour24 = when {
                        period == "night" && marker == null && hour == 12 -> 0
                        m == 'a' -> hour % 12
                        else -> hour % 12 + 12
                    }
                } else if (hour in 13..23 || hour == 0 || (hourText.length == 2 && hourText.startsWith("0"))) {
                    hour24 = hour
                } else if (hour in 1..12) {
                    val shown = "%d:%02d".format(Locale.ROOT, hour, minute)
                    return One.Ask("Is that $shown AM or PM? Say it again with AM or PM, for example \"set an alarm for $shown PM\".")
                } else {
                    return null
                }
            }
        }

        val date = when (dayWord) {
            "today" -> LocalDate.now(clock)
            "tomorrow" -> LocalDate.now(clock).plusDays(1)
            else -> null
        }
        return alarmStep(hour24, minute24, date)
    }

    private fun alarmStep(hour: Int, minute: Int, date: LocalDate?): One {
        val shown = "%02d:%02d".format(Locale.ROOT, hour, minute)
        return One.Step(
            "set_alarm",
            "Set an alarm for $shown",
            buildJsonObject {
                put("hour", hour)
                put("minute", minute)
                if (date != null) put("date", date.toString())
            },
        )
    }

    // ---- websites ----------------------------------------------------------------------------------------

    private val OPEN_SITE = Regex(
        """^(?:open|go to|visit|browse to|browse|launch|navigate to) (?:the )?(?:website |site |page |url |link )?(\S{3,300})$""",
        RegexOption.IGNORE_CASE,
    )
    private val DOMAIN = Regex("""^(?:https?://)?(?:[a-z0-9-]+\.)+([a-z]{2,24})(?:[:/?#]\S*)?$""", RegexOption.IGNORE_CASE)

    /** Looks like a file name, not a web address. Only checked when no scheme was typed. */
    private val FILE_EXTENSIONS = setOf(
        "txt", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "json", "xml", "md", "py", "kt", "js", "java",
        "png", "jpg", "jpeg", "gif", "mp3", "mp4", "zip", "apk", "exe", "log", "html", "htm",
    )

    private fun website(text: String): One? {
        val target = OPEN_SITE.matchEntire(text)?.groupValues?.get(1) ?: return null
        val tld = DOMAIN.matchEntire(target)?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: return null
        val hasScheme = target.startsWith("http://", true) || target.startsWith("https://", true)
        if (!hasScheme && tld in FILE_EXTENSIONS) return null
        return One.Step(
            "open_url",
            "Open ${target.take(80)} in the browser",
            buildJsonObject { put("url", target) },
        )
    }

    // ---- device status -----------------------------------------------------------------------------------

    private val INFO_PATTERNS = listOf(
        Regex(
            """^(?:what(?:'s| is) )?(?:my |the )?(?:phone |device )?(?:battery(?: level| percentage| percent| status| charge)?|storage(?: space)?|device info(?:rmation)?|phone info(?:rmation)?|phone status|device status)$""",
            RegexOption.IGNORE_CASE,
        ),
        Regex("""^how much (?:battery|storage)(?: space)?(?: do i have| is left| is free| left| do i have left)?$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:check|show|show me|tell me) (?:my |the )?(?:battery|storage|phone status|device info|phone info)$""", RegexOption.IGNORE_CASE),
    )

    private fun deviceInfo(text: String): One? =
        if (INFO_PATTERNS.any { it.matches(text) }) {
            One.Step("get_device_info", "Read device status", JsonObject(emptyMap()))
        } else {
            null
        }

    // ---- text clean-up -----------------------------------------------------------------------------------

    private val SPLIT = Regex("""\s*(?:,\s*(?:and\s+)?then\s+|\s+and then\s+|\s+then\s+|\s+and\s+|\s*;\s*|\s*,\s*)""", RegexOption.IGNORE_CASE)
    private val PREFIXES = listOf(
        "hey jarvis ", "ok jarvis ", "okay jarvis ", "jarvis, ", "jarvis ", "please ", "can you ", "could you ",
        "would you ", "will you ", "i want you to ", "i'd like you to ",
    )
    private val SUFFIXES = listOf(" please", " for me", " right now", " now")

    private fun clean(raw: String): String {
        var t = raw.trim().replace(Regex("""\s+"""), " ").trimEnd('.', '!', '?', ' ', ',', ';')
        var changed = true
        while (changed) {
            changed = false
            for (prefix in PREFIXES) {
                if (t.length > prefix.length && t.startsWith(prefix, ignoreCase = true)) {
                    t = t.substring(prefix.length).trimStart()
                    changed = true
                }
            }
            for (suffix in SUFFIXES) {
                if (t.length > suffix.length && t.endsWith(suffix, ignoreCase = true)) {
                    t = t.substring(0, t.length - suffix.length).trimEnd()
                    changed = true
                }
            }
        }
        return t
    }

    private const val MAX_LENGTH = 400
    private const val MAX_PARTS = 5
    private const val MAX_RELATIVE_MINUTES = 24 * 60L
}

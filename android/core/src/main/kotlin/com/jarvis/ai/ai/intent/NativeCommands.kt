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
        // A message's words belong to the person: they are matched before the generic clean-up, which would trim a
        // trailing "now" or "please" and the punctuation off the end of what they want to say.
        val lead = leadClean(text)
        if (lead.isNotEmpty() && lead.length <= MAX_LENGTH) (whatsappCount(lead) ?: whatsappRead(lead) ?: messaging(lead) ?: email(lead) ?: typeText(lead))?.let { return toMatch(listOf(it)) }

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
        flashlight(text) ?: youtube(text) ?: camera(text) ?: timer(text) ?: media(text) ?: alarm(text, clock) ?: website(text) ?: deviceInfo(text) ?: time(text) ?: weather(text) ?: calls(text) ?: phone(text) ?: offline(text) ?: openApp(text)

    // ---- weather ---------------------------------------------------------------------------------------------------
    //
    // A plain weather question is answered straight from the weather service, with no AI call, so it is fast and works without a key.

    private const val CITY = """[\p{L}][\p{L}\p{M} .'\-]{1,40}?"""
    private const val WHEN = """(?:today|tomorrow|tonight|now|currently)"""
    private const val LEAD = """(?:(?:tell me|give me|show me|check|get|find|fetch) )?(?:(?:what(?:'s| is)|how(?:'s| is)) )?(?:the )?"""
    private const val NOUN = """(?:weather|temperature|forecast)"""
    private val WEATHER_IN = Regex(
        """^$LEAD(?:(?:today|tomorrow|tonight)(?:'s)? )?$NOUN(?: (?:forecast|report|update|like|outside))?(?: $WHEN)? (?:in|at|for|of|near) ($CITY)(?: $WHEN)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val CITY_WEATHER = Regex("""^$LEAD($CITY) $NOUN(?: (?:forecast|report))?(?: $WHEN)?$""", RegexOption.IGNORE_CASE)
    private val WEATHER_NO_CITY = Regex(
        """^$LEAD(?:(?:today|tomorrow|tonight)(?:'s)? )?$NOUN(?: (?:forecast|report|update|like|outside))?(?: (?:$WHEN|here|outside))?$""",
        RegexOption.IGNORE_CASE,
    )
    private val NOT_A_CITY = setOf(
        "the", "what", "whats", "what's", "how", "hows", "how's", "today", "tomorrow", "tonight", "is", "my", "this", "that", "current",
        "tell", "give", "show", "me", "check", "get", "find", "fetch", "like", "outside", "here", "now", "weather", "temperature", "forecast",
    )

    private fun weather(text: String): One? {
        val tomorrow = Regex("""\btomorrow\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val named = (WEATHER_IN.matchEntire(text) ?: CITY_WEATHER.matchEntire(text))?.groupValues?.get(1)?.trim()
        val city = named?.takeIf { c -> NAME_LIKE.matches(c) && c.split(' ').none { it.lowercase(Locale.ROOT) in NOT_A_CITY } }
        if (city != null) {
            return One.Step(
                "get_weather",
                "Weather in ${city.take(40)}${if (tomorrow) " tomorrow" else ""}",
                buildJsonObject {
                    put("city", city)
                    put("day", if (tomorrow) "tomorrow" else "today")
                },
            )
        }
        if (WEATHER_NO_CITY.matches(text)) return One.Ask("Which city? For example, say \"weather in Delhi\".")
        return null
    }

    // ---- open an app -------------------------------------------------------------------------------------
    //
    // "open WhatsApp", "launch the camera app". Checked after the more specific commands (YouTube, web addresses, flashlight, alarms).
    // The app is looked up on the phone when the step runs; nothing is guessed here.

    private val OPEN_APP = Regex("""^(?:open|launch|start|run|go to|switch to) (?:up )?(?:the |my )?([\p{L}\p{N}][\p{L}\p{M}\p{N} .&'+\-]{0,38}?)(?: app| application)?$""", RegexOption.IGNORE_CASE)
    private val NOT_AN_APP = setOf(
        "a", "an", "some", "this", "that", "it", "up", "new", "door", "window", "file", "files", "link", "page", "tab", "website", "web",
        "site", "browser tab", "everything", "all", "me", "us", "them", "something", "anything", "one", "two", "three",
    )

    private val NOT_PART_OF_AN_APP_NAME = setOf("door", "window", "tab", "form", "file", "files", "folder", "link", "page", "website", "account")

    private fun openApp(text: String): One? {
        val name = OPEN_APP.matchEntire(text)?.groupValues?.get(1)?.trim() ?: return null
        val words = name.lowercase(Locale.ROOT).split(' ')
        if (name.lowercase(Locale.ROOT) in NOT_AN_APP || words.first() in NOT_AN_APP || words.size > 4 || words.any { it in NOT_PART_OF_AN_APP_NAME }) return null
        if ('.' in name && name.any { it.isLetter() } && !name.contains(' ')) return null
        return One.Step("open_app", "Open $name", buildJsonObject { put("name", name) })
    }

    // ---- phone calls -------------------------------------------------------------------------------------
    //
    // These only turn words into a step. A call is a high-risk tool, so the app always shows the exact name and number and
    // waits for a tap before anything is dialled, however the request arrived (typed or spoken).

    private val CALL_TARGET = Regex("""^(?:call|phone|ring|dial) (.{1,60})$""", RegexOption.IGNORE_CASE)
    private val NUMBER_LIKE = Regex("""^[+\d*#][\d\s\-().*#+]*$""")
    private val NAME_LIKE = Regex("""^[\p{L}][\p{L}\p{M} .'\-]{0,39}$""")
    private val NOT_A_NAME = setOf(
        "me", "us", "it", "them", "back", "a", "an", "the", "someone", "somebody", "anyone", "anybody", "everyone", "everybody",
        "of", "off", "out", "up", "on", "to", "for", "in", "about",
    )
    private val JOINERS = Regex("""\b(?:and|then|or)\b""", RegexOption.IGNORE_CASE)

    private val ANSWER = Regex(
        """^(?:answer|pick up|accept|take) (?:the |this |that |my |an? )?(?:incoming |ringing )?(?:phone )?(?:call|phone)$""",
        RegexOption.IGNORE_CASE,
    )
    private val HANG_UP = Regex("""^(?:hang ?up)(?: the| this| my| that)?(?: phone| call)?(?: now)?$""", RegexOption.IGNORE_CASE)
    private val END_CALL = Regex(
        """^(?:end|cut|reject|decline|disconnect|drop) (?:the |this |that |my )?(?:incoming |ringing |current |active )?(?:phone )?call$""",
        RegexOption.IGNORE_CASE,
    )

    private fun calls(text: String): One? {
        if (ANSWER.matches(text)) return One.Step("answer_call", "Answer the ringing call", JsonObject(emptyMap()))
        if (HANG_UP.matches(text) || END_CALL.matches(text)) return One.Step("end_call", "Hang up the call", JsonObject(emptyMap()))

        var target = CALL_TARGET.matchEntire(text)?.groupValues?.get(1)?.trim() ?: return null
        target = target.removeSuffix(" please").removeSuffix(" now").trim()
        if (target.isEmpty()) return null

        // Anything with digits or the characters * # + is a number, never a name, so carrier codes reach the number
        // checks (which refuse them) instead of being searched for in the contacts.
        if (target.any { it.isDigit() || it == '*' || it == '#' || it == '+' }) {
            if (!NUMBER_LIKE.matches(target)) return null
            return One.Step("call_number", "Call $target", buildJsonObject { put("number", target) })
        }

        val name = target.removePrefix("my ").trim()
        val words = name.split(' ')
        if (words.size > 4 || words.first().lowercase(Locale.ROOT) in NOT_A_NAME || !NAME_LIKE.matches(name)) return null
        // "call mom and dad" is two people; leave it to the planner so each call is confirmed on its own.
        if (JOINERS.containsMatchIn(name)) return null
        return One.Step("call_contact", "Call $name", buildJsonObject { put("name", name) })
    }

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
        Regex("""^(?:open|launch) youtube (?:and )?(?:search|find|look up|look for) (?:for )?(.{1,200})$""", RegexOption.IGNORE_CASE),
    )

    // "play Tum Hi Ho on YouTube", "open YouTube and play Tum Hi Ho (and tap on the song)", and the same in Hinglish.
    private const val YT_TAIL = """(?: and (?:then )?(?:tap|click|press|select|choose)(?: on)? (?:the |that |it |first |top )*(?:song|video|result|one)?)?"""
    private const val YT_PAR = """(?:youtube|यूट्यूब) (?:par|pe|me|mein|पर|पे|में)"""
    private const val YT_DO = """(?:chalao|chala do|chalaiye|play karo|play kar do|bajao|baja do|laga do|lagao|चलाओ|चला दो|बजाओ|बजा दो|लगाओ|लगा दो)"""
    private val YT_PLAY = listOf(
        Regex("""^(?:play|watch|put on) (.{1,200}?) (?:on|in|from) youtube$YT_TAIL$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:open|launch|go to|start) (?:the )?youtube(?: app)?(?: and| then)? (?:play|watch|put on) (.{1,200}?)$YT_TAIL$""", RegexOption.IGNORE_CASE),
        Regex("""^youtube(?: me)? (?:play|watch) (.{1,200}?)$YT_TAIL$""", RegexOption.IGNORE_CASE),
        Regex("""^$YT_PAR (.{1,200}?) $YT_DO$""", RegexOption.IGNORE_CASE),
        Regex("""^(.{1,200}?) $YT_PAR $YT_DO$""", RegexOption.IGNORE_CASE),
    )

    private fun youtube(text: String): One? {
        if (YT_OPEN.matches(text)) return One.Step("open_youtube", "Open YouTube", JsonObject(emptyMap()))
        for (pattern in YT_PLAY) {
            val query = pattern.matchEntire(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            return One.Step(
                "play_on_youtube",
                "Play \"${query.take(80)}\" on YouTube",
                buildJsonObject { put("query", query) },
            )
        }
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

    // ---- the phone itself: screen, navigation, notifications -------------------------------------------------------------
    //
    // Whole-phrase matches only. These run the tools in ScreenTools; if the matching Android access is off, the tool says so.

    private val NAV_PATTERNS = listOf(
        Regex("""^(?:go |press |navigate )?back$""", RegexOption.IGNORE_CASE) to "back",
        Regex("""^(?:go (?:to )?(?:the )?home(?: screen)?|press home|(?:show |open )?(?:the )?home screen)$""", RegexOption.IGNORE_CASE) to "home",
        Regex("""^(?:open |show )?(?:the )?(?:recent apps|recents|recent screen|app switcher)$""", RegexOption.IGNORE_CASE) to "recents",
        Regex("""^(?:open|show|pull down|pull down the) (?:the )?(?:notification (?:shade|panel|drawer|tray)|notifications|notifications panel)$""", RegexOption.IGNORE_CASE) to "notifications",
        Regex("""^(?:(?:open|show) (?:the )?)?quick settings$""", RegexOption.IGNORE_CASE) to "quick_settings",
        Regex("""^lock (?:the |my )?(?:phone|screen|device)$""", RegexOption.IGNORE_CASE) to "lock",
        Regex("""^(?:(?:take|capture) (?:a )?)?screenshot$""", RegexOption.IGNORE_CASE) to "screenshot",
    )
    private val SCROLL = Regex("""^(?:scroll|page) (up|down)$""", RegexOption.IGNORE_CASE)
    private val READ_SCREEN_PATTERNS = listOf(
        Regex("""^(?:read|read out|read aloud) (?:the |this |my )?(?:screen|page)(?: out| aloud)?$""", RegexOption.IGNORE_CASE),
        Regex("""^read this(?: out)?(?: to me)?$""", RegexOption.IGNORE_CASE),
        Regex("""^what(?:'s| is) on (?:the |my )?screen$""", RegexOption.IGNORE_CASE),
        Regex("""^what am i looking at$""", RegexOption.IGNORE_CASE),
    )
    private val READ_NOTIFICATION_PATTERNS = listOf(
        Regex("""^(?:read|read out|check|tell me) (?:my |the |all )*(?:new )?notifications$""", RegexOption.IGNORE_CASE),
        Regex("""^what(?:'s| is| are) (?:my |the )?(?:new )?notifications$""", RegexOption.IGNORE_CASE),
        Regex("""^what notifications do i have$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:do i have|any|have i got) (?:any )?(?:new )?notifications$""", RegexOption.IGNORE_CASE),
    )
    // "tap Chats", "press the search button", "type hello". Whole phrases only; the tools refuse send/pay/delete-style labels and are
    // off until the person switches tapping and typing on, and they say so when they are.
    private val TAP = Regex("""^(?:tap|click|touch|press)(?: on)?(?: the)? (.{1,40}?)(?: (?:button|tab|icon|option))?$""", RegexOption.IGNORE_CASE)
    private val TYPE = Regex("""^type (?:in |out )?(.{1,300})$""", RegexOption.IGNORE_CASE)
    /** The words are the person's own, so this is matched on the front-cleaned text and keeps their punctuation and ending. */
    private fun typeText(text: String): One? {
        val words = TYPE.matchEntire(text)?.groupValues?.get(1)?.trim()?.trim('"', '“', '”')?.trim().orEmpty()
        return if (words.isEmpty()) null else One.Step("type_text", "Type the words", buildJsonObject { put("text", words) })
    }

    private val CLEAR_NOTIFICATIONS = Regex("""^(?:clear|dismiss|remove) (?:all |my |the )*notifications$""", RegexOption.IGNORE_CASE)

    private val UPDATE_PATTERNS = listOf(
        Regex("""^(?:check|look) for (?:an? )?(?:app |jarvis )?updates?$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:is there|are there) (?:an? )?(?:new )?(?:update|version)(?: (?:for|of) jarvis)?$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:update|upgrade) (?:jarvis|yourself|the app)(?: now)?$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:am i|are you) up to date$""", RegexOption.IGNORE_CASE),
    )

    private fun phone(text: String): One? {
        if (UPDATE_PATTERNS.any { it.matches(text) }) return One.Step("check_for_updates", "Check for a newer version", JsonObject(emptyMap()))
        NAV_PATTERNS.firstOrNull { it.first.matches(text) }?.let { (_, action) ->
            return One.Step("phone_navigate", "Phone: ${action.replace('_', ' ')}", buildJsonObject { put("action", action) })
        }
        SCROLL.matchEntire(text)?.let { match ->
            val direction = match.groupValues[1].lowercase(Locale.ROOT)
            return One.Step("screen_scroll", "Scroll $direction", buildJsonObject { put("direction", direction) })
        }
        if (READ_SCREEN_PATTERNS.any { it.matches(text) }) return One.Step("read_screen", "Read the text on the screen", JsonObject(emptyMap()))
        if (READ_NOTIFICATION_PATTERNS.any { it.matches(text) }) return One.Step("read_notifications", "Read the current notifications", JsonObject(emptyMap()))
        if (CLEAR_NOTIFICATIONS.matches(text)) return One.Step("clear_notifications", "Dismiss all notifications", JsonObject(emptyMap()))
        TAP.matchEntire(text)?.let { m ->
            val label = m.groupValues[1].trim().trim('"', '“', '”', '.', ',').trim()
            if (label.isNotEmpty()) return One.Step("tap_on_screen", "Tap $label", buildJsonObject { put("label", label) })
        }
        return null
    }

    // ---- time and date -----------------------------------------------------------------------------------
    //
    // Whole-phrase matches only, so "what time is the meeting" or "what time does the shop open" still go to the assistant.

    private val TIME_PATTERNS = listOf(
        Regex("""^what(?:'s| is) the (?:current |local )?time(?: today)?$""", RegexOption.IGNORE_CASE),
        Regex("""^what time is it(?: today)?$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:tell me |give me |show me )?the (?:current |local )?time$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:current|local) time$""", RegexOption.IGNORE_CASE),
        Regex("""^time$""", RegexOption.IGNORE_CASE),
        Regex("""^what(?:'s| is) (?:the |today's |todays )?(?:date|day)(?: today)?$""", RegexOption.IGNORE_CASE),
        Regex("""^what (?:day|date) is it(?: today)?$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:tell me |give me |show me )?(?:today's|todays|the) (?:date|day)$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:today's|todays) date$""", RegexOption.IGNORE_CASE),
        Regex("""^date today$""", RegexOption.IGNORE_CASE),
    )

    private fun time(text: String): One? =
        if (TIME_PATTERNS.any { it.matches(text) }) {
            One.Step("get_time", "Read the time and date", JsonObject(emptyMap()))
        } else {
            null
        }

    // ---- offline library ---------------------------------------------------------------------------------------------
    //
    // Only an explicit "offline" request is answered from the saved library without asking an AI. An ordinary "who is ..." question
    // goes to the assistant as before; the app falls back to the library only when no AI service can be reached.

    private const val OFFLINE = """(?:offline library|offline wikipedia|offline encyclopedia|offline pack|offline data|offline)"""
    private val OFFLINE_PATTERNS = listOf(
        Regex("""^(?:search|look up|lookup|find|check|ask|read)(?: up)? (?:the |my )?$OFFLINE(?: (?:for|about|on))? (.+)$""", RegexOption.IGNORE_CASE),
        Regex("""^$OFFLINE[:,]? (.+)$""", RegexOption.IGNORE_CASE),
        Regex("""^(.+?) (?:in|from|on|using|with) (?:the |my )?$OFFLINE$""", RegexOption.IGNORE_CASE),
    )

    private val OFFLINE_VERB = Regex("""^(?:look up|lookup|search for|search|find|check|read up on|ask about)\s+""", RegexOption.IGNORE_CASE)

    private fun offline(text: String): One? {
        for (p in OFFLINE_PATTERNS) {
            val raw = p.matchEntire(text)?.groupValues?.get(1)?.trim() ?: continue
            val bare = raw.replace(OFFLINE_VERB, "")
            val topic = (com.jarvis.ai.knowledge.OfflineQuestions.subject(bare) ?: bare).trim().trim('"', '\'')
            if (topic.isEmpty() || topic.length > 80) return null
            return One.Step("offline_lookup", "Look up \"${topic.take(40)}\" offline", buildJsonObject { put("topic", topic) })
        }
        return null
    }

    // ---- messages ---------------------------------------------------------------------------------------------------
    //
    // "text mom saying I'm late" becomes a send_sms step. Like a call, a message is a high-risk tool: the app shows the exact
    // name, number and words and waits for a tap, and the messages app then opens with the text typed in for the person to send.
    // The recipient and the words are separated by a joining word ("saying", "that", "with", a colon), so the matcher never has to
    // guess where a name ends; "text mom I'm late" is therefore not matched and is left to the planner.

    private const val SAYING = """(?:\s+(?:saying|that says|that|with the message|with the text|with)\s+|\s*:\s*)"""
    private const val WA = """what'?s ?app"""
    private const val NOUN_SMS = """(?:text message|text|sms|message)"""
    private const val SEND_LEAD = """(?:(?:send|write|compose|type) (?:an? |my )?)?"""

    private val WHATSAPP_GO_SEND = Regex(
        """^(?:go to|open) $WA(?: and)? (?:send|write|message|text)(?: an? message)?(?: to)? (.{1,60}?)$SAYING(.{1,1000})$""",
        RegexOption.IGNORE_CASE,
    )

    private val WHATSAPP_PATTERNS = listOf(
        Regex("""^$SEND_LEAD$WA(?: message| text)?(?: to)? (.{1,60}?)$SAYING(.{1,1000})$""", RegexOption.IGNORE_CASE),
        Regex("""^$SEND_LEAD$NOUN_SMS (?:to )?(.{1,60}?) (?:on|via|using|through|in) $WA$SAYING(.{1,1000})$""", RegexOption.IGNORE_CASE),
        Regex("""^send (.{1,60}?) an? $WA(?: message| text)?$SAYING(.{1,1000})$""", RegexOption.IGNORE_CASE),
        WHATSAPP_GO_SEND,
    )
    // "go to WhatsApp and read my unread messages", "any new WhatsApp messages". Whole-phrase matches only, and checked before the
    // message-writing patterns so "read WhatsApp unread" is never taken for a message to someone called "unread".
    private val WHATSAPP_READ = listOf(
        Regex(
            """^(?:(?:go to|open) $WA(?: and)? )?(?:read|read out|check|tell me|show me)(?: me)? (?:(?:my|the|all|new|unread) )*""" +
                """(?:$WA(?: (?:unread|new))?(?: (?:messages?|chats?|inbox|notifications?))?|(?:unread|new) (?:messages?|inbox|chats?)(?: (?:on|in|from) $WA)?)$""",
            RegexOption.IGNORE_CASE,
        ),
        Regex("""^(?:do i have|have i got|are there|any)(?: any)? (?:new |unread )*$WA(?: messages?| chats?)?$""", RegexOption.IGNORE_CASE),
        Regex("""^what(?:'s| is| are)(?: my| the)? (?:new|unread)(?: messages?)?(?: on| in) $WA$""", RegexOption.IGNORE_CASE),
        Regex("""^what(?:'s| is) new (?:on|in) $WA$""", RegexOption.IGNORE_CASE),
    )

    // "go to WhatsApp and tell me how many inbox chats are unread": the screen is opened and looked at, not the notification shade.
    private val WA_NAME = Regex("""what'?s ?app|whatsap|व्हाट्सएप|व्हाट्सऐप|व्हाट्सअप""", RegexOption.IGNORE_CASE)
    private val HOW_MANY = Regex("""\bhow many\b|\bcount\b|\bnumber of\b|\bkitne\b|\bkitni\b|कितने|कितनी""", RegexOption.IGNORE_CASE)
    private val UNREADISH = Regex("""\bunread\b|\bunseen\b|\bnew\b|\binbox\b|\bpending\b|\bwaiting\b|\bnot read\b|अपठित""", RegexOption.IGNORE_CASE)
    private val HAS_MESSAGE_BODY = Regex("""\b(?:saying|that says|with the message|with the text)\b|:""", RegexOption.IGNORE_CASE)

    private fun whatsappCount(text: String): One? =
        if (text.length <= 160 && WA_NAME.containsMatchIn(text) && HOW_MANY.containsMatchIn(text) && UNREADISH.containsMatchIn(text) && !HAS_MESSAGE_BODY.containsMatchIn(text)) {
            One.Step("count_whatsapp_unread", "Open WhatsApp and count the unread chats", JsonObject(emptyMap()))
        } else {
            null
        }

    private fun whatsappRead(text: String): One? =
        if (WHATSAPP_READ.any { it.matches(text) }) {
            One.Step("read_whatsapp_unread", "Read the unread WhatsApp messages", JsonObject(emptyMap()))
        } else {
            null
        }

    private val SMS_PATTERNS = listOf(
        Regex("""^$SEND_LEAD$NOUN_SMS(?: to)? (.{1,60}?)$SAYING(.{1,1000})$""", RegexOption.IGNORE_CASE),
        Regex("""^send (.{1,60}?) an? $NOUN_SMS$SAYING(.{1,1000})$""", RegexOption.IGNORE_CASE),
    )
    private val MESSAGE_NO_WORDS = Regex("""^$SEND_LEAD(?:(?:$WA)(?: message| text)?|$NOUN_SMS)(?: to)? (.{1,60})$""", RegexOption.IGNORE_CASE)

    /** Only the front of the sentence is cleaned (a wake word, "please", "can you"); the person's own words stay as spoken. */
    private fun leadClean(raw: String): String {
        var t = raw.trim().replace(Regex("""[ \t]+"""), " ")
        var changed = true
        while (changed) {
            changed = false
            for (prefix in PREFIXES) {
                if (t.length > prefix.length && t.startsWith(prefix, ignoreCase = true)) {
                    t = t.substring(prefix.length).trimStart()
                    changed = true
                }
            }
        }
        return t
    }

    /** A name of up to four words or a number; null when it is anything else (several people, "me", a carrier code). */
    private fun recipient(rawTarget: String): String? {
        var target = rawTarget.trim().trim(',', '.').removeSuffix(" please").trim()
        if (target.isEmpty()) return null
        if (target.any { it.isDigit() || it == '*' || it == '#' || it == '+' }) return target.takeIf { NUMBER_LIKE.matches(it) }
        target = target.removePrefix("my ").trim()
        val words = target.split(' ')
        if (words.size > 4 || words.first().lowercase(Locale.ROOT) in NOT_A_NAME || !NAME_LIKE.matches(target)) return null
        if (JOINERS.containsMatchIn(target)) return null
        return target
    }

    private fun messaging(text: String): One? {
        for ((patterns, tool, what) in listOf(Triple(WHATSAPP_PATTERNS, "send_whatsapp", "WhatsApp"), Triple(SMS_PATTERNS, "send_sms", "Text"))) {
            for (p in patterns) {
                val m = p.matchEntire(text) ?: continue
                val to = recipient(m.groupValues[1]) ?: continue
                val body = m.groupValues[2].trim().trim('"', '“', '”').trim()
                if (body.isEmpty()) continue
                return One.Step(
                    tool,
                    "$what $to",
                    buildJsonObject {
                        put("to", to)
                        put("message", body)
                    },
                )
            }
        }
        // "text mom" with nothing to say: ask for the words instead of guessing them.
        // Only a single-word name or a number counts: "text mom I'm late" has no joining word, so it is left to the planner.
        val bare = MESSAGE_NO_WORDS.matchEntire(text)?.groupValues?.get(1)
        val to = bare?.let { recipient(it) }?.takeIf { ' ' !in it || it.all { c -> c.isDigit() || c == ' ' || c == '+' || c == '-' || c == '(' || c == ')' } } ?: return null
        val kind = if (Regex(WA, RegexOption.IGNORE_CASE).containsMatchIn(text)) "WhatsApp" else "text"
        return One.Ask("What should the message to $to say? Say it in one go, for example: $kind $to saying I'll be there in ten minutes.")
    }

    // ---- email -----------------------------------------------------------------------------------------------------
    //
    // "email sam@example.com saying I'll be late" becomes a compose_email step. Like a message, an email is a high-risk tool: the
    // app shows the exact address and words and waits for a tap, and the email app then opens with the draft for the person to
    // send. The address has to be typed out: the app cannot look up a contact's email address and does not guess one, so a
    // name is answered with a question.

    private const val EMAIL_ADDR = """[^\s@<>,;:"()\[\]\\]{1,64}@[^\s@<>,;:"()\[\]\\]{1,190}"""
    private const val EMAIL_WORD = """e-?mail"""
    private const val EMAIL_LEAD = """(?:(?:send|write|compose|draft|type) (?:an? |my |a new )?)?"""
    private val EMAIL_WITH_WORDS = listOf(
        Regex("""^$EMAIL_LEAD$EMAIL_WORD(?: to)? ($EMAIL_ADDR)$SAYING(.{1,3000})$""", RegexOption.IGNORE_CASE),
        Regex("""^send ($EMAIL_ADDR) an? $EMAIL_WORD$SAYING(.{1,3000})$""", RegexOption.IGNORE_CASE),
    )
    private val EMAIL_ADDRESS_ONLY = Regex("""^(?:(?:send|write|compose|draft|type) (?:an? |my |a new )?)?$EMAIL_WORD(?: to)? ($EMAIL_ADDR)$""", RegexOption.IGNORE_CASE)
    private val EMAIL_NAME_ONLY = listOf(
        Regex("""^(?:send|write|compose|draft|type) (?:an? |my |a new )?$EMAIL_WORD to ([^@]{1,60}?)(?:$SAYING.{1,3000})?$""", RegexOption.IGNORE_CASE),
        Regex("""^$EMAIL_WORD ([^@]{1,60}?)$SAYING.{1,3000}$""", RegexOption.IGNORE_CASE),
    )
    private val EMAIL_BARE = Regex("""^(?:send|write|compose|draft|type) (?:an? |my |a new )?$EMAIL_WORD$""", RegexOption.IGNORE_CASE)
    private const val EMAIL_ASK = "Who is the email to, and what should it say? I need the full address, for example: email name@example.com saying I'll be late."

    private fun email(text: String): One? {
        for (p in EMAIL_WITH_WORDS) {
            val m = p.matchEntire(text) ?: continue
            val body = m.groupValues[2].trim().trim('"', '“', '”').trim()
            if (body.isEmpty()) continue
            val to = m.groupValues[1].trim()
            return One.Step(
                "compose_email",
                "Email $to",
                buildJsonObject {
                    put("to", to)
                    put("body", body)
                },
            )
        }
        EMAIL_ADDRESS_ONLY.matchEntire(text)?.let { m ->
            val to = m.groupValues[1].trim()
            return One.Step("compose_email", "Email $to", buildJsonObject { put("to", to) })
        }
        for (p in EMAIL_NAME_ONLY) {
            val name = p.matchEntire(text)?.groupValues?.get(1)?.trim()?.trim(',', '.') ?: continue
            val words = name.split(' ')
            if (words.size <= 4 && words.first().lowercase(Locale.ROOT) !in NOT_A_NAME && NAME_LIKE.matches(name)) {
                return One.Ask("I can't look up ${name.take(40)}'s email address, so I need the full address. For example: email name@example.com saying I'll be late.")
            }
        }
        if (EMAIL_BARE.matches(text)) return One.Ask(EMAIL_ASK)
        return null
    }

    // ---- camera ----------------------------------------------------------------------------------------------------
    //
    // "take a photo" opens the camera app; the person presses the shutter. Nothing is captured by the app itself.

    private val CAMERA_PHOTO = Regex(
        """^(?:(?:open|start|launch|fire up)(?: up)? (?:the |my )?camera(?: app)?(?: (?:in|for) (?:photo|picture)s?(?: mode)?)?|(?:take|click|snap|capture) (?:a |another |one )?(?:photo|picture|pic)(?: for me)?)$""",
        RegexOption.IGNORE_CASE,
    )
    private val CAMERA_VIDEO = Regex(
        """^(?:(?:record|shoot|take|capture) (?:a |another )?video(?: for me)?|(?:open|start|launch)(?: up)? (?:the |my )?(?:video (?:camera|recorder)|camera (?:in|for) video(?: mode)?))$""",
        RegexOption.IGNORE_CASE,
    )

    private fun camera(text: String): One? = when {
        CAMERA_VIDEO.matches(text) -> One.Step("open_camera", "Open the camera for a video", buildJsonObject { put("mode", "video") })
        CAMERA_PHOTO.matches(text) -> One.Step("open_camera", "Open the camera for a photo", buildJsonObject { put("mode", "photo") })
        else -> null
    }

    // ---- timers ----------------------------------------------------------------------------------------------------
    //
    // "set a timer for 10 minutes" starts a countdown in the Clock app. Only a whole sentence that is a timer is matched.

    private val DURATION_PART = Regex(
        """(\d{1,5}(?:\.\d)?|an?|half an?|half) ?(hours?|hrs?|hr|h|minutes?|mins?|min|m|seconds?|secs?|sec|s)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val DURATION_FILLER = Regex("""(?:,|\band\b|\s)+""", RegexOption.IGNORE_CASE)
    private val DURATION_PHRASES = mapOf("an hour and a half" to 5400, "a minute and a half" to 90, "a quarter of an hour" to 900, "an hour and a quarter" to 4500)

    /** Seconds for a spoken length like "10 minutes", "1 hour 30 minutes" or "half an hour"; null if it is anything else. */
    private fun duration(raw: String): Int? {
        val text = raw.trim().lowercase(Locale.ROOT).removePrefix("for ").removePrefix("of ").trim()
        DURATION_PHRASES[text]?.let { return it }
        val parts = DURATION_PART.findAll(text).toList()
        if (parts.isEmpty()) return null
        if (DURATION_PART.replace(text, " ").replace(DURATION_FILLER, "").isNotEmpty()) return null
        var seconds = 0.0
        for (part in parts) {
            val amount = when (val a = part.groupValues[1]) {
                "a", "an" -> 1.0
                "half", "half a", "half an" -> 0.5
                else -> a.toDoubleOrNull() ?: return null
            }
            val unit = part.groupValues[2][0]
            seconds += amount * when (unit) {
                'h' -> 3600
                'm' -> 60
                else -> 1
            }
        }
        val whole = Math.round(seconds).toInt()
        return whole.takeIf { it in 1..MAX_TIMER_SECONDS }
    }

    private val TIMER_PATTERNS = listOf(
        Regex("""^(?:set|start|create|make|begin)(?: me)?(?: (?:an?|the))? timer (?:for|of) (.+)$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:set|start|create|make|begin)(?: me)?(?: (?:an?|the))? (.+?) timer$""", RegexOption.IGNORE_CASE),
        Regex("""^timer (?:for )?(.+)$""", RegexOption.IGNORE_CASE),
        Regex("""^(.+?) timer$""", RegexOption.IGNORE_CASE),
    )

    private fun timer(text: String): One? {
        for (p in TIMER_PATTERNS) {
            val seconds = p.matchEntire(text)?.groupValues?.get(1)?.let { duration(it) } ?: continue
            return One.Step("set_timer", "Start a timer", buildJsonObject { put("seconds", seconds) })
        }
        return null
    }

    // ---- media ------------------------------------------------------------------------------------------------------

    private const val MEDIA_NOUN = """(?:music|song|songs|track|video|playback|media|playing)"""
    private val MEDIA_PATTERNS: List<Pair<Regex, String>> = listOf(
        Regex("""^(?:play|unpause)(?: the)?(?: $MEDIA_NOUN)?$""", RegexOption.IGNORE_CASE) to "play",
        Regex("""^(?:resume|continue)(?: the)? $MEDIA_NOUN$""", RegexOption.IGNORE_CASE) to "play",
        Regex("""^pause(?: the| this)?(?: $MEDIA_NOUN)?$""", RegexOption.IGNORE_CASE) to "pause",
        Regex("""^stop (?:the |this )?$MEDIA_NOUN$""", RegexOption.IGNORE_CASE) to "stop",
        Regex("""^(?:play )?(?:the )?next (?:song|track|video)$""", RegexOption.IGNORE_CASE) to "next",
        Regex("""^skip(?: to)?(?: the| this| that)? (?:next )?(?:song|track|video)$""", RegexOption.IGNORE_CASE) to "next",
        Regex("""^(?:play )?(?:the )?(?:previous|last) (?:song|track|video)$""", RegexOption.IGNORE_CASE) to "previous",
        Regex("""^go back (?:a|one|to the previous) (?:song|track|video)$""", RegexOption.IGNORE_CASE) to "previous",
        Regex("""^(?:volume up|sound up|louder|turn it up|make it louder|make (?:the )?(?:music|sound) louder)$""", RegexOption.IGNORE_CASE) to "volume_up",
        Regex("""^(?:turn|crank|pump|bring) (?:the |my )?(?:volume|sound|music) up$""", RegexOption.IGNORE_CASE) to "volume_up",
        Regex("""^(?:turn up|raise|increase|boost) (?:the |my )?(?:volume|sound|music)$""", RegexOption.IGNORE_CASE) to "volume_up",
        Regex("""^(?:volume down|sound down|quieter|softer|turn it down|make it (?:quieter|softer)|make (?:the )?(?:music|sound) (?:quieter|softer))$""", RegexOption.IGNORE_CASE) to "volume_down",
        Regex("""^(?:turn|bring) (?:the |my )?(?:volume|sound|music) down$""", RegexOption.IGNORE_CASE) to "volume_down",
        Regex("""^(?:turn down|lower|reduce|decrease) (?:the |my )?(?:volume|sound|music)$""", RegexOption.IGNORE_CASE) to "volume_down",
    )

    private fun media(text: String): One? {
        for ((p, action) in MEDIA_PATTERNS) {
            if (p.matches(text)) return One.Step("media_control", "Media: ${action.replace('_', ' ')}", buildJsonObject { put("action", action) })
        }
        return null
    }

    // ---- text clean-up -----------------------------------------------------------------------------------

    private val SPLIT = Regex("""\s*(?:,\s*(?:and\s+)?then\s+|\s+and then\s+|\s+then\s+|\s+and\s+|\s*;\s*|\s*,\s*)""", RegexOption.IGNORE_CASE)
    private val PREFIXES = listOf(
        "hey jarvis, ", "hello jarvis, ", "hi jarvis, ", "hey jarvis ", "hello jarvis ", "hi jarvis ", "ok jarvis ", "okay jarvis ", "jarvis, ", "jarvis ", "please ", "can you ", "could you ",
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
    private const val MAX_TIMER_SECONDS = 24 * 3600
}

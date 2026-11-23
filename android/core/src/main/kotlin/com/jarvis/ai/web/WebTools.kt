package com.jarvis.ai.web

import com.jarvis.ai.ai.tools.ParamSpec
import com.jarvis.ai.ai.tools.ParamType
import com.jarvis.ai.ai.tools.Tool
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolError
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.ToolSpec
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.core.security.Risk

/**
 * Tools that fetch information from the internet and hand the answer back to the chat, so the person is never sent to a web page.
 * They only read; nothing here changes the phone, so they carry low risk. Text that comes from outside is marked untrusted, which
 * makes the executor ask for confirmation at medium risk and above for the rest of that request.
 */
object WebTools {
    const val GET_WEATHER = "get_weather"
    const val WIKI_LOOKUP = "wiki_lookup"
    const val WEB_ANSWER = "web_answer"

    val NAMES = setOf(GET_WEATHER, WIKI_LOOKUP, WEB_ANSWER)

    fun create(
        weather: WeatherClient,
        wiki: WikipediaClient,
        temperatureUnit: () -> TemperatureUnit = { TemperatureUnit.CELSIUS },
        grounded: GroundedSearch,
    ): List<Tool> = listOf(GetWeatherTool(weather, temperatureUnit), WikiLookupTool(wiki), WebAnswerTool(grounded))
}

class GetWeatherTool(
    private val client: WeatherClient,
    private val temperatureUnit: () -> TemperatureUnit = { TemperatureUnit.CELSIUS },
) : Tool {
    override val spec = ToolSpec(
        name = WebTools.GET_WEATHER,
        description = "Get the current weather or today's or tomorrow's forecast for a named city. Use it for any weather question. " +
            "If the person did not say which city, ask them instead of guessing.",
        params = listOf(
            ParamSpec("city", ParamType.STRING, "City name, for example Delhi", maxLength = 80),
            ParamSpec("day", ParamType.ENUM, "Which day", required = false, enumValues = listOf("today", "tomorrow")),
        ),
        risk = Risk.LOW,
        timeoutMs = 25_000L,
        resultFormat = "The weather as a short sentence, written by the app from the service's numbers.",
    )

    override fun describe(args: ToolArgs): String = "Look up the weather in ${args.string("city").take(60)}"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val tomorrow = args.stringOrNull("day").equals("tomorrow", ignoreCase = true)
        return when (val outcome = client.report(args.string("city"), tomorrow)) {
            is WeatherOutcome.Found -> ToolResult.Success(outcome.report.sentence(temperatureUnit()), Verification.CONFIRMED, answer = true)
            WeatherOutcome.NoSuchPlace -> ToolResult.Failure(ToolError.NeedsInput("I could not find a place called \"${safeLabel(args.string("city"))}\". Which city do you mean?"))
            is WeatherOutcome.Unavailable -> ToolResult.Failure(ToolError.Unavailable(outcome.reason))
        }
    }
}

class WikiLookupTool(private val client: WikipediaClient) : Tool {
    override val spec = ToolSpec(
        name = WebTools.WIKI_LOOKUP,
        description = "Look up a person, place, thing or concept on Wikipedia and give its short summary. Use it for questions about who or " +
            "what something is. For news, scores, prices or anything that changes by the day, use web_answer instead.",
        params = listOf(ParamSpec("topic", ParamType.STRING, "What to look up, for example Taj Mahal", maxLength = 100)),
        risk = Risk.LOW,
        timeoutMs = 25_000L,
        resultFormat = "A short Wikipedia summary, marked as outside content.",
    )

    override fun describe(args: ToolArgs): String = "Look up \"${args.string("topic").take(60)}\" on Wikipedia"

    override suspend fun execute(args: ToolArgs): ToolResult =
        when (val outcome = client.summary(args.string("topic"))) {
            is WikiOutcome.Found -> {
                val s = outcome.summary
                val heading = if (s.description != null) "${s.title} (${s.description})" else s.title
                ToolResult.Success("$heading: ${s.extract}\n(Source: Wikipedia)", Verification.CONFIRMED, untrusted = true, answer = true)
            }
            WikiOutcome.NotFound -> ToolResult.Failure(ToolError.Failed("Wikipedia has no page I could match to \"${safeLabel(args.string("topic"), 40)}\"."))
            is WikiOutcome.Unavailable -> ToolResult.Failure(ToolError.Unavailable(outcome.reason))
        }
}

class WebAnswerTool(private val search: GroundedSearch) : Tool {
    override val spec = ToolSpec(
        name = WebTools.WEB_ANSWER,
        description = "Answer a question that needs current information from the internet: news, sports scores, prices, schedules, " +
            "recent events, opening hours. Pass the person's question as written. Do not use it for weather (use get_weather).",
        params = listOf(ParamSpec("question", ParamType.STRING, "The question to look up", maxLength = 300)),
        risk = Risk.LOW,
        timeoutMs = 40_000L,
        resultFormat = "A short answer found with Google Search, marked as outside content.",
    )

    override fun describe(args: ToolArgs): String = "Search the web for \"${args.string("question").take(80)}\""

    override suspend fun execute(args: ToolArgs): ToolResult =
        when (val outcome = search.answer(args.string("question"))) {
            is GroundedOutcome.Answered -> {
                val sources = if (outcome.answer.sources.isEmpty()) "" else "\n(Sources: ${outcome.answer.sources.joinToString(", ")}, found with Google Search)"
                ToolResult.Success(outcome.answer.text + sources, Verification.CONFIRMED, untrusted = true, answer = true)
            }
            is GroundedOutcome.Unavailable -> ToolResult.Failure(ToolError.Unavailable(outcome.reason))
        }
}

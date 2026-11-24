package com.jarvis.ai.ai.tools.builtin

import com.jarvis.ai.ai.tools.ParamSpec
import com.jarvis.ai.ai.tools.ParamType
import com.jarvis.ai.ai.tools.Tool
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolError
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.ToolSpec
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.core.security.Risk
import java.util.Locale
import kotlin.random.Random

/** Tools that work entirely on the phone: no network, no other app, nothing to confirm. */

class CalculateTool : Tool {
    override val spec = ToolSpec(
        name = "calculate",
        description = "Work out arithmetic: plus, minus, times, divide, powers, percentages, brackets and square roots, for example \"15 percent of 2400\" or \"(2 + 3) * 4\".",
        params = listOf(ParamSpec("expression", ParamType.STRING, "The sum, as the person says or writes it", maxLength = 160)),
        risk = Risk.LOW,
        timeoutMs = 3_000L,
        resultFormat = "CONFIRMED: the computed result. Nothing is run except arithmetic.",
    )

    override fun describe(args: ToolArgs): String = "Calculate ${args.string("expression").take(60)}"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val expression = args.string("expression")
        return when (val outcome = Calculator.evaluate(expression)) {
            is Calculator.Outcome.Value -> ToolResult.Success(
                "${expression.trim().trimEnd('?', '.', '!').take(80)} is ${outcome.text}.",
                Verification.CONFIRMED,
                mapOf("result" to outcome.text),
                answer = true,
            )
            is Calculator.Outcome.Error -> ToolResult.Failure(ToolError.InvalidArguments(outcome.message))
        }
    }
}

class ConvertUnitsTool : Tool {
    override val spec = ToolSpec(
        name = "convert_units",
        description = "Convert a number between units: length, weight, volume, speed, time, data size, area and temperature, for example 5 kilometres to miles or 100 Fahrenheit to Celsius.",
        params = listOf(
            ParamSpec("value", ParamType.STRING, "The number to convert, digits only, for example 5 or 98.6", maxLength = 30),
            ParamSpec("from", ParamType.STRING, "The unit it is in, for example kilometres", maxLength = 30),
            ParamSpec("to", ParamType.STRING, "The unit wanted, for example miles", maxLength = 30),
        ),
        risk = Risk.LOW,
        timeoutMs = 3_000L,
        resultFormat = "CONFIRMED: the converted value as one sentence.",
    )

    override fun describe(args: ToolArgs): String = "Convert ${args.string("value").take(20)} ${args.string("from").take(20)} to ${args.string("to").take(20)}"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val value = args.string("value").replace(",", "").trim().toDoubleOrNull()
            ?.takeIf { it.isFinite() }
            ?: return ToolResult.Failure(ToolError.InvalidArguments("I could not read that number."))
        return when (val outcome = UnitConverter.convert(value, args.string("from"), args.string("to"))) {
            is UnitConverter.Outcome.Done -> ToolResult.Success(outcome.text, Verification.CONFIRMED, answer = true)
            is UnitConverter.Outcome.Refused -> ToolResult.Failure(ToolError.InvalidArguments(outcome.message))
        }
    }
}

class RandomPickTool(private val random: Random = Random.Default) : Tool {
    override val spec = ToolSpec(
        name = "random_pick",
        description = "Flip a coin, roll a die, or pick a random number between two numbers.",
        params = listOf(
            ParamSpec("kind", ParamType.ENUM, "coin, dice or number", enumValues = listOf("coin", "dice", "number")),
            ParamSpec("low", ParamType.INT, "Smallest number; only for kind number", required = false, min = 0, max = 1_000_000),
            ParamSpec("high", ParamType.INT, "Largest number; only for kind number", required = false, min = 1, max = 1_000_000),
            ParamSpec("sides", ParamType.INT, "Sides of the die, 6 if not said; only for kind dice", required = false, min = 2, max = 1_000),
        ),
        risk = Risk.LOW,
        timeoutMs = 3_000L,
        resultFormat = "CONFIRMED: the random result, chosen on the phone.",
    )

    override fun describe(args: ToolArgs): String = when (args.string("kind").lowercase(Locale.ROOT)) {
        "coin" -> "Flip a coin"
        "dice" -> "Roll a die"
        else -> "Pick a random number"
    }

    override suspend fun execute(args: ToolArgs): ToolResult = when (args.string("kind").lowercase(Locale.ROOT)) {
        "coin" -> {
            val heads = random.nextBoolean()
            ToolResult.Success("It is ${if (heads) "heads" else "tails"}.", Verification.CONFIRMED, mapOf("result" to if (heads) "heads" else "tails"), answer = true)
        }
        "dice" -> {
            val sides = args.intOrNull("sides") ?: 6
            val roll = random.nextInt(1, sides + 1)
            ToolResult.Success("You rolled a $roll on a $sides-sided die.", Verification.CONFIRMED, mapOf("result" to roll.toString()), answer = true)
        }
        else -> {
            val low = args.intOrNull("low") ?: 1
            val high = args.intOrNull("high") ?: 100
            if (high < low) {
                ToolResult.Failure(ToolError.InvalidArguments("The top number must not be smaller than the bottom number."))
            } else {
                val pick = random.nextInt(low, high + 1)
                ToolResult.Success("Your number between $low and $high is $pick.", Verification.CONFIRMED, mapOf("result" to pick.toString()), answer = true)
            }
        }
    }
}

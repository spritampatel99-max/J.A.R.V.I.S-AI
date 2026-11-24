package com.jarvis.ai.ai.tools.builtin

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale

/**
 * Converts between everyday units (length, weight, volume, speed, temperature, time, data size, area) entirely on the phone. No
 * internet and no model are involved, and a conversion between different kinds of unit (metres to kilograms) is refused, not guessed.
 * Currency is not here: exchange rates change and need a live source.
 */
object UnitConverter {

    enum class Kind(val spoken: String) {
        LENGTH("a length"),
        MASS("a weight"),
        VOLUME("a volume"),
        SPEED("a speed"),
        TEMPERATURE("a temperature"),
        TIME("a time"),
        DATA("a data size"),
        AREA("an area"),
    }

    data class Unit(val kind: Kind, val factor: Double, val singular: String, val plural: String)

    sealed interface Outcome {
        data class Done(val text: String) : Outcome

        data class Refused(val message: String) : Outcome
    }

    private val table = HashMap<String, Unit>()

    private fun add(kind: Kind, factor: Double, singular: String, plural: String, vararg aliases: String) {
        val unit = Unit(kind, factor, singular, plural)
        for (alias in listOf(singular, plural) + aliases) table[key(alias)] = unit
    }

    init {
        // Length (base: metre)
        add(Kind.LENGTH, 0.001, "millimetre", "millimetres", "mm", "millimeter", "millimeters")
        add(Kind.LENGTH, 0.01, "centimetre", "centimetres", "cm", "centimeter", "centimeters")
        add(Kind.LENGTH, 1.0, "metre", "metres", "m", "meter", "meters")
        add(Kind.LENGTH, 1000.0, "kilometre", "kilometres", "km", "kms", "kilometer", "kilometers")
        add(Kind.LENGTH, 0.0254, "inch", "inches", "in", "inchs")
        add(Kind.LENGTH, 0.3048, "foot", "feet", "ft")
        add(Kind.LENGTH, 0.9144, "yard", "yards", "yd", "yds")
        add(Kind.LENGTH, 1609.344, "mile", "miles", "mi")
        // Mass (base: kilogram)
        add(Kind.MASS, 0.000001, "milligram", "milligrams", "mg")
        add(Kind.MASS, 0.001, "gram", "grams", "g", "gm", "gms")
        add(Kind.MASS, 1.0, "kilogram", "kilograms", "kg", "kgs", "kilo", "kilos")
        add(Kind.MASS, 1000.0, "tonne", "tonnes", "metrictonne", "metrictonnes")
        add(Kind.MASS, 0.028349523125, "ounce", "ounces", "oz")
        add(Kind.MASS, 0.45359237, "pound", "pounds", "lb", "lbs")
        // Volume (base: litre)
        add(Kind.VOLUME, 0.001, "millilitre", "millilitres", "ml", "milliliter", "milliliters")
        add(Kind.VOLUME, 1.0, "litre", "litres", "l", "liter", "liters")
        add(Kind.VOLUME, 0.00492892159375, "teaspoon", "teaspoons", "tsp")
        add(Kind.VOLUME, 0.01478676478125, "tablespoon", "tablespoons", "tbsp")
        add(Kind.VOLUME, 0.2365882365, "cup", "cups")
        add(Kind.VOLUME, 0.0295735295625, "fluid ounce", "fluid ounces", "floz")
        add(Kind.VOLUME, 0.473176473, "pint", "pints", "pt")
        add(Kind.VOLUME, 0.946352946, "quart", "quarts", "qt")
        add(Kind.VOLUME, 3.785411784, "gallon", "gallons", "gal")
        // Speed (base: metre per second)
        add(Kind.SPEED, 1.0 / 3.6, "kilometre per hour", "kilometres per hour", "kmh", "kph", "km/h", "kilometer per hour", "kilometers per hour")
        add(Kind.SPEED, 0.44704, "mile per hour", "miles per hour", "mph")
        add(Kind.SPEED, 1.0, "metre per second", "metres per second", "m/s", "ms", "meter per second", "meters per second")
        add(Kind.SPEED, 0.514444, "knot", "knots", "kt", "kn")
        // Time (base: second)
        add(Kind.TIME, 1.0, "second", "seconds", "s", "sec", "secs")
        add(Kind.TIME, 60.0, "minute", "minutes", "min", "mins")
        add(Kind.TIME, 3600.0, "hour", "hours", "h", "hr", "hrs")
        add(Kind.TIME, 86400.0, "day", "days")
        add(Kind.TIME, 604800.0, "week", "weeks", "wk", "wks")
        // Data (base: byte, decimal as phones and networks show it)
        add(Kind.DATA, 1.0, "byte", "bytes")
        add(Kind.DATA, 1e3, "kilobyte", "kilobytes", "kb")
        add(Kind.DATA, 1e6, "megabyte", "megabytes", "mb")
        add(Kind.DATA, 1e9, "gigabyte", "gigabytes", "gb")
        add(Kind.DATA, 1e12, "terabyte", "terabytes", "tb")
        // Area (base: square metre)
        add(Kind.AREA, 1.0, "square metre", "square metres", "sqm", "square meter", "square meters", "m2")
        add(Kind.AREA, 0.09290304, "square foot", "square feet", "sqft", "ft2")
        add(Kind.AREA, 1e6, "square kilometre", "square kilometres", "sqkm", "square kilometer", "square kilometers", "km2")
        add(Kind.AREA, 4046.8564224, "acre", "acres")
        add(Kind.AREA, 10000.0, "hectare", "hectares", "ha")
        // Temperature has no single factor; the three names are handled in [convert].
        add(Kind.TEMPERATURE, 1.0, "degree Celsius", "degrees Celsius", "c", "celsius", "centigrade")
        add(Kind.TEMPERATURE, 1.0, "degree Fahrenheit", "degrees Fahrenheit", "f", "fahrenheit")
        add(Kind.TEMPERATURE, 1.0, "kelvin", "kelvin", "k")
    }

    private fun key(word: String): String = word.lowercase(Locale.ROOT)
        .replace(Regex("""\s+per\s+"""), "/")
        .replace(Regex("""\bdegrees?\b"""), "")
        .replace(Regex("""[\s.°_]"""), "")

    /** The unit a spoken word names, or null when it is not one this knows. */
    fun resolve(word: String): Unit? {
        val k = key(word)
        if (k.isEmpty() || k.length > 30) return null
        return table[k] ?: if (k.endsWith("s")) table[k.dropLast(1)] else null
    }

    fun convert(value: Double, fromWord: String, toWord: String): Outcome {
        val from = resolve(fromWord) ?: return Outcome.Refused("I do not know the unit \"${fromWord.trim().take(30)}\".")
        val to = resolve(toWord) ?: return Outcome.Refused("I do not know the unit \"${toWord.trim().take(30)}\".")
        if (from.kind != to.kind) {
            return Outcome.Refused("${from.singular.replaceFirstChar { it.uppercase() }} is ${from.kind.spoken} and ${to.singular} is ${to.kind.spoken}, so they cannot be converted.")
        }
        if (value.isNaN() || value.isInfinite()) return Outcome.Refused("That number is not usable.")
        val result = if (from.kind == Kind.TEMPERATURE) temperature(value, from, to) else value * from.factor / to.factor
        if (result.isNaN() || result.isInfinite()) return Outcome.Refused("That number is too large to convert.")
        val shownValue = number(value)
        val fromName = if (value == 1.0) from.singular else from.plural
        val shownResult = number(result)
        val toName = if (result == 1.0) to.singular else to.plural
        return Outcome.Done("$shownValue $fromName is $shownResult $toName.")
    }

    private fun temperature(value: Double, from: Unit, to: Unit): Double {
        val kelvin = when (from.singular) {
            "degree Celsius" -> value + 273.15
            "degree Fahrenheit" -> (value - 32) * 5 / 9 + 273.15
            else -> value
        }
        return when (to.singular) {
            "degree Celsius" -> kelvin - 273.15
            "degree Fahrenheit" -> (kelvin - 273.15) * 9 / 5 + 32
            else -> kelvin
        }
    }

    /** Six significant digits, without trailing zeros. */
    fun number(value: Double): String {
        if (value == 0.0) return "0"
        val rounded = BigDecimal(value).round(MathContext(6, RoundingMode.HALF_EVEN)).stripTrailingZeros()
        val plain = (if (rounded.scale() < 0) rounded.setScale(0, RoundingMode.UNNECESSARY) else rounded).toPlainString()
        return if (plain == "-0") "0" else plain
    }
}

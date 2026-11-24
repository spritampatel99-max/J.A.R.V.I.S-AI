package com.jarvis.ai.ai.tools.builtin

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale

/**
 * A small, safe calculator for spoken arithmetic: "15 percent of 2400", "2 to the power of 10", "square root of 144", "(2 + 3) * 4".
 *
 * It is a parser, not an interpreter: only numbers, + - * / ^ %, brackets and square root exist, so there is nothing to run and
 * nothing a sentence can make it do except add numbers up. Results are exact where they can be and rounded to 12 significant
 * digits where they cannot; division by zero, roots of negatives and numbers too large to be useful are reported, never guessed.
 */
object Calculator {

    sealed interface Outcome {
        data class Value(val text: String) : Outcome

        data class Error(val message: String) : Outcome
    }

    fun evaluate(raw: String): Outcome {
        val text = normalize(raw)
        if (text.isEmpty() || text.length > MAX_LENGTH) return Outcome.Error("I could not read that as a calculation.")
        return try {
            val value = Parser(tokenize(text)).parseAll()
            Outcome.Value(format(value))
        } catch (e: CalcError) {
            Outcome.Error(e.message ?: "I could not work that out.")
        }
    }

    /** True when [raw] is made only of things this calculator understands, so a plain sentence is never taken for a sum. */
    fun looksLikeArithmetic(raw: String): Boolean {
        val text = normalize(raw)
        if (text.isEmpty() || text.length > MAX_LENGTH) return false
        if (!text.any { it.isDigit() }) return false
        return try {
            tokenize(text)
            true
        } catch (e: CalcError) {
            false
        }
    }

    /** True when [raw] is arithmetic that actually does something (has an operator, a percentage or a root), not a lone number. */
    fun looksLikeSum(raw: String): Boolean {
        if (!looksLikeArithmetic(raw)) return false
        val text = normalize(raw)
        return text.any { it in "+-*/^%" } || text.contains("sqrt")
    }

    // ---- spoken words to symbols ---------------------------------------------------------------------------------

    private val THOUSANDS = Regex("""(?<=\d),(?=\d{3}(?!\d))""")
    private val TIMES_X = Regex("""(?<=[\d)])\s*x\s*(?=[\d(])""")
    private val PERCENT_OF = Regex("""(\d+(?:\.\d+)?)\s*(?:%|percent|per cent)\s+of\s+""")
    private val SQRT = Regex("""(?:the )?square root of\s+(-?\d+(?:\.\d+)?|\([^()]*\))""")
    private val SQUARED = Regex("""(\d+(?:\.\d+)?)\s+squared""")
    private val CUBED = Regex("""(\d+(?:\.\d+)?)\s+cubed""")
    private val WORDS = listOf(
        Regex("""\bto the power of\b|\braised to the power of\b|\braised to\b|\bpower\b""") to "^",
        Regex("""\bmultiplied by\b|\btimes\b|\binto\b""") to "*",
        Regex("""\bdivided by\b|\bover\b""") to "/",
        Regex("""\bplus\b""") to "+",
        Regex("""\bminus\b|\bless\b""") to "-",
    )

    private fun normalize(raw: String): String {
        var t = raw.lowercase(Locale.ROOT).trim().trimEnd('?', '.', '!', ' ')
        t = t.replace("×", "*").replace("÷", "/").replace("−", "-").replace("–", "-")
        t = THOUSANDS.replace(t, "")
        t = PERCENT_OF.replace(t) { "(${it.groupValues[1]}/100)*" }
        t = SQRT.replace(t) { "sqrt(${it.groupValues[1].removeSurrounding("(", ")")})" }
        t = SQUARED.replace(t) { "(${it.groupValues[1]})^2" }
        t = CUBED.replace(t) { "(${it.groupValues[1]})^3" }
        for ((pattern, symbol) in WORDS) t = pattern.replace(t, symbol)
        t = TIMES_X.replace(t, "*")
        return t.replace(Regex("""\s+"""), " ").trim()
    }

    // ---- tokens --------------------------------------------------------------------------------------------------

    private sealed interface Token {
        data class Num(val value: BigDecimal) : Token

        data class Op(val symbol: Char) : Token

        data object Sqrt : Token
    }

    private fun tokenize(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == ' ' -> i++
                c.isDigit() || c == '.' -> {
                    var j = i
                    while (j < text.length && (text[j].isDigit() || text[j] == '.')) j++
                    val literal = text.substring(i, j)
                    if (literal.count { it == '.' } > 1 || literal == "." || literal.length > MAX_NUMBER_DIGITS) throw CalcError("I could not read the number \"${literal.take(20)}\".")
                    tokens += Token.Num(BigDecimal(literal))
                    i = j
                }
                c in "+-*/^()%" -> {
                    tokens += Token.Op(c)
                    i++
                }
                text.startsWith("sqrt", i) -> {
                    tokens += Token.Sqrt
                    i += 4
                }
                else -> throw CalcError("I can only do arithmetic: plus, minus, times, divide, powers, percentages and square roots.")
            }
            if (tokens.size > MAX_TOKENS) throw CalcError("That calculation is too long.")
        }
        return tokens
    }

    // ---- parser --------------------------------------------------------------------------------------------------

    private class CalcError(message: String) : RuntimeException(message)

    private class Parser(private val tokens: List<Token>) {
        private var pos = 0
        private var depth = 0

        fun parseAll(): BigDecimal {
            if (tokens.isEmpty()) throw CalcError("There was nothing to work out.")
            val value = expression()
            if (pos != tokens.size) throw CalcError("I could not make sense of that calculation.")
            return value
        }

        private fun peekOp(): Char? = (tokens.getOrNull(pos) as? Token.Op)?.symbol

        private fun expression(): BigDecimal {
            var left = term()
            while (true) {
                when (peekOp()) {
                    '+' -> { pos++; left = check(left.add(term())) }
                    '-' -> { pos++; left = check(left.subtract(term())) }
                    else -> return left
                }
            }
        }

        private fun term(): BigDecimal {
            var left = unary()
            while (true) {
                when (peekOp()) {
                    '*' -> { pos++; left = check(left.multiply(unary())) }
                    '/' -> {
                        pos++
                        val right = unary()
                        if (right.signum() == 0) throw CalcError("You cannot divide by zero.")
                        left = check(left.divide(right, MC))
                    }
                    else -> return left
                }
            }
        }

        private fun unary(): BigDecimal = when (peekOp()) {
            '-' -> { pos++; nested { unary().negate() } }
            '+' -> { pos++; nested { unary() } }
            else -> power()
        }

        private fun power(): BigDecimal {
            val base = postfix()
            if (peekOp() != '^') return base
            pos++
            return check(raise(base, nested { unary() }))
        }

        private fun postfix(): BigDecimal {
            var value = primary()
            while (peekOp() == '%') {
                pos++
                value = value.divide(HUNDRED, MC)
            }
            return value
        }

        private fun primary(): BigDecimal {
            val token = tokens.getOrNull(pos) ?: throw CalcError("The calculation ended too soon.")
            pos++
            return when {
                token is Token.Num -> token.value
                token is Token.Op && token.symbol == '(' -> {
                    val inner = nested { expression() }
                    if (peekOp() != ')') throw CalcError("A bracket was not closed.")
                    pos++
                    inner
                }
                token is Token.Sqrt -> {
                    if (peekOp() != '(') throw CalcError("I could not read that square root.")
                    pos++
                    val inner = nested { expression() }
                    if (peekOp() != ')') throw CalcError("A bracket was not closed.")
                    pos++
                    if (inner.signum() < 0) throw CalcError("There is no real square root of a negative number.")
                    inner.sqrt(MC)
                }
                else -> throw CalcError("I could not make sense of that calculation.")
            }
        }

        private fun <T> nested(block: () -> T): T {
            if (++depth > MAX_DEPTH) throw CalcError("That calculation is too deeply nested.")
            try {
                return block()
            } finally {
                depth--
            }
        }

        private fun raise(base: BigDecimal, exponent: BigDecimal): BigDecimal {
            val whole = exponent.stripTrailingZeros()
            if (whole.scale() <= 0 && whole.abs() <= MAX_EXPONENT) {
                val n = whole.toInt()
                if (base.signum() == 0 && n < 0) throw CalcError("You cannot divide by zero.")
                return if (n >= 0) base.pow(n, MC) else BigDecimal.ONE.divide(base.pow(-n, MC), MC)
            }
            // A fractional power, such as 2 ^ 0.5.
            if (base.signum() < 0) throw CalcError("A negative number has no real fractional power.")
            val result = Math.pow(base.toDouble(), exponent.toDouble())
            if (result.isNaN() || result.isInfinite()) throw CalcError("That number is too large to work out.")
            return BigDecimal(result, MC)
        }

        private fun check(value: BigDecimal): BigDecimal {
            if (value.abs() > LIMIT) throw CalcError("That number is too large for me to work out.")
            return value
        }
    }

    private fun format(value: BigDecimal): String {
        val rounded = value.round(OUT).stripTrailingZeros()
        val plain = (if (rounded.scale() < 0) rounded.setScale(0, RoundingMode.UNNECESSARY) else rounded).toPlainString()
        return if (plain == "-0") "0" else plain
    }

    private val MC = MathContext(20, RoundingMode.HALF_EVEN)
    private val OUT = MathContext(12, RoundingMode.HALF_EVEN)
    private val HUNDRED = BigDecimal(100)
    private val LIMIT = BigDecimal("1E30")
    private val MAX_EXPONENT = BigDecimal(200)
    private const val MAX_LENGTH = 160
    private const val MAX_TOKENS = 120
    private const val MAX_DEPTH = 40
    private const val MAX_NUMBER_DIGITS = 30
}

package com.jarvis.ai.ai.tools

/**
 * Rules for a number the app is asked to dial.
 *
 * The important one: a "number" starting with `*` or `#`, or containing them anywhere, is never dialled. Such strings are
 * not phone numbers; they are carrier codes that can switch on call forwarding, run account operations or show device
 * identifiers, and they would reach the dialer looking like any other call.
 */
object PhoneNumbers {

    sealed interface Parsed {
        /** [dialable] is an optional `+` followed by digits only. */
        data class Valid(val dialable: String) : Parsed

        data class Invalid(val reason: String) : Parsed
    }

    private const val MIN_DIGITS = 3
    private const val MAX_DIGITS = 15

    fun parse(raw: String): Parsed {
        val text = raw.trim()
        if (text.isEmpty()) return Parsed.Invalid("No phone number was given.")
        if ('*' in text || '#' in text) {
            return Parsed.Invalid("Codes with * or # (call forwarding, carrier codes) are never dialled by this app.")
        }
        val allowed = text.withIndex().all { (i, c) ->
            c.isAsciiDigit() || c == ' ' || c == '-' || c == '.' || c == '(' || c == ')' || (c == '+' && i == 0)
        }
        if (!allowed) {
            return Parsed.Invalid("A phone number can only have digits, spaces, dashes, brackets and a + at the start.")
        }
        val digits = text.filter { it.isAsciiDigit() }
        if (digits.length < MIN_DIGITS) return Parsed.Invalid("That number is too short.")
        if (digits.length > MAX_DIGITS) return Parsed.Invalid("That number is too long to be a phone number.")
        return Parsed.Valid((if (text.startsWith("+")) "+" else "") + digits)
    }

    /**
     * Local emergency numbers. The app never places these itself: it opens the dialer with the number ready and the person
     * presses call. An emergency call should never start from a mis-heard voice command or a model's plan.
     */
    private val EMERGENCY = setOf("100", "101", "102", "108", "110", "112", "119", "911", "999", "000")

    fun isEmergency(dialable: String): Boolean = dialable in EMERGENCY

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}

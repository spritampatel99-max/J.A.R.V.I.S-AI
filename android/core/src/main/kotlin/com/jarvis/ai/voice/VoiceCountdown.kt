package com.jarvis.ai.voice

import com.jarvis.ai.core.security.ConfirmationRequest
import com.jarvis.ai.core.security.ConfirmationResponder
import java.util.Locale
import kotlinx.coroutines.delay

/** Where a request came from. Only the person's own spoken built-in command can use the read-back countdown. */
enum class RequestOrigin {
    /** Typed in the chat box. Calls and other sensitive actions are confirmed with a tap. */
    TYPED,

    /** Spoken by the person and matched by the app's own fixed command patterns, with no AI model deciding anything. */
    SPOKEN_BUILT_IN,

    /** Anything an AI model planned (spoken or typed), or anything that came from outside content. Always a tap. */
    ASSISTANT_PLANNED,
}

/**
 * The person asked for calls (and, once they switch it on, WhatsApp messages) by voice, with no yes/no tap: Jarvis reads the action
 * back and does it after a short window unless the person says "stop" or taps cancel.
 *
 * What stays true: an AI model can never use this path (its plans are [RequestOrigin.ASSISTANT_PLANNED]); only the call
 * tools, and WhatsApp while its own switch is on, qualify; and approval is given by the app's own timer through the same [ConfirmationResponder] the dialog uses, so
 * the fingerprint binding in the confirmation gate still applies to the exact action that was read back.
 */
object VoiceDirectPolicy {
    const val COUNTDOWN_SECONDS = 3

    private val CALL_TOOLS = setOf("call_contact", "call_number", "answer_call", "end_call")
    const val WHATSAPP_TOOL = "send_whatsapp"

    /**
     * A spoken WhatsApp message qualifies only when the person switched on hands-free sending ([whatsappSend]) AND the request is
     * the one that presses Send for them (its summary says so, which only the tool writes while that switch is on). Without the
     * switch it falls back to the on-screen confirmation, like any other message.
     */
    fun eligible(origin: RequestOrigin, request: ConfirmationRequest, whatsappSend: Boolean = false): Boolean {
        if (origin != RequestOrigin.SPOKEN_BUILT_IN) return false
        if (request.toolName in CALL_TOOLS) return true
        return whatsappSend && request.toolName == WHATSAPP_TOOL && request.summary.endsWith(SEND_PRESSED_NOTE)
    }

    /** The tool adds this to its summary while hands-free sending is on, so the read-back and the countdown cover exactly that action. */
    const val SEND_PRESSED_NOTE = " (Send is pressed for you)"
    private val WHATSAPP = Regex("""^WhatsApp (.+?)(?: on (\S+))?(?: \((.*)\))?: "(.*)"$""", RegexOption.DOT_MATCHES_ALL)

    private val CONTACT = Regex("""^Call (.+) on (\S+) \((.*)\)$""")
    private val NUMBER = Regex("""^Call (\S+)$""")

    /**
     * What Jarvis says before the countdown starts. It is built from the tool's own description of the validated action (the resolved
     * contact, or the number the app parsed), never from model text, so what is read out is what will be dialled.
     */
    fun readBack(request: ConfirmationRequest): String {
        val action = when (request.toolName) {
            "call_contact" -> CONTACT.matchEntire(request.summary)?.let { match ->
                val label = match.groupValues[3].trim()
                "Calling ${match.groupValues[1].trim()}" + if (label.isNotEmpty()) ", $label" else ""
            }
            "call_number" -> NUMBER.matchEntire(request.summary)?.let { "Calling ${spellOut(it.groupValues[1])}" }
            "answer_call" -> "Answering the call"
            "end_call" -> "Ending the call"
            WHATSAPP_TOOL -> WHATSAPP.matchEntire(request.summary.removeSuffix(SEND_PRESSED_NOTE))?.let { m ->
                "Sending WhatsApp to ${m.groupValues[1].trim()}: ${m.groupValues[4].take(300)}"
            }
            else -> null
        } ?: request.summary.take(80)
        return "$action. Say stop to cancel."
    }

    /** Digits one by one, so a speech engine does not read a phone number as a very large number. */
    private fun spellOut(number: String): String =
        number.mapNotNull { ch ->
            when {
                ch.isDigit() -> ch.toString()
                ch == '+' -> "plus"
                else -> null
            }
        }.joinToString(" ")
}

/** Words that stop a countdown. Matched on the whole phrase or its first word, so "stop calling" counts and "stopwatch" does not. */
object VoiceCancel {
    private val FIRST_WORDS = setOf("stop", "cancel", "no", "nope", "wait", "don't", "dont", "abort", "hold", "not")
    private val PHRASES = listOf("never mind", "nevermind", "hang on", "hold on", "not that", "wrong person", "wrong number")

    fun isCancel(heard: String): Boolean {
        val text = heard.lowercase(Locale.ROOT).replace(Regex("""[^\p{L}\p{N}' ]"""), " ").trim().replace(Regex("""\s+"""), " ")
        if (text.isEmpty()) return false
        if (PHRASES.any { text.contains(it) }) return true
        return text.substringBefore(' ') in FIRST_WORDS
    }
}

/** Counts down for one spoken call and approves it when the time is up. Cancelling the coroutine means "do not approve". */
class VoiceCountdown(private val responder: ConfirmationResponder) {

    enum class Outcome { NOT_ELIGIBLE, APPROVED, TOO_LATE }

    /**
     * Suspends for [VoiceDirectPolicy.COUNTDOWN_SECONDS] seconds, calling [onTick] with the seconds left (3, 2, 1), then approves.
     * Returns at once, approving nothing, when the request does not qualify. Cancelling the calling coroutine stops the countdown
     * without approving; the caller then denies the request.
     */
    suspend fun run(request: ConfirmationRequest, origin: RequestOrigin, whatsappSend: Boolean = false, onTick: (Int) -> Unit = {}): Outcome {
        if (!VoiceDirectPolicy.eligible(origin, request, whatsappSend)) return Outcome.NOT_ELIGIBLE
        for (left in VoiceDirectPolicy.COUNTDOWN_SECONDS downTo 1) {
            onTick(left)
            delay(1_000L)
        }
        return if (responder.approve(request.id)) Outcome.APPROVED else Outcome.TOO_LATE
    }
}

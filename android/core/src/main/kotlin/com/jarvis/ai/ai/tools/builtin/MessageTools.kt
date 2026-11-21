package com.jarvis.ai.ai.tools.builtin

import com.jarvis.ai.ai.tools.ParamSpec
import com.jarvis.ai.ai.tools.ParamType
import com.jarvis.ai.ai.tools.PhoneNumbers
import com.jarvis.ai.ai.tools.Prepared
import com.jarvis.ai.ai.tools.Tool
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolError
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.ToolSpec
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.core.security.Risk

/**
 * Text and WhatsApp messages. These tools never send anything: they open the messages app or WhatsApp with the text already typed
 * in, and the person reads it there and presses Send. Before that, the app shows the person the recipient, the exact number and the
 * message, and waits for a tap; the model can never approve it (the tools are [Risk.HIGH]) and can never supply the number, which
 * app code resolves in [Tool.prepare]. The approval is bound to the resolved number and the exact text.
 */
internal object MessageText {
    const val MAX_LENGTH = 1000

    /** Drops control characters (keeping line breaks) so nothing invisible rides along in a message. */
    fun clean(raw: String): String =
        raw.filter { it == '\n' || it == '\t' || !it.isISOControl() }.trim()

    fun preview(text: String): String {
        val oneLine = text.replace('\n', ' ')
        return if (oneLine.length <= 200) oneLine else oneLine.take(200) + "… (${text.length} characters)"
    }
}

private sealed interface Recipient {
    data class Resolved(val name: String?, val number: String, val label: String?) : Recipient

    data class Stop(val error: ToolError) : Recipient
}

private suspend fun resolveRecipient(contacts: ContactsPort?, to: String): Recipient {
    val target = to.trim()
    if (target.isEmpty()) return Recipient.Stop(ToolError.InvalidArguments("No contact or number was given."))
    // Anything with a digit or * # + is a number, never a name, so carrier codes reach the number checks and are refused.
    if (target.any { it.isDigit() || it == '*' || it == '#' || it == '+' }) {
        return when (val parsed = PhoneNumbers.parse(target)) {
            is PhoneNumbers.Parsed.Valid -> Recipient.Resolved(null, parsed.dialable, null)
            is PhoneNumbers.Parsed.Invalid -> Recipient.Stop(ToolError.InvalidArguments(parsed.reason))
        }
    }
    if (contacts == null) {
        return Recipient.Stop(ToolError.Unavailable("This phone's contacts are not available to this app, so give me the number instead."))
    }
    return when (val found = ContactLookup.resolve(contacts, target)) {
        is ContactLookup.Found -> Recipient.Resolved(found.name, found.number, found.label)
        is ContactLookup.Stop -> Recipient.Stop(found.error)
    }
}

private fun prepared(recipient: Recipient.Resolved, to: String, message: String) = Prepared.Ready(
    ToolArgs(
        mapOf(
            "to" to to.trim(),
            "contact" to (recipient.name ?: ""),
            "number" to recipient.number,
            "label" to (recipient.label ?: ""),
            "message" to message,
        ),
    ),
)

private fun who(args: ToolArgs): String {
    val number = args.string("number")
    val contact = args.stringOrNull("contact").orEmpty()
    val label = args.stringOrNull("label").orEmpty()
    return when {
        contact.isEmpty() -> number
        label.isEmpty() -> "$contact on $number"
        else -> "$contact on $number ($label)"
    }
}

private fun messageParams() = listOf(
    ParamSpec("to", ParamType.STRING, "Who to message: a contact's name as the user said it, or a phone number", maxLength = 60),
    ParamSpec("message", ParamType.STRING, "The words of the message, exactly as the user wants them", maxLength = MessageText.MAX_LENGTH),
)

class SendSmsTool(private val contacts: ContactsPort?, private val messaging: MessagingPort) : Tool {
    override val spec = ToolSpec(
        name = "send_sms",
        description = "Write a text message (SMS) to a contact or number. It opens the messages app with the text typed in; " +
            "the user presses Send there. The user is shown the exact name, number and message and must confirm first.",
        params = messageParams(),
        risk = Risk.HIGH,
        timeoutMs = 10_000L,
        resultFormat = "States that the messages app was opened with the text ready. It never says the message was sent.",
    )

    override suspend fun prepare(args: ToolArgs): Prepared {
        val message = MessageText.clean(args.string("message"))
        if (message.isEmpty()) return Prepared.Stop(ToolError.InvalidArguments("The message was empty."))
        return when (val recipient = resolveRecipient(contacts, args.string("to"))) {
            is Recipient.Stop -> Prepared.Stop(recipient.error)
            is Recipient.Resolved -> prepared(recipient, args.string("to"), message)
        }
    }

    override fun describe(args: ToolArgs): String {
        val number = args.stringOrNull("number")
        val message = args.stringOrNull("message")
        return if (number != null && message != null) {
            "Text ${who(args)}: \"${MessageText.preview(message)}\""
        } else {
            "Text \"${args.string("to").take(60)}\""
        }
    }

    override suspend fun execute(args: ToolArgs): ToolResult {
        val number = args.stringOrNull("number")
            ?: return ToolResult.Failure(ToolError.Failed("The recipient's number was not resolved, so no message was opened."))
        val parsed = PhoneNumbers.parse(number)
        if (parsed !is PhoneNumbers.Parsed.Valid) return ToolResult.Failure(ToolError.InvalidArguments((parsed as PhoneNumbers.Parsed.Invalid).reason))
        val message = MessageText.clean(args.stringOrNull("message").orEmpty())
        if (message.isEmpty()) return ToolResult.Failure(ToolError.InvalidArguments("The message was empty."))

        val name = args.stringOrNull("contact")?.takeIf { it.isNotEmpty() } ?: parsed.dialable
        return when (val result = messaging.composeSms(parsed.dialable, message)) {
            MessageHandoff.Opened -> ToolResult.Success(
                "Opened the messages app with your text to $name ready. It has not been sent: read it and press Send there.",
                Verification.HANDED_OFF,
                mapOf("number" to parsed.dialable),
            )
            is MessageHandoff.NoApp -> ToolResult.Failure(ToolError.Unavailable("This phone has no app that can write text messages."))
            is MessageHandoff.Failed -> ToolResult.Failure(ToolError.Failed(result.reason))
        }
    }
}

class SendWhatsAppTool(private val contacts: ContactsPort?, private val messaging: MessagingPort) : Tool {
    override val spec = ToolSpec(
        name = "send_whatsapp",
        description = "Write a WhatsApp message to a contact or number. It opens WhatsApp with the text typed in; " +
            "the user presses Send there. The user is shown the exact name, number and message and must confirm first.",
        params = messageParams(),
        risk = Risk.HIGH,
        timeoutMs = 10_000L,
        resultFormat = "States that WhatsApp was opened with the text ready. It never says the message was sent.",
    )

    override suspend fun prepare(args: ToolArgs): Prepared {
        val message = MessageText.clean(args.string("message"))
        if (message.isEmpty()) return Prepared.Stop(ToolError.InvalidArguments("The message was empty."))
        return when (val recipient = resolveRecipient(contacts, args.string("to"))) {
            is Recipient.Stop -> Prepared.Stop(recipient.error)
            is Recipient.Resolved -> prepared(recipient, args.string("to"), message)
        }
    }

    override fun describe(args: ToolArgs): String {
        val number = args.stringOrNull("number")
        val message = args.stringOrNull("message")
        return if (number != null && message != null) {
            "WhatsApp ${who(args)}: \"${MessageText.preview(message)}\""
        } else {
            "WhatsApp \"${args.string("to").take(60)}\""
        }
    }

    override suspend fun execute(args: ToolArgs): ToolResult {
        val number = args.stringOrNull("number")
            ?: return ToolResult.Failure(ToolError.Failed("The recipient's number was not resolved, so WhatsApp was not opened."))
        val parsed = PhoneNumbers.parse(number)
        if (parsed !is PhoneNumbers.Parsed.Valid) return ToolResult.Failure(ToolError.InvalidArguments((parsed as PhoneNumbers.Parsed.Invalid).reason))
        val message = MessageText.clean(args.stringOrNull("message").orEmpty())
        if (message.isEmpty()) return ToolResult.Failure(ToolError.InvalidArguments("The message was empty."))

        val name = args.stringOrNull("contact")?.takeIf { it.isNotEmpty() } ?: parsed.dialable
        // WhatsApp can open a chat directly only from a number with its country code. Without one the number could belong to a
        // different person abroad, so the chat is not guessed: WhatsApp opens with the text ready and the person picks the chat.
        val international = parsed.dialable.startsWith("+")
        val result = if (international) messaging.openWhatsAppChat(parsed.dialable, message) else messaging.shareToWhatsApp(message)
        return when (result) {
            MessageHandoff.Opened -> ToolResult.Success(
                if (international) {
                    "Opened WhatsApp with your message to $name ready. It has not been sent: read it and press Send there."
                } else {
                    "Opened WhatsApp with your message ready. This number has no country code, so choose $name's chat yourself, then press Send. Nothing has been sent."
                },
                Verification.HANDED_OFF,
                mapOf("number" to parsed.dialable),
            )
            is MessageHandoff.NoApp -> ToolResult.Failure(ToolError.Unavailable("WhatsApp is not installed on this phone."))
            is MessageHandoff.Failed -> ToolResult.Failure(ToolError.Failed(result.reason))
        }
    }
}

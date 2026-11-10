package com.jarvis.ai.ai.tools.builtin

import com.jarvis.ai.ai.tools.ContactChoice
import com.jarvis.ai.ai.tools.ContactResolver
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

object CallPermissions {
    const val READ_CONTACTS = "android.permission.READ_CONTACTS"
    const val ANSWER_PHONE_CALLS = "android.permission.ANSWER_PHONE_CALLS"
}

/**
 * Calls always need the user's tap on a native confirmation (the tools are [Risk.HIGH]). The confirmation shows the contact
 * and the exact number, which app code resolves in [Tool.prepare] before it asks; the approval is bound to that number, so
 * a different one can never be dialled under it. Results say only what Android reported: a call is "requested", never "made".
 */
private fun startCall(calls: CallPort, dialable: String, who: String): ToolResult {
    val direct = !PhoneNumbers.isEmergency(dialable)
    return when (val started = calls.call(dialable, allowDirect = direct)) {
        CallStart.Placed -> ToolResult.Success(
            "Asked Android to call $who. Android does not tell this app whether the call connected.",
            Verification.HANDED_OFF,
            mapOf("number" to dialable),
        )
        CallStart.DialerOpened -> ToolResult.Success(
            if (direct) {
                "Opened the phone app with $who's number ready. Press the call button to dial; calling directly needs the Phone permission in the Access tab."
            } else {
                "Opened the phone app with $dialable ready. Emergency numbers are never dialled by this app: press the call button yourself."
            },
            Verification.HANDED_OFF,
            mapOf("number" to dialable),
        )
        CallStart.NoHandler -> ToolResult.Failure(ToolError.Unavailable("This phone has no app that can place calls."))
        is CallStart.Failed -> ToolResult.Failure(ToolError.Failed(started.reason))
    }
}

class CallContactTool(private val contacts: ContactsPort, private val calls: CallPort) : Tool {
    override val spec = ToolSpec(
        name = "call_contact",
        description = "Call a person from the phone's contacts by name. The user is shown the exact name and number and must confirm.",
        params = listOf(ParamSpec("name", ParamType.STRING, "The contact's name as the user said it", maxLength = 60)),
        risk = Risk.HIGH,
        requiredPermissions = setOf(CallPermissions.READ_CONTACTS),
        timeoutMs = 10_000L,
        resultFormat = "States that Android was asked to place the call, or that the phone app was opened with the number.",
    )

    override suspend fun prepare(args: ToolArgs): Prepared {
        val query = args.string("name").trim()
        if (query.isEmpty()) return Prepared.Stop(ToolError.InvalidArguments("No contact name was given."))

        var found = search(query) ?: return Prepared.Stop(ToolError.Failed("Contacts could not be searched."))
        var label: String? = null
        if (found is ContactSearch.Found && found.contacts.isEmpty()) {
            // "mom mobile": the last word may name the number type rather than be part of the name.
            val (namePart, labelPart) = ContactResolver.splitTrailingLabel(query)
            if (labelPart != null) {
                found = search(namePart) ?: return Prepared.Stop(ToolError.Failed("Contacts could not be searched."))
                label = labelPart
            }
        }
        return when (found) {
            ContactSearch.PermissionMissing -> Prepared.Stop(
                ToolError.PermissionDenied(setOf(CallPermissions.READ_CONTACTS), "Contacts access is off. Allow it in the Access tab and ask again."),
            )
            is ContactSearch.Failed -> Prepared.Stop(ToolError.Failed(found.reason))
            is ContactSearch.Found -> {
                val queryForMatching = if (label != null) ContactResolver.splitTrailingLabel(query).first else query
                when (val choice = ContactResolver.choose(queryForMatching, found.contacts, label)) {
                    is ContactChoice.Chosen -> Prepared.Ready(
                        ToolArgs(mapOf("name" to query, "contact" to choice.name, "number" to choice.number, "label" to choice.label)),
                    )
                    is ContactChoice.Ask -> Prepared.Stop(ToolError.NeedsInput(choice.question))
                    is ContactChoice.NoMatch -> Prepared.Stop(ToolError.Unavailable(choice.message))
                }
            }
        }
    }

    private suspend fun search(query: String): ContactSearch? =
        try {
            contacts.search(query)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    override fun describe(args: ToolArgs): String {
        val number = args.stringOrNull("number")
        val contact = args.stringOrNull("contact")
        return if (number != null && contact != null) {
            "Call $contact on $number (${args.stringOrNull("label") ?: "number"})"
        } else {
            "Call the contact named \"${args.string("name").take(60)}\""
        }
    }

    override suspend fun execute(args: ToolArgs): ToolResult {
        val number = args.stringOrNull("number")
            ?: return ToolResult.Failure(ToolError.Failed("The contact's number was not resolved, so no call was made."))
        val parsed = PhoneNumbers.parse(number)
        if (parsed !is PhoneNumbers.Parsed.Valid) {
            return ToolResult.Failure(ToolError.InvalidArguments((parsed as PhoneNumbers.Parsed.Invalid).reason))
        }
        return startCall(calls, parsed.dialable, args.stringOrNull("contact") ?: parsed.dialable)
    }
}

class CallNumberTool(private val calls: CallPort) : Tool {
    override val spec = ToolSpec(
        name = "call_number",
        description = "Call a phone number the user gave. The user is shown the exact number and must confirm.",
        params = listOf(ParamSpec("number", ParamType.STRING, "The phone number, digits only or with a leading +", maxLength = 32)),
        risk = Risk.HIGH,
        timeoutMs = 10_000L,
        resultFormat = "States that Android was asked to place the call, or that the phone app was opened with the number.",
    )

    override suspend fun prepare(args: ToolArgs): Prepared =
        when (val parsed = PhoneNumbers.parse(args.string("number"))) {
            is PhoneNumbers.Parsed.Valid -> Prepared.Ready(ToolArgs(mapOf("number" to parsed.dialable)))
            is PhoneNumbers.Parsed.Invalid -> Prepared.Stop(ToolError.InvalidArguments(parsed.reason))
        }

    override fun describe(args: ToolArgs): String = "Call ${args.string("number").take(40)}"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val parsed = PhoneNumbers.parse(args.string("number"))
        if (parsed !is PhoneNumbers.Parsed.Valid) {
            return ToolResult.Failure(ToolError.InvalidArguments((parsed as PhoneNumbers.Parsed.Invalid).reason))
        }
        return startCall(calls, parsed.dialable, parsed.dialable)
    }
}

class AnswerCallTool(private val calls: CallPort) : Tool {
    override val spec = ToolSpec(
        name = "answer_call",
        description = "Answer the phone call that is ringing right now.",
        params = emptyList(),
        risk = Risk.MEDIUM,
        requiredPermissions = setOf(CallPermissions.ANSWER_PHONE_CALLS),
        timeoutMs = 5_000L,
        resultFormat = "States that Android was asked to answer; it does not say whether a call was ringing.",
    )

    override fun describe(args: ToolArgs): String = "Answer the ringing phone call"

    override suspend fun execute(args: ToolArgs): ToolResult =
        when (val result = calls.answerRinging()) {
            AnswerResult.Requested -> ToolResult.Success(
                "Asked Android to answer the ringing call. If nothing was ringing, nothing happened; Android does not say which.",
                Verification.HANDED_OFF,
            )
            is AnswerResult.Failed -> ToolResult.Failure(ToolError.Failed(result.reason))
        }
}

class EndCallTool(private val calls: CallPort) : Tool {
    override val spec = ToolSpec(
        name = "end_call",
        description = "Hang up the current phone call, or reject the one that is ringing.",
        params = emptyList(),
        risk = Risk.MEDIUM,
        requiredPermissions = setOf(CallPermissions.ANSWER_PHONE_CALLS),
        timeoutMs = 5_000L,
        resultFormat = "States what Android reported: a call is being ended, or there was no call.",
    )

    override fun describe(args: ToolArgs): String = "Hang up or reject the current phone call"

    override suspend fun execute(args: ToolArgs): ToolResult =
        when (val result = calls.endCall()) {
            EndCallResult.Ended -> ToolResult.Success(
                "Android reported a call and is ending it.",
                Verification.CONFIRMED,
            )
            EndCallResult.NoCall -> ToolResult.Failure(ToolError.Unavailable("Android reports there is no call to end."))
            is EndCallResult.Failed -> ToolResult.Failure(ToolError.Failed(result.reason))
        }
}

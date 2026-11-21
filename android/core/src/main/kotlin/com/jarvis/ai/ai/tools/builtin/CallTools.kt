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
 * Calls always go through a native confirmation (the tools are [Risk.HIGH]). A typed or assistant-planned call needs the person's tap.
 * A call the person SPOKE and the app's own fixed patterns matched is read back and approved by a 3-second countdown the person can
 * cancel (see VoiceCountdown); the model can never use that path. The confirmation shows the contact
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
        return when (val found = ContactLookup.resolve(contacts, query)) {
            is ContactLookup.Found -> Prepared.Ready(
                ToolArgs(mapOf("name" to query, "contact" to found.name, "number" to found.number, "label" to found.label)),
            )
            is ContactLookup.Stop -> Prepared.Stop(found.error)
        }
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

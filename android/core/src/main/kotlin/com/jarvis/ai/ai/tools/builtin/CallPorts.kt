package com.jarvis.ai.ai.tools.builtin

import com.jarvis.ai.ai.tools.ContactEntry

/** What a contacts search found. A permission problem is its own case so nobody mistakes it for "no such person". */
sealed interface ContactSearch {
    data class Found(val contacts: List<ContactEntry>) : ContactSearch

    data object PermissionMissing : ContactSearch

    data class Failed(val reason: String) : ContactSearch
}

interface ContactsPort {
    /** Contacts whose name matches [query]. Reads only; it never changes a contact. */
    suspend fun search(query: String): ContactSearch
}

sealed interface CallStart {
    /** Android accepted the request to place the call. Whether it connected is not observable. */
    data object Placed : CallStart

    /** The phone app was opened with the number filled in; the person has to press the call button. */
    data object DialerOpened : CallStart

    data object NoHandler : CallStart

    data class Failed(val reason: String) : CallStart
}

sealed interface AnswerResult {
    /** Android was asked to answer. It does not say whether a call was ringing. */
    data object Requested : AnswerResult

    data class Failed(val reason: String) : AnswerResult
}

sealed interface EndCallResult {
    /** Android reported that a call exists and is being ended or rejected. */
    data object Ended : EndCallResult

    /** Android reported no call to end. */
    data object NoCall : EndCallResult

    data class Failed(val reason: String) : EndCallResult
}

interface CallPort {
    /**
     * Starts a call. With [allowDirect] and the Phone permission it places the call at once; otherwise it opens the phone
     * app with the number ready. [number] has already been validated and contains only an optional `+` and digits.
     */
    fun call(number: String, allowDirect: Boolean): CallStart

    fun answerRinging(): AnswerResult

    fun endCall(): EndCallResult
}

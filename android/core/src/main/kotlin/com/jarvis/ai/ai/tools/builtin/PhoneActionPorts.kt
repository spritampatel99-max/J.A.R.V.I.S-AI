package com.jarvis.ai.ai.tools.builtin

/**
 * What the platform can do for messages, timers and media keys. All three only hand a request to Android or to another app:
 * none of them taps, types or sends anything inside another app.
 */

/** How far a message got. Nothing here ever means "delivered": the person presses Send in the other app. */
sealed interface MessageHandoff {
    /** The other app is open with the text already typed in. Nothing has been sent. */
    data object Opened : MessageHandoff

    /** No installed app can take the request. [what] names the missing app, for example "WhatsApp". */
    data class NoApp(val what: String) : MessageHandoff

    data class Failed(val reason: String) : MessageHandoff
}

interface MessagingPort {
    /** Opens the messages app with a new text to [number] ([body] typed in). [number] is digits with an optional leading `+`. */
    fun composeSms(number: String, body: String): MessageHandoff

    /** Opens the WhatsApp chat with [internationalNumber] (a `+` and digits) with [body] typed in. */
    fun openWhatsAppChat(internationalNumber: String, body: String): MessageHandoff

    /** Opens WhatsApp's chat picker with [body] ready, for a number that has no country code. The person chooses the chat. */
    fun shareToWhatsApp(body: String): MessageHandoff
}

interface TimerLauncher {
    /** Asks the system Clock app to start a countdown of [seconds]. Android does not say whether it started. */
    fun start(seconds: Int, label: String?): LaunchResult
}

enum class MediaAction(val spoken: String) {
    PLAY("play"),
    PAUSE("pause"),
    NEXT("skip to the next track"),
    PREVIOUS("go back to the previous track"),
    STOP("stop"),
    VOLUME_UP("turn the volume up"),
    VOLUME_DOWN("turn the volume down"),
}

sealed interface MediaResult {
    /** The media key was delivered to Android's media system. Whether an app reacted is not observable. */
    data object Sent : MediaResult

    /** Media volume was changed; the levels are what Android reported before and after. */
    data class Volume(val before: Int, val after: Int, val max: Int) : MediaResult

    data class Failed(val reason: String) : MediaResult
}

interface MediaKeys {
    /** [steps] only applies to the volume actions. */
    fun perform(action: MediaAction, steps: Int): MediaResult
}
